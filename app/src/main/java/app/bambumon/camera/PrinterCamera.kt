package app.bambumon.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import app.bambumon.config.PrinterConfig
import app.bambumon.printer.PrinterTls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CameraState(
    val status: Status = Status.Stopped,
    val width: Int = 0,
    val height: Int = 0,
    /** Time from start() to the first rendered frame of the current connection. */
    val firstFrameMs: Long? = null,
    val droppedFrames: Long = 0,
    /** Times playback froze waiting for data after it had started. */
    val rebuffers: Int = 0,
    val reconnects: Int = 0,
    val lastError: String? = null,
) {
    enum class Status { Stopped, Connecting, Playing, Retrying }
}

/**
 * The X1C chamber camera: RTSPS on port 322, H.264 over RTP interleaved in the RTSP TCP
 * connection.
 *
 * Media3 speaks plain RTSP, but takes a SocketFactory; handing it the serial-pinned TLS
 * factory gives RTSPS with no relay. The URI must keep the rtsps:// scheme because the
 * printer (LIVE555) answers 301 to any request line that isn't rtsps://host:322/..., and
 * the Digest auth hash covers that same URI.
 *
 * Only runs while visible: call [start] / [stop] from the screen's lifecycle. Failed or
 * stalled connections are retried with capped exponential backoff.
 */
@OptIn(UnstableApi::class)
class PrinterCamera(
    private val context: Context,
    private val tls: PrinterTls,
    private val config: PrinterConfig,
) {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    var player: ExoPlayer? = null
        private set
    private var startedAt = 0L
    private var attempt = 0

    fun start(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // The printer's own WiFi delivers frames with gaps up to ~700 ms even to a
                    // wired client (tools/stream_jitter.py), and the VPN adds more. Holding
                    // ~1 s before playing (and after any rebuffer) rides those out at the
                    // cost of ~1 s latency, which is fine for watching a print.
                    .setBufferDurationsMs(
                        /* minBufferMs = */ CUSHION_MS,
                        /* maxBufferMs = */ 4_000,
                        /* bufferForPlaybackMs = */ CUSHION_MS,
                        /* bufferForPlaybackAfterRebufferMs = */ CUSHION_MS,
                    )
                    .build()
            )
            .build()
        p.addListener(playerListener)
        p.addAnalyticsListener(analyticsListener)
        player = p
        connect()
        return p
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (player != null) Log.i(TAG, "stopped: ${_state.value}")
        player?.release()
        player = null
        attempt = 0
        _state.update { it.copy(status = CameraState.Status.Stopped) }
    }

    private fun connect() {
        val p = player ?: return
        startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = CameraState.Status.Connecting, firstFrameMs = null) }
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // the printer only offers RTP interleaved over TCP
            .setSocketFactory(tls.socketFactory(config.serial))
            .setTimeoutMs(CONNECT_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(streamUri(config)))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun scheduleRetry(error: PlaybackException) {
        val delay = (RETRY_BASE_MS shl attempt.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
        attempt++
        Log.w(TAG, "camera error (${error.errorCodeName}); retry #$attempt in ${delay}ms", error)
        _state.update {
            it.copy(
                status = CameraState.Status.Retrying,
                reconnects = it.reconnects + 1,
                lastError = error.errorCodeName + (error.cause?.message?.let { m -> ": $m" } ?: ""),
            )
        }
        main.postDelayed(::connect, delay)
    }

    private val stallCheck = Runnable { restart("stalled for ${STALL_TIMEOUT_MS}ms") }

    private fun restart(reason: String) {
        player?.stop()
        scheduleRetry(PlaybackException(reason, null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = scheduleRetry(error)

        override fun onPlaybackStateChanged(playbackState: Int) {
            main.removeCallbacks(stallCheck)
            when (playbackState) {
                // A dead tunnel can leave RTSP-over-TCP buffering forever without an error.
                Player.STATE_BUFFERING -> {
                    main.postDelayed(stallCheck, STALL_TIMEOUT_MS)
                    if (_state.value.status == CameraState.Status.Playing) {
                        _state.update { it.copy(rebuffers = it.rebuffers + 1) }
                        Log.i(TAG, "freeze #${_state.value.rebuffers}: buffer ran dry")
                    }
                }
                // A live stream that "ends" means the printer dropped the session.
                Player.STATE_ENDED -> restart("stream ended")
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(width = videoSize.width, height = videoSize.height) }
        }

        override fun onRenderedFirstFrame() {
            val ms = SystemClock.elapsedRealtime() - startedAt
            attempt = 0
            Log.i(TAG, "first frame after ${ms}ms")
            _state.update { it.copy(status = CameraState.Status.Playing, firstFrameMs = ms) }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            _state.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
        }
    }

    companion object {
        private const val TAG = "bambumon.camera"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val STALL_TIMEOUT_MS = 10_000L
        private const val CUSHION_MS = 1_000

        fun streamUri(config: PrinterConfig): Uri = Uri.Builder()
            .scheme("rtsps")
            .encodedAuthority("bblp:${Uri.encode(config.accessCode)}@${hostForUri(config.host)}:${PrinterTls.RTSPS_PORT}")
            .path("/streaming/live/1")
            .build()

        private fun hostForUri(host: String) = if (':' in host && !host.startsWith('[')) "[$host]" else host
    }
}

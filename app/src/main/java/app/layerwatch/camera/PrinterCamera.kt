package app.layerwatch.camera

import android.content.Context
import android.content.pm.ApplicationInfo
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
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.util.EventLogger
import app.layerwatch.config.PrinterConfig
import app.layerwatch.printer.PrinterTls
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
    /** Current adaptive cushion and how far behind live playback is. */
    val cushionMs: Long = 0,
    val latencyMs: Long = 0,
) {
    enum class Status { Stopped, Connecting, Playing, Retrying }
}

/**
 * The RTSPS chamber camera (X1, P2S, H2 and X2 series): port 322, H.264 over RTP interleaved in the RTSP TCP
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
    private val cushion = CushionPolicy()
    /** When the renderer last released a frame; written on the playback thread. */
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastFramePtsUs = Long.MIN_VALUE
    @Volatile private var framesReleased = 0L
    private val diagnostics = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun start(): ExoPlayer {
        player?.let { return it }
        Snapshot.viewerStarted()
        // The printer's own WiFi delivers frames with gaps up to ~700 ms even to a wired
        // client (tools/stream_jitter.py), and the VPN adds more. DefaultLoadControl keeps
        // loading; when to *start* playing is the adaptive cushion's call.
        val loads = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 4_000,
                /* maxBufferMs = */ 8_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()
        val p = ExoPlayer.Builder(context)
            .setLoadControl(CushionedLoadControl(loads) { cushion.cushionMs * 1_000 })
            .build()
        cushion.onStart(SystemClock.elapsedRealtime())
        p.addListener(playerListener)
        p.addAnalyticsListener(analyticsListener)
        p.setVideoFrameMetadataListener { ptsUs, releaseNs, format, _ ->
            lastFrameAt = SystemClock.elapsedRealtime()
            framesReleased++
            if (diagnostics) {
                val prev = lastFramePtsUs
                // 30 fps is ~33 ms apart; anything far off that is a timestamp discontinuity.
                if (prev != Long.MIN_VALUE && (ptsUs <= prev || ptsUs - prev > 500_000)) {
                    Log.w(TAG, "diag: pts jump ${prev} -> ${ptsUs} (${(ptsUs - prev) / 1000} ms), release=${releaseNs}, fmt=${format.codecs}")
                }
            }
            lastFramePtsUs = ptsUs
        }
        if (diagnostics) p.addAnalyticsListener(EventLogger(TAG))
        player = p
        connect()
        main.post(tick)
        return p
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            // The stall check only covers BUFFERING. The player can also sit in READY,
            // "playing", while no frame arrives any more; only fresh frames prove it's live.
            val frameAge = SystemClock.elapsedRealtime() - lastFrameAt
            if (_state.value.status == CameraState.Status.Playing &&
                p.playbackState == Player.STATE_READY && frameAge > (if (diagnostics) 60_000 else FROZEN_MS)
            ) {
                restart("no new frame for ${frameAge}ms while playing")
            } else if (p.isPlaying) {
                val ahead = p.bufferedPosition - p.currentPosition
                val speed = cushion.onTick(SystemClock.elapsedRealtime(), ahead)
                if (p.playbackParameters.speed != speed) p.setPlaybackSpeed(speed)
                _state.update { it.copy(cushionMs = cushion.cushionMs, latencyMs = ahead) }
            }
            if (diagnostics && ++ticks % 5 == 0) {
                val c = p.videoDecoderCounters
                Log.i(
                    TAG, "diag: state=${p.playbackState} playing=${p.isPlaying} pos=${p.currentPosition} " +
                        "buf=${p.bufferedPosition} speed=${p.playbackParameters.speed} frameAge=${frameAge}ms " +
                        "released=$framesReleased lastPts=${lastFramePtsUs / 1000} " +
                        "rendered=${c?.renderedOutputBufferCount} dropped=${c?.droppedBufferCount} " +
                        "skipped=${c?.skippedOutputBufferCount} queuedIn=${c?.queuedInputBufferCount} " +
                        "maxLate=${c?.maxConsecutiveDroppedBufferCount}",
                )
            }
            main.postDelayed(this, 1_000)
        }
    }
    private var ticks = 0

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (player != null) {
            Log.i(TAG, "stopped: ${_state.value}")
            Snapshot.viewerStopped()
        }
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
            .setSocketFactory(
                RtpTimestampSmoother.socketFactory(tls.socketFactory(config.serial)) { channel, step ->
                    Log.i(TAG, "smoothed an RTP re-base on channel $channel (step ${step / 90} ms)")
                }
            )
            .setTimeoutMs(CONNECT_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(streamUri(config)))
        // The printer re-bases the stream's timestamps whenever another client starts it.
        p.setMediaSource(ContinuousTimestampSource(source))
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
                        cushion.onFreeze(SystemClock.elapsedRealtime())
                        _state.update { it.copy(rebuffers = it.rebuffers + 1, cushionMs = cushion.cushionMs) }
                        Log.i(TAG, "freeze #${_state.value.rebuffers}: buffer ran dry; cushion now ${cushion.cushionMs}ms")
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
            lastFrameAt = SystemClock.elapsedRealtime()
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
        private const val TAG = "layerwatch.camera"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val STALL_TIMEOUT_MS = 10_000L
        /** Frames normally come at 30 fps with gaps up to ~700 ms on weak printer WiFi. */
        private const val FROZEN_MS = 6_000L

        fun streamUri(config: PrinterConfig): Uri = Uri.Builder()
            .scheme("rtsps")
            .encodedAuthority("bblp:${Uri.encode(config.accessCode)}@${hostForUri(config.host)}:${PrinterTls.RTSPS_PORT}")
            .path("/streaming/live/1")
            .build()

        private fun hostForUri(host: String) = if (':' in host && !host.startsWith('[')) "[$host]" else host
    }
}

/**
 * Defers to [delegate] for everything except when playback may (re)start.
 *
 * Forwarded explicitly: Kotlin's `by` delegation skips Java default methods, and
 * LoadControl is almost entirely defaults, so `by delegate` crashes at player build.
 * These are the overloads ExoPlayer 1.11 calls; the deprecated ones chain to them.
 */
@OptIn(UnstableApi::class)
private class CushionedLoadControl(
    private val delegate: LoadControl,
    private val cushionUs: () -> Long,
) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters) = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ) = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        parameters.bufferedDurationUs >= cushionUs()
}

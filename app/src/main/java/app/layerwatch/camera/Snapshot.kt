package app.layerwatch.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import app.layerwatch.config.PrinterConfig
import app.layerwatch.printer.PrinterTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Grabs one frame from the chamber camera, for attaching to alerts while no player is on
 * screen. Uses the same pinned-TLS RTSP source as [PrinterCamera]. Returns null rather
 * than throwing: a missing picture must never hold up or break an alert.
 */
object Snapshot {
    private const val TAG = "layerwatch.snapshot"

    /**
     * Number of on-screen camera players. The printer's camera serves a second client
     * badly: a snapshot taken while the dashboard plays froze the live view. So grabs are
     * skipped while anyone is watching (they're looking at the camera anyway).
     */
    private val viewers = java.util.concurrent.atomic.AtomicInteger()

    /** A grab in progress; cancelled the moment a viewer opens the camera. */
    @Volatile private var inFlight: java.util.concurrent.Future<*>? = null

    fun viewerStarted() {
        viewers.incrementAndGet()
        inFlight?.cancel(true)
    }

    fun viewerStopped() {
        viewers.decrementAndGet()
    }

    @OptIn(UnstableApi::class)
    suspend fun grab(context: Context, tls: PrinterTls, config: PrinterConfig, timeoutMs: Long = 12_000): Bitmap? {
        if (config.isDemo) return null // the demo has no camera
        if (viewers.get() > 0) {
            Log.i(TAG, "skipped: camera is on screen")
            return null
        }
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setSocketFactory(tls.socketFactory(config.serial))
            .setTimeoutMs(8_000)
        // FrameExtractor drives an ExoPlayer internally and wants a Looper thread to build on.
        val extractor = withContext(Dispatchers.Main) {
            FrameExtractor.Builder(context, MediaItem.fromUri(PrinterCamera.streamUri(config)))
                .setMediaSourceFactory(source)
                .build()
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val future = extractor.getFrame(0)
                    inFlight = future
                    cont.invokeOnCancellation { future.cancel(true) }
                    future.addListener({
                        val frame = runCatching { future.get() }
                            .onFailure { Log.w(TAG, "snapshot failed: $it") }
                            .getOrNull()
                        if (cont.isActive) cont.resume(frame?.bitmap)
                    }, Executor(Runnable::run))
                }
            }.also { if (it == null) Log.w(TAG, "snapshot timed out or failed") }
        } finally {
            inFlight = null
            withContext(Dispatchers.Main) { extractor.close() }
        }
    }
}

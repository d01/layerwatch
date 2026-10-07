package app.bambumon.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import app.bambumon.config.PrinterConfig
import app.bambumon.printer.PrinterTls
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
    private const val TAG = "bambumon.snapshot"

    @OptIn(UnstableApi::class)
    suspend fun grab(context: Context, tls: PrinterTls, config: PrinterConfig, timeoutMs: Long = 12_000): Bitmap? {
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
            withContext(Dispatchers.Main) { extractor.close() }
        }
    }
}

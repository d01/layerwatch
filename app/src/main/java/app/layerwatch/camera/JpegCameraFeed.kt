package app.layerwatch.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.layerwatch.config.PrinterConfig
import app.layerwatch.printer.PrinterTls
import app.layerwatch.protocol.camera.JpegCameraStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlin.random.Random

/**
 * The P1/A1 chamber camera ([JpegCameraStream]) as a stream of decoded frames. Reconnects
 * with jittered backoff; the last frame stays up meanwhile. One frame every second or two
 * is all these printers send, so there's no cushion to manage.
 */
class JpegCameraFeed(private val tls: PrinterTls, private val config: PrinterConfig) {
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private var scope: CoroutineScope? = null
    @Volatile private var stream: JpegCameraStream? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        s.launch { run() }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        stream?.close() // unblocks a pending read
        _state.update { it.copy(status = CameraState.Status.Stopped) }
    }

    private suspend fun run() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            _state.update { it.copy(status = if (failures == 0) CameraState.Status.Connecting else CameraState.Status.Retrying) }
            try {
                val started = System.currentTimeMillis()
                runInterruptible { JpegCameraStream.open(tls.socketFactory(config.serial), config.host, config.accessCode) }.use { cam ->
                    stream = cam
                    var first = true
                    while (currentCoroutineContext().isActive) {
                        val jpeg = runInterruptible { cam.nextFrame() }
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        _frame.value = bitmap
                        failures = 0
                        _state.update {
                            it.copy(
                                status = CameraState.Status.Playing, width = bitmap.width, height = bitmap.height,
                                firstFrameMs = if (first) System.currentTimeMillis() - started else it.firstFrameMs, lastError = null,
                            )
                        }
                        first = false
                    }
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return
                failures++
                Log.w(TAG, "camera: ${e.message}")
                _state.update { it.copy(status = CameraState.Status.Retrying, reconnects = it.reconnects + 1, lastError = e.message) }
                delay(backoffMs(failures))
            } finally {
                stream = null
            }
        }
    }

    private fun backoffMs(failures: Int): Long {
        val base = (1_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(15_000)
        return base / 2 + Random.nextLong(base / 2 + 1)
    }

    companion object {
        private const val TAG = "layerwatch.jpegcam"

        /** One frame, for alert snapshots. */
        fun grab(tls: PrinterTls, config: PrinterConfig): Bitmap? = runCatching {
            JpegCameraStream.open(tls.socketFactory(config.serial), config.host, config.accessCode).use { cam ->
                val jpeg = cam.nextFrame()
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            }
        }.onFailure { Log.w(TAG, "snapshot failed: $it") }.getOrNull()
    }
}

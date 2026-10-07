package app.layerwatch.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.glance.appwidget.updateAll
import app.layerwatch.printer.printerModelName
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.ui.components.Format
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** What the widget shows: a flattened, already-formatted copy of the printer's state. */
data class WidgetState(
    val model: String,
    val active: Boolean,
    val phase: String,
    val jobName: String?,
    val percent: Int?,
    val remainingMinutes: Int?,
    val reportAtMs: Long,
) {
    companion object {
        fun from(serial: String, s: PrinterStatus, reportAtMs: Long) = WidgetState(
            model = printerModelName(serial),
            active = s.jobState.isActive,
            phase = Format.phase(s),
            jobName = s.jobName,
            percent = s.percent,
            remainingMinutes = s.remainingMinutes?.takeIf { s.jobState.isActive },
            reportAtMs = reportAtMs,
        )
    }
}

/**
 * The widget's persisted state: `widget.json` + `widget.jpg` in the app's files dir.
 * The widget reads only these, so it renders the last known state even when nothing
 * of Layerwatch is running.
 */
object WidgetStore {
    private const val TAG = "layerwatch.widget"
    private val lock = Mutex()
    private var lastWritten: WidgetState? = null

    private fun dir(context: Context) = File(context.filesDir, "widget").apply { mkdirs() }

    /** Writes [state] and refreshes widgets if it changed in a way the widget shows. */
    suspend fun push(context: Context, state: WidgetState) = lock.withLock {
        val prev = lastWritten
        val visibleChange = prev == null || prev.copy(reportAtMs = 0) != state.copy(reportAtMs = 0) ||
            state.reportAtMs - prev.reportAtMs > STALE_REFRESH_MS
        if (!visibleChange) return@withLock
        val json = buildJsonObject {
            put("model", state.model)
            put("active", state.active)
            put("phase", state.phase)
            state.jobName?.let { put("job", it) }
            state.percent?.let { put("pct", it) }
            state.remainingMinutes?.let { put("rem", it) }
            put("at", state.reportAtMs)
        }
        atomicWrite(File(dir(context), "widget.json")) { it.writeText(json.toString()) }
        lastWritten = state
        refresh(context)
    }

    /** Stores a camera frame for the widget, downscaled to keep RemoteViews light. */
    suspend fun pushFrame(context: Context, frame: Bitmap) {
        val w = 480
        val scaled = Bitmap.createScaledBitmap(frame, w, (frame.height * w.toFloat() / frame.width).toInt(), true)
        atomicWrite(File(dir(context), "widget.jpg")) { f -> f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        refresh(context)
    }

    fun read(context: Context): WidgetState? = runCatching {
        val o = Json.parseToJsonElement(File(dir(context), "widget.json").readText()).jsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content
        fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        WidgetState(
            model = str("model") ?: "Printer",
            active = str("active") == "true",
            phase = str("phase") ?: "—",
            jobName = str("job"),
            percent = long("pct")?.toInt(),
            remainingMinutes = long("rem")?.toInt(),
            reportAtMs = long("at") ?: 0L,
        )
    }.getOrNull()

    fun readFrame(context: Context): Bitmap? =
        File(dir(context), "widget.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private suspend fun refresh(context: Context) {
        runCatching { PrinterWidget().updateAll(context) }.onFailure { Log.w(TAG, "widget update failed: $it") }
    }

    private inline fun atomicWrite(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        write(tmp)
        tmp.renameTo(target)
    }

    /** Re-render at least this often so the "as of" age doesn't go stale on screen. */
    private const val STALE_REFRESH_MS = 60_000L
}

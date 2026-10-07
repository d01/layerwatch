package app.layerwatch.history

import android.util.Log
import app.layerwatch.printer.PrinterRepository
import app.layerwatch.printer.PrinterTls
import app.layerwatch.protocol.ftps.FtpsClient
import app.layerwatch.protocol.ftps.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Where a timelapse download stands, app-wide (it outlives the screen that started it). */
sealed interface Download {
    data class Running(val bytes: Long, val total: Long) : Download
    data class Done(val file: File) : Download
    data class Failed(val message: String) : Download
}

/** A print's timelapse on the printer, and how sure we are it's that print's. */
data class TimelapseRef(val name: String, val size: Long, val certain: Boolean) {
    val path get() = "$DIR/$name"
    val thumbnailPath get() = "$DIR/thumbnail/${name.removeSuffix(".mp4")}.jpg"

    companion object { const val DIR = "/timelapse" }
}

/**
 * Finds each print's timelapse on the printer without trusting the printer's clock (it
 * has no time sync in LAN-only mode and its file names drift by hours): when a print is
 * first seen, the existing timelapse names are recorded; afterwards, a file that wasn't
 * there is that print's video. Prints recorded before that snapshot existed fall back to
 * the newest timelapse, marked uncertain.
 */
class Timelapses(
    private val printer: PrinterRepository,
    private val tls: PrinterTls,
    private val historyDir: File,
    private val cacheDir: File,
) {
    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    /** By timelapse file name. Absent = not started (or cached from an earlier run: see [cachedFile]). */
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    fun cachedFile(ref: TimelapseRef): File? = File(cacheDir, ref.name).takeIf { it.exists() && it.length() == ref.size }

    internal fun report(name: String, d: Download) {
        _downloads.value = _downloads.value + (name to d)
    }

    private fun beforeFile(key: String) = File(historyDir, "timelapse-before-" + key.filter { it.isLetterOrDigit() || it == '-' }.take(60))

    /** Record the timelapse files that exist as [key] starts. Idempotent. */
    suspend fun snapshotBefore(key: String) = withContext(Dispatchers.IO) {
        val f = beforeFile(key)
        if (f.exists()) return@withContext
        runCatching {
            val names = list().map { it.name }
            historyDir.mkdirs()
            f.writeText(names.joinToString("\n"))
            Log.i(TAG, "recorded ${names.size} existing timelapses before $key")
        }.onFailure { Log.w(TAG, "couldn't snapshot timelapses for $key: $it") }
    }

    suspend fun find(key: String): TimelapseRef? = withContext(Dispatchers.IO) {
        val now = list()
        val before = beforeFile(key).takeIf { it.exists() }?.readLines()?.toSet()
        if (before != null) {
            now.filter { it.name !in before }.maxByOrNull { it.name }?.let { TimelapseRef(it.name, it.size, certain = true) }
        } else {
            now.maxByOrNull { it.name }?.let { TimelapseRef(it.name, it.size, certain = false) }
        }
    }

    /** Small thumbnail the printer keeps for each timelapse; cached. */
    suspend fun thumbnail(ref: TimelapseRef): File? = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "thumb-${ref.name.removeSuffix(".mp4")}.jpg")
        if (out.exists() && out.length() > 0) return@withContext out
        runCatching { download(ref.thumbnailPath, out) {} ; out }.getOrNull()
    }

    /**
     * Downloads the video into the cache, resuming a partial file from an earlier attempt.
     * Runs inside [TimelapseDownloadJob] so it survives the user leaving the app.
     */
    suspend fun download(ref: TimelapseRef, onProgress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        cachedFile(ref)?.let { return@withContext it }
        cacheDir.mkdirs()
        val out = File(cacheDir, ref.name)
        val part = File(cacheDir, ref.name + ".part")
        if (part.length() > ref.size) part.delete()
        val offset = part.length()
        client().use { ftp ->
            java.io.FileOutputStream(part, /* append = */ true).buffered().use { ftp.retrieve(ref.path, it, offset, onProgress) }
        }
        check(part.length() == ref.size) { "Download incomplete (${part.length()} of ${ref.size} bytes)" }
        part.renameTo(out)
        prune()
        out
    }

    private fun list(): List<RemoteFile> = client().use { ftp -> ftp.list(TimelapseRef.DIR).filter { !it.isDirectory && it.name.endsWith(".mp4") } }

    private fun download(path: String, out: File, onProgress: (Long) -> Unit) {
        cacheDir.mkdirs()
        val part = File(out.parentFile, out.name + ".part")
        client().use { ftp -> part.outputStream().buffered().use { ftp.retrieve(path, it, onProgress = onProgress) } }
        part.renameTo(out)
    }

    private fun client(): FtpsClient {
        val cfg = checkNotNull(printer.config.value) { "No printer configured" }
        return FtpsClient.connect(tls.socketFactory(cfg.serial), cfg.host, "bblp", cfg.accessCode)
    }

    /** Videos are 5–20 MB; keep the few most recently watched. */
    private fun prune() {
        cacheDir.listFiles { f -> f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(4)
            ?.forEach { it.delete() }
    }

    private companion object { const val TAG = "layerwatch.timelapse" }
}

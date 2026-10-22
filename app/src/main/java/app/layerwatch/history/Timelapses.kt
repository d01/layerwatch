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
 * first seen, the existing timelapse names are recorded; the first file added after that,
 * and before the next print's snapshot, is that print's video. Prints without a snapshot
 * fall back to a guess, marked uncertain.
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

    private fun beforeFile(key: String) = File(historyDir, "timelapse-before-" + PrintHistory.fileSafe(key, 60))

    /** Record the timelapse files that exist as [key] starts. Idempotent. */
    suspend fun snapshotBefore(key: String) = withContext(Dispatchers.IO) {
        if (printer.config.value?.isDemo == true) return@withContext
        val f = beforeFile(key)
        if (f.exists()) return@withContext
        runCatching {
            val names = list().map { it.name }
            historyDir.mkdirs()
            f.writeText(names.joinToString("\n"))
            Log.i(TAG, "recorded ${names.size} existing timelapses before $key")
        }.onFailure { Log.w(TAG, "couldn't snapshot timelapses for $key: $it") }
    }

    /**
     * The timelapse of the print recorded as [key]. [nextKey] is the print followed after it
     * (null for the newest): its own snapshot bounds the search, so a later print's video
     * is never taken for this one's.
     */
    suspend fun find(key: String, nextKey: String?): TimelapseRef? = withContext(Dispatchers.IO) {
        if (printer.config.value?.isDemo == true) return@withContext null
        pick(list(), before(key), nextKey?.let(::before), isNewest = nextKey == null)
    }

    private fun before(key: String) = beforeFile(key).takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() }?.toSet()
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

    internal companion object {
        private const val TAG = "layerwatch.timelapse"

        /**
         * [before] / [nextBefore]: the timelapses that existed as this print and the next one
         * started (null if never recorded). The print's video is the first file added
         * between the two; later ones belong to prints after it, followed or not. Names
         * (`video_<printer time>.mp4`) sort in the order the printer wrote them.
         */
        fun pick(now: List<RemoteFile>, before: Set<String>?, nextBefore: Set<String>?, isNewest: Boolean): TimelapseRef? {
            val upTo = if (nextBefore != null) now.filter { it.name in nextBefore } else now
            return when {
                // More than one new file: prints Layerwatch didn't follow ran in between, and the
                // printer's clock jumps, so name order is only a guess.
                before != null -> upTo.filter { it.name !in before }.let { new ->
                    new.minByOrNull { it.name }?.let { TimelapseRef(it.name, it.size, certain = new.size == 1) }
                }
                // Unrecorded start: the newest file before the next print is a fair guess; with
                // neither bound, only the newest print may claim the newest file.
                nextBefore != null || isNewest -> upTo.maxByOrNull { it.name }?.let { TimelapseRef(it.name, it.size, certain = false) }
                else -> null
            }
        }
    }
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Where a timelapse download stands, app-wide (it outlives the screen that started it). */
sealed interface Download {
    data class Running(val bytes: Long, val total: Long) : Download
    data class Done(val file: File) : Download
    data class Failed(val message: String) : Download
}

/** A print's timelapse on the printer. */
data class TimelapseRef(val name: String, val size: Long) {
    val path get() = "$DIR/$name"
    val thumbnailPath get() = "$DIR/thumbnail/${name.removeSuffix(".mp4")}.jpg"

    companion object { const val DIR = "/timelapse" }
}

/**
 * Links each print to its timelapse by watching the printer write it. The printer puts no
 * job or task id in the file, its MQTT reports or the MP4, and its file names and times
 * come from clocks that drift by hours, so neither can be matched to a print later. What
 * is certain: the file appears only once the print is over, and no other print can run
 * meanwhile. So the existing names are recorded as a print starts, and as it ends the one
 * file added since is that print's; its name is stored on the history record. A print
 * whose end Layerwatch didn't see has no timelapse rather than a guessed one.
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

    /**
     * Record the timelapse files that exist as [key] starts. Idempotent. Retries while the
     * printer's WiFi drops out: without this list the print's file can't be told apart.
     */
    suspend fun snapshotBefore(key: String) = withContext(Dispatchers.IO) {
        if (printer.config.value?.isDemo == true) return@withContext
        val f = beforeFile(key)
        repeat(SNAPSHOT_TRIES) { attempt ->
            if (f.exists()) return@withContext
            runCatching {
                val names = list().map { it.name }
                historyDir.mkdirs()
                val tmp = File(historyDir, f.name + ".tmp")
                tmp.writeText(names.joinToString("\n"))
                tmp.renameTo(f)
                Log.i(TAG, "recorded ${names.size} existing timelapses before $key")
            }.onFailure {
                Log.w(TAG, "couldn't snapshot timelapses for $key (try ${attempt + 1}): $it")
                delay(RETRY_MS)
            }
        }
    }

    /**
     * Called as [key]'s print ends: waits for the printer to write that print's timelapse
     * and returns its name. Null if none appears (timelapse off, or the print never got
     * going) or if the file can't be singled out. The caller bounds the wait.
     */
    suspend fun awaitNew(key: String): String? = withContext(Dispatchers.IO) {
        if (printer.config.value?.isDemo == true) return@withContext null
        // Without the start list, a list taken now does: the file isn't written yet.
        val baseline = beforeFile(key).takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() }?.toSet()
            ?: retrying { list().map { it.name }.toSet() }
        var added: NewFile = NewFile.None
        while (added == NewFile.None) {
            delay(RETRY_MS)
            added = runCatching { newFile(baseline, list()) }.getOrDefault(NewFile.None)
        }
        (added as? NewFile.One)?.name
            .also { Log.i(TAG, if (it != null) "timelapse of $key is $it" else "several new timelapses since $key started; not linking") }
    }

    /** [name] on the printer now, with its size; null if it's been deleted there. */
    suspend fun find(name: String): TimelapseRef? = withContext(Dispatchers.IO) {
        if (printer.config.value?.isDemo == true) return@withContext null
        list().firstOrNull { it.name == name }?.let { TimelapseRef(it.name, it.size) }
    }

    private suspend fun <T> retrying(block: () -> T): T {
        while (true) {
            runCatching(block).onSuccess { return it }.onFailure { Log.w(TAG, "timelapse list failed: $it") }
            delay(RETRY_MS)
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

    internal sealed interface NewFile {
        data object None : NewFile
        data class One(val name: String) : NewFile
        data object Several : NewFile
    }

    internal companion object {
        private const val TAG = "layerwatch.timelapse"
        private const val RETRY_MS = 10_000L
        private const val SNAPSHOT_TRIES = 30

        /** What the printer added to [now] since [baseline]: a finished print adds exactly one file. */
        fun newFile(baseline: Set<String>, now: List<RemoteFile>): NewFile {
            val added = now.filter { it.name !in baseline }
            return when (added.size) {
                0 -> NewFile.None
                1 -> NewFile.One(added.single().name)
                else -> NewFile.Several
            }
        }
    }
}

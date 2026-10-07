package app.bambumon.job

import android.util.Log
import app.bambumon.printer.PrinterRepository
import app.bambumon.printer.PrinterTls
import app.bambumon.protocol.ftps.FtpsClient
import app.bambumon.protocol.job.JobArchive
import app.bambumon.protocol.PrinterStatus
import app.bambumon.protocol.job.JobFile
import app.bambumon.protocol.job.LayerSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which job file and plate is printing. */
data class JobKey(val name: String, val plate: Int)

sealed interface JobLoad {
    data object None : JobLoad
    data class Downloading(val key: JobKey, val bytes: Long, val total: Long?) : JobLoad
    data class Parsing(val key: JobKey) : JobLoad
    data class Ready(val key: JobKey, val job: JobFile) : JobLoad
    data class Unavailable(val key: JobKey, val reason: String) : JobLoad
}

/**
 * Fetches the running job's `.gcode.3mf` from the printer's storage (FTPS), once, into a
 * small on-phone cache, and parses it for the details screen, forecasts and markers.
 *
 * Runs whenever printer status flows with an active job, i.e. while the app is visible
 * or the print monitor is running — never from a frozen background process.
 */
class JobRepository(
    private val scope: CoroutineScope,
    private val printer: PrinterRepository,
    private val tls: PrinterTls,
    private val dir: File,
) {
    private val _state = MutableStateFlow<JobLoad>(JobLoad.None)
    val state: StateFlow<JobLoad> = _state.asStateFlow()
    private var loading: Job? = null

    /** When each layer of the active job was seen starting (for the pace estimate). */
    private val _samples = MutableStateFlow<List<LayerSample>>(emptyList())
    val samples: StateFlow<List<LayerSample>> = _samples.asStateFlow()
    private var samplesKey: JobKey? = null
    private var lastSeenLayer: Int? = null

    init {
        scope.launch(Dispatchers.IO) { printer.status.collect(::recordLayer) }
        scope.launch {
            printer.status
                .map { s -> s?.takeIf { it.jobState.isActive }?.jobName?.let { JobKey(it, s.plateIndex ?: 1) } }
                .distinctUntilChanged()
                .collect { key -> if (key != null) load(key) }
        }
    }

    /**
     * A sample is kept only for a layer change actually observed in this session, so
     * opening the app mid-layer doesn't skew the pace. The very first sighting of a job
     * is kept too, as the anchor for the slicer's plan.
     */
    private fun recordLayer(s: PrinterStatus?) {
        val key = s?.takeIf { it.jobState.isActive }?.jobName?.let { JobKey(it, s.plateIndex ?: 1) } ?: return
        val layer = s.layer?.takeIf { it > 0 } ?: return
        if (key != samplesKey) {
            samplesKey = key
            lastSeenLayer = null
            _samples.value = loadSamples(key)
        }
        val seen = lastSeenLayer
        lastSeenLayer = layer
        val last = _samples.value.lastOrNull()?.layer
        val observedChange = seen != null && layer > seen
        if ((observedChange && (last == null || layer > last)) || _samples.value.isEmpty()) {
            val sample = LayerSample(layer, System.currentTimeMillis())
            _samples.value = _samples.value + sample
            runCatching { samplesFile(key).appendText("${sample.layer},${sample.atMs}\n") }
        }
    }

    private fun samplesFile(key: JobKey) = File(dir.apply { mkdirs() }, localFile(key).name.removeSuffix(".gcode.3mf") + ".layers")

    private fun loadSamples(key: JobKey): List<LayerSample> = runCatching {
        samplesFile(key).readLines().mapNotNull { line ->
            val (l, t) = line.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            LayerSample(l.toIntOrNull() ?: return@mapNotNull null, t.toLongOrNull() ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /** A previously downloaded job, for viewing details from history. Null if not cached. */
    suspend fun readCached(name: String, plate: Int = 1): JobFile? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        cached(JobKey(name, plate))?.let { f -> runCatching { JobArchive.read(f, plate) }.getOrNull() }
    }

    fun isCached(name: String, plate: Int = 1): Boolean = cached(JobKey(name, plate)) != null

    /** Try again after a failure (e.g. the printer was unreachable). */
    fun retry() {
        (state.value as? JobLoad.Unavailable)?.key?.let(::load)
    }

    private fun load(key: JobKey) {
        val current = state.value
        if (current is JobLoad.Ready && current.key == key) return
        loading?.cancel()
        loading = scope.launch(Dispatchers.IO) {
            try {
                val file = cached(key) ?: download(key) ?: return@launch
                _state.value = JobLoad.Parsing(key)
                val started = System.currentTimeMillis()
                val job = withContext(Dispatchers.Default) { JobArchive.read(file, key.plate) }
                Log.i(TAG, "parsed ${key.name}: ${job.gcode?.layers?.size} layers in ${System.currentTimeMillis() - started} ms")
                file.setLastModified(System.currentTimeMillis())
                _state.value = JobLoad.Ready(key, job)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "job load failed for $key", e)
                _state.value = JobLoad.Unavailable(key, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun localFile(key: JobKey) = File(dir, key.name.replace(Regex("[^A-Za-z0-9._ -]"), "_") + ".gcode.3mf")

    private fun cached(key: JobKey): File? = localFile(key).takeIf { it.exists() && it.length() > 0 }

    /** Finds and downloads the job file; null (with state set) if it isn't on the printer. */
    private fun download(key: JobKey): File? {
        val cfg = printer.config.value ?: return null
        val wanted = "${key.name}.gcode.3mf"
        FtpsClient.connect(tls.socketFactory(cfg.serial), cfg.host, "bblp", cfg.accessCode).use { ftp ->
            // Jobs sent over the LAN land at the root; some firmware uses /cache or /model.
            val (path, size) = SEARCH_DIRS.firstNotNullOfOrNull { d ->
                runCatching { ftp.list(d) }.getOrNull()?.firstOrNull { it.name == wanted }?.let { "${d.trimEnd('/')}/$wanted" to it.size }
            } ?: run {
                _state.value = JobLoad.Unavailable(key, "This job's file isn't on the printer's storage")
                return null
            }
            dir.mkdirs()
            val part = File(dir, "${localFile(key).name}.part")
            _state.value = JobLoad.Downloading(key, 0, size)
            part.outputStream().buffered().use { out ->
                ftp.retrieve(path, out) { n -> _state.value = JobLoad.Downloading(key, n, size) }
            }
            if (part.length() != size) error("Download incomplete: ${part.length()} of $size bytes")
            val file = localFile(key)
            part.renameTo(file)
            prune()
            Log.i(TAG, "downloaded $path ($size bytes)")
            return file
        }
    }

    /** Keep the most recently used job files only. */
    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".gcode.3mf") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED)
            ?.forEach { stale ->
                stale.delete()
                File(dir, stale.name.removeSuffix(".gcode.3mf") + ".layers").delete()
            }
    }

    private companion object {
        const val TAG = "bambumon.job"
        const val MAX_CACHED = 12
        val SEARCH_DIRS = listOf("/", "/cache", "/model")
    }
}

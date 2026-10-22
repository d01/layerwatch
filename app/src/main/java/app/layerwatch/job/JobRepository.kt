package app.layerwatch.job

import android.util.Log
import app.layerwatch.history.PrintHistory
import app.layerwatch.history.PrintRecord
import app.layerwatch.printer.PrinterRepository
import app.layerwatch.printer.PrinterTls
import app.layerwatch.protocol.ftps.FtpsClient
import app.layerwatch.protocol.job.JobArchive
import app.layerwatch.protocol.DemoPrinter
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.job.JobFile
import app.layerwatch.protocol.job.LayerSample
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

/** Which job file and plate is printing; [taskId] tells runs of the same file apart. */
data class JobKey(val name: String, val plate: Int, val taskId: String? = null)

/** A job file on the phone and the plate a print used from it. */
private data class CachedJob(val file: File, val plate: Int)

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
 * The printer keeps one file per job name, and re-slicing or sending another plate of the
 * same project replaces it (a sent file carries only the plate being printed). So each
 * version is cached separately, keyed by the printer's modification time, and each run
 * remembers which version and plate it printed — history shows what actually printed.
 *
 * Runs whenever printer status flows with an active job, i.e. while the app is visible
 * or the print monitor is running — never from a frozen background process.
 */
class JobRepository(
    private val scope: CoroutineScope,
    private val printer: PrinterRepository,
    private val tls: PrinterTls,
    private val dir: File,
    private val assets: android.content.res.AssetManager,
) {
    private val _state = MutableStateFlow<JobLoad>(JobLoad.None)
    val state: StateFlow<JobLoad> = _state.asStateFlow()
    private var loading: Job? = null

    /** When each layer of the active job was seen starting (for the pace estimate). */
    private val _samples = MutableStateFlow<List<LayerSample>>(emptyList())
    val samples: StateFlow<List<LayerSample>> = _samples.asStateFlow()
    /** Whose samples [_samples] holds: the job plus the printer's task id for this run. */
    private var samplesKey: Pair<JobKey, String?>? = null
    private var lastSeenLayer: Int? = null

    init {
        scope.launch(Dispatchers.IO) { printer.status.collect(::recordLayer) }
        scope.launch {
            printer.status
                .map { s -> s?.takeIf { it.jobState.isActive }?.jobName?.let { JobKey(it, s.plateIndex ?: 1, s.taskId) } }
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
        val key = s?.takeIf { it.jobState.isActive }?.jobName?.let { JobKey(it, s.plateIndex ?: 1, s.taskId) } ?: return
        val layer = s.layer?.takeIf { it > 0 } ?: return
        // The same file printed again is a new task, with its own pace and plan anchor.
        val run = key to s.taskId
        if (run != samplesKey) {
            samplesKey = run
            lastSeenLayer = null
            _samples.value = loadSamples(key, s.taskId)
        }
        // Without a task id, a layer below the last sample means a new print of the same job.
        if (s.taskId == null && (_samples.value.lastOrNull()?.layer ?: 0) > layer) {
            _samples.value = emptyList()
            runCatching { samplesFile(key, null).delete() }
        }
        val seen = lastSeenLayer
        lastSeenLayer = layer
        val last = _samples.value.lastOrNull()?.layer
        val observedChange = seen != null && layer > seen
        if ((observedChange && (last == null || layer > last)) || _samples.value.isEmpty()) {
            val sample = LayerSample(layer, System.currentTimeMillis())
            _samples.value = _samples.value + sample
            runCatching { samplesFile(key, s.taskId).appendText("${sample.layer},${sample.atMs}\n") }
        }
    }

    private fun samplesFile(key: JobKey, taskId: String?) = File(
        dir.apply { mkdirs() },
        safeName(key.name) + (taskId?.let { "-task-" + it.filter(Char::isLetterOrDigit) } ?: "") + ".layers",
    )

    private fun loadSamples(key: JobKey, taskId: String?): List<LayerSample> = runCatching {
        samplesFile(key, taskId).readLines().mapNotNull { line ->
            val (l, t) = line.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            LayerSample(l.toIntOrNull() ?: return@mapNotNull null, t.toLongOrNull() ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /** Whether the job file [record] printed is still on the phone. */
    suspend fun hasArchived(record: PrintRecord): Boolean = withContext(Dispatchers.IO) { archived(record) != null }

    /** The job file [record] printed, for viewing its details from history. Null if not cached. */
    suspend fun readArchived(record: PrintRecord): JobLoad.Ready? = withContext(Dispatchers.IO) {
        val name = record.jobName ?: return@withContext null
        val found = archived(record) ?: return@withContext null
        runCatching { JobArchive.read(found.file, found.plate) }.getOrNull()
            ?.let { JobLoad.Ready(JobKey(name, found.plate), it) }
    }

    /**
     * The file and plate [record]'s run printed, as recorded when it loaded for that task.
     * No guessing from same-named files: a name is reused across plates and re-slices.
     */
    private fun archived(record: PrintRecord): CachedJob? = readRun(record.key)

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
                val file = key.taskId?.let { readRun(PrintHistory.keyFor(it, key.name, 0)) }?.file
                    ?: download(key)
                    ?: return@launch
                if (key.plate !in JobArchive.plates(file)) error("The printer's job file doesn't include plate ${key.plate}")
                key.taskId?.let { writeRun(PrintHistory.keyFor(it, key.name, 0), CachedJob(file, key.plate)) }
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

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")

    /** One version of a job file; `~` can't occur in a safe name, so it separates the version. */
    private fun versionFile(name: String, version: String) = File(dir, "${safeName(name)}~$version.gcode.3mf")

    /** Job name part of a cached file's name (older caches have no version). */
    private fun baseOf(f: File) = f.name.removeSuffix(".gcode.3mf").substringBefore('~')

    private fun runFile(runKey: String) = File(dir, "run-" + runKey.filter { it.isLetterOrDigit() || it == '-' } + ".ref")

    /** Which cached file and plate a run printed; null if unknown or since pruned. */
    private fun readRun(runKey: String): CachedJob? = runCatching {
        val (name, plate) = runFile(runKey).readLines().takeIf { it.size >= 2 } ?: return null
        CachedJob(File(dir, name).takeIf { it.exists() && it.length() > 0 } ?: return null, plate.toInt())
    }.getOrNull()

    private fun writeRun(runKey: String, job: CachedJob) {
        if (readRun(runKey) == job) return
        runCatching { runFile(runKey).writeText("${job.file.name}\n${job.plate}\n") }
    }

    /** Finds and downloads the job file; null (with state set) if it isn't on the printer. */
    private fun download(key: JobKey): File? {
        val cfg = printer.config.value ?: return null
        if (cfg.isDemo) return demoJob(key)
        val wanted = "${key.name}.gcode.3mf"
        FtpsClient.connect(tls.socketFactory(cfg.serial), cfg.host, "bblp", cfg.accessCode).use { ftp ->
            // Jobs sent over the LAN land at the root; some firmware uses /cache or /model.
            val (path, size) = SEARCH_DIRS.firstNotNullOfOrNull { d ->
                runCatching { ftp.list(d) }.getOrNull()?.firstOrNull { it.name == wanted }?.let { "${d.trimEnd('/')}/$wanted" to it.size }
            } ?: run {
                _state.value = JobLoad.Unavailable(key, "This job's file isn't on the printer's storage")
                return null
            }
            // The modification time tells this version apart from earlier ones of the same name.
            val version = ftp.modified(path)?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() } ?: "s$size"
            val file = versionFile(key.name, version)
            if (file.length() == size) return file
            dir.mkdirs()
            val part = File(dir, "${file.name}.part")
            _state.value = JobLoad.Downloading(key, 0, size)
            part.outputStream().buffered().use { out ->
                ftp.retrieve(path, out) { n -> _state.value = JobLoad.Downloading(key, n, size) }
            }
            if (part.length() != size) error("Download incomplete: ${part.length()} of $size bytes")
            part.renameTo(file)
            prune()
            Log.i(TAG, "downloaded $path ($size bytes)")
            return file
        }
    }

    /** The demo printer's job ships in the app's assets. */
    private fun demoJob(key: JobKey): File? {
        if (key.name != DemoPrinter.JOB_NAME) return null
        dir.mkdirs()
        val out = versionFile(key.name, "demo")
        assets.open("demo/${DemoPrinter.JOB_NAME}.gcode.3mf").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** Keep the most recently used job files only, and the run records and layer samples that go with them. */
    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".gcode.3mf") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED)
            ?.forEach { it.delete() }
        val kept = dir.listFiles { f -> f.name.endsWith(".gcode.3mf") }.orEmpty()
        val keptNames = kept.mapTo(HashSet()) { it.name }
        val keptBases = kept.mapTo(HashSet(), ::baseOf)
        dir.listFiles { f -> f.name.endsWith(".ref") }.orEmpty()
            .filter { ref -> runCatching { ref.readLines().first() }.getOrNull() !in keptNames }
            .forEach { it.delete() }
        dir.listFiles { f -> f.name.endsWith(".layers") }.orEmpty()
            .filter { f -> f.name.removeSuffix(".layers").let { b -> keptBases.none { b == it || b.startsWith("$it-task-") } } }
            .forEach { it.delete() }
    }

    private companion object {
        const val TAG = "layerwatch.job"
        const val MAX_CACHED = 12
        val SEARCH_DIRS = listOf("/", "/cache", "/model")
    }
}

package app.layerwatch.history

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

enum class Outcome { InProgress, Finished, Failed, Stopped, Unknown }

/** One print the monitor followed. */
data class PrintRecord(
    /** `task_id` when the printer reports one, else name + first-seen time. */
    val key: String,
    val jobName: String?,
    /** When Layerwatch first saw it running — the print may have started earlier. */
    val firstSeenAtMs: Long,
    val endedAtMs: Long?,
    val outcome: Outcome,
    val printError: String?,
    val totalLayers: Int?,
    /** Final chamber snapshot, relative to the history directory. */
    val snapshotFile: String?,
) {
    internal fun toJson() = buildJsonObject {
        put("key", key)
        jobName?.let { put("job", it) }
        put("seen", firstSeenAtMs)
        endedAtMs?.let { put("ended", it) }
        put("outcome", outcome.name)
        printError?.let { put("error", it) }
        totalLayers?.let { put("layers", it) }
        snapshotFile?.let { put("snapshot", it) }
    }

    companion object {
        internal fun fromJson(o: JsonObject): PrintRecord? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content
            fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            return PrintRecord(
                key = str("key") ?: return null,
                jobName = str("job"),
                firstSeenAtMs = long("seen") ?: return null,
                endedAtMs = long("ended"),
                outcome = str("outcome")?.let { runCatching { Outcome.valueOf(it) }.getOrNull() } ?: Outcome.Unknown,
                printError = str("error"),
                totalLayers = long("layers")?.toInt(),
                snapshotFile = str("snapshot"),
            )
        }
    }
}

/**
 * Print history kept on the phone: `history.json` plus one JPEG per print in [dir].
 * Small by nature (one entry per print), so the whole list is held in memory and the
 * file rewritten atomically on change. Newest first, capped at [MAX_RECORDS].
 */
class PrintHistory(private val dir: File) {
    private val file = File(dir, "history.json")
    private val lock = Mutex()
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<PrintRecord>> = _records.asStateFlow()

    /** Start (or resume) the record for a print seen running. Idempotent per key. */
    suspend fun begin(key: String, jobName: String?, totalLayers: Int?, nowMs: Long) = edit { list ->
        if (list.any { it.key == key }) list
        else listOf(PrintRecord(key, jobName, nowMs, null, Outcome.InProgress, null, totalLayers, null)) + list
    }

    suspend fun finish(key: String, outcome: Outcome, printError: String?, nowMs: Long) = edit { list ->
        list.map { if (it.key == key && it.outcome == Outcome.InProgress) it.copy(outcome = outcome, printError = printError, endedAtMs = nowMs) else it }
    }

    suspend fun attachSnapshot(key: String, bitmap: Bitmap) {
        val name = "snap-" + key.filter { it.isLetterOrDigit() || it == '-' }.take(40) + ".jpg"
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(File(dir, name))
        edit { list -> list.map { if (it.key == key) it.copy(snapshotFile = name) else it } }
    }

    /**
     * Closes records left "in progress" whose print is no longer running, e.g. because the
     * monitor wasn't alive when it ended. [activeKey] is the job the printer is running now
     * (null if none); [reportedKey]/[reportedOutcome] what it says about its last job.
     */
    suspend fun reconcile(activeKey: String?, reportedKey: String?, reportedOutcome: Outcome?, nowMs: Long) = edit { list ->
        list.map { r ->
            if (r.outcome != Outcome.InProgress || r.key == activeKey) r
            else r.copy(
                outcome = if (r.key == reportedKey && reportedOutcome != null) reportedOutcome else Outcome.Unknown,
                endedAtMs = nowMs,
            )
        }
    }

    fun snapshot(record: PrintRecord): File? = record.snapshotFile?.let { File(dir, it) }?.takeIf { it.exists() }

    suspend fun deleteWhere(predicate: (PrintRecord) -> Boolean) = edit { list ->
        list.filter(predicate).forEach { snapshot(it)?.delete() }
        list.filterNot(predicate)
    }

    suspend fun delete(key: String) = edit { list ->
        list.firstOrNull { it.key == key }?.let { snapshot(it)?.delete() }
        list.filterNot { it.key == key }
    }

    private suspend fun edit(change: (List<PrintRecord>) -> List<PrintRecord>) = lock.withLock {
        val next = change(_records.value).take(MAX_RECORDS)
        if (next == _records.value) return@withLock
        // Snapshots of records that fell off the end go with them.
        _records.value.filter { old -> next.none { it.key == old.key } }.forEach { snapshot(it)?.delete() }
        _records.value = next
        dir.mkdirs()
        val tmp = File(dir, "history.json.tmp")
        tmp.writeText(JsonArray(next.map { it.toJson() }).toString())
        tmp.renameTo(file)
    }

    private fun load(): List<PrintRecord> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { PrintRecord.fromJson(it.jsonObject) }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_RECORDS = 200

        fun keyFor(taskId: String?, jobName: String?, nowMs: Long) = taskId?.let { "task-$it" } ?: "job-${jobName ?: "print"}-$nowMs"
    }
}

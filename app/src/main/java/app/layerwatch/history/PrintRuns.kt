package app.layerwatch.history

import app.layerwatch.protocol.JobState
import app.layerwatch.protocol.PrinterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * One print as Layerwatch saw it. [id] is what history, the job file, layer samples and
 * the timelapse are all filed under. The printer's task id can't serve: in LAN mode it's
 * a random number below 10000, so it repeats across prints.
 */
data class PrintRun(
    val id: String,
    val taskId: String?,
    val jobName: String,
    val startedAtMs: Long,
    /** Set once the run was seen to end; a later print is then a new run whatever its task id. */
    val end: Outcome? = null,
) {
    val isActive get() = end == null

    /** Whether [s] reports this run, rather than another print. */
    fun matches(s: PrinterStatus) = s.jobName == jobName && (s.taskId == null || taskId == null || s.taskId == taskId)

    internal fun toJson() = buildJsonObject {
        put("id", id)
        taskId?.let { put("task", it) }
        put("job", jobName)
        put("started", startedAtMs)
        end?.let { put("end", it.name) }
    }

    internal companion object {
        fun fromJson(o: JsonObject): PrintRun? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content
            return PrintRun(
                id = str("id") ?: return null,
                taskId = str("task"),
                jobName = str("job") ?: return null,
                startedAtMs = (o["started"] as? JsonPrimitive)?.longOrNull ?: return null,
                end = str("end")?.let { runCatching { Outcome.valueOf(it) }.getOrNull() },
            )
        }
    }
}

/**
 * Tells prints apart: the run the printer is on (or last finished), kept in `run.json`
 * so it survives the process. Fed every status report by one collector, in order.
 *
 * A report with the same job (and task id, where both have one) continues the current
 * run, even across app restarts. A run ends when a report shows the job no longer
 * active; the next active job starts a new run. Two prints of the same job with the same
 * random task id, with no report seen between them, would still read as one.
 *
 * [legacyId]: records written before runs existed are filed under `task-<id>`; a print
 * already running at the upgrade keeps that record.
 */
class PrintRuns(dir: File, private val legacyId: (taskId: String) -> String? = { null }) {
    private val file = File(dir, "run.json")
    private val _current = MutableStateFlow(load())
    /** The run in progress, or the last one seen (then [PrintRun.end] is set). Null before any. */
    val current: StateFlow<PrintRun?> = _current.asStateFlow()

    @Synchronized
    fun observe(s: PrinterStatus, nowMs: Long) {
        val cur = _current.value
        val next = next(cur, s, nowMs, legacyId) ?: return
        if (next == cur) return
        _current.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(next.toJson().toString())
            tmp.renameTo(file)
        }
    }

    private fun load(): PrintRun? = runCatching { PrintRun.fromJson(Json.parseToJsonElement(file.readText()).jsonObject) }.getOrNull()

    internal companion object {
        /** The run after [s]; null when [s] says nothing about runs. */
        fun next(cur: PrintRun?, s: PrinterStatus, nowMs: Long, legacyId: (String) -> String? = { null }): PrintRun? = when {
            s.jobState == JobState.Unknown -> null
            s.jobState.isActive -> {
                val name = s.jobName
                when {
                    name == null -> null
                    cur != null && cur.isActive && cur.matches(s) -> if (cur.taskId == null && s.taskId != null) cur.copy(taskId = s.taskId) else cur
                    else -> PrintRun(
                        id = s.taskId?.takeIf { cur == null }?.let(legacyId) ?: "run-$nowMs",
                        taskId = s.taskId, jobName = name, startedAtMs = nowMs,
                    )
                }
            }
            cur == null || !cur.isActive -> null
            else -> cur.copy(
                end = when (s.jobState) {
                    JobState.Finished -> Outcome.Finished
                    JobState.Failed -> if (s.isStopped) Outcome.Stopped else Outcome.Failed
                    else -> Outcome.Unknown
                },
            )
        }
    }
}

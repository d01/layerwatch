package app.layerwatch.ui.details

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.layerwatch.LayerwatchApp
import app.layerwatch.job.JobLoad
import app.layerwatch.protocol.JobState
import app.layerwatch.protocol.LinkState
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.job.FilamentForecast
import app.layerwatch.protocol.job.FilamentNeed
import app.layerwatch.protocol.job.JobFile
import app.layerwatch.protocol.job.Schedule
import app.layerwatch.protocol.job.ScheduleEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DetailsState(
    val load: JobLoad = JobLoad.None,
    val status: PrinterStatus? = null,
    val schedule: ScheduleEstimate? = null,
    val filament: List<FilamentNeed> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
    /** Skip-object state for the running print; null when there's nothing live to skip. */
    val skip: SkipState? = null,
) {
    val currentLayer: Int get() = status?.layer ?: 0
}

/**
 * What the objects card needs to offer skipping. [unavailable] says why skipping can't be
 * offered at all (shown once, not per object); otherwise each object can be skipped
 * unless it's done, in flight, or the last one left.
 */
data class SkipState(
    val skipped: Set<Int>,
    /** Sent, not yet in the printer's `s_obj`. */
    val pending: Set<Int>,
    /** Sent (twice) and never confirmed: the user should look and try again. */
    val unconfirmed: Set<Int>,
    val unavailable: String?,
    /** A send that never left the phone (no connection). Cleared by the next attempt. */
    val notSent: Int? = null,
)

/** One skip request for one print, so a new print never inherits stale pending state. */
private data class SkipRequest(val taskId: String?, val id: Int, val confirmed: Boolean = false, val gaveUp: Boolean = false)

class DetailsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LayerwatchApp

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(15_000)
        }
    }

    private val requests = MutableStateFlow<List<SkipRequest>>(emptyList())
    private val notSent = MutableStateFlow<Pair<String?, Int>?>(null)
    private val live = combine(graph.printer.status, graph.printer.link) { s, l -> s to (l is LinkState.Online) }
    private val skipInputs = combine(requests, notSent) { r, n -> r to n }

    val state: StateFlow<DetailsState> = combine(
        graph.jobs.state, graph.jobs.samples, live, ticker, skipInputs,
    ) { load, samples, (status, online), now, (reqs, failedSend) ->
        val job = (load as? JobLoad.Ready)?.job
        val model = job?.gcode
        val layer = status?.layer ?: 0
        DetailsState(
            load = load,
            status = status,
            schedule = model?.let { Schedule.estimate(it, samples, now, status?.remainingMinutes) },
            filament = if (model != null && status != null) {
                FilamentForecast.compute(
                    model, job.settings, layer, status.amsMapping,
                    status.ams.flatMap { it.trays }.associateBy { it.slot },
                )
            } else emptyList(),
            nowMs = now,
            skip = if (job != null && status != null) skipState(job, status, online, reqs, failedSend) else null,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsState())

    fun retry() = graph.jobs.retry()

    /** Keep the printer connection open while details are on screen. */
    fun acquireConnection(): () -> Unit = graph.printer.acquire()

    private val skipJobs = mutableMapOf<Int, Job>()

    /**
     * Skip one object (already confirmed by the 4 s hold). The printer's `s_obj` is the
     * only proof it happened: commands go out at QoS 0 over weak WiFi and can die with
     * the session, so if `s_obj` hasn't changed in [CONFIRM_MS] it's sent once more
     * (skipping is idempotent) and then left to the user, flagged as unconfirmed.
     */
    fun skipObject(id: Int) {
        val status = graph.printer.status.value ?: return
        if (id in status.skippedObjects) return
        val task = status.taskId
        skipJobs[id]?.cancel()
        notSent.value = null
        requests.update { list -> list.filterNot { it.id == id } + SkipRequest(task, id) }
        skipJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            repeat(SEND_ATTEMPTS) { attempt ->
                val conn = graph.printer.connection.value
                if (conn == null || !conn.skipObjects(listOf(id))) {
                    Log.w(TAG, "skip $id: not sent (offline)")
                    requests.update { list -> list.filterNot { it.id == id } }
                    notSent.value = task to id
                    return@launch
                }
                Log.i(TAG, "skip $id: sent (attempt ${attempt + 1})")
                val done = withTimeoutOrNull(CONFIRM_MS) {
                    graph.printer.status.first { it == null || it.taskId != task || id in it.skippedObjects }
                }
                if (done != null) {
                    Log.i(TAG, "skip $id: confirmed by printer")
                    requests.update { list -> list.map { if (it.id == id) it.copy(confirmed = true) else it } }
                    return@launch
                }
            }
            Log.w(TAG, "skip $id: printer never reported it skipped")
            requests.update { list -> list.map { if (it.id == id) it.copy(gaveUp = true) else it } }
        }
    }

    private fun skipState(job: JobFile, s: PrinterStatus, online: Boolean, reqs: List<SkipRequest>, failedSend: Pair<String?, Int>?): SkipState? {
        if (!s.jobState.isActive) return null
        val objects = job.slice?.objects.orEmpty()
        if (objects.isEmpty()) return null
        val mine = reqs.filter { it.taskId == s.taskId }
        val skipped = s.skippedObjects + objects.filter { it.skipped }.map { it.identifyId }
        return SkipState(
            skipped = skipped,
            pending = mine.filter { !it.confirmed && !it.gaveUp && it.id !in skipped }.map { it.id }.toSet(),
            unconfirmed = mine.filter { it.gaveUp && it.id !in skipped }.map { it.id }.toSet(),
            unavailable = when {
                s.commandsLocked -> "Skipping needs Developer Mode on the printer, like the other controls."
                !s.supportsSkipObjects -> "This printer's firmware doesn't offer skipping objects."
                job.slice?.labelObjects == false -> "This plate was sliced without object labels, so the printer can't skip parts. " +
                    "Turn on Label objects in the slicer for future prints."
                objects.size > MAX_OBJECTS -> "The printer can only skip objects on plates with $MAX_OBJECTS or fewer."
                s.jobState != JobState.Running && s.jobState != JobState.Paused -> "Skipping becomes available once printing starts."
                !online -> "Reconnecting to the printer…"
                else -> null
            },
            notSent = failedSend?.takeIf { it.first == s.taskId }?.second,
        )
    }

    private companion object {
        const val TAG = "layerwatch.skip"
        const val CONFIRM_MS = 12_000L
        const val SEND_ATTEMPTS = 2
        /** Bambu Studio refuses plates with more (PartSkipDialog). */
        const val MAX_OBJECTS = 64
    }
}

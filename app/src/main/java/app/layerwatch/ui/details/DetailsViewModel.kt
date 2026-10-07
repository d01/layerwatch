package app.layerwatch.ui.details

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.layerwatch.LayerwatchApp
import app.layerwatch.job.JobLoad
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.job.FilamentForecast
import app.layerwatch.protocol.job.FilamentNeed
import app.layerwatch.protocol.job.Schedule
import app.layerwatch.protocol.job.ScheduleEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class DetailsState(
    val load: JobLoad = JobLoad.None,
    val status: PrinterStatus? = null,
    val schedule: ScheduleEstimate? = null,
    val filament: List<FilamentNeed> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
) {
    val currentLayer: Int get() = status?.layer ?: 0
}

class DetailsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LayerwatchApp

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(15_000)
        }
    }

    val state: StateFlow<DetailsState> = combine(
        graph.jobs.state, graph.jobs.samples, graph.printer.status, ticker,
    ) { load, samples, status, now ->
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
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsState())

    fun retry() = graph.jobs.retry()

    /** Keep the printer connection open while details are on screen. */
    fun acquireConnection(): () -> Unit = graph.printer.acquire()
}

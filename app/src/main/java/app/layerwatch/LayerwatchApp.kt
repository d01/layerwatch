package app.layerwatch

import android.app.Application
import app.layerwatch.config.AppSettings
import app.layerwatch.config.PrinterConfigStore
import app.layerwatch.history.Outcome
import app.layerwatch.history.PrintHistory
import app.layerwatch.protocol.JobState
import app.layerwatch.history.Timelapses
import app.layerwatch.job.JobRepository
import app.layerwatch.printer.NetworkMonitor
import app.layerwatch.printer.PrinterRepository
import app.layerwatch.printer.PrinterTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.layerwatch.widget.WidgetState
import app.layerwatch.widget.WidgetStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class LayerwatchApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore by lazy { PrinterConfigStore(this) }
    val settings by lazy { AppSettings(this) }
    val history by lazy { PrintHistory(File(filesDir, "history")) }
    val jobs by lazy { JobRepository(appScope, printer, tls, File(cacheDir, "jobs")) }
    val timelapses by lazy { Timelapses(printer, tls, File(filesDir, "history"), File(cacheDir, "timelapse")) }
    val tls by lazy { PrinterTls(this) }
    val printer by lazy { PrinterRepository(appScope, configStore, tls, NetworkMonitor(this)) }

    override fun onCreate() {
        super.onCreate()
        jobs // start following the running job's file
        // Close history entries whose print ended while nothing was watching.
        appScope.launch {
            printer.status.collect { s ->
                if (s == null || s.jobState == JobState.Unknown) return@collect
                val key = PrintHistory.keyFor(s.taskId, s.jobName, 0)
                val outcome = when (s.jobState) {
                    JobState.Finished -> Outcome.Finished
                    JobState.Failed -> Outcome.Failed
                    else -> null
                }
                history.reconcile(activeKey = key.takeIf { s.jobState.isActive }, reportedKey = key, reportedOutcome = outcome, nowMs = System.currentTimeMillis())
            }
        }
        // Mirror printer state to the home-screen widget whenever reports flow (app open
        // or monitor running). WidgetStore skips writes the widget wouldn't show.
        appScope.launch {
            combine(printer.config, printer.status, printer.lastReportAtMs, ::Triple).collect { (cfg, s, at) ->
                if (cfg != null && s != null && at != null) WidgetStore.push(this@LayerwatchApp, WidgetState.from(cfg.serial, s, at))
            }
        }
    }
}

package app.layerwatch

import android.app.Application
import app.layerwatch.config.AppSettings
import app.layerwatch.config.PrinterConfigStore
import app.layerwatch.history.Outcome
import app.layerwatch.history.PrintHistory
import app.layerwatch.history.PrintRuns
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
    /** Which print is which; everything per print is filed under its run id. */
    val runs by lazy {
        PrintRuns(File(filesDir, "history")) { taskId ->
            history.records.value.firstOrNull { it.key == "task-$taskId" && it.outcome == Outcome.InProgress }?.key
        }
    }
    val jobs by lazy { JobRepository(appScope, printer, tls, runs.current, File(cacheDir, "jobs"), assets) }
    val timelapses by lazy { Timelapses(printer, tls, File(filesDir, "history"), File(cacheDir, "timelapse")) }
    val tls by lazy { PrinterTls(this) }
    val printer by lazy { PrinterRepository(appScope, configStore, tls, NetworkMonitor(this)) }

    override fun onCreate() {
        super.onCreate()
        // The one place reports become runs, in order.
        appScope.launch { printer.status.collect { s -> if (s != null) runs.observe(s, System.currentTimeMillis()) } }
        jobs // start following the running job's file
        // Close history entries whose print ended while nothing was watching.
        appScope.launch {
            runs.current.collect { run ->
                if (run == null) return@collect
                history.reconcile(activeKey = run.id.takeIf { run.isActive }, reportedKey = run.id, reportedOutcome = run.end, nowMs = System.currentTimeMillis())
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

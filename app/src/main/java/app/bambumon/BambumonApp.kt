package app.bambumon

import android.app.Application
import app.bambumon.config.AppSettings
import app.bambumon.config.PrinterConfigStore
import app.bambumon.history.PrintHistory
import app.bambumon.printer.NetworkMonitor
import app.bambumon.printer.PrinterRepository
import app.bambumon.printer.PrinterTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.bambumon.widget.WidgetState
import app.bambumon.widget.WidgetStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class BambumonApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore by lazy { PrinterConfigStore(this) }
    val settings by lazy { AppSettings(this) }
    val history by lazy { PrintHistory(File(filesDir, "history")) }
    val tls by lazy { PrinterTls(this) }
    val printer by lazy { PrinterRepository(appScope, configStore, tls, NetworkMonitor(this)) }

    override fun onCreate() {
        super.onCreate()
        // Mirror printer state to the home-screen widget whenever reports flow (app open
        // or monitor running). WidgetStore skips writes the widget wouldn't show.
        appScope.launch {
            combine(printer.config, printer.status, printer.lastReportAtMs, ::Triple).collect { (cfg, s, at) ->
                if (cfg != null && s != null && at != null) WidgetStore.push(this@BambumonApp, WidgetState.from(cfg.serial, s, at))
            }
        }
    }
}

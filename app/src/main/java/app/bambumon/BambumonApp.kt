package app.bambumon

import android.app.Application
import app.bambumon.config.PrinterConfigStore
import app.bambumon.printer.NetworkMonitor
import app.bambumon.printer.PrinterRepository
import app.bambumon.printer.PrinterTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BambumonApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore by lazy { PrinterConfigStore(this) }
    val tls by lazy { PrinterTls(this) }
    val printer by lazy { PrinterRepository(appScope, configStore, tls, NetworkMonitor(this)) }
}

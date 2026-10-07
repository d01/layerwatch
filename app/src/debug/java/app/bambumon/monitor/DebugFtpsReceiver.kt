package app.bambumon.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.bambumon.BambumonApp
import app.bambumon.protocol.ftps.FtpsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Debug builds only: checks the printer's FTPS storage over the app's pinned TLS, on the
 * device's own TLS stack. Read-only.
 *
 *   adb shell am broadcast -n app.bambumon/.monitor.DebugFtpsReceiver
 */
class DebugFtpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BambumonApp
        Log.i(TAG, "received")
        val cfg = app.printer.config.value ?: run { Log.w(TAG, "no printer configured (config not loaded yet?)"); return }
        Log.i(TAG, "starting FTPS check against ${cfg.host}")
        // Hold the broadcast open: otherwise the cached-app freezer suspends the process
        // mid-transfer when nothing else of the app is running.
        val pending = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            runCatching {
                FtpsClient.connect(
                    app.tls.socketFactory(cfg.serial), cfg.host, "bblp", cfg.accessCode,
                    trace = { Log.i(TAG, it) },
                ).use { ftp ->
                    val jobs = ftp.list("/").filter { it.name.endsWith(".gcode.3mf") }
                    Log.i(TAG, "listed ${jobs.size} job files")
                    val smallest = jobs.minBy { it.size }
                    val n = ftp.retrieve("/${smallest.name}", ByteArrayOutputStream())
                    Log.i(TAG, "downloaded ${smallest.name}: $n of ${smallest.size} bytes")
                    Log.i(TAG, "timelapse files: ${ftp.list("/timelapse").size}")
                }
            }.onFailure { Log.e(TAG, "FTPS check failed", it) }
            pending.finish()
        }
    }

    private companion object { const val TAG = "bambumon.ftps" }
}

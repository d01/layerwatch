package app.layerwatch.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.layerwatch.LayerwatchApp
import app.layerwatch.protocol.HmsAlert
import app.layerwatch.protocol.PrintEvent
import kotlinx.coroutines.launch

/**
 * Debug builds only. Posts a sample alert through the real path (text first, snapshot
 * after). Guarded by DUMP, which only the shell holds:
 *
 *   adb shell am broadcast -n app.layerwatch/.monitor.DebugAlertReceiver --es event first_layer
 *
 * events: first_layer | finished | failed | stopped | paused | hms
 */
class DebugAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LayerwatchApp
        val job = app.printer.status.value?.jobName ?: "test print"
        val event = when (intent.getStringExtra("event")) {
            "finished" -> PrintEvent.Finished(job)
            "failed" -> PrintEvent.Failed(job, "0300_8016")
            "stopped" -> PrintEvent.Stopped(job)
            "paused" -> PrintEvent.Paused(job, "Paused: filament runout", byPrinter = true)
            "hms" -> PrintEvent.NewHms(job, listOf(HmsAlert(0x07002000, 0x00020001)))
            else -> PrintEvent.FirstLayerDone(job)
        }
        // Not goAsync: a snapshot can outlast a receiver's ~10 s budget. The app scope
        // carries it; fine for a debug hook.
        app.appScope.launch { postAlertWithSnapshot(context, MonitorNotifications(context), event) }
    }
}

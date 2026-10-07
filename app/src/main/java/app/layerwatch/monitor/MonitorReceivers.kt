package app.layerwatch.monitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.layerwatch.LayerwatchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Notification actions. */
class MonitorActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LIGHT) return
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        val conn = (context.applicationContext as LayerwatchApp).printer.connection.value ?: return
        val pending = goAsync()
        (context.applicationContext as LayerwatchApp).appScope.launch(Dispatchers.IO) {
            try {
                conn.setChamberLight(on)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_LIGHT = "app.layerwatch.action.LIGHT"
        private const val EXTRA_ON = "on"

        fun lightIntent(context: Context, on: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context, if (on) 1 else 0,
            Intent(context, MonitorActionReceiver::class.java).setAction(ACTION_LIGHT).putExtra(EXTRA_ON, on),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

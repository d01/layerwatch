package app.layerwatch.monitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.layerwatch.LayerwatchApp
import app.layerwatch.camera.Snapshot
import app.layerwatch.printer.printerModelName
import app.layerwatch.protocol.PrintEvent

/**
 * Posts an alert immediately, then attaches a chamber snapshot when one arrives. The
 * text alert never waits on the camera. Returns the snapshot so callers can reuse it
 * (history, widget).
 */
suspend fun postAlertWithSnapshot(context: Context, notes: MonitorNotifications, event: PrintEvent): Bitmap? {
    val app = context.applicationContext as LayerwatchApp
    val config = app.printer.config.value
    val model = config?.serial?.let(::printerModelName) ?: "printer"
    if (!notes.alert(event, model) || config == null) return null
    val picture = Snapshot.grab(context, app.tls, config) ?: return null
    Log.i("layerwatch.monitor", "snapshot ${picture.width}x${picture.height} attached to $event")
    notes.alert(event, model, picture)
    return picture
}

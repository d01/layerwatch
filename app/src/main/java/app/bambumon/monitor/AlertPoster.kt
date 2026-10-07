package app.bambumon.monitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.bambumon.BambumonApp
import app.bambumon.camera.Snapshot
import app.bambumon.printer.printerModelName
import app.bambumon.protocol.PrintEvent

/**
 * Posts an alert immediately, then attaches a chamber snapshot when one arrives. The
 * text alert never waits on the camera. Returns the snapshot so callers can reuse it
 * (history, widget).
 */
suspend fun postAlertWithSnapshot(context: Context, notes: MonitorNotifications, event: PrintEvent): Bitmap? {
    val app = context.applicationContext as BambumonApp
    val config = app.printer.config.value
    val model = config?.serial?.let(::printerModelName) ?: "printer"
    if (!notes.alert(event, model) || config == null) return null
    val picture = Snapshot.grab(context, app.tls, config) ?: return null
    Log.i("bambumon.monitor", "snapshot ${picture.width}x${picture.height} attached to $event")
    notes.alert(event, model, picture)
    return picture
}

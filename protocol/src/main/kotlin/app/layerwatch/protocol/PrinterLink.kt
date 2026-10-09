package app.layerwatch.protocol

import kotlinx.coroutines.flow.StateFlow

/**
 * A printer the app can watch and command: the real MQTT [PrinterConnection], or the
 * [DemoPrinter] used when no printer is set up.
 */
interface PrinterLink {
    val link: StateFlow<LinkState>
    val status: StateFlow<PrinterStatus?>
    val lastReportAtMs: StateFlow<Long?>

    fun start()
    fun stop()
    fun reconnectNow()

    fun setChamberLight(on: Boolean): Boolean
    fun pause(): Boolean
    fun resume(): Boolean
    fun stopPrint(): Boolean
    fun setSpeed(level: SpeedLevel): Boolean
}

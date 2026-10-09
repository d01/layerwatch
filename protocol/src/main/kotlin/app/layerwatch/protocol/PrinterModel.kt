package app.layerwatch.protocol

/**
 * What differs between Bambu Lab models, as far as monitoring goes. Serial prefixes are
 * from Bambu Studio's printer definitions (the "sn_prefix" in each file under resources/printers).
 */
enum class PrinterModel(
    val prefix: String?,
    val displayName: String,
    /** How the chamber camera is reached over the LAN. */
    val camera: CameraKind,
    /** Has a chamber temperature sensor (the others report a placeholder). */
    val chamberSensor: Boolean,
) {
    X1Carbon("00M", "X1 Carbon", CameraKind.Rtsps, true),
    X1("00W", "X1", CameraKind.Rtsps, true),
    X1E("03W", "X1E", CameraKind.Rtsps, true),
    P1P("01S", "P1P", CameraKind.Jpeg, false),
    P1S("01P", "P1S", CameraKind.Jpeg, false),
    P2S("22E", "P2S", CameraKind.Rtsps, true),
    A1("039", "A1", CameraKind.Jpeg, false),
    A1Mini("030", "A1 mini", CameraKind.Jpeg, false),
    A2L("26A", "A2L", CameraKind.Jpeg, false),
    H2D("094", "H2D", CameraKind.Rtsps, true),
    H2DPro("239", "H2D Pro", CameraKind.Rtsps, true),
    H2S("093", "H2S", CameraKind.Rtsps, true),
    H2C("31B", "H2C", CameraKind.Rtsps, true),
    X2D("20P", "X2D", CameraKind.Rtsps, true),
    /** A model this app doesn't know yet: the printer's report decides the camera. */
    Unknown(null, "Printer", CameraKind.Rtsps, true);

    enum class CameraKind {
        /** H.264 over RTSPS on port 322 (X1, P2S, H2, X2 series). */
        Rtsps,

        /** A JPEG every second or two over TLS on port 6000 (P1 and A1 series). */
        Jpeg,
    }

    companion object {
        fun fromSerial(serial: String): PrinterModel =
            entries.firstOrNull { it.prefix != null && serial.startsWith(it.prefix, ignoreCase = true) } ?: Unknown
    }
}

/** What the printer says about its RTSPS live view (`ipcam.rtsp_url`). */
enum class Liveview {
    /** Serving RTSPS on 322. */
    Rtsps,

    /** Has RTSPS, but "LAN Only Liveview" is switched off on the printer. */
    Disabled,

    /** Field absent: models with the port-6000 JPEG camera. */
    NotReported;

    companion object {
        fun from(rtspUrl: String?): Liveview = when {
            rtspUrl == null -> NotReported
            rtspUrl.startsWith("rtsp", ignoreCase = true) -> Rtsps
            else -> Disabled // "disable", or empty
        }
    }
}

/** Which camera to use: the printer's own report wins; the model decides when it's silent. */
fun cameraKind(model: PrinterModel, liveview: Liveview?): PrinterModel.CameraKind = when (liveview) {
    Liveview.Rtsps, Liveview.Disabled -> PrinterModel.CameraKind.Rtsps
    Liveview.NotReported -> PrinterModel.CameraKind.Jpeg
    null -> model.camera
}

package app.layerwatch.printer

/** Bambu serial prefixes → model names; unknown prefixes fall back to "Printer". */
fun printerModelName(serial: String): String =
    if (serial == "DEMO") "Demo printer" else app.layerwatch.protocol.PrinterModel.fromSerial(serial).displayName

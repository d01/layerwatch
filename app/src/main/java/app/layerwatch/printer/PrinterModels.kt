package app.layerwatch.printer

/** Bambu serial prefixes → model names; unknown prefixes fall back to "Printer". */
fun printerModelName(serial: String): String = when (serial.take(3)) {
    "00M" -> "X1 Carbon"
    "00W" -> "X1"
    "03W" -> "X1E"
    "01S" -> "P1P"
    "01P" -> "P1S"
    "030" -> "A1 mini"
    "039" -> "A1"
    "094" -> "H2D"
    else -> "Printer"
}

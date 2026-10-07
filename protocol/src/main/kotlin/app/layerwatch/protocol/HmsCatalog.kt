package app.layerwatch.protocol

/** HMS levels, from the high half of the alert's code. */
enum class HmsSeverity(val level: Int, val label: String) {
    Fatal(1, "Fatal error"), Serious(2, "Error"), Common(3, "Notice"), Info(4, "Info"), Unknown(0, "Alert");

    /** Needs the user now (vs. something the printer handled itself). */
    val isError get() = this == Fatal || this == Serious

    companion object {
        fun of(level: Int) = entries.firstOrNull { it.level == level } ?: Unknown
    }
}

val HmsAlert.level: HmsSeverity get() = HmsSeverity.of(severity)

/**
 * Plain-English texts for HMS alerts and print errors, from Bambu Lab's public list
 * (bundled as `hms_en.tsv`; refresh with tools/update_hms.py). Unknown codes fall back to
 * the module that raised them.
 */
object HmsCatalog {
    private val texts: Map<String, String> by lazy {
        val stream = HmsCatalog::class.java.getResourceAsStream("/hms_en.tsv") ?: return@lazy emptyMap()
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotEmpty() && it[0] != '#' }.mapNotNull { l ->
                val parts = l.split('\t', limit = 3)
                if (parts.size == 3) "${parts[0]}:${parts[1]}" to parts[2] else null
            }.toMap()
        }
    }

    fun describe(alert: HmsAlert): String =
        texts["H:" + "%08X%08X".format(alert.attr, alert.code)]
            ?: "${module(alert.attr)} ${alert.level.label.lowercase()} (HMS ${alert.id})"

    /** For `print_error` values, formatted as the printer reports them (e.g. 0x0300400C). */
    fun describeError(code: Long): String? = texts["E:" + "%08X".format(code)]

    private fun module(attr: Long): String = when ((attr ushr 24).toInt()) {
        0x03, 0x05 -> "Printer"
        0x07 -> "AMS"
        0x08 -> "Toolhead"
        0x0C -> "Camera"
        0x12 -> "AMS Lite"
        0x18 -> "AMS HT"
        else -> "Printer"
    }
}

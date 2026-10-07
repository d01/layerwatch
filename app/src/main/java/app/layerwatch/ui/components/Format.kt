package app.layerwatch.ui.components

import androidx.compose.ui.graphics.Color
import app.layerwatch.protocol.JobState
import app.layerwatch.protocol.PrinterStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Format {
    private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** "2h 02m", "14m", or "<1m". */
    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "<1m"
        minutes < 60 -> "${minutes}m"
        else -> "%dh %02dm".format(minutes / 60, minutes % 60)
    }

    fun finishTime(nowMs: Long, remainingMinutes: Int): String =
        Instant.ofEpochMilli(nowMs + remainingMinutes * 60_000L).atZone(ZoneId.systemDefault()).format(clock)

    fun clockTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)

    fun temp(c: Double?): String = c?.let { "${it.toInt()}°" } ?: "—"

    fun age(sec: Long): String = when {
        sec < 60 -> "${sec} s"
        sec < 3600 -> "${sec / 60} min"
        else -> "${sec / 3600} h"
    }

    /** The eyebrow over the job name: the sub-stage while active, else the job state. */
    fun phase(s: PrinterStatus): String = when (s.jobState) {
        JobState.Running -> s.stage?.label ?: "Printing"
        JobState.Paused -> s.stage?.takeIf { it.isPause }?.label ?: "Paused"
        JobState.Preparing -> s.stage?.label ?: "Preparing"
        JobState.Slicing -> "Slicing"
        JobState.Finished -> "Finished"
        JobState.Failed -> "Failed"
        JobState.Idle -> "Idle"
        JobState.Unknown -> "—"
    }

    /** Filament colors arrive as RRGGBBAA; null or fully transparent means "clear". */
    fun filamentColor(hex: String?): Color? {
        if (hex == null || hex.length < 6) return null
        val rgb = hex.substring(0, 6).toLongOrNull(16) ?: return null
        val alpha = if (hex.length >= 8) hex.substring(6, 8).toIntOrNull(16) ?: 255 else 255
        if (alpha == 0) return null
        return Color(0xFF000000 or rgb)
    }
}

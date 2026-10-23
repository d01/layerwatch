package app.layerwatch.ui.details

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.layerwatch.R
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.job.FilamentNeed
import app.layerwatch.protocol.job.FilamentVerdict
import app.layerwatch.protocol.job.JobFile
import app.layerwatch.protocol.job.ScheduleEstimate
import app.layerwatch.ui.components.Format
import app.layerwatch.ui.components.Glyph
import app.layerwatch.ui.components.Panel
import app.layerwatch.ui.components.Spool
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette
import kotlin.math.abs

// ---------- schedule ----------

/** Finish time at the observed pace is the headline; the slicer's plan sits beside it. */
@Composable
fun ScheduleCard(e: ScheduleEstimate?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val t = LocalBambuType.current
    val delta = e?.deltaMin
    val (label, color) = when {
        delta == null -> "Estimating" to Palette.muted
        abs(delta) <= 5 -> "On plan" to Palette.blue
        delta > 0 -> "Behind plan" to Palette.amber
        else -> "Ahead of plan" to Palette.blue
    }
    Panel(modifier, padding = if (compact) 14.dp else 18.dp, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Schedule", style = t.section, color = Palette.text, modifier = Modifier.weight(1f))
                Text(label.uppercase(), style = t.eyebrow, color = color)
            }
        } else {
            Text(label.uppercase(), style = t.eyebrow.copy(fontSize = t.tiny.fontSize), color = color)
        }
        Text(e?.paceFinishMs?.let(Format::clockTime) ?: "—", style = t.metric.copy(fontSize = t.metric.fontSize * 0.85f), color = Palette.text)
        val sub = buildList {
            add(if (e?.paceRatio != null) "at actual pace" else "printer estimate")
            e?.planFinishMs?.let { add("slicer ${Format.clockTime(it)}") }
            delta?.takeIf { it != 0L }?.let { add((if (it > 0) "+" else "−") + "${abs(it)} min") }
        }.joinToString(" · ")
        Text(sub, style = t.caption, color = Palette.muted)
    }
}

// ---------- filament ----------

@Composable
fun FilamentCard(
    needs: List<FilamentNeed>,
    job: JobFile,
    status: PrinterStatus?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val t = LocalBambuType.current
    val worst = needs.maxByOrNull { it.verdict.ordinal.let { o -> if (it.verdict == FilamentVerdict.Unknown) -1 else o } }
    val (label, color) = when (worst?.verdict) {
        FilamentVerdict.Short -> "Will run out" to Palette.danger
        FilamentVerdict.Tight -> "Tight" to Palette.amber
        FilamentVerdict.Enough -> "Enough ✓" to Palette.blue
        else -> "Unknown" to Palette.muted
    }
    val trays = status?.ams.orEmpty().flatMap { it.trays }.associateBy { it.slot }
    if (compact) {
        val n = needs.firstOrNull()
        Panel(modifier, padding = 14.dp, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(), style = t.eyebrow.copy(fontSize = t.tiny.fontSize), color = color)
            Text(n?.let { grams(it.neededG) } ?: "—", style = t.metric.copy(fontSize = t.metric.fontSize * 0.85f), color = Palette.text)
            Text(
                n?.let { "still to print" + (it.availableG?.let { a -> "\n~${grams(a)} on spool" } ?: "") } ?: "no data",
                style = t.caption, color = Palette.muted,
            )
        }
        return
    }
    Panel(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Filament", style = t.section, color = Palette.text, modifier = Modifier.weight(1f))
            Text(label.uppercase(), style = t.eyebrow, color = color)
        }
        needs.forEach { n ->
            val tray = n.amsSlot?.let { trays[it] }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tray != null) Spool(tray, active = n.amsSlot == status?.activeSlot, size = 30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        listOfNotNull(n.amsSlot?.let { "Slot ${it % 4 + 1}" }, tray?.name ?: tray?.material).joinToString(" · ").ifBlank { "Filament ${n.tool + 1}" },
                        style = t.label, color = Palette.text,
                    )
                    Text("${grams(n.neededG)} still to print", style = t.mono, color = Palette.text)
                    n.availableG?.let { Text("~${grams(it)} on spool", style = t.mono, color = Palette.text) }
                    val avail = n.availableG
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.track)) {
                        if (avail != null && avail > 0) {
                            Box(Modifier.fillMaxWidth(1f).height(6.dp).background(Palette.outline))
                            Box(Modifier.fillMaxWidth((n.neededG / avail).toFloat().coerceIn(0f, 1f)).height(6.dp).background(color))
                        }
                    }
                    if (n.backupSlots.isNotEmpty()) {
                        Text("incl. backup " + n.backupSlots.joinToString(", ") { "slot ${it % 4 + 1}" } + " (auto-refill)", style = t.caption, color = Palette.muted)
                    }
                    n.runsOutAtLayer?.let { Text("Runs out around layer $it", style = t.caption, color = Palette.danger) }
                }
            }
        }
        // Totals from the G-code itself, so printed + still to print add up. They run a little
        // over the slicer's figure (Slicing card): it leaves part of the start-up purge out.
        val footer = needs.singleOrNull()?.let { n ->
            listOfNotNull(
                "${grams(n.printedG)} printed so far",
                "whole job ${grams(n.printedG + n.neededG)} incl. purges",
                n.leftAfterG?.let { "≈ ${grams(it)} left on the spool after" },
            ).joinToString(" · ")
        }.orEmpty()
        if (footer.isNotBlank()) Text(footer, style = t.caption, color = Palette.muted)
    }
}

private fun grams(g: Double) = if (g >= 100) "%.0f g".format(g) else "%.1f g".format(g)

// ---------- slicing ----------

@Composable
fun SlicingCard(job: JobFile, render: ImageBitmap?, modifier: Modifier = Modifier, full: Boolean) {
    val t = LocalBambuType.current
    val s = job.slice
    val st = job.settings
    val slot = s?.filaments?.firstOrNull()?.id?.minus(1) ?: 0
    Panel(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(if (full) 96.dp else 76.dp).clip(RoundedCornerShape(14.dp)).background(Palette.well)) {
                render?.let { Image(it, "Slicer render of the plate", contentScale = ContentScale.Fit) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Slicing", style = t.section, color = Palette.text)
                s?.slicer?.let { Text(it, style = t.caption, color = Palette.muted) }
                st?.printProfile?.let { Text(it, style = t.caption, color = Palette.muted, maxLines = 2) }
            }
        }
        if (full) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val g = job.gcode
                Tile(s?.predictionSec?.let { Format.duration(it / 60) } ?: g?.header?.totalEstimatedSec?.let { Format.duration(it / 60) } ?: "—", "predicted", Modifier.weight(1f))
                Tile(s?.weightG?.let { "%.1f g".format(it) } ?: "—", s?.filaments?.firstOrNull()?.let { f -> listOfNotNull(f.usedM?.let { "%.1f m".format(it) }, f.type).joinToString(" ") } ?: "filament", Modifier.weight(1f))
                val cost = s?.weightG?.let { w -> st?.filamentCostPerKg(slot)?.let { w * it / 1000 } }
                Tile(cost?.let { "≈ $%.2f".format(it) } ?: "—", st?.filamentCostPerKg(slot)?.let { "at $%.2f/kg".format(it) } ?: "cost", Modifier.weight(1f))
            }
        }
        val groups = settingGroups(job, slot)
        (if (full) groups else listOf("" to groups.flatMap { it.second }.take(6))).forEach { (title, rows) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title.isNotEmpty()) Text(title.uppercase(), style = t.eyebrow, color = Palette.faint, modifier = Modifier.padding(bottom = 4.dp))
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(k, style = t.label, color = Palette.muted, modifier = Modifier.weight(1f))
                        Text(v, style = t.mono, color = Palette.text)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
                }
            }
        }
        s?.warnings?.forEach { w ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.amberSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(R.drawable.ic_warning, Palette.amberText, 14.dp)
                Text("Slicer warning: ${w.message.replaceFirstChar { it.lowercase() }}", style = t.caption, color = Palette.amberText)
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    val t = LocalBambuType.current
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Palette.well).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = t.metricSmall, color = Palette.text, maxLines = 1)
        Text(label, style = t.tiny, color = Palette.muted, maxLines = 1)
    }
}

private fun settingGroups(job: JobFile, slot: Int): List<Pair<String, List<Pair<String, String>>>> {
    val st = job.settings ?: return emptyList()
    val g = job.gcode
    fun mm(v: Double?) = v?.let { "%.2f mm".format(it) }
    fun speed(v: Double?) = v?.let { "%.0f".format(it) }
    return listOf(
        "Quality" to listOfNotNull(
            mm(st.layerHeight)?.let { "Layer height" to it },
            g?.let { m -> "Layers · height" to "${m.layers.size} · ${"%.1f".format(m.layers.lastOrNull()?.z ?: 0f)} mm" },
            st.seam?.let { "Seam" to it },
        ),
        "Strength" to listOfNotNull(
            st.wallLoops?.let { "Walls" to "$it" },
            if (st.topShells != null || st.bottomShells != null) "Top / bottom shells" to "${st.topShells ?: "—"} / ${st.bottomShells ?: "—"}" else null,
            st.infillDensity?.let { "Infill" to listOfNotNull(it, st.infillPattern).joinToString(" ") },
        ),
        "Speed" to listOfNotNull(
            if (st.outerWallSpeed != null || st.innerWallSpeed != null) "Outer / inner wall" to "${speed(st.outerWallSpeed) ?: "—"} / ${speed(st.innerWallSpeed) ?: "—"} mm/s" else null,
            speed(st.infillSpeed)?.let { "Sparse infill" to "$it mm/s" },
            st.acceleration?.let { "Acceleration" to "%,.0f mm/s²".format(it) },
        ),
        "Temperature & adhesion" to listOfNotNull(
            st.nozzleTemp(slot)?.let { "Nozzle" to "%.0f °C".format(it) },
            st.bedTemp(slot)?.let { "Bed" to "%.0f °C".format(it) + (st.bedType?.let { b -> " · " + b.lowercase().removeSuffix(" plate") } ?: "") },
            "Supports · brim" to "${if (st.supports == true) "on" else "none"} · ${st.brim?.replace("_", " ") ?: "—"}",
        ),
    ).filter { it.second.isNotEmpty() }
}

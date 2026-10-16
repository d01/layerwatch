package app.layerwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.layerwatch.R
import app.layerwatch.protocol.AmsUnit
import app.layerwatch.protocol.DryStatus
import app.layerwatch.protocol.HeatState
import app.layerwatch.protocol.cannotDryText
import app.layerwatch.protocol.PrinterModel
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.Temperature
import app.layerwatch.protocol.Tray
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

// ---------- primitives ----------

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    padding: Dp = 18.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    /** Drawn over the card's background and under its content (e.g. [emberGlow]). */
    surface: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .clip(RoundedCornerShape(radius))
        .background(Palette.card)
        .then(surface)
        .padding(padding),
    verticalArrangement = verticalArrangement,
    content = content,
)

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Palette.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(height)).background(color))
    }
}

@Composable
fun Glyph(res: Int, tint: Color, size: Dp = 18.dp, description: String? = null) =
    Icon(painterResource(res), contentDescription = description, tint = tint, modifier = Modifier.size(size))

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

// ---------- job ----------

/** Phase, job name, big percent, time left / finish time, progress bar, layer line. */
@Composable
fun JobCard(
    s: PrinterStatus?,
    nowMs: Long,
    live: Boolean,
    modifier: Modifier = Modifier,
    heroSize: Int = 64,
    onDetails: (() -> Unit)? = null,
) {
    val t = LocalBambuType.current
    val accent = if (live) Palette.amber else Palette.muted
    Panel(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((s?.let(Format::phase) ?: "Waiting for printer").uppercase(), style = t.eyebrow, color = accent)
                Text(s?.jobName ?: "—", style = t.jobName, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onDetails != null && s?.jobState?.isActive == true) DetailsLink(onDetails)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(s?.percent?.toString() ?: "—", style = t.heroNumber.copy(fontSize = heroSize.sp, lineHeight = heroSize.sp), color = Palette.text)
                Text("%", style = t.heroUnit, color = Palette.muted, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
            }
            val remaining = s?.remainingMinutes?.takeIf { s.jobState.isActive }
            if (remaining != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Format.duration(remaining), style = t.metric.copy(fontSize = 22.sp), color = Palette.text)
                    Text("left · done ≈ ${Format.finishTime(nowMs, remaining)}", style = t.label, color = Palette.muted)
                }
            }
        }
        val pct = s?.percent ?: 0
        Bar(
            pct / 100f, accent, height = 6.dp,
            modifier = Modifier.semantics {
                contentDescription = "Print progress"
                progressBarRangeInfo = ProgressBarRangeInfo(pct / 100f, 0f..1f)
            },
        )
        Row {
            Text(
                if (s?.layer != null && s.totalLayers != null) "Layer ${s.layer} / ${s.totalLayers}" else "Layer —",
                style = t.mono, color = Palette.muted, modifier = Modifier.weight(1f),
            )
            val trayMaterial = s?.ams?.flatMap { it.trays }?.firstOrNull { it.slot == s.activeSlot }?.material
            Text(
                listOfNotNull(s?.nozzleDiameter?.let { "$it mm nozzle" }, trayMaterial).joinToString(" · "),
                style = t.label, color = Palette.muted,
            )
        }
    }
}

/** "Details ›" — opens the print details screen. */
@Composable
fun DetailsLink(onClick: () -> Unit) {
    val t = LocalBambuType.current
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Print details", onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Details ›", style = t.label.copy(fontWeight = FontWeight.SemiBold), color = Palette.blue) }
}

/** No job on the printer: idle is a state, not a print at 0%. */
fun isIdle(s: PrinterStatus?): Boolean =
    s != null && (s.jobState == app.layerwatch.protocol.JobState.Idle ||
        (s.jobState == app.layerwatch.protocol.JobState.Unknown && s.jobName == null))

/**
 * Shown instead of the job card when the printer is idle: "Ready", the last print (opens
 * its history entry), and any AMS drying, which is often the only thing happening.
 */
@Composable
fun ReadyCard(
    s: PrinterStatus,
    live: Boolean,
    lastPrint: app.layerwatch.history.PrintRecord?,
    onOpenLast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalBambuType.current
    Panel(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("READY", style = t.eyebrow, color = if (live) Palette.blue else Palette.muted)
        Text("Idle · ready for the next print", style = t.jobName, color = Palette.text)
        s.ams.filter { it.isDrying }.forEach { unit ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(Palette.amber, 7.dp)
                Text(
                    "${unit.type.label} drying" + (if (unit.dryMinutesLeft > 0) " · ${Format.duration(unit.dryMinutesLeft)} left" else ""),
                    style = t.label, color = Palette.amberText,
                )
            }
        }
        lastPrint?.let { r ->
            val (label, color) = when (r.outcome) {
                app.layerwatch.history.Outcome.Finished -> "finished" to Palette.blue
                app.layerwatch.history.Outcome.Failed -> "failed" to Palette.danger
                app.layerwatch.history.Outcome.Stopped -> "stopped" to Palette.muted
                else -> "ended" to Palette.muted
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(Palette.cardRaised)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Open last print") { onOpenLast(r.key) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Last print", style = t.caption, color = Palette.muted)
                    Text(r.jobName ?: "Untitled print", style = t.label.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        label + (r.endedAtMs?.let { " · " + Format.clockTime(it) } ?: ""),
                        style = t.caption, color = color,
                    )
                }
                Text("›", style = t.section, color = Palette.muted)
            }
        }
    }
}

// ---------- temperatures ----------

/** [passive]: a chamber that's measured but not heated, so it has no target to show. */
private data class TempRow(val label: String, val temp: Temperature?, val passive: Boolean = false) {
    val current get() = temp?.current
    val state get() = if (passive) HeatState.Off else temp?.heatState ?: HeatState.Off
    val heating get() = state != HeatState.Off
    val caption get() = when {
        passive -> "passive"
        state == HeatState.HeatingUp -> "heating to ${Format.temp(temp?.target)}"
        state == HeatState.CoolingDown -> "cooling to ${Format.temp(temp?.target)}"
        temp?.target == 0.0 -> "off"
        else -> "target ${Format.temp(temp?.target)}"
    }

    /** Reading colour: ember while climbing, amber while held, plain otherwise or when stale. */
    fun readingColor(live: Boolean) = when {
        !live -> Palette.text
        state == HeatState.HeatingUp -> Palette.emberText
        heating -> Palette.amber
        else -> Palette.text
    }
}

/**
 * A slowly pulsing ember glow rising from the bottom edge, while a heater climbs to its
 * target. Nothing is drawn (or animated) otherwise.
 */
@Composable
private fun Modifier.emberGlow(active: Boolean, bleed: Dp = 0.dp): Modifier {
    if (!active) return this
    val pulse by rememberInfiniteTransition(label = "ember").animateFloat(
        initialValue = 0.16f, targetValue = 0.34f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ember-pulse",
    )
    return drawBehind {
        // [bleed] widens the band past the row into the card's padding, so it runs edge to
        // edge instead of starting on the text's own margin.
        val x = bleed.toPx()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.35f to Color.Transparent,
                1f to Palette.ember.copy(alpha = pulse),
            ),
            topLeft = Offset(-x, 0f),
            size = Size(size.width + 2 * x, size.height),
        )
    }
}

/** Nozzle (or both, on dual-nozzle printers), bed, and the chamber where there's a sensor. */
private fun temps(s: PrinterStatus?, model: PrinterModel) = buildList {
    if (s != null && s.nozzles.size >= 2) {
        add(TempRow("Nozzle R", s.nozzles[0]))
        add(TempRow("Nozzle L", s.nozzles[1]))
    } else {
        add(TempRow("Nozzle", s?.nozzle))
    }
    add(TempRow("Bed", s?.bed))
    if (model.chamberSensor) {
        val c = s?.chamber
        add(TempRow("Chamber", c, passive = (c?.target ?: 0.0) <= 0.0))
    }
}

/** Three tiles side by side (folded layout). */
@Composable
fun TempTiles(s: PrinterStatus?, live: Boolean, model: PrinterModel, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        temps(s, model).forEach { row ->
            val climbing = live && row.state == HeatState.HeatingUp
            Panel(
                Modifier.weight(1f), radius = 16.dp, padding = 14.dp, verticalArrangement = Arrangement.spacedBy(6.dp),
                surface = Modifier.emberGlow(climbing),
            ) {
                val current = row.current
                val target = row.temp?.target
                Text(row.label, style = t.caption, color = Palette.muted)
                Text(Format.temp(current), style = t.metric, color = row.readingColor(live))
                Text(row.caption, style = t.caption, color = Palette.faint)
                val heating = row.heating
                val frac = if (heating && target != null && current != null) (current / target).toFloat() else ((current ?: 0.0) / 100.0).toFloat()
                Bar(frac, if (climbing) Palette.ember else if (heating && live) Palette.amber else Palette.idleBar)
            }
        }
    }
}

private val LIST_PADDING = 18.dp

/** Stacked rows in one panel (unfolded side column). */
@Composable
fun TempList(s: PrinterStatus?, live: Boolean, model: PrinterModel, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    Panel(modifier, padding = LIST_PADDING) {
        val rows = temps(s, model)
        rows.forEachIndexed { i, row ->
            val current = row.current
            Row(
                Modifier.fillMaxWidth()
                    .emberGlow(live && row.state == HeatState.HeatingUp, bleed = LIST_PADDING).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.label, style = t.label, color = Palette.muted)
                    Text(row.caption, style = t.caption, color = Palette.faint)
                }
                Text(Format.temp(current), style = t.metric, color = row.readingColor(live))
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        }
    }
}

/** Compact tiles for the tabletop lower half. */
@Composable
fun TempStrip(s: PrinterStatus?, live: Boolean, model: PrinterModel, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        temps(s, model).forEach { row ->
            val current = row.current
            Panel(Modifier.weight(1f), radius = 16.dp, padding = 14.dp, surface = Modifier.emberGlow(live && row.state == HeatState.HeatingUp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.label, style = t.caption, color = Palette.muted)
                        Text(
                            if (row.passive) s?.chamberFanPercent?.let { "fan $it%" } ?: "passive" else row.caption,
                            style = t.tiny, color = Palette.faint,
                        )
                    }
                    Text(Format.temp(current), style = t.metric.copy(fontSize = 24.sp), color = row.readingColor(live))
                }
            }
        }
    }
}

// ---------- AMS ----------

@Composable
fun Spool(tray: Tray, active: Boolean, size: Dp) {
    val color = Format.filamentColor(tray.colorHex)
    val ring = if (active) Palette.amber else Palette.outline
    Box(
        Modifier.size(size).clip(CircleShape).border(if (size > 40.dp) 3.dp else 2.dp, ring, CircleShape).padding(if (size > 40.dp) 7.dp else 5.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight().clip(CircleShape)
                .background(color ?: Color.White.copy(alpha = 0.10f))
                .then(
                    when {
                        color == null -> Modifier.border(1.dp, Palette.idleBar, CircleShape)
                        color.luminance() < 0.08f -> Modifier.border(1.dp, Color(0xFF3A4046), CircleShape)
                        else -> Modifier
                    }
                )
        )
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun amsSummary(unit: AmsUnit) = listOfNotNull(
    when {
        unit.humidityPercent != null && unit.humidityLevel != null -> "${unit.humidityPercent}% RH (${unit.humidityLevel}/5)"
        unit.humidityLevel != null -> "humidity ${unit.humidityLevel}/5"
        else -> null
    },
    unit.temperatureC?.let { "%.1f °C".format(it) },
).joinToString(" · ")

private fun amsTitle(unit: AmsUnit, many: Boolean) =
    unit.type.label + if (many) " ${if (unit.id >= 128) "HT ${unit.id - 127}" else ('A' + unit.id).toString()}" else ""

/**
 * Drying state for AMS units that can dry: "Drying · 9h 43m left · 63.2 °C now" in amber
 * while it runs (started from the printer's screen; remote start isn't offered on the X1C
 * yet). Reasons the printer can't dry are shown only around an attempt, since during a
 * print it always reports itself busy.
 */
@Composable
fun DryingLine(unit: AmsUnit, modifier: Modifier = Modifier) {
    if (!unit.type.canDry) return
    val t = LocalBambuType.current
    val attempting = unit.dryStatus == DryStatus.Checking || unit.dryStatus == DryStatus.Error || unit.dryStatus == DryStatus.HeatOutOfControl
    if (unit.isDrying) {
        val parts = buildList {
            add(if (unit.dryStatus == DryStatus.Off || unit.dryStatus == DryStatus.Unknown) "Drying" else unit.dryStatus.label)
            if (unit.dryMinutesLeft > 0) add(Format.duration(unit.dryMinutesLeft) + " left")
            unit.temperatureC?.let { add("%.1f °C now".format(it)) }
        }
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.amberSoft).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(Palette.amber, 7.dp)
            Text(parts.joinToString(" · "), style = t.label, color = Palette.amberText)
        }
    }
    if (attempting) {
        unit.cannotDryReasons.mapNotNull(::cannotDryText).distinct().forEach {
            Text("Can't dry: $it", style = t.caption, color = Palette.errorText)
        }
    }
}

/** Four spools in a grid (folded layout). */
@Composable
fun AmsCard(s: PrinterStatus?, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    val units = s?.ams.orEmpty()
    if (units.isEmpty()) return
    units.forEach { unit ->
        Panel(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(amsTitle(unit, units.size > 1), style = t.section, color = Palette.text, modifier = Modifier.weight(1f))
                Text(amsSummary(unit), style = t.label, color = Palette.muted)
            }
            DryingLine(unit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                unit.trays.forEach { tray ->
                    val active = tray.slot == s?.activeSlot
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Spool(tray, active, 56.dp)
                        Text(tray.remainingPercent?.let { "$it%" } ?: "—", style = t.metricSmall, color = Palette.text)
                        Text(tray.name ?: tray.material ?: "Empty", style = t.tiny, color = Palette.muted, maxLines = 2, lineHeight = 14.sp)
                        Text(if (active) "Feeding" else "Slot ${tray.slot % 4 + 1}", style = t.tiny.copy(fontWeight = FontWeight.SemiBold), color = if (active) Palette.amber else Palette.faint)
                    }
                }
            }
        }
    }
}

/** One spool per row with a remaining bar (unfolded side column). */
@Composable
fun AmsList(s: PrinterStatus?, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    val units = s?.ams.orEmpty()
    if (units.isEmpty()) return
    Panel(modifier, padding = 16.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        units.forEach { unit ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(amsTitle(unit, units.size > 1), style = t.section, color = Palette.text, modifier = Modifier.weight(1f))
                Text(amsSummary(unit), style = t.caption, color = Palette.muted)
            }
            DryingLine(unit)
            unit.trays.forEach { tray ->
                val active = tray.slot == s?.activeSlot
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spool(tray, active, 32.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(
                                (tray.name ?: tray.material ?: "Empty") + if (active) " · feeding" else "",
                                style = t.label, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(tray.remainingPercent?.let { "$it%" } ?: "—", style = t.mono, color = Palette.text)
                        }
                        Bar((tray.remainingPercent ?: 0) / 100f, if (active) Palette.amber else Palette.idleBar)
                    }
                }
            }
        }
    }
}

/** A single horizontal strip (tabletop lower half). */
@Composable
fun AmsStrip(s: PrinterStatus?, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    val unit = s?.ams?.firstOrNull() ?: return
    Panel(modifier, radius = 16.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text(unit.type.label, style = t.section, color = Palette.text)
                Text(if (unit.isDrying) "Drying · " + Format.duration(unit.dryMinutesLeft) + " left" else amsSummary(unit), style = t.tiny, color = if (unit.isDrying) Palette.amberText else Palette.muted)
            }
            unit.trays.forEach { tray ->
                val active = tray.slot == s.activeSlot
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spool(tray, active, 34.dp)
                    Column {
                        Text(tray.remainingPercent?.let { "$it%" } ?: "—", style = t.metricSmall.copy(fontSize = 14.sp), color = Palette.text)
                        Text(if (active) "Feeding" else tray.material ?: "Empty", style = t.tiny, color = if (active) Palette.amber else Palette.faint, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- fans ----------

@Composable
fun FanGrid(s: PrinterStatus?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val t = LocalBambuType.current
    val fans = listOf(
        (if (compact) "Part" else "Part cooling") to s?.partFanPercent,
        "Aux" to s?.auxFanPercent,
        "Chamber" to s?.chamberFanPercent,
        "Heatbreak" to s?.heatbreakFanPercent,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fans.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { (label, pct) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(label, style = if (compact) t.caption else t.label, color = Palette.muted, modifier = Modifier.weight(1f))
                            Text(pct?.let { "$it%" } ?: "—", style = t.mono, color = Palette.text)
                        }
                        Bar((pct ?: 0) / 100f, Palette.blue)
                    }
                }
            }
        }
    }
}

@Composable
fun WifiNote(s: PrinterStatus?, modifier: Modifier = Modifier) {
    val dbm = s?.wifiDbm ?: return
    val t = LocalBambuType.current
    val weak = dbm <= -67
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Glyph(R.drawable.ic_wifi, if (weak) Palette.amber else Palette.faint, 14.dp)
        Text(
            "Printer Wi-Fi $dbm dBm" + if (weak) " · weak signal, video is buffered" else "",
            style = t.caption, color = Palette.faint,
        )
    }
}

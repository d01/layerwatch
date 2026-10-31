package app.layerwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.layerwatch.R
import app.layerwatch.protocol.AmsUnit
import app.layerwatch.protocol.DryingSettings
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.cannotDryText
import app.layerwatch.protocol.dryingBlocker
import app.layerwatch.protocol.DryingPreset
import app.layerwatch.protocol.DryingPresets
import app.layerwatch.protocol.PrinterModel
import app.layerwatch.protocol.defaultDryingPreset
import app.layerwatch.protocol.dryingPresetFor
import app.layerwatch.protocol.feederHeatProblem
import app.layerwatch.protocol.loadedTrays
import app.layerwatch.ui.dashboard.DashboardState
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

/** What the AMS cards need to offer drying: dashboard state plus the two commands. */
class DryingActions(
    val state: DashboardState,
    /** Picks the filaments Bambu Studio would offer, with the status's nozzle. */
    val model: PrinterModel,
    val onStart: (amsId: Int, DryingSettings) -> Unit,
    val onStop: (amsId: Int) -> Unit,
)

/**
 * Start and stop drying for one unit, under its [DryingLine]. Idle: a "Dry filament"
 * button opens the settings in place, filled in from Bambu's preset for the coolest
 * loaded filament as Bambu Studio does; starting is a tap there. Drying: "Stop drying"
 * behind a 2 s hold, shorter than a print's stop since a stopped run only costs a restart.
 * Hidden during a print, when the printer can't dry anyway.
 */
@Composable
fun DryingControls(unit: AmsUnit, s: PrinterStatus, actions: DryingActions, modifier: Modifier = Modifier) {
    if (!unit.type.canDry || s.jobState.isActive) return
    val t = LocalBambuType.current
    val live = actions.state.isLive
    val request = actions.state.dryRequests[unit.id]
    var editing by rememberSaveable(unit.id) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            request != null && !request.unconfirmed -> ControlButton(
                if (request.start) "Starting drying…" else "Stopping drying…", ControlStyle.Secondary, {}, Modifier.fillMaxWidth(),
                enabled = false, minHeight = 48.dp,
            ) {}
            unit.isDrying -> HoldToConfirmButton(
                "Stop drying", ControlStyle.Danger, { actions.onStop(unit.id) }, Modifier.fillMaxWidth(),
                enabled = live, holdMillis = 2_000, minHeight = 48.dp,
            ) { Glyph(R.drawable.ic_stop, Palette.danger, 16.dp) }
            editing -> DryingEditor(
                unit,
                choices = DryingPresets.forPrinter(actions.model, s.nozzleDiameter),
                enabled = live && dryingBlocker(unit, s) == null,
                onCancel = { editing = false },
                onStart = { editing = false; actions.onStart(unit.id, it) },
            )
            else -> {
                val blocker = dryingBlocker(unit, s)
                ControlButton(
                    "Dry filament", ControlStyle.Secondary, { editing = true }, Modifier.fillMaxWidth(),
                    enabled = live && blocker == null, minHeight = 48.dp,
                ) { Glyph(R.drawable.ic_play, if (live && blocker == null) Palette.text else Palette.faint, 16.dp) }
                blocker?.let { Text(it, style = t.caption, color = Palette.muted) }
            }
        }
        if (request?.unconfirmed == true) {
            Text(
                if (request.start) "The printer didn't start drying." else "The printer is still drying.",
                style = t.caption, color = Palette.errorText,
            )
            unit.cannotDryReasons.mapNotNull(::cannotDryText).distinct().forEach {
                Text("Can't dry: $it", style = t.caption, color = Palette.errorText)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DryingEditor(unit: AmsUnit, choices: List<DryingPreset>, enabled: Boolean, onCancel: () -> Unit, onStart: (DryingSettings) -> Unit) {
    val t = LocalBambuType.current
    val type = unit.type
    var presetId by rememberSaveable(unit.id) { mutableStateOf(defaultDryingPreset(unit)?.id) }
    val preset = DryingPresets.byId(presetId)
    var tempC by rememberSaveable(unit.id) { mutableIntStateOf(preset?.settings(type)?.tempC ?: type.minDryC) }
    var hours by rememberSaveable(unit.id) { mutableIntStateOf(preset?.settings(type)?.hours ?: 8) }
    var rotate by rememberSaveable(unit.id) { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    fun pick(p: DryingPreset) {
        presetId = p.id
        p.settings(type).let { tempC = it.tempC; hours = it.hours }
    }
    // Loaded filaments first, as quick picks; everything else is in the full list.
    val quick = (unit.loadedTrays.mapNotNull(::dryingPresetFor) + listOfNotNull(preset)).distinctBy { it.id }
    val heatProblem = feederHeatProblem(unit, tempC)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.well).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Filament", style = t.caption, color = Palette.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { p -> Chip(p.name.removePrefix("Bambu "), p.id == presetId) { pick(p) } }
            Box {
                Chip("All filaments…", false) { listOpen = true }
                DropdownMenu(listOpen, onDismissRequest = { listOpen = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                    choices.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name, color = if (p.id == presetId) Palette.amberText else Palette.text) },
                            onClick = { listOpen = false; pick(p) },
                        )
                    }
                }
            }
        }
        if (preset != null && type !in preset.driesFullyOn) {
            Text("The ${type.label} may not dry ${preset.name.removePrefix("Bambu ")} completely.", style = t.caption, color = Palette.muted)
        }
        Stepper("Temperature", "$tempC °C", tempC > type.minDryC, tempC < type.maxDryC,
            onMinus = { tempC = (tempC - 5).coerceAtLeast(type.minDryC) },
            onPlus = { tempC = (tempC + 5).coerceAtMost(type.maxDryC) })
        Stepper("Time", "$hours h", hours > DryingSettings.MIN_HOURS, hours < DryingSettings.MAX_HOURS,
            onMinus = { hours-- }, onPlus = { hours++ })
        heatProblem?.let { Text(it, style = t.caption, color = Palette.errorText) }
        Row(
            Modifier.fillMaxWidth().toggleable(rotate, role = Role.Switch) { rotate = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Rotate spools", style = t.label, color = Palette.text)
                Text(
                    if (rotate) "Pull filament back out of the feeders first" else "Filament can stay in the feeders",
                    style = t.caption, color = if (rotate && unit.loadedTrays.isNotEmpty()) Palette.amberText else Palette.muted,
                )
            }
            Switch(
                rotate, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.amber, checkedThumbColor = Palette.onAmber),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton("Cancel", ControlStyle.Secondary, onCancel, Modifier.weight(1f), minHeight = 48.dp) {}
            val ready = enabled && preset != null && heatProblem == null
            ControlButton(
                "Start drying", ControlStyle.Primary,
                { preset?.let { onStart(it.settings(type).copy(tempC = tempC, hours = hours, rotateSpools = rotate)) } },
                Modifier.weight(1.4f), enabled = ready, minHeight = 48.dp,
            ) { Glyph(R.drawable.ic_play, if (ready) Palette.onAmber else Palette.faint, 16.dp) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalBambuType.current
    Box(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.outline else Palette.card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = t.label, color = if (selected) Palette.text else Palette.muted) }
}

@Composable
private fun Stepper(label: String, value: String, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    val t = LocalBambuType.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = t.label, color = Palette.text, modifier = Modifier.weight(1f))
        StepButton("−", "Less $label".lowercase(), canMinus, onMinus)
        Text(value, style = t.metricSmall, color = Palette.text, modifier = Modifier.padding(horizontal = 4.dp))
        StepButton("+", "More $label".lowercase(), canPlus, onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalBambuType.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Palette.card).border(1.dp, Palette.outline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, style = t.section, color = if (enabled) Palette.text else Palette.faint) }
}

package app.layerwatch.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.layerwatch.R
import app.layerwatch.protocol.HmsCatalog
import app.layerwatch.protocol.JobState
import app.layerwatch.protocol.level
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.SpeedLevel
import app.layerwatch.ui.dashboard.DashboardState
import app.layerwatch.ui.dashboard.Health
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

/** "● Live · via VPN · updated 1 s ago" under the printer name. */
@Composable
fun LinkChip(state: DashboardState, compact: Boolean = false) {
    val t = LocalBambuType.current
    val (dot, text) = when (val h = state.health) {
        Health.Live -> Palette.blue to buildList {
            add("Live")
            if (state.viaVpn) add(if (compact) "VPN" else "via VPN")
            state.reportAgeSec?.let {
                add(
                    when {
                        it < 2 -> if (compact) "now" else "updated just now"
                        compact -> Format.age(it)
                        else -> "updated ${Format.age(it)} ago"
                    }
                )
            }
        }.joinToString(" · ")
        Health.Connecting -> Palette.faint to "Connecting…"
        is Health.Reconnecting -> Palette.amber to "Reconnecting"
        Health.PhoneOffline -> Palette.faint to "Offline"
        Health.AuthFailed -> Palette.danger to "Access code rejected"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(dot)
        Text(text, style = t.label, color = Palette.muted, maxLines = 1)
    }
}

/**
 * Shown above the data whenever the link isn't live. The data below stays visible but
 * dimmed, with its age, so "the printer is fine but I can't see it" never looks like
 * "the printer stopped".
 */
@Composable
fun HealthBanner(state: DashboardState, onRetry: () -> Unit, onFixAccessCode: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    val context = LocalContext.current
    val age = state.reportAgeSec?.let { "Last report ${Format.age(it)} ago" }
    when (val h = state.health) {
        Health.Live, Health.Connecting -> Unit
        is Health.Reconnecting -> Banner(
            icon = R.drawable.ic_refresh, bg = Palette.amberSoft, fg = Palette.amberText,
            title = if (h.retryInSec > 0) "Reconnecting · retry in ${h.retryInSec} s" else "Reconnecting…",
            body = listOfNotNull(age, h.reason).joinToString(" · "),
            action = "Retry now", onAction = onRetry, modifier = modifier,
        )
        Health.PhoneOffline -> Banner(
            icon = R.drawable.ic_wifi_off, bg = Palette.cardRaised, fg = Color(0xFFC9D1D9),
            title = "No network or VPN",
            body = "This phone can't reach your network; the printer isn't the problem." + (age?.let { " $it." } ?: ""),
            action = "Open VPN settings",
            onAction = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            modifier = modifier,
        )
        Health.AuthFailed -> Banner(
            icon = R.drawable.ic_lock, bg = Palette.errorSoft, fg = Palette.errorText,
            title = "The printer refused the access code",
            body = "It changes if it's regenerated on the printer. Find the current one under Settings → LAN on its screen.",
            action = "Update access code", onAction = onFixAccessCode, modifier = modifier,
        )
    }
}

@Composable
private fun Banner(
    icon: Int, bg: Color, fg: Color, title: String, body: String,
    action: String, onAction: () -> Unit, modifier: Modifier,
) {
    val t = LocalBambuType.current
    Panel(modifier.fillMaxWidth(), padding = 14.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(icon, fg, 16.dp)
            Text(title, style = t.label, color = fg)
        }
        if (body.isNotBlank()) Text(body, style = t.caption, color = Palette.muted)
        ControlButton(action, ControlStyle.Secondary, onAction, Modifier.fillMaxWidth(), minHeight = 44.dp) {}
    }
}

/** True when the printer has something to say: paused, failed, an error, or an HMS alert not dismissed. */
fun needsAttention(s: PrinterStatus?, dismissed: Set<String> = emptySet()): Boolean =
    s != null && (s.jobState == JobState.Paused || s.jobState == JobState.Failed || s.printErrorCode != 0L ||
        s.hms.any { it.id !in dismissed })

/**
 * Paused / failed / HMS alerts in plain English (Bambu's own texts), linked to the wiki.
 * Errors and pauses get the amber attention style; notices the printer handled itself
 * (e.g. an AMS auto-refill) get a calm style and can be dismissed.
 */
@Composable
fun PrinterAlertCard(
    s: PrinterStatus,
    jobControlEnabled: Boolean,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    dismissed: Set<String> = emptySet(),
    onDismiss: (Set<String>) -> Unit = {},
) {
    val t = LocalBambuType.current
    val uri = LocalUriHandler.current
    val paused = s.jobState == JobState.Paused
    val failed = s.jobState == JobState.Failed
    val alerts = s.hms.filter { it.id !in dismissed }
    val serious = paused || failed || s.printErrorCode != 0L || alerts.any { it.level.isError }
    val border = if (serious) Color(0xFF4A3A1C) else Palette.outline
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.card)
            .border(1.dp, border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val eyebrow = when {
            paused -> "PAUSED"
            failed -> "FAILED"
            serious -> "PRINTER ERROR"
            else -> "NOTICE"
        }
        Text(eyebrow, style = t.eyebrow, color = if (serious) Palette.amber else Palette.blue)
        when {
            paused -> Text(s.stage?.takeIf { it.isPause }?.label ?: "Paused", style = t.jobName, color = Palette.text)
            failed -> Text(Format.phase(s), style = t.jobName, color = Palette.text)
        }
        s.printError?.let { code ->
            val text = HmsCatalog.describeError(s.printErrorCode)
            Text(text ?: "Print error $code", style = t.body, color = Palette.text)
        }
        alerts.forEach { alert ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(HmsCatalog.describe(alert), style = t.body, color = Palette.text)
                Text(
                    buildAnnotatedString { withStyle(SpanStyle(color = Palette.blue)) { append("HMS ${alert.id} ↗") } },
                    style = t.mono,
                    modifier = Modifier.heightIn(min = 32.dp).padding(vertical = 6.dp)
                        .selectable(selected = false, role = Role.Button, onClick = { uri.openUri(alert.wikiUrl) }),
                )
            }
        }
        // After an AMS auto-refill, say where the filament comes from now.
        if (!serious && s.jobState == JobState.Running && alerts.any { (it.attr ushr 24).toInt() == 0x07 }) {
            val tray = s.ams.flatMap { it.trays }.firstOrNull { it.slot == s.activeSlot }
            if (tray != null) {
                Text(
                    "Now feeding slot ${tray.slot % 4 + 1}" + listOfNotNull(tray.name ?: tray.material).joinToString(prefix = " · ") + " · print continues",
                    style = t.label, color = Palette.blue,
                )
            }
        }
        if (paused) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton("Resume", ControlStyle.Primary, onResume, Modifier.weight(1f), enabled = jobControlEnabled, minHeight = 48.dp) {
                    Glyph(R.drawable.ic_play, Palette.onAmber, 16.dp)
                }
                HoldToConfirmButton("Stop", ControlStyle.Danger, onStop, Modifier.weight(1f), enabled = jobControlEnabled, minHeight = 48.dp) {
                    Glyph(R.drawable.ic_stop, Palette.danger, 14.dp)
                }
            }
        } else if (!serious && alerts.isNotEmpty()) {
            ControlButton("Dismiss", ControlStyle.Secondary, { onDismiss(alerts.map { it.id }.toSet()) }, Modifier.fillMaxWidth(), minHeight = 44.dp) {}
        }
    }
}

/** Light · Pause · Stop — the same order in every layout. */
@Composable
fun ControlRow(
    state: DashboardState,
    onLight: (Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    stacked: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val s = state.status
    val lightOn = state.chamberLightOn == true
    val canSend = state.isLive
    val active = s?.jobState?.isActive == true
    val paused = s?.jobState == JobState.Paused
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlButton(
            if (lightOn) "Light on" else "Light off",
            if (lightOn) ControlStyle.Primary else ControlStyle.Secondary,
            onClick = { onLight(!lightOn) },
            modifier = Modifier.weight(1.1f), enabled = canSend, minHeight = minHeight, stacked = stacked,
            stateDescription = if (lightOn) "On" else "Off",
        ) { Glyph(R.drawable.ic_light, if (lightOn) Palette.onAmber else Palette.text, if (stacked) 22.dp else 18.dp) }
        leading?.let { Box(Modifier.weight(1f)) { it() } }
        if (paused) {
            ControlButton(
                "Resume", ControlStyle.Secondary, onResume, Modifier.weight(1f),
                enabled = canSend && state.jobControlEnabled, minHeight = minHeight, stacked = stacked,
            ) { Glyph(R.drawable.ic_play, Palette.text, 16.dp) }
        } else {
            HoldToConfirmButton(
                "Pause", ControlStyle.Secondary, onPause, Modifier.weight(1f),
                enabled = canSend && active && state.jobControlEnabled, minHeight = minHeight, stacked = stacked,
            ) { Glyph(R.drawable.ic_pause, Palette.text, 16.dp) }
        }
        HoldToConfirmButton(
            "Stop", ControlStyle.Danger, onStop, Modifier.weight(1f),
            enabled = canSend && active && state.jobControlEnabled, minHeight = minHeight, stacked = stacked,
        ) { Glyph(R.drawable.ic_stop, Palette.danger, 14.dp) }
    }
}

/** Silent · Standard · Sport · Ludicrous. */
@Composable
fun SpeedPicker(current: SpeedLevel?, enabled: Boolean, onPick: (SpeedLevel) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speed", style = t.label, color = Palette.muted)
        Row(
            // No Modifier.alpha inside the clipped background: see ControlColors in Controls.kt.
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.well).padding(4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpeedLevel.entries.forEach { level ->
                val selected = level == current
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) (if (enabled) Palette.outline else Palette.cardRaised) else Color.Transparent)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onPick(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Shrinks to fit narrow columns (the unfolded side pane) instead of clipping.
                    BasicText(
                        level.label,
                        style = t.label.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = when {
                                selected && enabled -> Palette.text
                                selected -> Palette.muted
                                else -> Palette.faint
                            },
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = t.label.fontSize),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Speed picker guarded by a 4 s hold above it. Locked, the picker still shows the
 * current speed but doesn't respond; unlocked, it takes one change within the window.
 */
@Composable
fun SpeedControl(
    state: DashboardState,
    onUnlock: () -> Unit,
    onPick: (SpeedLevel) -> Unit,
    modifier: Modifier = Modifier,
    showUnlock: Boolean = true,
) {
    val t = LocalBambuType.current
    val available = state.isLive && state.jobControlEnabled
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showUnlock) {
            if (state.speedUnlocked) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .background(Palette.amberSoft).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Glyph(R.drawable.ic_gauge, Palette.amberText, 16.dp)
                    Text("Speed unlocked · choose within ${state.speedUnlockSec} s", style = t.label, color = Palette.amberText)
                }
            } else {
                HoldToConfirmButton(
                    "Unlock speed", ControlStyle.Secondary, onUnlock, Modifier.fillMaxWidth(),
                    enabled = available, minHeight = 48.dp,
                ) { Glyph(R.drawable.ic_lock, Palette.text, 16.dp) }
            }
        }
        SpeedPicker(state.status?.speed, available && state.speedUnlocked, onPick)
    }
}

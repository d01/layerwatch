package app.bambumon.ui.components

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
import app.bambumon.R
import app.bambumon.protocol.JobState
import app.bambumon.protocol.PrinterStatus
import app.bambumon.protocol.SpeedLevel
import app.bambumon.ui.dashboard.DashboardState
import app.bambumon.ui.dashboard.Health
import app.bambumon.ui.theme.LocalBambuType
import app.bambumon.ui.theme.Palette

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

/** True when the printer itself needs attention: paused, failed, or reporting errors. */
fun needsAttention(s: PrinterStatus?): Boolean =
    s != null && (s.jobState == JobState.Paused || s.jobState == JobState.Failed || s.printErrorCode != 0L || s.hms.isNotEmpty())

/** Paused / failed / HMS alerts, with codes linked to Bambu's wiki. */
@Composable
fun PrinterAlertCard(
    s: PrinterStatus,
    jobControlEnabled: Boolean,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalBambuType.current
    val uri = LocalUriHandler.current
    val paused = s.jobState == JobState.Paused
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.card)
            .border(1.dp, Color(0xFF4A3A1C), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (paused) "PAUSED" else if (s.jobState == JobState.Failed) "FAILED" else "ATTENTION", style = t.eyebrow, color = Palette.amber)
        Text(
            s.stage?.takeIf { it.isPause }?.label ?: if (paused) "Paused" else Format.phase(s),
            style = t.jobName.copy(fontWeight = FontWeight.SemiBold), color = Palette.text,
        )
        s.printError?.let { Text("Print error $it", style = t.mono, color = Palette.muted) }
        s.hms.forEach { alert ->
            Text(
                buildAnnotatedString { withStyle(SpanStyle(color = Palette.blue)) { append("HMS ${alert.id} ↗") } },
                style = t.mono,
                modifier = Modifier.heightIn(min = 32.dp).padding(vertical = 6.dp)
                    .selectable(selected = false, role = Role.Button, onClick = { uri.openUri(alert.wikiUrl) }),
            )
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.well).padding(4.dp).selectableGroup()
                .alpha(if (enabled) 1f else 0.7f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpeedLevel.entries.forEach { level ->
                val selected = level == current
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Palette.outline else Color.Transparent)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onPick(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Shrinks to fit narrow columns (the unfolded side pane) instead of clipping.
                    BasicText(
                        level.label,
                        style = t.label.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) Palette.text else Palette.muted,
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

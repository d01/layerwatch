package app.layerwatch.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.layerwatch.R
import app.layerwatch.config.PrinterConfig
import app.layerwatch.printer.PrinterTls
import app.layerwatch.printer.printerModelName
import app.layerwatch.ui.components.AmsCard
import app.layerwatch.ui.components.AmsList
import app.layerwatch.ui.components.AmsStrip
import app.layerwatch.ui.components.CameraAspect
import app.layerwatch.ui.components.CameraPanel
import app.layerwatch.ui.components.ControlRow
import app.layerwatch.ui.components.ControlStyle
import app.layerwatch.ui.components.FanGrid
import app.layerwatch.ui.components.Format
import app.layerwatch.ui.components.Glyph
import app.layerwatch.ui.components.HealthBanner
import app.layerwatch.ui.components.JobCard
import app.layerwatch.ui.components.LinkChip
import app.layerwatch.ui.components.Panel
import app.layerwatch.ui.components.PrinterAlertCard
import app.layerwatch.ui.components.SpeedControl
import app.layerwatch.ui.components.HoldToConfirmButton
import app.layerwatch.ui.components.TempList
import app.layerwatch.ui.components.TempStrip
import app.layerwatch.ui.components.TempTiles
import app.layerwatch.ui.components.WifiNote
import app.layerwatch.ui.components.needsAttention
import app.layerwatch.ui.components.ReadyCard
import app.layerwatch.ui.components.isIdle
import app.layerwatch.ui.details.DetailsScreen
import app.layerwatch.ui.history.HistoryScreen
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

/** Everything a layout needs: state plus the user's actions. */
private class Dash(
    val state: DashboardState,
    val config: PrinterConfig,
    val tls: PrinterTls,
    val vm: DashboardViewModel,
    val onFullscreen: () -> Unit,
    val onHistory: () -> Unit,
    val onDetails: () -> Unit,
    val onForget: () -> Unit,
    val onUpdateCode: () -> Unit,
) {
    val live get() = state.isLive
    val model = app.layerwatch.protocol.PrinterModel.fromSerial(config.serial)
    var dismissed: Set<String> = emptySet()
    var lastPrint: app.layerwatch.history.PrintRecord? = null
    var onOpenRecord: (String) -> Unit = {}
}

@Composable
fun DashboardScreen(
    config: PrinterConfig,
    tls: PrinterTls,
    onForget: () -> Unit,
    onUpdateCode: () -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var openRecord by rememberSaveable { mutableStateOf<String?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    // Hold the printer connection open while the dashboard is visible.
    DisposableEffect(vm) {
        val release = vm.acquireConnection()
        onDispose(release)
    }

    // A print seen while open is handed to the background monitor (state only updates
    // while visible, so this never fires from the background).
    val printing = state.isLive && state.status?.jobState?.isActive == true
    LaunchedEffect(printing) { if (printing) vm.printSeenWhileOpen() }

    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    if (showDetails) {
        DetailsScreen(onBack = { showDetails = false })
        return
    }

    if (showHistory) {
        val records by vm.history.records.collectAsStateWithLifecycle()
        HistoryScreen(records, vm.history, onBack = { showHistory = false; openRecord = null }, initialOpen = openRecord)
        return
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        Box(Modifier.fillMaxSize().background(Palette.ground)) {
            CameraPanel(
                config, tls, Modifier.fillMaxSize().safeDrawingPadding(), radius = 0.dp,
                fullscreenIcon = R.drawable.ic_close, onFullscreen = { fullscreen = false },
            )
        }
        return
    }

    val d = Dash(state, config, tls, vm, { fullscreen = true }, { showHistory = true }, { showDetails = true }, onForget, onUpdateCode)
    d.dismissed = vm.dismissedHms.collectAsStateWithLifecycle().value
    d.lastPrint = vm.lastPrint.collectAsStateWithLifecycle().value
    d.onOpenRecord = { key -> openRecord = key; showHistory = true }
    val info = currentWindowAdaptiveInfo()
    when {
        info.windowPosture.isTabletop -> TabletopLayout(d)
        info.windowSizeClass.minWidthDp >= 600 -> UnfoldedLayout(d)
        else -> FoldedLayout(d)
    }
}

// ---------- folded: one scrolling column ----------

@Composable
private fun FoldedLayout(d: Dash) {
    val s = d.state.status
    Column(
        Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(d)
        HealthBanner(d.state, d.vm::retryNow, d.onUpdateCode)
        CameraPanel(d.config, d.tls, Modifier.fillMaxWidth().aspectRatio(CameraAspect), onFullscreen = d.onFullscreen)
        if (needsAttention(s, d.dismissed)) PrinterAlertCard(s!!, d.state.jobControlEnabled, d.vm::resume, d.vm::stopPrint, dismissed = d.dismissed, onDismiss = d.vm::dismissHms)
        Column(Modifier.dimWhenStale(d.live), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isIdle(s)) ReadyCard(s!!, d.live, d.lastPrint, d.onOpenRecord, Modifier.fillMaxWidth())
            else JobCard(s, d.state.nowMs, d.live, Modifier.fillMaxWidth(), onDetails = d.onDetails)
            TempTiles(s, d.live, d.model, Modifier.fillMaxWidth())
            AmsCard(s, Modifier.fillMaxWidth())
            Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FanGrid(s)
                SpeedControl(d.state, d.vm::unlockSpeed, d.vm::setSpeed)
            }
            WifiNote(s, Modifier.padding(horizontal = 4.dp))
        }
        Controls(d, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

// ---------- unfolded: two panes, nothing scrolls on a large screen ----------

@Composable
private fun UnfoldedLayout(d: Dash) {
    val s = d.state.status
    Row(
        Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.weight(1.62f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(d, showMenu = false)
            HealthBanner(d.state, d.vm::retryNow, d.onUpdateCode)
            CameraPanel(d.config, d.tls, Modifier.fillMaxWidth().aspectRatio(CameraAspect), radius = 22.dp, onFullscreen = d.onFullscreen)
            if (needsAttention(s, d.dismissed)) PrinterAlertCard(s!!, d.state.jobControlEnabled, d.vm::resume, d.vm::stopPrint, dismissed = d.dismissed, onDismiss = d.vm::dismissHms)
            if (isIdle(s)) ReadyCard(s!!, d.live, d.lastPrint, d.onOpenRecord, Modifier.fillMaxWidth().dimWhenStale(d.live))
            else JobCard(s, d.state.nowMs, d.live, Modifier.fillMaxWidth().dimWhenStale(d.live), heroSize = 72, onDetails = d.onDetails)
            Controls(d, Modifier.fillMaxWidth())
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).dimWhenStale(d.live),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MoreMenu(d) }
            TempList(s, d.live, d.model, Modifier.fillMaxWidth())
            AmsList(s, Modifier.fillMaxWidth())
            Panel(Modifier.fillMaxWidth(), padding = 16.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FanGrid(s, compact = true)
                SpeedControl(d.state, d.vm::unlockSpeed, d.vm::setSpeed)
                WifiNote(s)
            }
        }
    }
}

// ---------- tabletop: camera in the upper half, touch targets below ----------

@Composable
private fun TabletopLayout(d: Dash) {
    val s = d.state.status
    val t = LocalBambuType.current
    Column(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding()) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CameraPanel(d.config, d.tls, Modifier.weight(1f).fillMaxHeight(), crop = true, onFullscreen = d.onFullscreen)
            Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (isIdle(s)) "Ready" else s?.let(Format::phase) ?: "Waiting").uppercase(), style = t.eyebrow, color = if (d.live) Palette.amber else Palette.muted)
                    Text(s?.jobName ?: "—", style = t.jobName, color = Palette.text, maxLines = 2)
                    LinkChip(d.state, compact = true)
                    if (s?.jobState?.isActive == true) app.layerwatch.ui.components.DetailsLink(d.onDetails)
                }
                if (isIdle(s)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ready", style = t.heroNumber.copy(fontSize = t.heroNumber.fontSize * 0.7f, lineHeight = t.heroNumber.fontSize * 0.7f), color = Palette.text)
                        s!!.ams.firstOrNull { it.isDrying }?.let { u ->
                            Text("${u.type.label} drying · ${Format.duration(u.dryMinutesLeft)} left", style = t.label, color = Palette.amberText)
                        }
                        d.lastPrint?.let { r -> Text("Last: ${r.jobName ?: "print"}", style = t.label, color = Palette.muted, maxLines = 1) }
                    }
                } else Column(Modifier.dimWhenStale(d.live), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(s?.percent?.toString() ?: "—", style = t.heroNumber.copy(fontSize = t.heroNumber.fontSize * 1.3f, lineHeight = t.heroNumber.fontSize * 1.3f), color = Palette.text)
                        Text("%", style = t.heroUnit, color = Palette.muted, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
                    }
                    app.layerwatch.ui.components.Bar((s?.percent ?: 0) / 100f, if (d.live) Palette.amber else Palette.muted, height = 6.dp)
                    s?.remainingMinutes?.takeIf { s.jobState.isActive }?.let { rem ->
                        Text(Format.duration(rem) + " left", style = t.metric.copy(fontSize = t.metric.fontSize * 0.77f), color = Palette.text)
                        Text("done ≈ ${Format.finishTime(d.state.nowMs, rem)} · layer ${s.layer ?: "—"}/${s.totalLayers ?: "—"}", style = t.label, color = Palette.muted)
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HealthBanner(d.state, d.vm::retryNow, d.onUpdateCode)
            if (needsAttention(s, d.dismissed)) PrinterAlertCard(s!!, d.state.jobControlEnabled, d.vm::resume, d.vm::stopPrint, dismissed = d.dismissed, onDismiss = d.vm::dismissHms)
            Controls(d, Modifier.fillMaxWidth(), minHeight = 76.dp, stacked = true, withSpeed = true)
            if (d.state.speedUnlocked) {
                Text("Speed unlocked · choose within ${d.state.speedUnlockSec} s", style = t.label, color = Palette.amberText)
                SpeedControl(d.state, d.vm::unlockSpeed, d.vm::setSpeed, showUnlock = false)
            }
            TempStrip(s, d.live, d.model, Modifier.fillMaxWidth().dimWhenStale(d.live))
            AmsStrip(s, Modifier.fillMaxWidth().dimWhenStale(d.live))
        }
    }
}

// ---------- shared pieces ----------

@Composable
private fun Header(d: Dash, showMenu: Boolean = true) {
    val t = LocalBambuType.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(printerModelName(d.config.serial), style = t.title, color = Palette.text)
            LinkChip(d.state)
        }
        if (showMenu) MoreMenu(d)
    }
}

@Composable
private fun MoreMenu(d: Dash) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Palette.card)
                .clickable(role = Role.Button, onClickLabel = "More options") { open = true },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_more, Palette.text, 20.dp, description = "More options") }
        val watching by d.vm.watchInBackground.collectAsStateWithLifecycle()
        val screenOn by d.vm.keepScreenOn.collectAsStateWithLifecycle()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Print history") }, onClick = { open = false; d.onHistory() })
            DropdownMenuItem(
                text = { Text(if (watching) "Background watching: on" else "Background watching: off") },
                onClick = { open = false; d.vm.setWatchInBackground(!watching) },
            )
            DropdownMenuItem(
                text = { Text(if (screenOn) "Keep screen on: on" else "Keep screen on: off") },
                onClick = { open = false; d.vm.setKeepScreenOn(!screenOn) },
            )
            if (!d.config.isDemo) DropdownMenuItem(text = { Text("Change access code") }, onClick = { open = false; d.onUpdateCode() })
            DropdownMenuItem(text = { Text(if (d.config.isDemo) "Leave demo" else "Forget printer") }, onClick = { open = false; d.onForget() })
        }
    }
}

@Composable
private fun Controls(
    d: Dash,
    modifier: Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 56.dp,
    stacked: Boolean = false,
    withSpeed: Boolean = false,
) {
    val speed = d.state.status?.speed
    ControlRow(
        d.state,
        onLight = d.vm::setChamberLight,
        onPause = d.vm::pause,
        onResume = d.vm::resume,
        onStop = d.vm::stopPrint,
        modifier = modifier,
        minHeight = minHeight,
        stacked = stacked,
        leading = if (!withSpeed) null else {
            {
                HoldToConfirmButton(
                    speed?.label ?: "Speed", ControlStyle.Secondary,
                    onConfirm = d.vm::unlockSpeed,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = d.live && d.state.jobControlEnabled && !d.state.speedUnlocked,
                    minHeight = minHeight, stacked = stacked,
                ) { Glyph(R.drawable.ic_gauge, Palette.text, 22.dp) }
            }
        },
    )
}

/** Old data stays readable but visibly not-live. */
private fun Modifier.dimWhenStale(live: Boolean) = if (live) this else this.alpha(0.55f)

package app.layerwatch.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.layerwatch.LayerwatchApp
import app.layerwatch.history.Outcome
import app.layerwatch.history.PrintRecord
import app.layerwatch.monitor.PrintMonitorService
import app.layerwatch.printer.NetworkState
import app.layerwatch.protocol.LinkState
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.SpeedLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the dashboard should present the link to the printer. */
sealed interface Health {
    data object Live : Health
    data object Connecting : Health
    data class Reconnecting(val retryInSec: Long, val reason: String) : Health
    data object PhoneOffline : Health
    data object AuthFailed : Health
}

data class DashboardState(
    val health: Health,
    val viaVpn: Boolean,
    val status: PrinterStatus?,
    /** Seconds since the last report, or null if none yet. */
    val reportAgeSec: Long?,
    val nowMs: Long,
    /** Optimistic value while a light command is in flight. */
    val chamberLightOn: Boolean?,
    /** Pause / resume / stop / speed. Each sits behind its own 4 s hold (resume is a tap). */
    val jobControlEnabled: Boolean,
    /** Seconds left in the speed-change window opened by the 4 s unlock hold; 0 = locked. */
    val speedUnlockSec: Long = 0,
) {
    val speedUnlocked get() = speedUnlockSec > 0
    val isLive get() = health == Health.Live
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as LayerwatchApp).printer
    val history = (app as LayerwatchApp).history

    /** The most recent print that's no longer running, for the idle card. */
    val lastPrint: StateFlow<PrintRecord?> = history.records
        .map { list -> list.firstOrNull { it.outcome != Outcome.InProgress } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val pendingLight = MutableStateFlow<Pair<Boolean, Long>?>(null)
    private val speedUnlockedUntil = MutableStateFlow(0L)

    /** HMS notices the user dismissed; they stay hidden until a different code appears. */
    private val _dismissedHms = MutableStateFlow<Set<String>>(emptySet())
    val dismissedHms: StateFlow<Set<String>> = _dismissedHms

    fun dismissHms(ids: Set<String>) {
        _dismissedHms.value = _dismissedHms.value + ids
    }

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val state: StateFlow<DashboardState> = combine(
        repo.link, repo.network.state, repo.status, repo.lastReportAtMs,
        combine(ticker, pendingLight, speedUnlockedUntil, ::Triple),
    ) { link, net, status, lastAt, (now, pending, unlockUntil) ->
        val reported = status?.chamberLightOn
        val optimistic = pending?.takeIf { (value, at) -> value != reported && now - at < LIGHT_PENDING_MS }?.first
        DashboardState(
            health = health(link, net, now),
            viaVpn = net.viaVpn,
            status = status,
            reportAgeSec = lastAt?.let { ((now - it) / 1000).coerceAtLeast(0) },
            nowMs = now,
            chamberLightOn = optimistic ?: reported,
            jobControlEnabled = JOB_CONTROL_ENABLED,
            speedUnlockSec = ((unlockUntil - now + 999) / 1000).coerceAtLeast(0),
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        DashboardState(Health.Connecting, false, null, null, System.currentTimeMillis(), null, JOB_CONTROL_ENABLED),
    )

    val watchInBackground: StateFlow<Boolean> = (app as LayerwatchApp).settings.watchInBackground
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWatchInBackground(on: Boolean) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            (ctx as LayerwatchApp).settings.setWatchInBackground(on)
            if (!on) PrintMonitorService.stop(ctx) else if (repo.status.value?.jobState?.isActive == true) PrintMonitorService.start(ctx)
        }
    }

    val keepScreenOn: StateFlow<Boolean> = (app as LayerwatchApp).settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setKeepScreenOn(on: Boolean) {
        val ctx = getApplication<Application>()
        viewModelScope.launch { (ctx as LayerwatchApp).settings.setKeepScreenOn(on) }
    }

    /**
     * Called by the visible dashboard when it sees a print in progress: hand the print to
     * the background monitor so it's followed after the app closes. Starting a foreground
     * service is only allowed while visible, which is exactly when this runs.
     */
    fun printSeenWhileOpen() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            if ((ctx as LayerwatchApp).settings.watchInBackground.first()) PrintMonitorService.start(ctx)
        }
    }

    /** Keep the printer connection open while the dashboard is on screen. */
    fun acquireConnection(): () -> Unit = repo.acquire()

    fun retryNow() {
        repo.reconnectNow()
    }

    fun setChamberLight(on: Boolean) {
        val conn = repo.connection.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (conn.setChamberLight(on)) pendingLight.value = on to System.currentTimeMillis()
        }
    }

    fun pause() = command { it.pause() }
    fun resume() = command { it.resume() }
    fun stopPrint() = command { it.stopPrint() }
    /** Opened by the 4 s hold above the picker; allows one change within the window. */
    fun unlockSpeed() {
        speedUnlockedUntil.value = System.currentTimeMillis() + SPEED_UNLOCK_MS
    }

    fun setSpeed(level: SpeedLevel) {
        if (System.currentTimeMillis() >= speedUnlockedUntil.value) return
        speedUnlockedUntil.value = 0L // one change per unlock
        command { it.setSpeed(level) }
    }

    private fun command(block: (app.layerwatch.protocol.PrinterLink) -> Boolean) {
        if (!JOB_CONTROL_ENABLED) return
        val conn = repo.connection.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { block(conn) }
    }

    private fun health(link: LinkState, net: NetworkState, now: Long): Health = when {
        link is LinkState.AuthFailed -> Health.AuthFailed
        link is LinkState.Online -> Health.Live
        !net.available -> Health.PhoneOffline
        link is LinkState.Waiting -> Health.Reconnecting(((link.retryAtMs - now) / 1000).coerceAtLeast(0), link.reason)
        else -> Health.Connecting
    }

    private companion object {
        const val LIGHT_PENDING_MS = 5_000L
        const val SPEED_UNLOCK_MS = 20_000L
        const val JOB_CONTROL_ENABLED = true
    }
}

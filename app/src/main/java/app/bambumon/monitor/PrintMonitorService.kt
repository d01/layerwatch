package app.bambumon.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import app.bambumon.BambumonApp
import app.bambumon.printer.printerModelName
import app.bambumon.camera.Snapshot
import app.bambumon.history.Outcome
import app.bambumon.history.PrintHistory
import app.bambumon.protocol.JobState
import app.bambumon.protocol.PrintEvent
import app.bambumon.protocol.LinkState
import app.bambumon.protocol.PrintEventDetector
import app.bambumon.widget.WidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Follows one print with the app closed.
 *
 * Lifecycle: started by the app when it's open and sees a print in
 * progress; holds the printer connection so progress and alerts stay live; stops itself
 * once the print ends, after posting the finished/failed alert. Nothing runs between
 * prints — the next one is picked up the next time the app is opened during it.
 *
 * A `connectedDevice` foreground service: no daily time cap, fits a networked printer.
 * While printing the connection costs ~26 MB/h.
 */
class PrintMonitorService : Service() {
    // One thread: the coroutines below share `hold`.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private lateinit var notes: MonitorNotifications
    private var hold: (() -> Unit)? = null
    private val alertJobs = mutableListOf<Job>()

    private val app get() = application as BambumonApp
    private val repo get() = app.printer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notes = MonitorNotifications(this)
        startForeground(
            MonitorNotifications.ID_ONGOING,
            notes.ongoing(model(), repo.status.value, repo.link.value, repo.lastReportAtMs.value, null, System.currentTimeMillis()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        hold = repo.acquire()
        scope.launch { followPrint() }
        scope.launch { watchLostContact() }
        scope.launch { updateOngoing() }
        scope.launch { stopWhenDisabled() }
        scope.launch { refreshWidgetFrame() }
        Log.i(TAG, "monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        hold?.invoke()
        hold = null
        scope.cancel()
        Log.i(TAG, "monitor stopped")
        super.onDestroy()
    }

    /**
     * Turns reports into alerts, and ends the service when the job is over. One collector
     * so the final alert is always posted before stopping.
     */
    private suspend fun followPrint() {
        val detector = PrintEventDetector()
        val history = app.history
        var key: String? = null
        repo.status.filterNotNull().collect { s ->
            if (s.jobState.isActive && key == null) {
                key = PrintHistory.keyFor(s.taskId, s.jobName, System.currentTimeMillis())
                history.begin(key!!, s.jobName, s.totalLayers, System.currentTimeMillis())
            }
            detector.update(s).forEach { e ->
                Log.i(TAG, "event: $e")
                val recordKey = key
                val outcome = when (e) {
                    is PrintEvent.Finished -> Outcome.Finished
                    is PrintEvent.Failed -> Outcome.Failed
                    else -> null
                }
                if (outcome != null && recordKey != null) {
                    history.finish(recordKey, outcome, (e as? PrintEvent.Failed)?.printError, System.currentTimeMillis())
                }
                // Off the collector so the snapshot never delays the next report.
                alertJobs += scope.launch {
                    val picture = postAlertWithSnapshot(this@PrintMonitorService, notes, e)
                    if (picture != null) WidgetStore.pushFrame(this@PrintMonitorService, picture)
                    if (picture != null && outcome != null && recordKey != null) history.attachSnapshot(recordKey, picture)
                }
                alertJobs.removeAll { it.isCompleted }
            }
            if (s.jobState != JobState.Unknown && !s.jobState.isActive) {
                // Ended without a finished/failed transition we saw (e.g. stopped to idle).
                key?.let { history.finish(it, Outcome.Unknown, s.printError, System.currentTimeMillis()) }
                Log.i(TAG, "print over (${s.jobState}); stopping once alerts are out")
                // The "finished" snapshot is the one people most want; let it land.
                withTimeoutOrNull(SNAPSHOT_GRACE_MS) { alertJobs.joinAll() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Alert once if the print goes unheard-from for [LOST_AFTER_MS]; clear on recovery.
     * Judged by the age of the last report, not link transitions, which flap
     * Waiting <-> Connecting on every retry.
     */
    private suspend fun watchLostContact() {
        var alerted = false
        while (true) {
            val last = repo.lastReportAtMs.value
            val silentFor = last?.let { System.currentTimeMillis() - it } ?: 0L
            when {
                repo.link.value is LinkState.Online -> if (alerted) { notes.clearLostContact(); alerted = false }
                last != null && silentFor >= LOST_AFTER_MS && !alerted -> {
                    notes.lostContact(model(), last)
                    alerted = true
                }
            }
            delay(15_000)
        }
    }

    /** Refresh the foreground notification on change, at most every [NOTIFY_MIN_MS]. */
    private suspend fun updateOngoing() {
        combine(repo.status, repo.link, repo.lastReportAtMs, ::Triple).collectLatest { (s, link, last) ->
            if (s?.jobState?.isActive == false) return@collectLatest // stopping; don't repost
            notes.postOngoing(notes.ongoing(model(), s, link, last, s?.chamberLightOn, System.currentTimeMillis()))
            delay(NOTIFY_MIN_MS)
        }
    }

    /** A fresh chamber frame for the home-screen widget every [WIDGET_FRAME_MS] (~0.5 MB each). */
    private suspend fun refreshWidgetFrame() {
        while (true) {
            val cfg = repo.config.value
            if (cfg != null && repo.link.value is LinkState.Online) {
                Snapshot.grab(this, app.tls, cfg)?.let { WidgetStore.pushFrame(this, it) }
            }
            delay(WIDGET_FRAME_MS)
        }
    }

    private suspend fun stopWhenDisabled() {
        app.settings.watchInBackground.first { !it }
        Log.i(TAG, "background watching turned off")
        stopSelf()
    }

    private fun model() = repo.config.value?.serial?.let(::printerModelName) ?: "printer"

    companion object {
        private const val TAG = "bambumon.monitor"
        private const val LOST_AFTER_MS = 2 * 60_000L
        private const val NOTIFY_MIN_MS = 2_000L
        private const val SNAPSHOT_GRACE_MS = 15_000L
        private const val WIDGET_FRAME_MS = 10 * 60_000L

        /** Only legal while the app is visible, which is the only time it's called. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, PrintMonitorService::class.java))
            }.onFailure { Log.w(TAG, "couldn't start monitor: $it") }
        }

        fun stop(context: Context) = context.stopService(Intent(context, PrintMonitorService::class.java))
    }
}

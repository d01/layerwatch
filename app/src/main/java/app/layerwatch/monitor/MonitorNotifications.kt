package app.layerwatch.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.layerwatch.MainActivity
import app.layerwatch.R
import app.layerwatch.protocol.HmsCatalog
import app.layerwatch.protocol.HmsSeverity
import app.layerwatch.protocol.JobState
import app.layerwatch.protocol.level
import app.layerwatch.protocol.LinkState
import app.layerwatch.protocol.PrintEvent
import app.layerwatch.protocol.PrinterStatus
import app.layerwatch.protocol.job.ProgressMarkers
import app.layerwatch.ui.components.Format

/**
 * Builds and posts everything the monitor shows outside the app.
 *
 * Channels: "progress" (low, ongoing print; promoted to a Live Update on Android 16+),
 * "watching" (min, the idle foreground-service notice), "alerts" (high, things that need
 * the user), "connection" (default, lost contact during a print).
 */
class MonitorNotifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_PROGRESS, "Print progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live progress while the printer is printing"
                    setShowBadge(false)
                },
                NotificationChannel(CH_WATCHING, "Watching printer", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Layerwatch watches an idle printer for new prints"
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, "Printer alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Print finished, failed, paused, errors and first layer done"
                },
                NotificationChannel(CH_CONNECTION, "Connection", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Lost contact with the printer during a print"
                },
            )
        )
    }

    /** The foreground notification: live progress during a print, a quiet notice otherwise. */
    fun ongoing(
        model: String,
        status: PrinterStatus?,
        link: LinkState,
        lastReportAtMs: Long?,
        lightOn: Boolean?,
        nowMs: Long,
        markers: ProgressMarkers? = null,
        toolColor: (Int) -> Int? = { null },
    ): Notification {
        val active = status?.jobState?.isActive == true
        if (!active) {
            val checked = lastReportAtMs?.let { "checked ${Format.age((nowMs - it) / 1000)} ago" } ?: "waiting for printer"
            return Notification.Builder(context, CH_WATCHING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Watching $model")
                .setContentText("Idle · $checked")
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        }

        val s = status!!
        val pct = s.percent ?: 0
        val live = link is LinkState.Online
        val remaining = s.remainingMinutes
        val title = listOfNotNull(s.jobName ?: model, "$pct%").joinToString(" · ")
        val text = when {
            !live -> "Reconnecting… last report " + (lastReportAtMs?.let { "${Format.age((nowMs - it) / 1000)} ago" } ?: "unknown")
            s.jobState == JobState.Paused -> Format.phase(s)
            remaining != null -> "${Format.duration(remaining)} left · done ≈ ${Format.finishTime(nowMs, remaining)} · layer ${s.layer ?: "—"}/${s.totalLayers ?: "—"}"
            else -> Format.phase(s)
        }
        val b = Notification.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (live) Format.phase(s) else "Offline")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // readable on the lock screen
        if (lightOn != null && live) {
            b.addAction(
                Notification.Action.Builder(
                    null, if (lightOn) "Light off" else "Light on",
                    MonitorActionReceiver.lightIntent(context, !lightOn),
                ).build()
            )
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // Segments coloured by the filament loaded for each stretch; points at the first
            // layer and each filament change. Single-filament jobs stay one amber bar.
            val multi = (markers?.segments?.size ?: 0) > 1
            val segments = markers?.segments?.takeIf { multi }?.map {
                Notification.ProgressStyle.Segment((it.toPct - it.fromPct).coerceAtLeast(1)).setColor(toolColor(it.tool) ?: AMBER)
            } ?: listOf(Notification.ProgressStyle.Segment(100).setColor(AMBER))
            val points = markers?.points.orEmpty().map {
                Notification.ProgressStyle.Point(it.pct.coerceIn(1, 99)).setColor(
                    if (it.kind == ProgressMarkers.Kind.FirstLayerDone) BLUE else (it.toTool?.let(toolColor) ?: MUTED)
                )
            }
            b.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(segments)
                    .setProgressPoints(points)
                    .setProgress(pct)
            )
            b.setShortCriticalText("$pct%")
            b.setRequestPromotedOngoing(true)
        } else {
            b.setProgress(100, pct, false)
        }
        return b.build()
    }

    fun postOngoing(n: Notification) = nm.notify(ID_ONGOING, n)

    /**
     * Posts the alert for [event]; returns false for events that don't alert. Called a
     * second time with [snapshot] once a camera frame is in hand: same id, silent update.
     */
    fun alert(event: PrintEvent, model: String, snapshot: Bitmap? = null): Boolean {
        val job = event.jobName ?: "Print"
        val (id, title, text) = when (event) {
            is PrintEvent.Started -> return false // shown by the progress notification
            is PrintEvent.Resumed -> {
                nm.cancel(ID_PAUSED)
                return false
            }
            is PrintEvent.Finished -> Triple(ID_FINISHED, "Print finished", "$job is done on $model.")
            is PrintEvent.Failed -> Triple(
                ID_FAILED, "Print failed",
                "$job stopped on $model." + (event.printError?.let { code ->
                    code.replace("_", "").toLongOrNull(16)?.let(HmsCatalog::describeError)?.let { " $it" } ?: " Error $code."
                } ?: ""),
            )
            is PrintEvent.Paused -> Triple(
                ID_PAUSED,
                if (event.byPrinter) event.reason else "Print paused",
                if (event.byPrinter) "$job needs attention on $model." else "$job was paused.",
            )
            is PrintEvent.NewHms -> {
                // An auto-refill raises a run-out notice as it switches spools; the
                // switch notification already says it better, so don't overwrite it.
                if (event.alerts.none { it.level.isError } && System.currentTimeMillis() - lastSwitchAtMs < SWITCH_WINDOW_MS) return false
                val worst = event.alerts.minByOrNull { if (it.level == HmsSeverity.Unknown) 9 else it.level.level }!!
                Triple(
                    ID_HMS,
                    if (worst.level.isError) "Printer error" else "Printer notice",
                    event.alerts.joinToString(" ") { HmsCatalog.describe(it) } + " · $job",
                )
            }
            is PrintEvent.SpoolSwitched -> {
                lastSwitchAtMs = System.currentTimeMillis()
                Triple(
                    ID_HMS, "Switched to backup spool",
                    (event.fromSlot?.let { "Slot ${it % 4 + 1} ran out. " } ?: "") +
                        "Continuing from slot ${event.toSlot % 4 + 1}" + (event.toName?.let { " ($it)" } ?: "") + ". $job keeps printing.",
                )
            }
            is PrintEvent.FirstLayerDone -> Triple(ID_FIRST_LAYER, "First layer done", "Have a look at $job's first layer.")
        }
        val b = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                if (snapshot != null) Notification.BigPictureStyle().bigPicture(snapshot).setSummaryText(text)
                else Notification.BigTextStyle().bigText(text)
            )
            .apply { if (snapshot != null) setLargeIcon(snapshot) }
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // the snapshot update must not buzz again
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(
                when {
                    event is PrintEvent.Finished || event is PrintEvent.FirstLayerDone || event is PrintEvent.SpoolSwitched -> Notification.CATEGORY_STATUS
                    event is PrintEvent.NewHms && event.alerts.none { it.level.isError } -> Notification.CATEGORY_STATUS
                    else -> Notification.CATEGORY_ERROR
                }
            )
        if (event is PrintEvent.NewHms) {
            val url = event.alerts.first().wikiUrl
            b.addAction(
                Notification.Action.Builder(
                    null, "Details",
                    PendingIntent.getActivity(
                        context, ID_HMS, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
        }
        if (snapshot == null) nm.cancel(id) // a fresh event should alert even if an old one is still shown
        nm.notify(id, b.build())
        return true
    }

    /** Heads-up shortly before an AMS filament change. */
    fun filamentChangeSoon(model: String, minutes: Int, layer: Int, what: String, detail: String?) {
        val text = "Switching to $what at layer $layer" + (detail?.let { " · $it" } ?: "")
        nm.notify(
            ID_FILAMENT,
            Notification.Builder(context, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (minutes <= 0) "Filament change now" else "Filament change in ~$minutes min")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text on $model."))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build(),
        )
    }

    private var lastSwitchAtMs = 0L

    fun lostContact(model: String, sinceMs: Long) {
        nm.notify(
            ID_LOST,
            Notification.Builder(context, CH_CONNECTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Lost contact with $model")
                .setContentText("No reports since ${Format.clockTime(sinceMs)}. The print may still be running; retrying.")
                .setContentIntent(openApp())
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearLostContact() = nm.cancel(ID_LOST)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID_ONGOING = 1
        private const val ID_LOST = 2
        private const val ID_FINISHED = 10
        private const val ID_FAILED = 11
        private const val ID_PAUSED = 12
        private const val ID_HMS = 13
        private const val ID_FIRST_LAYER = 14
        private const val ID_FILAMENT = 15
        private const val SWITCH_WINDOW_MS = 2 * 60_000L

        private const val CH_PROGRESS = "progress"
        private const val CH_WATCHING = "watching"
        private const val CH_ALERTS = "alerts"
        private const val CH_CONNECTION = "connection"

        private const val AMBER = 0xFFF4B04A.toInt()
        private const val BLUE = 0xFF8AB8FF.toInt()
        private const val MUTED = 0xFFA2AAB2.toInt()
    }
}

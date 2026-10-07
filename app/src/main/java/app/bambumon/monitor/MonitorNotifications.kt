package app.bambumon.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.bambumon.MainActivity
import app.bambumon.R
import app.bambumon.protocol.JobState
import app.bambumon.protocol.LinkState
import app.bambumon.protocol.PrintEvent
import app.bambumon.protocol.PrinterStatus
import app.bambumon.ui.components.Format

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
                    description = "Shown while Bambumon watches an idle printer for new prints"
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
            b.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(AMBER)))
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

    fun alert(event: PrintEvent, model: String) {
        val job = event.jobName ?: "Print"
        val (id, title, text) = when (event) {
            is PrintEvent.Started -> return // shown by the progress notification
            is PrintEvent.Resumed -> {
                nm.cancel(ID_PAUSED)
                return
            }
            is PrintEvent.Finished -> Triple(ID_FINISHED, "Print finished", "$job is done on $model.")
            is PrintEvent.Failed -> Triple(
                ID_FAILED, "Print failed",
                "$job stopped on $model." + (event.printError?.let { " Error $it." } ?: ""),
            )
            is PrintEvent.Paused -> Triple(
                ID_PAUSED,
                if (event.byPrinter) event.reason else "Print paused",
                if (event.byPrinter) "$job needs attention on $model." else "$job was paused.",
            )
            is PrintEvent.NewHms -> Triple(
                ID_HMS, "Printer alert",
                event.alerts.joinToString(", ") { "HMS ${it.id}" } + " · $job",
            )
            is PrintEvent.FirstLayerDone -> Triple(ID_FIRST_LAYER, "First layer done", "Have a look at $job's first layer.")
        }
        val b = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(if (event is PrintEvent.Finished || event is PrintEvent.FirstLayerDone) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
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
        nm.notify(id, b.build())
    }

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

        private const val CH_PROGRESS = "progress"
        private const val CH_WATCHING = "watching"
        private const val CH_ALERTS = "alerts"
        private const val CH_CONNECTION = "connection"

        private const val AMBER = 0xFFF4B04A.toInt()
    }
}

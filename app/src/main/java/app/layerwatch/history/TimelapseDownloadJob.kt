package app.layerwatch.history

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import app.layerwatch.LayerwatchApp
import app.layerwatch.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Downloads a timelapse as a *user-initiated data transfer* job: Android's mechanism for
 * downloads the user asked for. It keeps running (with a progress notification) after
 * the user leaves the app, and if the system stops it, it's rescheduled and resumes from
 * the partial file.
 */
class TimelapseDownloadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val name = params.extras.getString(EXTRA_NAME) ?: return false
        val size = params.extras.getLong(EXTRA_SIZE)
        val save = params.extras.getBoolean(EXTRA_SAVE)
        val ref = TimelapseRef(name, size, certain = true)
        val app = application as LayerwatchApp
        val nm = getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        setNotification(params, params.jobId, progress(name, 0, size), JOB_END_NOTIFICATION_POLICY_REMOVE)

        running[params.jobId] = scope.launch {
            try {
                app.printer.configLoad.first { it != null } // a fresh process must load the config first
                var lastShown = 0L
                val file = app.timelapses.download(ref) { n ->
                    app.timelapses.report(name, Download.Running(n, size))
                    if (n - lastShown > size / 50) {
                        lastShown = n
                        nm.notify(params.jobId, progress(name, n, size))
                    }
                }
                app.timelapses.report(name, Download.Done(file))
                if (save) saveToMovies(this@TimelapseDownloadJob, file)
                Log.i(TAG, "downloaded $name")
                jobFinished(params, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download of $name failed", e)
                app.timelapses.report(name, Download.Failed(e.message ?: e.javaClass.simpleName))
                jobFinished(params, false)
            }
        }
        return true
    }

    /** The system stopped us (e.g. network lost): ask to be rescheduled; the .part resumes. */
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        params.extras.getString(EXTRA_NAME)?.let {
            (application as LayerwatchApp).timelapses.report(it, Download.Failed("Paused · will resume when possible"))
        }
        return true
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun progress(name: String, bytes: Long, total: Long): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading timelapse")
            .setContentText("%.1f of %.1f MB · %s".format(bytes / 1e6, total / 1e6, name))
            .setProgress(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "layerwatch.timelapse"
        private const val CHANNEL = "downloads"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_SAVE = "save"

        private fun ensureChannel(nm: NotificationManager) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }

        /**
         * Starts (or joins) the download of [ref]. Must be called while the app is visible
         * (user-initiated jobs require it). [saveWhenDone] also copies it to Movies.
         */
        fun start(context: Context, ref: TimelapseRef, saveWhenDone: Boolean) {
            val app = context.applicationContext as LayerwatchApp
            app.timelapses.cachedFile(ref)?.let { f ->
                app.timelapses.report(ref.name, Download.Done(f))
                return
            }
            ensureChannel(context.getSystemService(NotificationManager::class.java))
            val extras = PersistableBundle().apply {
                putString(EXTRA_NAME, ref.name)
                putLong(EXTRA_SIZE, ref.size)
                putBoolean(EXTRA_SAVE, saveWhenDone)
            }
            val job = JobInfo.Builder(ref.name.hashCode() and 0x7fffffff, ComponentName(context, TimelapseDownloadJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(ref.size, 0)
                .setExtras(extras)
                .build()
            app.timelapses.report(ref.name, Download.Running(0, ref.size))
            val result = context.getSystemService(JobScheduler::class.java).schedule(job)
            if (result != JobScheduler.RESULT_SUCCESS) app.timelapses.report(ref.name, Download.Failed("Couldn't start the download"))
        }
    }
}

/** Copies a downloaded timelapse into Movies/Layerwatch (no storage permission needed). */
fun saveToMovies(context: Context, file: java.io.File): Boolean = runCatching {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Layerwatch")
        put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    values.clear()
    values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    true
}.getOrDefault(false)

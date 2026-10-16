package app.layerwatch.protocol

/** Something worth telling the user about, derived from consecutive status reports. */
sealed interface PrintEvent {
    val jobName: String?

    data class Started(override val jobName: String?) : PrintEvent
    data class FirstLayerDone(override val jobName: String?) : PrintEvent
    data class Finished(override val jobName: String?) : PrintEvent
    data class Failed(override val jobName: String?, val printError: String?) : PrintEvent
    /** Ended FAILED without a reason ([PrinterStatus.isStopped]): stopped, not broken. */
    data class Stopped(override val jobName: String?) : PrintEvent
    /** [byPrinter] is false when the stage says the user paused it (from any client). */
    data class Paused(override val jobName: String?, val reason: String, val byPrinter: Boolean) : PrintEvent
    data class Resumed(override val jobName: String?) : PrintEvent
    data class NewHms(override val jobName: String?, val alerts: List<HmsAlert>) : PrintEvent
    /**
     * The printer moved the job to another AMS spool mid-print (auto-refill after a
     * run-out): the job's `mapping` changed without the print stopping.
     */
    data class SpoolSwitched(
        override val jobName: String?,
        val fromSlot: Int?,
        val toSlot: Int,
        /** e.g. "PETG Basic" of the new spool, when the AMS knows. */
        val toName: String?,
    ) : PrintEvent
}

/**
 * Turns a stream of [PrinterStatus] snapshots into [PrintEvent]s.
 *
 * The first snapshot is a baseline and never alerts, so reconnecting or restarting the
 * app doesn't replay "paused" or old HMS codes. Unknown job states are ignored rather
 * than treated as transitions, since a partial report can omit `gcode_state`.
 */
class PrintEventDetector {
    private var last: PrinterStatus? = null
    private var seenHms: Set<String> = emptySet()

    fun update(s: PrinterStatus): List<PrintEvent> {
        val prev = last
        if (prev == null) {
            last = s
            seenHms = s.hms.mapTo(mutableSetOf()) { it.id }
            return emptyList()
        }
        if (s.jobState == JobState.Unknown) return emptyList()
        last = s

        val events = mutableListOf<PrintEvent>()
        val was = prev.jobState
        val now = s.jobState
        val job = s.jobName ?: prev.jobName

        if (!was.isActive && was != JobState.Unknown && (now == JobState.Preparing || now == JobState.Running)) {
            events += PrintEvent.Started(job)
        }
        if (was != JobState.Paused && now == JobState.Paused) {
            val stage = s.stage
            val byUser = stage?.code == PAUSED_BY_USER
            events += PrintEvent.Paused(job, stage?.takeIf { it.isPause }?.label ?: "Paused", byPrinter = !byUser)
        }
        if (was == JobState.Paused && now == JobState.Running) events += PrintEvent.Resumed(job)
        if (was.isActive && now == JobState.Finished) events += PrintEvent.Finished(job)
        if (was != JobState.Failed && now == JobState.Failed) {
            events += if (s.isStopped) PrintEvent.Stopped(job) else PrintEvent.Failed(job, s.printError)
        }

        val prevLayer = prev.layer
        val layer = s.layer
        if (now == JobState.Running && prevLayer != null && layer != null && prevLayer <= 1 && layer >= 2) {
            events += PrintEvent.FirstLayerDone(job)
        }

        if (now.isActive && was.isActive && prev.amsMapping.isNotEmpty() && s.amsMapping.isNotEmpty() && prev.amsMapping != s.amsMapping) {
            val changed = s.amsMapping.indices.firstOrNull { prev.amsMapping.getOrNull(it) != s.amsMapping[it] }
            val to = changed?.let { s.amsMapping[it] }?.takeIf { it in 0..253 }
            if (to != null) {
                val tray = s.ams.flatMap { it.trays }.firstOrNull { it.slot == to }
                events += PrintEvent.SpoolSwitched(job, prev.amsMapping.getOrNull(changed)?.takeIf { it in 0..253 }, to, tray?.name ?: tray?.material)
            }
        }

        val ids = s.hms.mapTo(mutableSetOf()) { it.id }
        val fresh = s.hms.filter { it.id !in seenHms }
        if (fresh.isNotEmpty()) events += PrintEvent.NewHms(job, fresh)
        seenHms = ids // cleared codes may alert again if they come back

        return events
    }

    private companion object {
        const val PAUSED_BY_USER = 16
    }
}

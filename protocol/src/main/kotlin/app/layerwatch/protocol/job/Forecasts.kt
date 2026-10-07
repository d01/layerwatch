package app.layerwatch.protocol.job

import app.layerwatch.protocol.Tray
import kotlin.math.PI

enum class FilamentVerdict { Enough, Tight, Short, Unknown }

/** One filament's outlook for the rest of the print. */
data class FilamentNeed(
    /** G-code tool = slicer filament slot, 0-based (slicer filament id − 1). */
    val tool: Int,
    val neededG: Double,
    val amsSlot: Int?,
    /** On the mapped spool plus any matching backup spools the AMS can auto-refill from. */
    val availableG: Double?,
    /** Spools with the same filament that auto-refill can switch to (same type, brand, colour). */
    val backupSlots: List<Int> = emptyList(),
    /** First layer at which the spool would run dry, if it would. */
    val runsOutAtLayer: Int?,
) {
    val verdict: FilamentVerdict get() = when {
        availableG == null -> FilamentVerdict.Unknown
        neededG > availableG -> FilamentVerdict.Short
        neededG > availableG * TIGHT_FRACTION -> FilamentVerdict.Tight
        else -> FilamentVerdict.Enough
    }

    /** Grams expected on the spool when the print ends. */
    val leftAfterG: Double? get() = availableG?.let { it - neededG }

    private companion object {
        /** AMS remaining estimates are coarse; within 10% counts as tight. */
        const val TIGHT_FRACTION = 0.9
    }
}

object FilamentForecast {
    /**
     * Grams each filament still needs after [currentLayer], against what its AMS spool
     * holds. [mapping] is the printer's `mapping` (AMS slot per job filament); it is either
     * indexed by slicer slot or lists the used filaments in order, so both are handled.
     */
    fun compute(
        model: GcodeModel,
        settings: SlicerSettings?,
        currentLayer: Int,
        mapping: List<Int>,
        trays: Map<Int, Tray>,
    ): List<FilamentNeed> {
        val usedTools = model.layers.flatMap { it.extrudedMmByTool.keys }.toSortedSet().toList()
        return model.extrudedAfter(currentLayer).toSortedMap().map { (tool, mm) ->
            val gPerMm = gramsPerMm(settings, tool)
            val slot = amsSlot(tool, usedTools, mapping)
            val primary = slot?.let { trays[it] }
            val backups = primary?.let { p -> backupsFor(p, trays.values, exclude = mapping.toSet()) }.orEmpty()
            val available = primary?.remainingG?.let { it + backups.sumOf { b -> b.remainingG ?: 0.0 } }
            FilamentNeed(
                tool = tool,
                neededG = mm * gPerMm,
                amsSlot = slot,
                availableG = available,
                backupSlots = backups.map { it.slot },
                runsOutAtLayer = available?.let { runOutLayer(model, tool, currentLayer, it / gPerMm) },
            )
        }
    }

    /**
     * Spools auto-refill could switch to: same material, brand line and colour, with a
     * known amount left, not already feeding another of the job's filaments.
     */
    fun backupsFor(primary: Tray, all: Collection<Tray>, exclude: Set<Int>): List<Tray> = all.filter {
        it.slot != primary.slot && it.slot !in exclude && it.remainingG != null &&
            it.material != null && it.material == primary.material &&
            it.name == primary.name && it.colorHex.equals(primary.colorHex, ignoreCase = true)
    }.sortedBy { it.slot }

    internal fun gramsPerMm(settings: SlicerSettings?, tool: Int): Double {
        val d = settings?.filamentDiameter(tool) ?: 1.75
        val density = settings?.filamentDensity(tool) ?: 1.24
        return PI * (d / 2) * (d / 2) * density / 1000.0 // mm³ · g/cm³ → g
    }

    fun amsSlot(tool: Int, usedTools: List<Int>, mapping: List<Int>): Int? {
        val raw = when {
            tool < mapping.size && mapping.size > usedTools.size -> mapping[tool]
            mapping.size == usedTools.size -> mapping.getOrNull(usedTools.indexOf(tool))
            else -> mapping.getOrNull(tool)
        }
        return raw?.takeIf { it in 0..253 } // 254 = external spool, 255 = none
    }

    private fun runOutLayer(model: GcodeModel, tool: Int, after: Int, availableMm: Double): Int? {
        var used = 0.0
        for (l in model.layers) {
            if (l.number <= after) continue
            used += l.extrudedMmByTool[tool] ?: 0f
            if (used > availableMm) return l.number
        }
        return null
    }
}

/** When a layer was first seen printing. */
data class LayerSample(val layer: Int, val atMs: Long)

data class ScheduleEstimate(
    /** The slicer's plan, anchored at the earliest layer we saw printing. */
    val planFinishMs: Long?,
    /** Finish at the pace actually observed recently; falls back to the printer's estimate. */
    val paceFinishMs: Long?,
    /** Actual ÷ planned time over the recent window; > 1 means slower than planned. */
    val paceRatio: Double?,
) {
    val deltaMin: Long? get() = if (planFinishMs != null && paceFinishMs != null) Math.round((paceFinishMs - planFinishMs) / 60_000.0) else null
}

object Schedule {
    private const val WINDOW_MS = 30 * 60_000L
    private const val MIN_PLANNED_MIN = 3

    fun estimate(model: GcodeModel, samples: List<LayerSample>, nowMs: Long, printerRemainingMin: Int?): ScheduleEstimate {
        fun remaining(layer: Int) = model.layer(layer)?.plannedRemainingMin
        val sorted = samples.sortedBy { it.atMs }
        val anchor = sorted.firstOrNull { remaining(it.layer) != null }
        val planFinish = anchor?.let { it.atMs + remaining(it.layer)!! * 60_000L }

        val recent = sorted.filter { it.atMs >= nowMs - WINDOW_MS && remaining(it.layer) != null }
        val ratio = if (recent.size >= 2) {
            val a = recent.first()
            val b = recent.last()
            val plannedMin = remaining(a.layer)!! - remaining(b.layer)!!
            val actualMin = (b.atMs - a.atMs) / 60_000.0
            if (plannedMin >= MIN_PLANNED_MIN) (actualMin / plannedMin).coerceIn(0.5, 2.0) else null
        } else null

        val latest = sorted.lastOrNull { remaining(it.layer) != null }
        val paceFinish = when {
            ratio != null && latest != null -> latest.atMs + (remaining(latest.layer)!! * ratio * 60_000).toLong()
            printerRemainingMin != null -> nowMs + printerRemainingMin * 60_000L
            else -> null
        }
        return ScheduleEstimate(planFinish, paceFinish, ratio)
    }
}

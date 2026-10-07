package app.layerwatch.protocol.job

import app.layerwatch.protocol.Tray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class ForecastsTest {
    /** 10 layers of 100 mm each on tool 0, planned 10 min per layer (R = 100, 90, … 10). */
    private val model = GcodeParser.parse(
        StringReader(
            buildString {
                appendLine("M83")
                for (l in 1..10) {
                    appendLine("M73 P${l * 10} R${(11 - l) * 10}")
                    appendLine("; layer num/total_layer_count: $l/10")
                    appendLine("G1 X0 Y0")
                    appendLine("G1 X100 Y0 E100")
                }
            }
        ).buffered()
    )

    private fun tray(slot: Int, pct: Int, weight: Int = 1000) = Tray(slot, "PLA", null, null, pct, weight)

    @Test fun gramsPerMmMatchesStandardFilament() {
        // 1.75 mm PLA at 1.24 g/cm³ ≈ 2.98 g per metre.
        assertEquals(0.002983, FilamentForecast.gramsPerMm(null, 0), 1e-6)
    }

    @Test fun enoughTightShort() {
        // Remaining after layer 4: 6 layers × 100 mm ≈ 1.79 g.
        fun verdict(pct: Int, weight: Int) = FilamentForecast.compute(model, null, 4, listOf(2), mapOf(2 to tray(2, pct, weight))).single()
        val enough = verdict(pct = 50, weight = 1000)
        assertEquals(FilamentVerdict.Enough, enough.verdict)
        assertEquals(2, enough.amsSlot)
        assertEquals(1.79, enough.neededG, 0.01)
        assertEquals(FilamentVerdict.Tight, verdict(pct = 95, weight = 2).verdict) // 1.9 g on spool: within 10%
        val short = verdict(pct = 100, weight = 1) // 1 g ≈ 335 mm: lasts 3 more layers
        assertEquals(FilamentVerdict.Short, short.verdict)
        assertEquals(8, short.runsOutAtLayer)
    }

    @Test fun unknownWithoutSpoolData() {
        val n = FilamentForecast.compute(model, null, 4, emptyList(), emptyMap()).single()
        assertEquals(FilamentVerdict.Unknown, n.verdict)
        assertNull(n.amsSlot)
    }

    @Test fun mappingIndexedOrCompacted() {
        assertEquals(5, FilamentForecast.amsSlot(tool = 2, usedTools = listOf(2), mapping = listOf(5))) // compacted
        assertEquals(7, FilamentForecast.amsSlot(tool = 2, usedTools = listOf(0, 2), mapping = listOf(1, -1, 7, -1))) // by slot
        assertNull(FilamentForecast.amsSlot(tool = 0, usedTools = listOf(0), mapping = listOf(254))) // external spool
    }

    @Test fun scheduleOnPlanSlowAndFallback() {
        val t0 = 1_000_000_000L
        val min = 60_000L
        // Layer 2 seen at t0 (R=90 → plan finish t0+90 min); layers then take 12 min instead of 10.
        val slow = listOf(LayerSample(2, t0), LayerSample(3, t0 + 12 * min), LayerSample(4, t0 + 24 * min))
        val e = Schedule.estimate(model, slow, nowMs = t0 + 25 * min, printerRemainingMin = 70)
        assertEquals(t0 + 90 * min, e.planFinishMs)
        assertEquals(1.2, e.paceRatio!!, 1e-9)
        assertEquals(t0 + 24 * min + (70 * 1.2 * min).toLong(), e.paceFinishMs) // R(4)=70 at 1.2×
        assertEquals(18L, e.deltaMin)

        val fallback = Schedule.estimate(model, listOf(LayerSample(2, t0)), nowMs = t0 + min, printerRemainingMin = 88)
        assertNull(fallback.paceRatio)
        assertEquals(t0 + min + 88 * min, fallback.paceFinishMs)
    }
}

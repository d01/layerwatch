package app.bambumon.protocol.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class ProgressMarkersTest {
    /** 10 layers, 10 planned minutes each; filament 0 → 2 at layer 4, 2 → 1 at layer 8. */
    private fun model(changes: Map<Int, Int> = mapOf(4 to 2, 8 to 1)) = GcodeParser.parse(
        StringReader(
            buildString {
                appendLine("M83")
                appendLine("M620 S0A")
                for (l in 1..10) {
                    appendLine("M73 P${(l - 1) * 10} R${(11 - l) * 10}")
                    appendLine("; layer num/total_layer_count: $l/10")
                    changes[l]?.let { appendLine("M620 S${it}A") }
                    appendLine("G1 X0 Y0")
                    appendLine("G1 X10 Y0 E1")
                }
            }
        ).buffered()
    )

    @Test fun segmentsAndPointsFollowFilamentChanges() {
        val m = ProgressMarkers.from(model())!!
        assertEquals(
            listOf(ProgressMarkers.Segment(0, 30, 0), ProgressMarkers.Segment(30, 70, 2), ProgressMarkers.Segment(70, 100, 1)),
            m.segments,
        )
        assertEquals(
            listOf(
                ProgressMarkers.Point(10, ProgressMarkers.Kind.FirstLayerDone, 2),
                ProgressMarkers.Point(30, ProgressMarkers.Kind.FilamentChange, 4, toTool = 2),
                ProgressMarkers.Point(70, ProgressMarkers.Kind.FilamentChange, 8, toTool = 1),
            ),
            m.points,
        )
    }

    @Test fun singleFilamentIsOneSegment() {
        val m = ProgressMarkers.from(model(emptyMap()))!!
        assertEquals(listOf(ProgressMarkers.Segment(0, 100, 0)), m.segments)
        assertEquals(listOf(ProgressMarkers.Kind.FirstLayerDone), m.points.map { it.kind })
    }

    @Test fun minutesToNextChange() {
        val (change, min) = ProgressMarkers.minutesToNextChange(model(), currentLayer = 6)!!
        assertEquals(8, change.layer)
        assertEquals(20, min)
        assertNull(ProgressMarkers.minutesToNextChange(model(), currentLayer = 9))
    }
}

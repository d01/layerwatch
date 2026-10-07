package app.bambumon.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMergerTest {

    @Test fun partialReportUpdatesOnlyItsFields() {
        val m = ReportMerger()
        m.apply("""{"print":{"command":"push_status","mc_percent":10,"layer_num":3,"device":{"ctc":{"info":{"temp":30}},"bed":{"state":2}}}}""")
        assertTrue(m.apply("""{"print":{"command":"push_status","mc_percent":11,"device":{"ctc":{"info":{"temp":31}}}}}"""))
        assertEquals(11, m.state["mc_percent"]!!.jsonPrimitive.int)
        assertEquals(3, m.state["layer_num"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(31), m.state.path("device", "ctc", "info", "temp"))
        assertEquals(JsonPrimitive(2), m.state.path("device", "bed", "state"))
    }

    @Test fun arraysReplaceRatherThanMerge() {
        val m = ReportMerger()
        m.apply("""{"print":{"command":"push_status","hms":[{"attr":1,"code":2}]}}""")
        m.apply("""{"print":{"command":"push_status","hms":[]}}""")
        assertEquals("[]", m.state["hms"].toString())
    }

    @Test fun ignoresCommandRepliesAndGarbage() {
        val m = ReportMerger()
        assertFalse(m.apply("""{"print":{"command":"gcode_line","result":"SUCCESS"}}"""))
        assertFalse(m.apply("""{"info":{"command":"get_version"}}"""))
        assertFalse(m.apply("not json"))
        assertTrue(m.state.isEmpty())
    }

    @Test fun identicalReportIsNotAChange() {
        val m = ReportMerger()
        val r = """{"print":{"command":"push_status","mc_percent":5}}"""
        assertTrue(m.apply(r))
        assertFalse(m.apply(r))
    }

    @Test fun recordedCaptureMergesToFullState() {
        val m = ReportMerger()
        Fixtures.captureReports.forEach { m.apply(it) }
        assertEquals(96, m.state.size)
    }
}

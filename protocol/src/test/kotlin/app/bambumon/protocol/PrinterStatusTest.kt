package app.bambumon.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterStatusTest {

    private fun recorded(): PrinterStatus {
        val merger = ReportMerger()
        Fixtures.captureReports.forEach { merger.apply(it) }
        return PrinterStatus.from(merger.state)
    }

    @Test fun parsesRecordedPrint() {
        val s = recorded()
        assertEquals(JobState.Running, s.jobState)
        assertEquals("sample_part_wide_C1", s.jobName)
        assertEquals("1", s.taskId)
        assertEquals(1, s.plateIndex)
        assertEquals(502, s.totalLayers)
        assertEquals(39.0, s.chamberC!!, 0.0)
        assertEquals(70.0, s.bed.target!!, 0.0)
        assertEquals(SpeedLevel.Standard, s.speed)
        assertEquals(true, s.chamberLightOn)
        assertEquals(-75, s.wifiDbm)
        assertEquals("0.8", s.nozzleDiameter)
        assertNull(s.printError)
        assertTrue(s.hms.isEmpty())
    }

    @Test fun parsesAms() {
        val s = recorded()
        val unit = s.ams.single()
        assertEquals(listOf(0, 1, 2, 3), unit.trays.map { it.slot })
        assertEquals("PETG", unit.trays[0].material)
        assertEquals("PETG Translucent", unit.trays[3].name)
        assertEquals(41, unit.trays[0].remainingPercent)
        assertEquals(2, unit.humidityLevel)
        assertEquals(0, s.activeSlot)
    }

    @Test fun fanGearStepsBecomePercent() {
        val s = status("""{"print":{"command":"push_status","cooling_fan_speed":"15","big_fan1_speed":"6","heatbreak_fan_speed":"0"}}""")
        assertEquals(100, s.partFanPercent)
        assertEquals(40, s.auxFanPercent)
        assertEquals(0, s.heatbreakFanPercent)
        assertNull(s.chamberFanPercent)
    }

    @Test fun hmsAndPrintErrorFormatting() {
        val s = status(
            """{"print":{"command":"push_status","print_error":50348044,
               "hms":[{"attr":50336256,"code":131073}]}}"""
        )
        assertEquals("0300_400C", s.printError)
        assertEquals("0300_1200_0002_0001", s.hms.single().id)
        assertEquals(2, s.hms.single().severity)
    }

    @Test fun idleStageIsNullAndUnknownStageIsKept() {
        assertNull(status("""{"print":{"command":"push_status","stg_cur":255}}""").stage)
        assertEquals("Stage 99", status("""{"print":{"command":"push_status","stg_cur":99}}""").stage!!.label)
        assertTrue(status("""{"print":{"command":"push_status","stg_cur":6}}""").stage!!.isPause)
    }

    @Test fun emptyReportIsAllUnknown() {
        val s = PrinterStatus.from(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(JobState.Unknown, s.jobState)
        assertFalse(s.jobState.isActive)
        assertNull(s.percent)
        assertTrue(s.ams.isEmpty())
    }

    private fun status(report: String): PrinterStatus {
        val m = ReportMerger()
        m.apply(report)
        return PrinterStatus.from(m.state)
    }
}

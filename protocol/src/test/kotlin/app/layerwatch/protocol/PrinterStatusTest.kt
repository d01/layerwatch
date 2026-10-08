package app.layerwatch.protocol

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
        assertEquals(1000, unit.trays[0].spoolWeightG)
        assertEquals(410.0, unit.trays[0].remainingG!!, 1e-9)
        assertEquals(listOf(0), s.amsMapping)
        assertEquals(2, unit.humidityLevel)
        // An AMS 2 Pro: info 0x2003, drying off.
        assertEquals(AmsType.Ams2Pro, unit.type)
        assertEquals(DryStatus.Off, unit.dryStatus)
        assertEquals(false, unit.isDrying)
        assertEquals(32, unit.humidityPercent)
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

    @Test fun dryingStateDecodes() {
        // An X1C with an AMS 2 Pro mid-drying (values from ha-bambulab's X1C capture).
        val s = status("""{"print":{"command":"push_status","fun2":"1000","ams":{"ams":[
            {"id":"0","info":"1001","dry_time":0,"tray":[]},
            {"id":"2","info":"142023","dry_time":583,"temp":"65.0","humidity_raw":"18","tray":[]},
            {"id":"128","info":"2004","dry_time":0,"dry_sf_reason":[1],"tray":[]}]}}}""")
        val (plain, pro, ht) = s.ams
        assertEquals(AmsType.Ams, plain.type)
        assertEquals(false, plain.isDrying)
        assertEquals(AmsType.Ams2Pro, pro.type)
        assertEquals(DryStatus.Drying, pro.dryStatus)
        assertEquals(583, pro.dryMinutesLeft)
        assertEquals(true, pro.isDrying)
        assertEquals(AmsType.AmsHt, ht.type)
        assertEquals(listOf(1), ht.cannotDryReasons)
        assertEquals("Not enough power: connect the AMS's 24 V adapter", cannotDryText(1))
        assertEquals(false, s.supportsRemoteDrying) // fun2 0x1000: bit 5 clear, as on an X1C (firmware 01.12)
        assertEquals(true, status("""{"print":{"command":"push_status","fun2":"20"}}""").supportsRemoteDrying)

        // An AMS 2 Pro on an X1C mid-dry.
        val drying = status("""{"print":{"command":"push_status","ams":{"ams":[{"id":"0","info":"2023","dry_time":708,"temp":"48.2","humidity_raw":"28","humidity":"1","tray":[]}]}}}""").ams.single()
        assertEquals(AmsType.Ams2Pro, drying.type)
        assertEquals(DryStatus.Drying, drying.dryStatus)
        assertEquals(708, drying.dryMinutesLeft)
        assertEquals(true, drying.isDrying)
    }
}

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
        assertEquals(Temperature(39.0, null), s.chamber)
        assertEquals(Liveview.Rtsps, s.liveview)
        assertFalse(s.commandsLocked) // fun 20011830F9CFB: bit 29 clear, commands accepted
        assertTrue(s.nozzles.isEmpty()) // one extruder
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

    @Test fun packedDeviceTemperatures() {
        // The X1C's own device block: bed 0x0046_0046, extruder 0x00FF_00FC.
        assertEquals(Temperature(70.0, 70.0), packedTemperature(kotlinx.serialization.json.JsonPrimitive(4587590)))
        assertEquals(Temperature(252.0, 255.0), packedTemperature(kotlinx.serialization.json.JsonPrimitive(16711932)))
        assertEquals(Temperature(39.0, null), packedTemperature(kotlinx.serialization.json.JsonPrimitive(39)))
    }

    @Test fun dualNozzleAndHeatedChamber() {
        // H2D shape: two extruders and a chamber heading for 45°; no top-level temperatures.
        val s = status("""{"print":{"command":"push_status","device":{
            "extruder":{"info":[{"id":1,"temp":${(220 shl 16) or 180}},{"id":0,"temp":${(250 shl 16) or 249}}],"state":2},
            "bed":{"info":{"temp":${(80 shl 16) or 79}}},"ctc":{"info":{"temp":${(45 shl 16) or 40}}}}}}""")
        assertEquals(listOf(Temperature(249.0, 250.0), Temperature(180.0, 220.0)), s.nozzles)
        assertEquals(Temperature(249.0, 250.0), s.nozzle)
        assertEquals(Temperature(79.0, 80.0), s.bed)
        assertEquals(Temperature(40.0, 45.0), s.chamber)
    }

    @Test fun cameraAndLocks() {
        assertEquals(Liveview.NotReported, status("""{"print":{"command":"push_status","ipcam":{"ipcam_dev":"1"}}}""").liveview) // P1, A1
        assertEquals(Liveview.Disabled, status("""{"print":{"command":"push_status","ipcam":{"rtsp_url":"disable"}}}""").liveview)
        assertNull(status("""{"print":{"command":"push_status"}}""").liveview)
        assertTrue(status("""{"print":{"command":"push_status","fun":"3EC1AFFF9CFF"}}""").commandsLocked) // Developer Mode off
        assertFalse(status("""{"print":{"command":"push_status","fun":"3EC18FFF9CFF"}}""").commandsLocked)
    }

    @Test fun modelsAndCameras() {
        assertEquals(PrinterModel.X1Carbon, PrinterModel.fromSerial("00M00A000000000"))
        assertEquals(PrinterModel.A1Mini, PrinterModel.fromSerial("0300000000000"))
        assertEquals(PrinterModel.Unknown, PrinterModel.fromSerial("ZZZ"))
        assertEquals(PrinterModel.CameraKind.Jpeg, cameraKind(PrinterModel.P1S, null))
        assertEquals(PrinterModel.CameraKind.Jpeg, cameraKind(PrinterModel.Unknown, Liveview.NotReported))
        assertEquals(PrinterModel.CameraKind.Rtsps, cameraKind(PrinterModel.Unknown, Liveview.Rtsps))
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

    @Test fun skipObjects() {
        // This X1C's real `fun` (firmware 01.12): bit 49 set, bit 29 (signed commands) clear.
        val s = status("""{"print":{"command":"push_status","fun":"20011830F9CFB","s_obj":[270,"373"]}}""")
        assertEquals(true, s.supportsSkipObjects)
        assertEquals(false, s.commandsLocked)
        assertEquals(setOf(270, 373), s.skippedObjects)
        val bare = status("""{"print":{"command":"push_status","fun":"1830F9CFB","s_obj":[]}}""")
        assertEquals(false, bare.supportsSkipObjects)
        assertEquals(emptySet<Int>(), bare.skippedObjects)
    }
}

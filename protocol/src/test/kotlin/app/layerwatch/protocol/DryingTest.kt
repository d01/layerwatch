package app.layerwatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DryingTest {
    private fun status(state: String, trayNow: Int, info: String = "2003", dryTime: Int = 0, trays: String = PETG_PLA): PrinterStatus {
        val m = ReportMerger()
        m.apply(
            """{"print":{"command":"push_status","gcode_state":"$state","ams":{"tray_now":"$trayNow","ams":[""" +
                """{"id":"0","info":"$info","dry_time":$dryTime,"tray":[$trays]}]}}}""",
        )
        return PrinterStatus.from(m.state)
    }

    private fun blocker(s: PrinterStatus) = dryingBlocker(s.ams.single(), s)

    @Test fun idleWithNothingLoadedCanStart() = assertNull(blocker(status("FINISH", 255)))

    @Test fun blockedWhilePrintingLoadedOrDrying() {
        assertEquals("The printer is busy with a print", blocker(status("RUNNING", 255)))
        assertEquals("Filament is loaded through the AMS outlet: unload it first", blocker(status("FINISH", 2)))
        assertEquals("Already drying", blocker(status("IDLE", 255, info = "2023", dryTime = 478)))
        assertEquals("This AMS can't dry filament", blocker(status("IDLE", 255, info = "1001")))
    }

    @Test fun presetsAreBambuStudios() {
        // Values from Bambu Studio's profiles: [AMS 2 Pro idle, AMS HT idle, ...].
        val support = DryingPresets.byId("GFS05")!!
        assertEquals("Bambu Support For PLA/PETG", support.name)
        assertEquals(DryingSettings("PLA", 60, 12, rotateSpools = false, coolingC = 55), support.settings(AmsType.Ams2Pro))
        assertEquals(50, support.heatDistortionC)
        val tpu = DryingPresets.byId("GFU01")!!
        assertEquals(65, tpu.tempC(AmsType.Ams2Pro))
        assertEquals(75, tpu.tempC(AmsType.AmsHt))
        assertEquals(setOf(AmsType.AmsHt), tpu.driesFullyOn)
    }

    @Test fun listIsFilteredByPrinterAndNozzle() {
        val x1c = DryingPresets.forPrinter(PrinterModel.X1Carbon, "0.8")
        assertTrue(x1c.any { it.id == "GFS05" })
        assertTrue(x1c.size in 50 until DryingPresets.all.size + 1)
        assertEquals(x1c.map { it.name }.sorted(), x1c.map { it.name })
    }

    @Test fun defaultIsTheCoolestLoadedFilamentOrPlaBasic() {
        // PETG Basic (65) and PLA Basic (45): PLA wins so nothing overheats.
        assertEquals("GFA00", defaultDryingPreset(status("IDLE", 255).ams.single())!!.id)
        assertEquals("GFG00", defaultDryingPreset(status("IDLE", 255, trays = """{"id":"0","tray_type":"PETG","tray_info_idx":"GFG00"}""").ams.single())!!.id)
        assertEquals("GFA00", defaultDryingPreset(status("IDLE", 255, trays = "").ams.single())!!.id)
    }

    @Test fun loadedFilamentLimitsTheTemperature() {
        val unit = status("IDLE", 255).ams.single() // PLA Basic loaded: heat distortion 45
        assertNull(feederHeatProblem(unit, 45))
        assertEquals(
            "Too hot for the filament in slot 2 (limit 45 °C): pull it back out of the feeder or lower the temperature",
            feederHeatProblem(unit, 60),
        )
        assertNull(feederHeatProblem(status("IDLE", 255, trays = "").ams.single(), 65))
    }

    private companion object {
        const val PETG_PLA = """{"id":"0","tray_type":"PETG","tray_info_idx":"GFG00"},{"id":"1","tray_type":"PLA","tray_info_idx":"GFA00"},{"id":"2"}"""
    }
}

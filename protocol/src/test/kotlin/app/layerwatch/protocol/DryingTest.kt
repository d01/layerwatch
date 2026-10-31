package app.layerwatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DryingTest {
    private fun status(state: String, trayNow: Int, info: String = "2003", dryTime: Int = 0): PrinterStatus {
        val m = ReportMerger()
        m.apply(
            """{"print":{"command":"push_status","gcode_state":"$state","ams":{"tray_now":"$trayNow","ams":[""" +
                """{"id":"0","info":"$info","dry_time":$dryTime,"tray":[{"id":"0","tray_type":"PETG"},{"id":"1","tray_type":"PLA"},{"id":"2","tray_type":"PETG"}]}]}}}""",
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

    @Test fun suggestionsUsePresetsAndTheUnitsLimit() {
        assertEquals(DryingSettings("PETG", 65, 8, false), DryingSettings.suggested("petg", AmsType.Ams2Pro))
        assertEquals("PLA", DryingSettings.suggested("Mystery", AmsType.Ams2Pro).filament)
        assertEquals(listOf("PETG", "PLA"), loadedMaterials(status("IDLE", 255).ams.single()))
    }
}

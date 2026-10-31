package app.layerwatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PrinterCommandsTest {
    @Test fun pushAllMatchesVerifiedShape() =
        assertEquals("""{"pushing":{"sequence_id":"1","command":"pushall"}}""", PrinterCommands.pushAll(1))

    @Test fun jobControl() {
        assertEquals("""{"print":{"sequence_id":"7","command":"pause"}}""", PrinterCommands.pause(7))
        assertEquals("""{"print":{"sequence_id":"8","command":"resume"}}""", PrinterCommands.resume(8))
        assertEquals("""{"print":{"sequence_id":"9","command":"stop"}}""", PrinterCommands.stop(9))
    }

    @Test fun speedUsesLevelCode() = assertEquals(
        """{"print":{"sequence_id":"3","command":"print_speed","param":"3"}}""",
        PrinterCommands.speed(3, SpeedLevel.Sport),
    )

    @Test fun skipObjectsMatchesBambuStudio() = assertEquals(
        """{"print":{"sequence_id":"5","command":"skip_objects","obj_list":[270,373]}}""",
        PrinterCommands.skipObjects(5, listOf(270, 373)),
    )

    @Test fun dryingMatchesWhatTheX1cAccepted() {
        assertEquals(
            """{"print":{"sequence_id":"6","command":"ams_filament_drying","ams_id":0,"mode":1,"filament":"PLA-S",""" +
                """"temp":60,"duration":8,"humidity":0,"rotate_tray":true,"cooling_temp":45,"close_power_conflict":false}}""",
            PrinterCommands.startDrying(6, 0, DryingSettings("PLA-S", 60, 8, rotateSpools = true)),
        )
        assertEquals(
            """{"print":{"sequence_id":"7","command":"ams_filament_drying","ams_id":1,"mode":0,"filament":"",""" +
                """"temp":0,"duration":0,"humidity":0,"rotate_tray":false,"cooling_temp":45,"close_power_conflict":false}}""",
            PrinterCommands.stopDrying(7, 1),
        )
    }

    @Test fun chamberLight() = assertEquals(
        """{"system":{"sequence_id":"4","command":"ledctrl","led_node":"chamber_light","led_mode":"off",""" +
            """"led_on_time":500,"led_off_time":500,"loop_times":0,"interval_time":0}}""",
        PrinterCommands.chamberLight(4, on = false),
    )
}

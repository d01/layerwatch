package app.bambumon.protocol

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

    @Test fun chamberLight() = assertEquals(
        """{"system":{"sequence_id":"4","command":"ledctrl","led_node":"chamber_light","led_mode":"off",""" +
            """"led_on_time":500,"led_off_time":500,"loop_times":0,"interval_time":0}}""",
        PrinterCommands.chamberLight(4, on = false),
    )
}

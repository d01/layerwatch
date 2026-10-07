package app.layerwatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintEventDetectorTest {
    private val merger = ReportMerger()
    private val detector = PrintEventDetector()

    /** Feeds a partial push_status and returns the events it produced. */
    private fun feed(fields: String): List<PrintEvent> {
        merger.apply("""{"print":{"command":"push_status",$fields}}""")
        return detector.update(PrinterStatus.from(merger.state))
    }

    @Test fun firstReportIsABaseline() {
        assertTrue(feed(""""gcode_state":"PAUSE","stg_cur":6,"hms":[{"attr":1,"code":2}]""").isEmpty())
    }

    @Test fun fullPrintLifecycle() {
        feed(""""gcode_state":"IDLE","subtask_name":"bracket","layer_num":0""")
        assertEquals(listOf(PrintEvent.Started("bracket")), feed(""""gcode_state":"PREPARE""""))
        assertTrue(feed(""""gcode_state":"RUNNING","stg_cur":0,"layer_num":1""").isEmpty())
        assertEquals(listOf(PrintEvent.FirstLayerDone("bracket")), feed(""""layer_num":2"""))
        assertTrue(feed(""""layer_num":3""").isEmpty())
        assertEquals(listOf(PrintEvent.Finished("bracket")), feed(""""gcode_state":"FINISH""""))
    }

    @Test fun pauseByPrinterVersusUser() {
        feed(""""gcode_state":"RUNNING","subtask_name":"x"""")
        val runout = feed(""""gcode_state":"PAUSE","stg_cur":6""").single() as PrintEvent.Paused
        assertEquals("Paused: filament runout", runout.reason)
        assertTrue(runout.byPrinter)
        assertEquals(listOf(PrintEvent.Resumed("x")), feed(""""gcode_state":"RUNNING","stg_cur":0"""))
        val user = feed(""""gcode_state":"PAUSE","stg_cur":16""").single() as PrintEvent.Paused
        assertEquals(false, user.byPrinter)
    }

    @Test fun failureCarriesPrintError() {
        feed(""""gcode_state":"RUNNING","subtask_name":"x"""")
        val failed = feed(""""gcode_state":"FAILED","print_error":50348044""").single() as PrintEvent.Failed
        assertEquals("0300_400C", failed.printError)
    }

    @Test fun hmsAlertsOnlyWhenNewAndAgainAfterClearing() {
        feed(""""gcode_state":"RUNNING","hms":[]""")
        val first = feed(""""hms":[{"attr":50336256,"code":131073}]""").single() as PrintEvent.NewHms
        assertEquals("0300_1200_0002_0001", first.alerts.single().id)
        assertTrue(feed(""""hms":[{"attr":50336256,"code":131073}]""").isEmpty())
        feed(""""hms":[]""")
        assertEquals(1, feed(""""hms":[{"attr":50336256,"code":131073}]""").size)
    }

    @Test fun unknownStateIsNotATransition() {
        feed(""""gcode_state":"RUNNING"""")
        assertTrue(detector.update(PrinterStatus.from(kotlinx.serialization.json.JsonObject(emptyMap()))).isEmpty())
        assertTrue(feed(""""gcode_state":"RUNNING"""").isEmpty())
    }
}

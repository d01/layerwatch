package app.layerwatch.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the demo printer's clock by hand and checks it tells a coherent story. */
class DemoPrinterTest {
    private val demo = DemoPrinter(CoroutineScope(Dispatchers.Unconfined))
    private val detector = PrintEventDetector()
    private val events = mutableListOf<PrintEvent>()

    private fun step(seconds: Int) = repeat(seconds) {
        demo.advance(1.0)
        demo.emit()
        events += detector.update(demo.status.value!!)
    }

    @Test fun fullCycleHasTheEventsAPrintWould() {
        demo.emit()
        detector.update(demo.status.value!!)
        assertEquals(JobState.Preparing, demo.status.value!!.jobState)
        step(60)
        assertEquals(JobState.Running, demo.status.value!!.jobState)
        step(250) // into the finished window (cycle: 45 s prepare, 240 s layers, 60 s finished)
        val kinds = events.map { it::class.simpleName }
        assertTrue(kinds.toString(), "FirstLayerDone" in kinds && "Finished" in kinds)
        assertEquals(100, demo.status.value!!.percent)
        assertEquals(DemoPrinter.LAYERS, demo.status.value!!.layer)
    }

    @Test fun pauseFreezesProgressAndStopFails() {
        demo.emit(); detector.update(demo.status.value!!)
        step(120)
        demo.pause()
        val frozen = demo.status.value!!.percent
        step(30)
        assertEquals(JobState.Paused, demo.status.value!!.jobState)
        assertEquals(frozen, demo.status.value!!.percent)
        demo.resume()
        step(5)
        assertEquals(JobState.Running, demo.status.value!!.jobState)
        demo.stopPrint()
        step(1)
        assertEquals(JobState.Failed, demo.status.value!!.jobState)
        assertEquals("The task was canceled.", HmsCatalog.describeError(demo.status.value!!.printErrorCode))
        assertTrue(events.any { it is PrintEvent.Failed })
    }

    @Test fun lightAndSpeedRespond() {
        demo.setChamberLight(false)
        assertEquals(false, demo.status.value!!.chamberLightOn)
        demo.setSpeed(SpeedLevel.Sport)
        assertEquals(SpeedLevel.Sport, demo.status.value!!.speed)
        assertEquals(AmsType.Ams2Pro, demo.status.value!!.ams.single().type)
    }

    @Test fun skipObjectsOnlyWhilePrintingAndClearsNextRun() {
        demo.emit()
        assertEquals(true, demo.status.value!!.supportsSkipObjects)
        demo.skipObjects(listOf(270)) // still preparing
        assertEquals(emptySet<Int>(), demo.status.value!!.skippedObjects)
        step(60)
        demo.skipObjects(listOf(270))
        assertEquals(setOf(270), demo.status.value!!.skippedObjects)
        step(400) // past the cycle's end
        assertEquals(emptySet<Int>(), demo.status.value!!.skippedObjects)
    }
}

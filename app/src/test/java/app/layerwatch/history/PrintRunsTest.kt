package app.layerwatch.history

import app.layerwatch.protocol.PrinterStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrintRunsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun report(state: String, job: String? = "case", task: String? = "1234", file: String? = "/data/Metadata/plate_1.gcode") =
        PrinterStatus.from(buildJsonObject {
            put("gcode_state", state)
            job?.let { put("subtask_name", it) }
            put("task_id", task ?: "0")
            file?.let { put("gcode_file", it) }
        })

    private fun PrintRuns.feed(vararg reports: Pair<PrinterStatus, Long>) = reports.forEach { (s, at) -> observe(s, at) }

    @Test fun reusedTaskIdAfterAnEndIsANewRun() {
        val runs = PrintRuns(tmp.root)
        runs.feed(report("RUNNING") to 1, report("FINISH") to 2)
        val first = runs.current.value!!
        assertEquals(Outcome.Finished, first.end)
        runs.feed(report("PREPARE") to 3) // the printer drew the same random task id
        val second = runs.current.value!!
        assertNotEquals(first.id, second.id)
        assertNull(second.end)
    }

    @Test fun aPrintIsOneRunThroughPrepareAndPlateChanges() {
        val runs = PrintRuns(tmp.root)
        runs.feed(report("PREPARE", task = null, file = null) to 1, report("RUNNING", file = "/data/Metadata/plate_2.gcode") to 2, report("PAUSE") to 3)
        val run = runs.current.value!!
        assertEquals("run-1", run.id)
        assertEquals("1234", run.taskId) // filled in once the printer reported it
    }

    @Test fun survivesTheProcess() {
        PrintRuns(tmp.root).feed(report("RUNNING") to 1)
        val again = PrintRuns(tmp.root)
        again.feed(report("RUNNING") to 50) // monitor restarted mid-print
        assertEquals("run-1", again.current.value!!.id)
    }

    @Test fun anotherJobIsAnotherRunEvenWithoutAnEndSeen() {
        val runs = PrintRuns(tmp.root)
        runs.feed(report("RUNNING") to 1, report("RUNNING", job = "knob", task = "17") to 2)
        assertEquals("run-2", runs.current.value!!.id)
    }

    @Test fun unknownStateChangesNothing() {
        val runs = PrintRuns(tmp.root)
        runs.feed(report("RUNNING") to 1, report("") to 2)
        assertNull(runs.current.value!!.end)
    }

    @Test fun stoppedEndsTheRunAsStopped() {
        val runs = PrintRuns(tmp.root)
        runs.feed(report("RUNNING") to 1, report("FAILED") to 2)
        assertEquals(Outcome.Stopped, runs.current.value!!.end) // no fault code: the user stopped it
    }

    @Test fun printRunningAtTheUpgradeKeepsItsOldRecord() {
        val runs = PrintRuns(tmp.root) { task -> "task-$task".takeIf { task == "1234" } }
        runs.feed(report("RUNNING") to 1)
        assertEquals("task-1234", runs.current.value!!.id)
        runs.feed(report("FINISH") to 2, report("RUNNING") to 3)
        assertEquals("run-3", runs.current.value!!.id) // only the run found at the upgrade
    }
}

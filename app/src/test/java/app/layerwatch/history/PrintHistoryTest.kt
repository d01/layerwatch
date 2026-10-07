package app.layerwatch.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrintHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun beginIsIdempotentAndFinishRecordsOutcome() = runBlocking {
        val h = PrintHistory(tmp.root)
        h.begin("task-82", "sample_part", 502, 1_000)
        h.begin("task-82", "sample_part", 502, 2_000) // seen again after a restart
        h.finish("task-82", Outcome.Finished, null, 9_000)
        val r = h.records.value.single()
        assertEquals(1_000, r.firstSeenAtMs)
        assertEquals(Outcome.Finished, r.outcome)
        assertEquals(9_000L, r.endedAtMs)
    }

    @Test fun finishOnlyClosesAnOpenRecord() = runBlocking {
        val h = PrintHistory(tmp.root)
        h.begin("k", "a", null, 1)
        h.finish("k", Outcome.Failed, "0300_400C", 2)
        h.finish("k", Outcome.Finished, null, 3) // late duplicate must not overwrite
        assertEquals(Outcome.Failed, h.records.value.single().outcome)
        assertEquals("0300_400C", h.records.value.single().printError)
    }

    @Test fun survivesReload() = runBlocking {
        PrintHistory(tmp.root).apply {
            begin("a", "first", 10, 1)
            begin("b", "second", null, 2)
        }
        val reloaded = PrintHistory(tmp.root).records.value
        assertEquals(listOf("b", "a"), reloaded.map { it.key }) // newest first
        assertEquals(Outcome.InProgress, reloaded[0].outcome)
        assertNull(reloaded[0].totalLayers)
    }

    @Test fun keys() {
        assertEquals("task-82", PrintHistory.keyFor("82", "x", 5))
        assertEquals("job-x-5", PrintHistory.keyFor(null, "x", 5))
    }

    @Test fun reconcileClosesEntriesWhosePrintEnded() = runBlocking {
        val h = PrintHistory(tmp.root)
        h.begin("task-82", "a", null, 1)
        h.begin("task-83", "b", null, 2)
        h.begin("task-84", "c", null, 3)
        // Printer now reports task-83 finished; task-84 is running.
        h.reconcile(activeKey = "task-84", reportedKey = "task-83", reportedOutcome = Outcome.Finished, nowMs = 9)
        val byKey = h.records.value.associateBy { it.key }
        assertEquals(Outcome.Unknown, byKey["task-82"]!!.outcome) // ended unseen
        assertEquals(Outcome.Finished, byKey["task-83"]!!.outcome)
        assertEquals(Outcome.InProgress, byKey["task-84"]!!.outcome)
        assertEquals(9L, byKey["task-83"]!!.endedAtMs)
    }
}

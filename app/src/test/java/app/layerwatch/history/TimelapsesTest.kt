package app.layerwatch.history

import app.layerwatch.protocol.ftps.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapsesTest {
    private fun file(name: String) = RemoteFile(name, 1_000, isDirectory = false, listing = "")
    private val old = "video_2026-10-01_08-00-00.mp4"
    private val a = "video_2026-10-02_08-00-00.mp4"
    private val b = "video_2026-10-03_08-00-00.mp4"
    private val c = "video_2026-10-04_08-00-00.mp4"
    private val now = listOf(old, a, b, c).map(::file)

    @Test fun olderPrintKeepsItsOwnVideoAfterLaterPrints() {
        // Print A started with only `old` there; B started after A's video appeared.
        val ref = Timelapses.pick(now, before = setOf(old), nextBefore = setOf(old, a), isNewest = false)!!
        assertEquals(a, ref.name)
        assertTrue(ref.certain)
    }

    @Test fun firstNewFileWinsWhenTheNextPrintWasNotRecorded() {
        // Prints after A ran without Layerwatch; their videos are newer than A's.
        val ref = Timelapses.pick(now, before = setOf(old), nextBefore = null, isNewest = false)!!
        assertEquals(a, ref.name)
        assertFalse(ref.certain) // b and c are candidates too
    }

    @Test fun newestPrintTakesTheFileItAdded() {
        assertEquals(c, Timelapses.pick(now, before = setOf(old, a, b), nextBefore = null, isNewest = true)!!.name)
    }

    @Test fun noFileAddedMeansNoTimelapse() {
        assertNull(Timelapses.pick(now, before = setOf(old, a), nextBefore = setOf(old, a), isNewest = false))
    }

    @Test fun unrecordedStartGuessesTheLastFileBeforeTheNextPrint() {
        val ref = Timelapses.pick(now, before = null, nextBefore = setOf(old, a), isNewest = false)!!
        assertEquals(a, ref.name)
        assertFalse(ref.certain)
    }

    @Test fun unrecordedOlderPrintDoesNotClaimTheNewestVideo() {
        assertNull(Timelapses.pick(now, before = null, nextBefore = null, isNewest = false))
        assertEquals(c, Timelapses.pick(now, before = null, nextBefore = null, isNewest = true)!!.name)
    }

    @Test fun deletedVideoIsNotOffered() {
        // A's video was deleted from the printer since.
        assertNull(Timelapses.pick(listOf(old, b).map(::file), before = setOf(old), nextBefore = setOf(old, a), isNewest = false))
    }
}

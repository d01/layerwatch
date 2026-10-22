package app.layerwatch.history

import app.layerwatch.history.Timelapses.NewFile
import app.layerwatch.protocol.ftps.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelapsesTest {
    private fun files(vararg names: String) = names.map { RemoteFile(it, 1_000, isDirectory = false, listing = "") }
    private val old = "video_2026-01-02_08-00-00.mp4"
    private val prev = "video_2026-01-03_08-00-00.mp4"
    // The printer's clock jumps: a print's file can sort before older ones.
    private val mine = "video_2026-01-01_08-00-00.mp4"

    @Test fun theOneFileAddedSinceTheStartIsThePrints() {
        assertEquals(NewFile.One(mine), Timelapses.newFile(setOf(old, prev), files(old, mine, prev)))
    }

    @Test fun nothingYetWhileThePrinterIsStillWriting() {
        assertEquals(NewFile.None, Timelapses.newFile(setOf(old, prev), files(old, prev)))
    }

    @Test fun deletionsSinceTheStartDontMatter() {
        assertEquals(NewFile.One(mine), Timelapses.newFile(setOf(old, prev), files(mine)))
    }

    @Test fun twoNewFilesAreNotLinked() {
        assertEquals(NewFile.Several, Timelapses.newFile(setOf(old), files(old, prev, mine)))
    }
}

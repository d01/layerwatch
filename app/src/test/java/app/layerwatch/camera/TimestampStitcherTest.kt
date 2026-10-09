package app.layerwatch.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampStitcherTest {
    private val frame = 33_333L

    /** Feeds [count] frames starting at [fromUs], returning the player-facing times. */
    private fun TimestampStitcher.feed(fromUs: Long, count: Int) = (0 until count).map { map(fromUs + it * frame, commit = true) }

    @Test
    fun `continuous stream passes through unchanged`() {
        val s = TimestampStitcher()
        assertEquals((0 until 5).map { 1_000_000 + it * frame }, s.feed(1_000_000, 5))
        assertEquals(0, s.discontinuities)
    }

    @Test
    fun `media3's view of the printer re-base is stitched to the next frame`() {
        // What Media3 reported: ~148.6 s, then ~46,746 s (a 1129.55 s step
        // back in RTP time, read through 32-bit wraparound).
        val s = TimestampStitcher()
        val before = s.feed(148_000_000, 20)
        val after = s.feed(46_746_362_000, 3)
        assertEquals(before.last() + frame, after.first())
        assertEquals(after.first() + frame, after[1])
        assertEquals(1, s.discontinuities)
    }

    @Test
    fun `a plain backward step is stitched too`() {
        val s = TimestampStitcher()
        val before = s.feed(1_200_000_000, 10)
        val after = s.feed(1_200_000_000 + 10 * frame - 1_129_552_000, 2)
        assertEquals(before.last() + frame, after.first())
    }

    @Test
    fun `network gaps and small reordering are not discontinuities`() {
        val s = TimestampStitcher()
        s.feed(0, 10)
        val last = 9 * frame
        assertEquals(last + 700_000, s.map(last + 700_000, commit = true)) // weak WiFi gap
        assertEquals(last + 650_000, s.map(last + 650_000, commit = true)) // B-frame order
        assertEquals(0, s.discontinuities)
    }

    @Test
    fun `peeks don't move the stitch point`() {
        val s = TimestampStitcher()
        s.feed(0, 3)
        val jumped = 900_000_000L
        val peeked = s.map(jumped, commit = false)
        assertEquals(0, s.discontinuities)
        assertEquals(peeked, s.map(jumped, commit = true))
        assertEquals(1, s.discontinuities)
    }

    @Test
    fun `buffered position stays continuous across an unread jump`() {
        val s = TimestampStitcher()
        s.feed(148_000_000, 10)
        val lastOut = 148_000_000 + 9 * frame
        assertEquals(lastOut + 800_000, s.mapAhead(lastOut + 800_000))
        // The loader has queued frames past the re-base that the decoder hasn't read yet.
        val first = s.mapAhead(46_746_362_000)
        assertEquals(lastOut + 800_000 + frame, first)
        // It keeps growing with the stream, never collapsing to "nothing buffered".
        assertEquals(first + 500_000, s.mapAhead(46_746_862_000))
        // Once the jump is read, positions map through the stitch offset again.
        s.feed(46_746_000_000, 1)
        assertEquals(lastOut + frame + 862_000, s.mapAhead(46_746_862_000))
    }

    @Test
    fun `positions map both ways after a stitch`() {
        val s = TimestampStitcher()
        s.feed(148_000_000, 10)
        val lastOut = 148_000_000 + 9 * frame
        s.feed(46_746_362_000, 1)
        val out = lastOut + frame
        assertEquals(46_746_362_000, s.toRaw(out))
        assertEquals(out, s.toOut(46_746_362_000))
    }
}

package app.layerwatch.camera

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtpTimestampSmootherTest {
    private val step = 3_000L // one frame at 30 fps on the 90 kHz clock

    private fun rtp(ts: Long, channel: Int = 0, marker: Boolean = true): ByteArray {
        val p = ByteArray(16)
        p[0] = 0x80.toByte() // version 2
        p[1] = ((if (marker) 0x80 else 0) or 96).toByte() // dynamic H.264 payload type
        for (i in 0..3) p[4 + i] = (ts shr (24 - 8 * i)).toByte()
        return frame(channel, p)
    }

    private fun frame(channel: Int, payload: ByteArray) =
        byteArrayOf('$'.code.toByte(), channel.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    private fun rtcp(channel: Int = 1): ByteArray {
        val p = ByteArray(28)
        p[0] = 0x80.toByte(); p[1] = 200.toByte() // sender report
        for (i in 16..19) p[i] = 0x7F
        return frame(channel, p)
    }

    private fun smooth(vararg parts: ByteArray, onRebase: (Int, Long) -> Unit = { _, _ -> }): ByteArray {
        val input = parts.fold(ByteArray(0)) { a, b -> a + b }
        val out = ByteArrayOutputStream()
        val s = RtpTimestampSmoother(ByteArrayInputStream(input), onRebase)
        val buf = ByteArray(7) // odd size, so reads split frames and messages
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The RTP timestamps in [bytes], in order, read the way Media3 frames the stream. */
    private fun timestamps(bytes: ByteArray): List<Long> {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        val out = mutableListOf<Long>()
        while (d.available() > 0) {
            if (d.readByte() != '$'.code.toByte()) continue
            d.readUnsignedByte()
            val p = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
            if ((p[1].toInt() and 0xFF) in 200..206) continue
            out += (4..7).fold(0L) { acc, i -> (acc shl 8) or (p[i].toLong() and 0xFF) }
        }
        return out
    }

    @Test
    fun `a continuous stream passes through byte for byte`() {
        val parts = arrayOf(rtp(1_000), rtp(4_000), rtcp(), rtp(7_000))
        assertArrayEquals(parts.fold(ByteArray(0)) { a, b -> a + b }, smooth(*parts))
    }

    @Test
    fun `a forward re-base continues one frame on`() {
        val out = smooth(rtp(1_000), rtp(4_000), rtp(1_970_000_000), rtp(1_970_003_000))
        assertEquals(listOf(1_000L, 4_000L, 7_000L, 10_000L), timestamps(out))
    }

    @Test
    fun `a backward re-base continues one frame on`() {
        // A real backward re-base: Media3 time went from ~39,849 s back to ~14,047 s.
        val out = smooth(rtp(3_586_000_000), rtp(3_586_003_000), rtp(1_264_000_000), rtp(1_264_003_000))
        assertEquals(listOf(3_586_000_000L, 3_586_003_000L, 3_586_006_000L, 3_586_009_000L), timestamps(out))
    }

    @Test
    fun `packets of one frame keep sharing its timestamp across a re-base`() {
        val out = smooth(rtp(90_000), rtp(93_000), rtp(5_000_000, marker = false), rtp(5_000_000), rtp(5_003_000))
        assertEquals(listOf(90_000L, 93_000L, 96_000L, 96_000L, 99_000L), timestamps(out))
    }

    @Test
    fun `natural 32-bit wraparound is not a re-base`() {
        var rebases = 0
        val out = smooth(rtp(0xFFFF_F000L), rtp(0x0000_0BB8L), onRebase = { _, _ -> rebases++ })
        assertEquals(listOf(0xFFFF_F000L, 0x0000_0BB8L), timestamps(out))
        assertEquals(0, rebases)
    }

    @Test
    fun `network gaps are not re-bases`() {
        // ~700 ms gaps happen on the printer's WiFi; a late frame steps forward, not a re-base.
        val out = smooth(rtp(90_000), rtp(156_000), rtp(159_000))
        assertEquals(listOf(90_000L, 156_000L, 159_000L), timestamps(out))
    }

    @Test
    fun `channels are tracked separately and RTCP is untouched`() {
        val sr = rtcp(channel = 1)
        val out = smooth(rtp(1_000, channel = 0), rtp(500_000_000, channel = 2), sr, rtp(4_000, channel = 0))
        assertEquals(listOf(1_000L, 500_000_000L, 4_000L), timestamps(out))
        assertEquals(true, out.toList().windowed(sr.size).any { it == sr.toList() })
    }

    @Test
    fun `RTSP messages pass through, including a body that starts with a dollar sign`() {
        val msg = "RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 6\r\n\r\n\$ab\r\nx".toByteArray()
        val play = "RTSP/1.0 200 OK\r\nCSeq: 4\r\nRTP-Info: url=x;seq=1;rtptime=1000\r\n\r\n".toByteArray()
        val out = smooth(msg, rtp(1_000), play, rtp(900_000_000), rtp(900_003_000))
        assertArrayEquals(msg, out.copyOfRange(0, msg.size))
        val playAt = msg.size + rtp(0).size
        assertArrayEquals(play, out.copyOfRange(playAt, playAt + play.size))
        assertEquals(listOf(1_000L, 4_000L, 7_000L), timestamps(out.copyOfRange(msg.size, out.size)))
    }

    @Test
    fun `each re-base is reported with its step`() {
        val steps = mutableListOf<Long>()
        smooth(rtp(1_000), rtp(4_000), rtp(1_000_000_000), rtp(1_000_003_000), onRebase = { _, s -> steps += s })
        assertEquals(listOf(1_000_000_000L - 4_000), steps)
    }
}

package app.layerwatch.protocol.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory
import kotlin.concurrent.thread

class JpegCameraTest {
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(n) { it.toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun header(size: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(size).putInt(0).putInt(1).putInt(0).array()

    /** A one-client camera: checks the auth, then sends [frames], optionally split awkwardly. */
    private fun camera(code: String, frames: List<ByteArray>, chunk: Int = Int.MAX_VALUE): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val auth = ByteArray(80).also { DataInputStream(s.getInputStream()).readFully(it) }
                if (!auth.contentEquals(JpegCameraStream.authPacket("bblp", code))) return@use
                val out = s.getOutputStream()
                val bytes = frames.flatMap { (header(it.size) + it).toList() }.toByteArray()
                bytes.asList().chunked(chunk).forEach { out.write(it.toByteArray()); out.flush() }
            }
        }
        return server
    }

    @Test fun authPacketLayout() {
        val p = JpegCameraStream.authPacket("bblp", "12345678")
        assertEquals(80, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x40, b.getInt(0))
        assertEquals(0x3000, b.getInt(4))
        assertEquals("bblp", String(p, 16, 4))
        assertEquals(0, p[20].toInt())
        assertEquals("12345678", String(p, 48, 8))
        assertEquals(0, p[79].toInt())
    }

    @Test fun readsFramesSplitAcrossReads() {
        val frames = listOf(jpeg(1000), jpeg(5), jpeg(70_000))
        camera("abc", frames, chunk = 7).use { server ->
            JpegCameraStream.open(SocketFactory.getDefault(), "127.0.0.1", "abc", server.localPort).use { cam ->
                frames.forEach { assertArrayEquals(it, cam.nextFrame()) }
                assertThrows(IOException::class.java) { cam.nextFrame() }
            }
        }
    }

    @Test fun wrongCodeIsAClosedStream() {
        camera("right", listOf(jpeg(10))).use { server ->
            JpegCameraStream.open(SocketFactory.getDefault(), "127.0.0.1", "wrong", server.localPort).use { cam ->
                val e = assertThrows(IOException::class.java) { cam.nextFrame() }
                assert("access code" in e.message!!)
            }
        }
    }
}

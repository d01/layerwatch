package app.layerwatch.protocol.camera

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

/**
 * The chamber camera of the P1 and A1 series: TLS on port 6000, a JPEG every second or
 * two. Protocol as documented by OpenBambuAPI (video.md) and used by ha-bambulab:
 *
 * - The client sends one 80-byte auth packet (little-endian): u32 0x40, u32 0x3000, u32 0,
 *   u32 0, then the username ("bblp") and the access code, each null-padded to 32 bytes.
 * - The printer then sends frames, each a 16-byte header (u32 payload size, u32 0, u32 1,
 *   u32 0) followed by that many bytes of JPEG. There's no reply to the auth; a wrong
 *   access code just closes the connection.
 */
class JpegCameraStream private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))

    /** Blocks for the next frame. Throws [IOException] when the stream ends or is corrupt. */
    fun nextFrame(): ByteArray {
        val header = ByteArray(HEADER)
        try {
            input.readFully(header)
        } catch (e: EOFException) {
            throw IOException("Camera closed the stream (wrong access code, or another viewer took over)", e)
        }
        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        if (size !in MIN_FRAME..MAX_FRAME) throw IOException("Implausible frame size $size")
        val jpeg = ByteArray(size)
        input.readFully(jpeg)
        if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw IOException("Frame is not a JPEG")
        return jpeg
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 6000
        private const val HEADER = 16
        private const val MIN_FRAME = 4
        private const val MAX_FRAME = 8 * 1024 * 1024

        /**
         * Connects and authenticates. [socketFactory] must produce TLS sockets that trust the
         * printer (the app pins its serial); tests pass a plain factory.
         */
        fun open(
            socketFactory: SocketFactory,
            host: String,
            accessCode: String,
            port: Int = PORT,
            timeoutMs: Int = 10_000,
            username: String = "bblp",
        ): JpegCameraStream {
            val socket = socketFactory.createSocket()
            try {
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.getOutputStream().apply {
                    write(authPacket(username, accessCode))
                    flush()
                }
                return JpegCameraStream(socket)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        fun authPacket(username: String, accessCode: String): ByteArray =
            ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x40)
                putInt(0x3000)
                putInt(0)
                putInt(0)
                put(field(username))
                put(field(accessCode))
            }.array()

        private fun field(s: String): ByteArray {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            require(bytes.size <= 32) { "Field longer than 32 bytes" }
            return bytes.copyOf(32)
        }
    }
}

package app.layerwatch.camera

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Keeps RTP timestamps continuous in an RTSP-over-TCP byte stream, before Media3 sees them.
 *
 * Every client that starts the printer's shared stream re-bases its RTP timestamps for
 * all viewers (see [TimestampStitcher]). Stitched after the fact, a backward jump leaves
 * the sample queue with later-stamped samples at its head, which freezes the buffered
 * position (the queue's largest timestamp never comes back down) and, unhandled, leaks the
 * stream into the heap. Rewriting the timestamps as they arrive means Media3 never sees the
 * jump: the first packet after it is placed one frame after the last one before it.
 *
 * Mirrors Media3's RtspMessageChannel framing: at a message boundary `$` starts an
 * interleaved frame (channel, 16-bit length, payload); anything else is an RTSP message
 * (lines up to a blank one, then a Content-Length body). Only RTP packets are touched.
 */
class RtpTimestampSmoother(
    upstream: InputStream,
    private val onRebase: (channel: Int, stepTicks: Long) -> Unit = { _, _ -> },
) : InputStream() {
    private val input = DataInputStream(BufferedInputStream(upstream))
    private var unit = ByteArray(0)
    private var pos = 0
    private val clocks = HashMap<Int, Clock>()

    private class Clock(var lastIn: Long, var lastOut: Long, var offset: Long = 0, var step: Long = DEFAULT_STEP)

    override fun read(): Int = if (fill()) unit[pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, unit.size - pos)
        System.arraycopy(unit, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = unit.size - pos

    override fun close() = input.close()

    /** Reads the next whole frame or message into [unit]; false at end of stream. */
    private fun fill(): Boolean {
        while (pos >= unit.size) {
            val first = input.read()
            if (first < 0) return false
            unit = if (first == '$'.code) readInterleaved() else readMessage(first)
            pos = 0
        }
        return true
    }

    private fun readInterleaved(): ByteArray {
        val channel = input.readUnsignedByte()
        val size = input.readUnsignedShort()
        val out = ByteArray(4 + size)
        out[0] = '$'.code.toByte()
        out[1] = channel.toByte()
        out[2] = (size shr 8).toByte()
        out[3] = size.toByte()
        input.readFully(out, 4, size)
        smooth(channel, out, 4, size)
        return out
    }

    /** One RTSP message, as Media3 reads it: lines until a start line, headers, body. */
    private fun readMessage(first: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var started = false
        var bodyLength = 0
        var line = readLine(first, out)
        while (true) {
            if (!started) {
                started = isStartLine(line)
            } else {
                CONTENT_LENGTH.find(line)?.let { bodyLength = it.groupValues[1].toInt() }
                if (line.isEmpty()) break
            }
            line = readLine(input.readUnsignedByte(), out)
        }
        if (bodyLength > 0) {
            val body = ByteArray(bodyLength)
            input.readFully(body)
            out.write(body)
        }
        return out.toByteArray()
    }

    /** Copies one CRLF-terminated line into [out]; returns it without the CRLF. */
    private fun readLine(first: Int, out: ByteArrayOutputStream): String {
        val line = ByteArrayOutputStream()
        var prev = first
        line.write(first)
        while (true) {
            val b = input.readUnsignedByte()
            line.write(b)
            if (prev == '\r'.code && b == '\n'.code) break
            prev = b
        }
        val bytes = line.toByteArray()
        out.write(bytes)
        return String(bytes, 0, bytes.size - 2, Charsets.UTF_8)
    }

    private fun smooth(channel: Int, p: ByteArray, off: Int, len: Int) {
        if (len < 12 || (p[off].toInt() shr 6) and 3 != 2) return
        if ((p[off + 1].toInt() and 0xFF) in RTCP_TYPES) return
        val ts = u32(p, off + 4)
        val c = clocks[channel] ?: Clock(lastIn = ts, lastOut = ts).also { clocks[channel] = it }
        val delta = (ts - c.lastIn).toInt().toLong() // signed 32-bit step, wrap-safe
        if (delta > MAX_FORWARD_TICKS || delta < -MAX_BACKWARD_TICKS) {
            c.offset = (c.lastOut + c.step - ts) and MASK
            onRebase(channel, delta)
        } else if (delta > 0) {
            c.step = delta
        }
        c.lastIn = ts
        c.lastOut = (ts + c.offset) and MASK
        putU32(p, off + 4, c.lastOut)
    }

    companion object {
        // The printer's camera is H.264 only, on the 90 kHz RTP video clock.
        private const val CLOCK_HZ = 90_000L
        private const val MAX_FORWARD_TICKS = 2 * CLOCK_HZ
        private const val MAX_BACKWARD_TICKS = 1 * CLOCK_HZ
        private const val DEFAULT_STEP = CLOCK_HZ / 30
        private const val MASK = 0xFFFF_FFFFL
        /** RTCP packet types share the second byte with RTP's marker bit + payload type. */
        private val RTCP_TYPES = 200..206
        private val CONTENT_LENGTH = Regex("^content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val REQUEST_LINE = Regex("^\\S+ \\S+ RTSP/1\\.0$")

        private fun isStartLine(line: String) = line.startsWith("RTSP/1.0") || REQUEST_LINE.matches(line)

        private fun u32(b: ByteArray, i: Int): Long =
            ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

        private fun putU32(b: ByteArray, i: Int, v: Long) {
            b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte(); b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte()
        }

        /** Wraps [delegate] so each socket's input runs through a smoother. */
        fun socketFactory(delegate: SocketFactory, onRebase: (channel: Int, stepTicks: Long) -> Unit): SocketFactory =
            object : SocketFactory() {
                override fun createSocket(): Socket = SmoothingSocket(delegate.createSocket(), onRebase)
                override fun createSocket(host: String, port: Int): Socket = SmoothingSocket(delegate.createSocket(host, port), onRebase)
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                    SmoothingSocket(delegate.createSocket(host, port, localHost, localPort), onRebase)
                override fun createSocket(host: InetAddress, port: Int): Socket = SmoothingSocket(delegate.createSocket(host, port), onRebase)
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                    SmoothingSocket(delegate.createSocket(address, port, localAddress, localPort), onRebase)
            }
    }
}

/** Delegates to [inner]; only its input stream is wrapped. */
private class SmoothingSocket(private val inner: Socket, onRebase: (Int, Long) -> Unit) : Socket() {
    private val smoothed by lazy { RtpTimestampSmoother(inner.getInputStream(), onRebase) }

    override fun getInputStream(): InputStream = smoothed
    override fun getOutputStream(): OutputStream = inner.getOutputStream()
    override fun connect(endpoint: SocketAddress?) = inner.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = inner.connect(endpoint, timeout)
    override fun close() = inner.close()
    override fun isClosed() = inner.isClosed
    override fun isConnected() = inner.isConnected
    override fun isInputShutdown() = inner.isInputShutdown
    override fun isOutputShutdown() = inner.isOutputShutdown
    override fun shutdownInput() = inner.shutdownInput()
    override fun shutdownOutput() = inner.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
    override fun getSoTimeout() = inner.soTimeout
    override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
    override fun getTcpNoDelay() = inner.tcpNoDelay
    override fun getInetAddress(): InetAddress? = inner.inetAddress
    override fun getLocalAddress(): InetAddress? = inner.localAddress
    override fun getPort() = inner.port
    override fun getLocalPort() = inner.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress
}

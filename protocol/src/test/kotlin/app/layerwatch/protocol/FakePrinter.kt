package app.layerwatch.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * An in-process MQTT broker that behaves like the X1C closely enough to test
 * [PrinterConnection]: answers CONNECT/SUBSCRIBE/PINGREQ, and replies to a pushall with
 * [reports]. Can reject credentials, drop clients, or go silent like a dead VPN tunnel.
 */
class FakePrinter(
    private val serial: String,
    private val accessCode: String,
    private val reports: List<String>,
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    val connects = AtomicInteger()
    val pushalls = AtomicInteger()
    val pings = AtomicInteger()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var silent = false
    private val clients = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "fake-printer") {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                clients += c
                thread(isDaemon = true) { runCatching { serve(c) } ; runCatching { c.close() } }
            }
        }
    }

    /** Abruptly drops every connected client (printer reboot, WiFi blip). */
    fun dropClients() = clients.forEach { runCatching { it.close() } }.also { clients.clear() }

    override fun close() {
        dropClients()
        server.close()
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        while (true) {
            val header = input.read()
            if (header < 0) throw EOFException()
            val body = ByteArray(readLength(input)).also { input.readFully(it) }
            if (silent) continue // swallow everything, answer nothing
            when (header ushr 4) {
                1 -> { // CONNECT: password is the last string in the payload
                    connects.incrementAndGet()
                    val ok = String(body).endsWith(accessCode)
                    out.packet(0x20, byteArrayOf(0, if (ok) 0 else 5))
                    if (!ok) return
                }
                8 -> out.packet(0x90, byteArrayOf(body[0], body[1], 0)) // SUBACK
                3 -> { // PUBLISH from client = a command
                    val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val topic = String(body, 2, topicLen)
                    val payload = String(body, 2 + topicLen, body.size - 2 - topicLen)
                    check(topic == "device/$serial/request") { "unexpected topic $topic" }
                    requests += payload
                    if ("pushall" in payload) {
                        pushalls.incrementAndGet()
                        reports.forEach { out.publish("device/$serial/report", it) }
                    }
                }
                12 -> { pings.incrementAndGet(); out.packet(0xD0, ByteArray(0)) } // PINGRESP
                14 -> return // DISCONNECT
            }
        }
    }

    fun publishToAll(report: String) = clients.forEach { c ->
        runCatching { c.getOutputStream().publish("device/$serial/report", report) }
    }

    private fun OutputStream.publish(topic: String, payload: String) {
        val t = topic.toByteArray()
        val p = payload.toByteArray()
        packet(0x30, byteArrayOf((t.size ushr 8).toByte(), t.size.toByte()) + t + p)
    }

    private fun OutputStream.packet(header: Int, body: ByteArray) = synchronized(this) {
        write(header)
        var len = body.size
        do {
            var d = len % 128
            len /= 128
            if (len > 0) d = d or 0x80
            write(d)
        } while (len > 0)
        write(body)
        flush()
    }

    private fun readLength(input: DataInputStream): Int {
        var value = 0
        var mul = 1
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            value += (b and 0x7F) * mul
            if (b and 0x80 == 0) return value
            mul *= 128
        }
    }
}

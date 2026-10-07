package app.layerwatch.protocol.mqtt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket

/** An inbound MQTT control packet, reduced to what the printer sends us. */
sealed interface MqttPacket {
    data class ConnAck(val returnCode: Int) : MqttPacket
    data class Publish(val topic: String, val payload: ByteArray) : MqttPacket {
        override fun equals(other: Any?) =
            other is Publish && topic == other.topic && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * topic.hashCode() + payload.contentHashCode()
    }
    data class SubAck(val packetId: Int, val granted: List<Int>) : MqttPacket
    data object PingResp : MqttPacket
    data class Other(val type: Int) : MqttPacket
}

class MqttException(message: String) : IOException(message)

/** CONNACK refused for bad credentials (return code 4 or 5): retrying won't help. */
class MqttAuthException(returnCode: Int) : IOException("MQTT connection refused: not authorized ($returnCode)")

/**
 * A minimal MQTT 3.1.1 client session: CONNECT, SUBSCRIBE and PUBLISH at QoS 0, PINGREQ.
 * That is all the Bambu printer protocol needs, and owning it means owning the timeouts
 * that decide how fast a dead VPN tunnel is noticed.
 *
 * Blocking I/O; [readPacket] honours the socket read timeout passed to [open]. Writes are
 * synchronized so a pinger and a command sender can share the session.
 */
class MqttSession private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var nextPacketId = 1

    fun subscribe(topic: String): Int {
        val id = nextPacketId++
        val body = ByteArrayOutputStream().apply {
            writeShort(id)
            writeString(topic)
            write(0) // requested QoS 0
        }
        send(0x82, body.toByteArray())
        return id
    }

    fun publish(topic: String, payload: ByteArray) {
        val body = ByteArrayOutputStream().apply {
            writeString(topic)
            write(payload)
        }
        send(0x30, body.toByteArray())
    }

    fun ping() = send(0xC0, ByteArray(0))

    /** Blocks for the next packet; throws SocketTimeoutException after the read timeout. */
    fun readPacket(): MqttPacket {
        val header = input.read()
        if (header < 0) throw EOFException("Printer closed the connection")
        val length = readRemainingLength()
        val body = ByteArray(length).also { input.readFully(it) }
        return when (header ushr 4) {
            2 -> MqttPacket.ConnAck(body.getOrElse(1) { 0 }.toInt() and 0xFF)
            3 -> parsePublish(header, body)
            9 -> MqttPacket.SubAck(
                packetId = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF),
                granted = body.drop(2).map { it.toInt() and 0xFF },
            )
            13 -> MqttPacket.PingResp
            else -> MqttPacket.Other(header ushr 4)
        }
    }

    override fun close() {
        runCatching { send(0xE0, ByteArray(0)) }
        runCatching { socket.close() }
    }

    private fun parsePublish(header: Int, body: ByteArray): MqttPacket.Publish {
        val qos = (header ushr 1) and 0x3
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val topic = String(body, 2, topicLen, Charsets.UTF_8)
        var offset = 2 + topicLen
        if (qos > 0) {
            val id = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
            offset += 2
            if (qos == 1) send(0x40, byteArrayOf((id ushr 8).toByte(), id.toByte())) // PUBACK
        }
        return MqttPacket.Publish(topic, body.copyOfRange(offset, body.size))
    }

    private fun readRemainingLength(): Int {
        var value = 0
        var multiplier = 1
        repeat(4) {
            val b = input.read()
            if (b < 0) throw EOFException("Truncated MQTT header")
            value += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) return value
            multiplier *= 128
        }
        throw MqttException("Malformed remaining length")
    }

    @Synchronized
    private fun send(header: Int, body: ByteArray) {
        output.write(header)
        var len = body.size
        do {
            var digit = len % 128
            len /= 128
            if (len > 0) digit = digit or 0x80
            output.write(digit)
        } while (len > 0)
        output.write(body)
        output.flush()
    }

    companion object {
        /**
         * Opens the socket, sends CONNECT and waits for CONNACK.
         * @throws MqttAuthException if the printer rejects the access code.
         */
        fun open(
            socketFactory: SocketFactory,
            host: String,
            port: Int,
            clientId: String,
            username: String,
            password: String,
            keepAliveSec: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): MqttSession {
            val socket = socketFactory.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = connectTimeoutMs
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                (socket as? SSLSocket)?.startHandshake()
                val session = MqttSession(socket)
                session.send(0x10, connectBody(clientId, username, password, keepAliveSec))
                when (val ack = session.readPacket()) {
                    is MqttPacket.ConnAck -> when (ack.returnCode) {
                        0 -> Unit
                        4, 5 -> throw MqttAuthException(ack.returnCode)
                        else -> throw MqttException("MQTT connection refused (${ack.returnCode})")
                    }
                    else -> throw MqttException("Expected CONNACK, got $ack")
                }
                socket.soTimeout = readTimeoutMs
                return session
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        private fun connectBody(clientId: String, username: String, password: String, keepAliveSec: Int) =
            ByteArrayOutputStream().apply {
                writeString("MQTT")
                write(4) // protocol level 3.1.1
                write(0xC2) // username + password + clean session
                writeShort(keepAliveSec)
                writeString(clientId)
                writeString(username)
                writeString(password)
            }.toByteArray()

        private fun ByteArrayOutputStream.writeShort(v: Int) {
            write((v ushr 8) and 0xFF)
            write(v and 0xFF)
        }

        private fun ByteArrayOutputStream.writeString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeShort(bytes.size)
            write(bytes)
        }
    }
}

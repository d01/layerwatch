package app.layerwatch.protocol.ftps

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class FtpsException(val code: Int, message: String) : IOException(message)

/**
 * A real, normally connected socket that reports [reportedPort] as its remote port, so a
 * TLS layer over it keys its session cache like the control connection's.
 */
private class ControlPortSocket(private val reportedPort: Int) : Socket() {
    override fun getPort(): Int = if (isConnected) reportedPort else super.getPort()
}

/** One entry of a `LIST` reply. */
data class RemoteFile(val name: String, val size: Long, val isDirectory: Boolean, val listing: String)

/**
 * Minimal implicit-TLS FTP client for the printer's storage (port 990): login, LIST,
 * SIZE, RETR. Passive mode, binary, private data channel.
 *
 * The printer's server (vsftpd-style) rejects a data connection unless it *resumes the
 * control connection's TLS session*. TLS stacks (JDK and Android's Conscrypt alike)
 * look sessions up by host and the underlying socket's remote port, and the data port
 * differs, so the data connection is a [ControlPortSocket]: a normal connected socket
 * that reports the control port. The lookup then finds and resumes the control session.
 * Use the same [socketFactory] instance for the whole session.
 */
class FtpsClient private constructor(
    private val socketFactory: SSLSocketFactory,
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int,
    private val control: SSLSocket,
    private val trace: (String) -> Unit,
) : Closeable {
    private val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
    private val writer: OutputStream = control.getOutputStream()

    fun list(path: String): List<RemoteFile> {
        val text = dataCommand("LIST $path") { it.readBytes() }.toString(Charsets.UTF_8)
        return text.lineSequence().mapNotNull(::parseListLine).toList()
    }

    fun size(path: String): Long? = runCatching { command("SIZE $path", 213).substringAfter(' ').trim().toLong() }.getOrNull()

    /**
     * Streams [path] into [out]; returns bytes copied. [onProgress] gets running totals
     * (including [offset]). A non-zero [offset] resumes a partial download via REST.
     */
    fun retrieve(path: String, out: OutputStream, offset: Long = 0, onProgress: (Long) -> Unit = {}): Long =
        dataCommand("RETR $path", restAt = offset) { input ->
            val buf = ByteArray(64 * 1024)
            var total = offset
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                onProgress(total)
            }
            total
        }

    override fun close() {
        runCatching { send("QUIT") }
        runCatching { control.close() }
    }

    private fun <T> dataCommand(cmd: String, restAt: Long = 0, consume: (InputStream) -> T): T {
        val (dataHost, dataPort) = passive()
        if (restAt > 0) command("REST $restAt", 350)
        trace("data: connecting to $dataHost:$dataPort for '$cmd'")
        val raw = ControlPortSocket(port).apply {
            soTimeout = timeoutMs
            connect(InetSocketAddress(dataHost, dataPort), timeoutMs)
        }
        trace("data: connected")
        // Session reuse: present the *control* endpoint so the TLS cache resumes it.
        val data = socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            send(cmd)
            expect(reply(), 125, 150)
            trace("data: 150 received, handshaking")
            data.startHandshake()
            trace("data: handshake done, resumed=${data.session.id.contentEquals(control.session.id)}")
            val result = data.getInputStream().use(consume)
            trace("data: transfer done")
            data.close()
            expect(reply(), 226, 250)
            return result
        } catch (e: Exception) {
            runCatching { data.close() }
            throw e
        }
    }

    private fun passive(): Pair<String, Int> {
        val r = command("PASV", 227)
        val nums = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(r)?.groupValues?.drop(1)?.map(String::toInt)
            ?: throw FtpsException(227, "Unparseable PASV reply: $r")
        // Use the control host: the printer may advertise an address that isn't reachable
        // from the phone (e.g. across a VPN or NAT).
        return host to (nums[4] * 256 + nums[5])
    }

    private fun command(cmd: String, vararg ok: Int): String {
        send(cmd)
        val (code, text) = reply()
        if (ok.isNotEmpty() && code !in ok) throw FtpsException(code, "$cmd -> $code $text")
        return text
    }

    private fun send(cmd: String) {
        writer.write("$cmd\r\n".toByteArray(Charsets.UTF_8))
        writer.flush()
    }

    private fun expect(r: Pair<Int, String>, vararg ok: Int) {
        if (r.first !in ok) throw FtpsException(r.first, "${r.first} ${r.second}")
    }

    /** Reads one (possibly multi-line) reply. */
    private fun reply(): Pair<Int, String> {
        val first = reader.readLine() ?: throw IOException("FTP connection closed")
        val code = first.take(3).toIntOrNull() ?: throw IOException("Bad FTP reply: $first")
        if (first.getOrNull(3) == '-') {
            while (true) {
                val line = reader.readLine() ?: throw IOException("FTP connection closed")
                if (line.startsWith("$code ")) break
            }
        }
        return code to first.drop(4)
    }

    companion object {
        fun connect(
            socketFactory: SSLSocketFactory,
            host: String,
            username: String,
            password: String,
            port: Int = 990,
            timeoutMs: Int = 15_000,
            trace: (String) -> Unit = {},
        ): FtpsClient {
            trace("control: connecting to $host:$port")
            val raw = Socket().apply {
                soTimeout = timeoutMs
                connect(InetSocketAddress(host, port), timeoutMs)
            }
            val control = socketFactory.createSocket(raw, host, port, true) as SSLSocket
            control.startHandshake()
            trace("control: TLS up (${control.session.protocol})")
            val client = FtpsClient(socketFactory, host, port, timeoutMs, control, trace)
            try {
                client.expect(client.reply(), 220)
                client.command("USER $username", 331)
                client.command("PASS $password", 230)
                client.command("PBSZ 0", 200)
                client.command("PROT P", 200)
                client.command("TYPE I", 200)
                trace("control: logged in")
            } catch (e: Exception) {
                client.close()
                throw e
            }
            return client
        }

        /** Parses a Unix-style `ls -l` line; null for anything else. */
        internal fun parseListLine(line: String): RemoteFile? {
            val parts = line.trim().split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) return null
            val size = parts[4].toLongOrNull() ?: return null
            return RemoteFile(name = parts[8], size = size, isDirectory = parts[0].startsWith("d"), listing = line)
        }
    }
}

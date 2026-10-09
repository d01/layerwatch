package app.layerwatch.protocol

import app.layerwatch.protocol.mqtt.MqttAuthException
import app.layerwatch.protocol.mqtt.MqttPacket
import app.layerwatch.protocol.mqtt.MqttSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class PrinterEndpoint(val host: String, val serial: String, val accessCode: String)

sealed interface LinkState {
    data object Stopped : LinkState
    data class Connecting(val attempt: Int) : LinkState
    data class Online(val sinceMs: Long) : LinkState
    /** Disconnected; the next attempt starts at [retryAtMs] unless [PrinterConnection.reconnectNow] is called. */
    data class Waiting(val reason: String, val retryAtMs: Long, val attempt: Int) : LinkState
    /** The printer rejected the access code. No automatic retries until [PrinterConnection.reconnectNow]. */
    data class AuthFailed(val message: String) : LinkState
}

/**
 * Keeps an MQTT session to one printer alive and folds its reports into [status].
 *
 * Robustness rules, because printers often sit on weak WiFi and are reached over a VPN:
 * - A session with no inbound packet for [Timing.deadAfterMs] is declared dead, even if
 *   TCP hasn't noticed: the socket read times out every [Timing.pingIntervalMs], which
 *   sends a PINGREQ, and the PINGRESP counts as traffic.
 * - Reconnects back off exponentially with jitter, capped at [Timing.backoffMaxMs], and
 *   reset once a report actually arrives (not merely on TCP connect).
 * - [reconnectNow] skips the wait, for when the app learns the network changed.
 * - The last known [status] is kept while offline; [lastReportAtMs] says how old it is.
 */
class PrinterConnection(
    private val endpoint: PrinterEndpoint,
    private val socketFactory: SocketFactory,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val port: Int = 8883,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PrinterLink {
    data class Timing(
        val connectTimeoutMs: Int = 10_000,
        val pingIntervalMs: Int = 10_000,
        val deadAfterMs: Long = 25_000,
        val keepAliveSec: Int = 60,
        val backoffBaseMs: Long = 1_000,
        val backoffMaxMs: Long = 30_000,
    )

    private val _link = MutableStateFlow<LinkState>(LinkState.Stopped)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _status = MutableStateFlow<PrinterStatus?>(null)
    override val status: StateFlow<PrinterStatus?> = _status.asStateFlow()

    private val _lastReportAtMs = MutableStateFlow<Long?>(null)
    override val lastReportAtMs: StateFlow<Long?> = _lastReportAtMs.asStateFlow()

    /** The merged raw report, for fields not yet modelled in [PrinterStatus]. */
    val merger = ReportMerger()

    private val reportTopic = "device/${endpoint.serial}/report"
    private val requestTopic = "device/${endpoint.serial}/request"
    private val sequence = AtomicLong(1)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val clientId = "layerwatch-" + Random.nextInt(0, Int.MAX_VALUE).toString(36)

    @Volatile private var session: MqttSession? = null
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch(ioDispatcher) { superviseLoop() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        session?.close()
        _link.value = LinkState.Stopped
    }

    /** Drop the current session (if any) and connect immediately, resetting backoff. */
    override fun reconnectNow() {
        kick.trySend(Unit)
        session?.close()
    }

    /** Ask the printer for a full state dump. */
    fun requestFullStatus(): Boolean = send(PrinterCommands.pushAll(nextSequence()))

    override fun setChamberLight(on: Boolean): Boolean = send(PrinterCommands.chamberLight(nextSequence(), on))
    override fun pause(): Boolean = send(PrinterCommands.pause(nextSequence()))
    override fun resume(): Boolean = send(PrinterCommands.resume(nextSequence()))
    override fun stopPrint(): Boolean = send(PrinterCommands.stop(nextSequence()))
    override fun setSpeed(level: SpeedLevel): Boolean = send(PrinterCommands.speed(nextSequence(), level))

    private fun nextSequence() = sequence.getAndIncrement()

    /** Publishes a raw command to the printer. Returns false if not currently online. */
    fun send(json: String): Boolean {
        val s = session ?: return false
        return runCatching { s.publish(requestTopic, json.toByteArray()) }.isSuccess
    }

    private suspend fun superviseLoop() {
        var attempt = 0
        while (coroutineContext.isActive) {
            _link.value = LinkState.Connecting(attempt)
            var reason: String
            try {
                val s = runInterruptible {
                    MqttSession.open(
                        socketFactory, endpoint.host, port, clientId,
                        username = "bblp", password = endpoint.accessCode,
                        keepAliveSec = timing.keepAliveSec,
                        connectTimeoutMs = timing.connectTimeoutMs,
                        readTimeoutMs = timing.pingIntervalMs,
                    )
                }
                session = s
                s.subscribe(reportTopic)
                requestFullStatus()
                _link.value = LinkState.Online(clock())
                runInterruptible { readLoop(s) { attempt = 0 } }
                reason = "Connection ended"
            } catch (e: CancellationException) {
                throw e
            } catch (e: MqttAuthException) {
                _link.value = LinkState.AuthFailed(e.message ?: "Access code rejected")
                closeSession()
                kick.receive() // wait for reconnectNow(), e.g. after the user fixes the code
                attempt = 0
                continue
            } catch (e: Exception) {
                reason = e.message ?: e.javaClass.simpleName
            } finally {
                closeSession()
            }

            val delayMs = backoff(attempt++)
            _link.value = LinkState.Waiting(reason, clock() + delayMs, attempt)
            if (withTimeoutOrNull(delayMs) { kick.receive() } != null) attempt = 0
        }
    }

    /** Runs until the session dies. [onReport] fires after each accepted status report. */
    private fun readLoop(s: MqttSession, onReport: () -> Unit) {
        var lastRx = clock()
        while (true) {
            val packet = try {
                s.readPacket()
            } catch (_: SocketTimeoutException) {
                val silent = clock() - lastRx
                if (silent >= timing.deadAfterMs) throw IOException("No response from printer for ${silent / 1000}s")
                s.ping()
                continue
            }
            lastRx = clock()
            if (packet is MqttPacket.Publish && packet.topic == reportTopic) {
                if (merger.apply(packet.payload.decodeToString())) {
                    _status.value = PrinterStatus.from(merger.state)
                }
                _lastReportAtMs.value = lastRx
                onReport()
            }
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun backoff(attempt: Int): Long {
        val exp = timing.backoffBaseMs shl attempt.coerceAtMost(10)
        val capped = exp.coerceAtMost(timing.backoffMaxMs)
        return capped / 2 + Random.nextLong(capped / 2 + 1) // "equal jitter"
    }
}

package app.bambumon.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.SocketFactory

/** Runs [PrinterConnection] against [FakePrinter] over real loopback sockets. */
class PrinterConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val printer = FakePrinter(Fixtures.SERIAL, "secret", Fixtures.captureReports)
    private val fast = PrinterConnection.Timing(
        connectTimeoutMs = 2_000,
        pingIntervalMs = 200,
        deadAfterMs = 800,
        backoffBaseMs = 50,
        backoffMaxMs = 200,
    )

    private fun connection(code: String = "secret") = PrinterConnection(
        PrinterEndpoint("127.0.0.1", Fixtures.SERIAL, code),
        SocketFactory.getDefault(), scope, fast, port = printer.port,
    )

    @After fun tearDown() {
        scope.cancel()
        printer.close()
    }

    @Test fun connectsAndParsesStatus() = runBlocking {
        val c = connection()
        c.start()
        val status = withTimeout(5_000) { c.status.first { it != null } }!!
        assertEquals(JobState.Running, status.jobState)
        assertTrue(c.link.value is LinkState.Online)
        assertEquals(1, printer.pushalls.get())
        c.stop()
    }

    @Test fun reconnectsAndRefreshesAfterDrop() = runBlocking {
        val c = connection()
        c.start()
        withTimeout(5_000) { c.status.first { it != null } }
        printer.dropClients()
        withTimeout(5_000) { c.link.first { it is LinkState.Waiting } }
        withTimeout(5_000) { c.link.first { it is LinkState.Online } }
        withTimeout(5_000) { while (printer.pushalls.get() < 2) kotlinx.coroutines.delay(20) }
        assertEquals(2, printer.connects.get())
        // Last known status survives the outage.
        assertEquals(JobState.Running, c.status.value!!.jobState)
        c.stop()
    }

    @Test fun silentPrinterIsDeclaredDead() = runBlocking {
        val c = connection()
        c.start()
        withTimeout(5_000) { c.status.first { it != null } }
        printer.silent = true // like a VPN tunnel that stops passing traffic
        val waiting = withTimeout(5_000) { c.link.first { it is LinkState.Waiting } } as LinkState.Waiting
        assertTrue(waiting.reason, waiting.reason.startsWith("No response from printer"))
        printer.silent = false
        withTimeout(5_000) { c.link.first { it is LinkState.Online } }
        c.stop()
    }

    @Test fun wrongAccessCodeStopsRetrying() = runBlocking {
        val c = connection(code = "wrong")
        c.start()
        withTimeout(5_000) { c.link.first { it is LinkState.AuthFailed } }
        Thread.sleep(500)
        assertEquals("no retry storm on bad credentials", 1, printer.connects.get())
        c.stop()
    }

    @Test fun reconnectNowSkipsBackoff() = runBlocking {
        val slow = PrinterConnection(
            PrinterEndpoint("127.0.0.1", Fixtures.SERIAL, "secret"),
            SocketFactory.getDefault(), scope,
            fast.copy(backoffBaseMs = 60_000, backoffMaxMs = 60_000), port = printer.port,
        )
        slow.start()
        withTimeout(5_000) { slow.status.first { it != null } }
        printer.dropClients()
        withTimeout(5_000) { slow.link.first { it is LinkState.Waiting } }
        slow.reconnectNow()
        withTimeout(2_000) { slow.link.first { it is LinkState.Online } }
        slow.stop()
    }
}

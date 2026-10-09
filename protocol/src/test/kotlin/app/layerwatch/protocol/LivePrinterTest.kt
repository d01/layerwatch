package app.layerwatch.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Talks to a real printer. Skipped unless BAMBU_HOST, BAMBU_SERIAL and BAMBU_CODE are set:
 *   BAMBU_HOST=… BAMBU_SERIAL=… BAMBU_CODE=… ./gradlew :protocol:test --tests '*LivePrinterTest*'
 */
class LivePrinterTest {
    @Test fun receivesRealStatus() = runBlocking {
        val host = System.getenv("BAMBU_HOST")
        val serial = System.getenv("BAMBU_SERIAL")
        val code = System.getenv("BAMBU_CODE")
        assumeNotNull(host, serial, code)

        val cas = File("../app/src/main/res/raw/bbl_ca.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); cas.forEachIndexed { i, c -> setCertificateEntry("ca$i", c) } })
        }
        val tls = SSLContext.getInstance("TLSv1.2").apply { init(null, trust.trustManagers, null) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val c = PrinterConnection(PrinterEndpoint(host, serial, code), tls.socketFactory, scope)
        c.start()
        val s = withTimeout(15_000) { c.status.first { it != null } }!!
        println("LIVE: $s")
        c.stop()
        scope.cancel()
    }
}

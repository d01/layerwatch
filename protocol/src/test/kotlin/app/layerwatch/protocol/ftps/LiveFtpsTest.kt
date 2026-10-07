package app.layerwatch.protocol.ftps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Talks to a real printer's storage. Skipped unless BAMBU_HOST and BAMBU_CODE are set.
 * Read-only: lists the root and downloads the smallest job file.
 */
class LiveFtpsTest {
    @Test fun listsAndDownloads() {
        val host = System.getenv("BAMBU_HOST")
        val code = System.getenv("BAMBU_CODE")
        assumeNotNull(host, code)
        val ca = File("../app/src/main/res/raw/bbl_ca.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", ca) })
        }
        val factory = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory

        FtpsClient.connect(factory, host, "bblp", code).use { ftp ->
            val jobs = ftp.list("/").filter { it.name.endsWith(".gcode.3mf") }
            println("LIVE: ${jobs.size} job files")
            assertTrue(jobs.isNotEmpty())
            val smallest = jobs.minBy { it.size }
            val out = ByteArrayOutputStream()
            val n = ftp.retrieve("/${smallest.name}", out)
            println("LIVE: downloaded ${smallest.name} ($n bytes)")
            assertEquals(smallest.size, n)
            assertEquals(smallest.size, ftp.size("/${smallest.name}"))
            // Second transfer on the same session, as the app will do.
            assertTrue(ftp.list("/timelapse").isNotEmpty())
        }
    }
}

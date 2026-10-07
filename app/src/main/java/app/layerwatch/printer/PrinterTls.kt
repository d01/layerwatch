package app.layerwatch.printer

import android.content.Context
import app.layerwatch.R
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for the printer's MQTT (8883), RTSPS (322) and FTPS (990) ports.
 *
 * All three present the same leaf certificate: CN = printer serial, issued by Bambu's
 * "BBL CA". A connection is trusted only if the chain validates against that bundled CA
 * *and* the leaf CN equals the configured serial, which rejects any other Bambu printer.
 * Hostname verification doesn't apply: the printer is addressed by IP and its cert names
 * the serial, not a host.
 */
class PrinterTls(context: Context) {

    private val caTrust: X509TrustManager = run {
        val ca = context.resources.openRawResource(R.raw.bbl_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("bbl-ca", ca)
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    /** Socket factory that only completes handshakes with the printer whose serial is [serial]. */
    fun socketFactory(serial: String): SSLSocketFactory = sslContext(SerialPinnedTrust(serial)).socketFactory

    /**
     * Connects to [host]:[port], validates the chain against the BBL CA, and returns the
     * serial from the leaf CN. Used during setup, before the serial is known.
     */
    fun probeSerial(host: String, port: Int = MQTT_PORT, timeoutMs: Int = 5_000): String {
        val trust = SerialPinnedTrust(expectedSerial = null)
        val socket = sslContext(trust).socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = timeoutMs
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.startHandshake()
        }
        return checkNotNull(trust.seenSerial) { "Printer certificate has no CN" }
    }

    private fun sslContext(trust: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }

    private inner class SerialPinnedTrust(private val expectedSerial: String?) : X509TrustManager {
        @Volatile var seenSerial: String? = null
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            // The printer on 322/990 sends only its leaf; the CA comes from our bundle.
            caTrust.checkServerTrusted(chain, authType)
            val cn = commonName(chain.first())
            seenSerial = cn
            if (expectedSerial != null && !cn.equals(expectedSerial, ignoreCase = true)) {
                throw CertificateException("Printer serial $cn does not match configured $expectedSerial")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not accepted")

        override fun getAcceptedIssuers(): Array<X509Certificate> = caTrust.acceptedIssuers
    }

    companion object {
        const val MQTT_PORT = 8883
        const val RTSPS_PORT = 322

        internal fun commonName(cert: X509Certificate): String =
            commonName(cert.subjectX500Principal.name)
                ?: throw CertificateException("Certificate has no CN")

        /** Extracts the CN from an RFC 2253 distinguished name, honouring backslash escapes. */
        internal fun commonName(rfc2253: String): String? {
            val parts = mutableListOf<String>()
            val cur = StringBuilder()
            var escaped = false
            for (c in rfc2253) {
                when {
                    escaped -> { cur.append(c); escaped = false }
                    c == '\\' -> escaped = true
                    c == ',' || c == '+' -> { parts += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
            }
            parts += cur.toString()
            return parts.firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq).trim().equals("CN", ignoreCase = true)) {
                    part.substring(eq + 1).trim()
                } else null
            }
        }
    }
}

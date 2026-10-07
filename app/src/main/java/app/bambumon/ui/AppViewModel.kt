package app.bambumon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bambumon.BambumonApp
import app.bambumon.config.PrinterConfig
import app.bambumon.printer.PrinterTls
import app.bambumon.protocol.mqtt.MqttAuthException
import app.bambumon.protocol.mqtt.MqttSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface Screen {
    data object Loading : Screen
    /** First run, or re-entering credentials ([prefillHost] set when updating). */
    data class Setup(val prefillHost: String?) : Screen
    data class Dashboard(val config: PrinterConfig) : Screen
}

sealed interface SetupState {
    data object Idle : SetupState
    data class Checking(val step: String) : SetupState
    data class Failed(val message: String) : SetupState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as BambumonApp
    private val store = graph.configStore
    val tls: PrinterTls = graph.tls

    private val editing = MutableStateFlow(false)

    val screen: StateFlow<Screen> = combine(graph.printer.configLoad, editing) { load, edit ->
        val cfg = load?.config
        when {
            load == null -> Screen.Loading
            cfg == null -> Screen.Setup(null)
            edit -> Screen.Setup(cfg.host)
            else -> Screen.Dashboard(cfg)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Loading)

    private val _setup = MutableStateFlow<SetupState>(SetupState.Idle)
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    /**
     * Verifies the printer end to end before saving: TLS chain to the BBL CA (reading the
     * serial from the certificate), then an MQTT login with the access code.
     */
    fun connect(host: String, accessCode: String) {
        val h = host.trim()
        val code = accessCode.trim()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    _setup.value = SetupState.Checking("Verifying printer certificate…")
                    val serial = tls.probeSerial(h)
                    _setup.value = SetupState.Checking("Checking access code…")
                    MqttSession.open(
                        tls.socketFactory(serial), h, PrinterTls.MQTT_PORT, "bambumon-setup", "bblp", code,
                        keepAliveSec = 30, connectTimeoutMs = 8_000, readTimeoutMs = 8_000,
                    ).close()
                    PrinterConfig(h, serial, code)
                }
            }
            result
                .onSuccess {
                    store.save(it)
                    editing.value = false
                    _setup.value = SetupState.Idle
                }
                .onFailure { _setup.value = SetupState.Failed(explain(it, h)) }
        }
    }

    fun beginUpdateAccessCode() {
        _setup.value = SetupState.Idle
        editing.value = true
    }

    fun cancelUpdate() {
        editing.value = false
    }

    fun forgetPrinter() {
        viewModelScope.launch { store.clear() }
    }

    private fun explain(e: Throwable, host: String): String = when (e) {
        is MqttAuthException -> "The printer rejected that access code. Check Settings → LAN on the printer."
        is UnknownHostException -> "Couldn't find $host."
        is ConnectException, is SocketTimeoutException ->
            "Couldn't reach $host. Check the address, and that the phone is on your network or VPN."
        is SSLException, is java.security.cert.CertificateException ->
            "$host didn't present a Bambu printer certificate."
        else -> "Couldn't connect to $host: ${e.message ?: e.javaClass.simpleName}"
    }
}

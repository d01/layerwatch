package app.bambumon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bambumon.config.PrinterConfig
import app.bambumon.config.PrinterConfigStore
import app.bambumon.printer.PrinterTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConfigState {
    data object Loading : ConfigState
    data object Missing : ConfigState
    data class Ready(val config: PrinterConfig) : ConfigState
}

sealed interface SetupState {
    data object Idle : SetupState
    data object Checking : SetupState
    data class Failed(val message: String) : SetupState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrinterConfigStore(app)
    val tls = PrinterTls(app)

    val config: StateFlow<ConfigState> = store.config
        .map { it?.let(ConfigState::Ready) ?: ConfigState.Missing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConfigState.Loading)

    private val _setup = MutableStateFlow<SetupState>(SetupState.Idle)
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    /** Reads the serial from the printer's certificate, then saves the config. */
    fun connect(host: String, accessCode: String) {
        _setup.value = SetupState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { tls.probeSerial(host.trim()) } }
            result
                .onSuccess { serial ->
                    store.save(PrinterConfig(host.trim(), serial, accessCode.trim()))
                    _setup.value = SetupState.Idle
                }
                .onFailure {
                    _setup.value = SetupState.Failed(
                        "Couldn't verify a Bambu printer at $host: ${it.message ?: it.javaClass.simpleName}"
                    )
                }
        }
    }

    fun forgetPrinter() {
        viewModelScope.launch { store.clear() }
    }
}

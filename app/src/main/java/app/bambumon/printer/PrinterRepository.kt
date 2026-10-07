package app.bambumon.printer

import android.util.Log
import app.bambumon.config.PrinterConfig
import app.bambumon.config.PrinterConfigStore
import app.bambumon.protocol.LinkState
import app.bambumon.protocol.PrinterConnection
import app.bambumon.protocol.PrinterEndpoint
import app.bambumon.protocol.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide owner of the printer connection.
 *
 * The connection runs while anything holds demand ([acquire]): the visible UI today, the
 * print-monitoring service later. When demand drops to zero it lingers briefly so that
 * rotating, folding or a quick app switch doesn't tear the session down.
 */
class PrinterRepository(
    private val scope: CoroutineScope,
    configStore: PrinterConfigStore,
    private val tls: PrinterTls,
    val network: NetworkMonitor,
) {
    /** Null until the stored config has been read; then [Loaded], possibly with no printer. */
    val configLoad: StateFlow<Loaded?> = configStore.config
        .map { Loaded(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val config: StateFlow<PrinterConfig?> = configLoad
        .map { it?.config }
        .stateIn(scope, SharingStarted.Eagerly, null)

    data class Loaded(val config: PrinterConfig?)

    private val _connection = MutableStateFlow<PrinterConnection?>(null)
    val connection: StateFlow<PrinterConnection?> = _connection

    val link: StateFlow<LinkState> = _connection
        .flatMapLatest { it?.link ?: flowOf(LinkState.Stopped) }
        .stateIn(scope, SharingStarted.Eagerly, LinkState.Stopped)
    val status: StateFlow<PrinterStatus?> = _connection
        .flatMapLatest { it?.status ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val lastReportAtMs: StateFlow<Long?> = _connection
        .flatMapLatest { it?.lastReportAtMs ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var demand = 0
    private var lingerJob: Job? = null

    init {
        // Rebuild the connection whenever the config changes (setup, new access code).
        scope.launch {
            config.collect { cfg ->
                _connection.value?.stop()
                _connection.value = cfg?.let {
                    PrinterConnection(
                        PrinterEndpoint(it.host, it.serial, it.accessCode),
                        tls.socketFactory(it.serial),
                        scope,
                    )
                }
                if (demand > 0) _connection.value?.start()
            }
        }
        // A new default network means existing sockets are bound to a dead path.
        scope.launch {
            network.state.map { it.networkId }.distinctUntilChanged().drop(1).collect { id ->
                if (id != null) {
                    Log.i(TAG, "default network changed; reconnecting now")
                    _connection.value?.reconnectNow()
                }
            }
        }
    }

    /**
     * Hold the connection open until the returned handle is released. After the last
     * release it lingers briefly so a fold, rotation or quick app switch is seamless.
     */
    @Synchronized
    fun acquire(): () -> Unit {
        demand++
        lingerJob?.cancel()
        _connection.value?.start()
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    demand--
                    if (demand == 0) {
                        lingerJob = scope.launch {
                            delay(LINGER_MS)
                            _connection.value?.stop()
                        }
                    }
                }
            }
        }
    }

    fun reconnectNow() = _connection.value?.reconnectNow()

    private companion object {
        const val TAG = "bambumon.repo"
        const val LINGER_MS = 15_000L
    }
}

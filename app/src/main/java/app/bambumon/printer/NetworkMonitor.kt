package app.bambumon.printer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's default network, as far as reaching the printer is concerned. */
data class NetworkState(
    val available: Boolean,
    val viaVpn: Boolean,
    /** Changes whenever the default network does (WiFi ↔ cellular, VPN up/down). */
    val networkId: Long?,
)

/**
 * Tracks the default network so the app can tell "phone offline" apart from "printer
 * unreachable", and reconnect immediately when the path changes rather than waiting for
 * the dead-link timeout on a socket bound to a network that no longer exists.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(NetworkState(available = true, viaVpn = false, networkId = null))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _state.value = NetworkState(
                available = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                viaVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                networkId = network.networkHandle,
            )
        }

        override fun onLost(network: Network) {
            _state.value = NetworkState(available = false, viaVpn = false, networkId = null)
        }
    }

    init {
        // Seed synchronously so the first frame doesn't flash "offline".
        val active = cm.activeNetwork
        val caps = active?.let(cm::getNetworkCapabilities)
        _state.value = NetworkState(
            available = caps != null,
            viaVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            networkId = active?.networkHandle,
        )
        cm.registerDefaultNetworkCallback(callback)
    }
}

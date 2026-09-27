package com.example.EdgeMemo.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive connectivity status for UI display only (the "EDGE READY /
 * OFFLINE" header chip).
 *
 * - Event-driven via [ConnectivityManager.registerDefaultNetworkCallback]:
 *   no polling, no backend probing, no fake state.
 * - `true` only while the default network claims the INTERNET capability;
 *   `false` otherwise (disconnected, airplane mode, or a default network
 *   that cannot reach the internet).
 * - Deliberately separate from [AndroidConnectivityMonitor], which remains
 *   the synchronous quorum for the Ask cloud-escalation decision. Changing
 *   that behavior is out of scope here.
 * - Registration is app-scoped (single instance owned by AppContainer);
 *   [close] unregisters the callback (used by tests).
 */
class ConnectivityStatusFlow(
    context: Context,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(
        isOnlineStatus(
            connectivityManager.activeNetwork,
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork),
        ),
    )
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile
    private var currentNetwork: Network? = connectivityManager.activeNetwork

    @Volatile
    private var currentCapabilities: NetworkCapabilities? =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            currentNetwork = network
            currentCapabilities = connectivityManager.getNetworkCapabilities(network)
            publish()
        }

        override fun onLost(network: Network) {
            if (currentNetwork == network) {
                currentNetwork = null
                currentCapabilities = null
            }
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (currentNetwork == network) {
                currentCapabilities = networkCapabilities
            }
            publish()
        }

        override fun onUnavailable() {
            currentNetwork = null
            currentCapabilities = null
            publish()
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun publish() {
        _isOnline.value = isOnlineStatus(currentNetwork, currentCapabilities)
    }
}

/**
 * Pure status decision, extracted for direct unit testing: online only when
 * a network exists AND it claims the INTERNET capability.
 */
internal fun isOnlineStatus(network: Network?, capabilities: NetworkCapabilities?): Boolean {
    if (network == null || capabilities == null) return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

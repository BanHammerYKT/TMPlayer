package com.tmplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide connectivity, from the default network's capabilities rather than Wi-Fi being
 * switched on.
 *
 * Offline means no network that claims the internet. A network Android has not validated (a
 * captive portal, or a country or network where Google's check page is blocked, which is often
 * where Telegram is blocked too) is unknown, not offline: it used to be read as offline, so the app
 * said "check this device's internet" to people whose internet worked. See [NetworkFacts.status].
 *
 * Every change is also handed to [Td.onNetworkChanged], which tells TDLib about a switch of
 * network; TDLib cannot see one for itself here. TDLib has its own connection state as well, and
 * screens combine the two because a live TDLib connection is stronger evidence than an
 * occasionally late Android connectivity callback.
 */
object NetworkMonitor : Connectivity {

    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    /**
     * Whether the connection currently in use is one the viewer pays for by the byte.
     *
     * Defaults to false: an unknown network is treated as unmetered, because a guess that blocks
     * playback is worse than a guess that allows it.
     */
    private val _metered = MutableStateFlow(false)
    override val metered: StateFlow<Boolean> = _metered.asStateFlow()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true

            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = manager.activeNetwork
            val initial = active?.let(manager::getNetworkCapabilities)
            publish(factsOf(active, initial))
            _metered.value = meteredOf(initial)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                watchEveryNetwork(manager)
                return
            }
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        publish(factsOf(network, capabilities))
                        _metered.value = meteredOf(capabilities)
                    }

                    override fun onLost(network: Network) {
                        publish(NetworkFacts(known = true, hasNetwork = false, internet = false))
                        _metered.value = false
                    }
                },
            )
        }
    }

    /**
     * Android 6 has no callback for the default network alone, only one per network. Any change
     * to any of them re-reads the default one, so a second network coming or going (Wi-Fi beside
     * Ethernet on a box) never stands in for the one in use.
     */
    private fun watchEveryNetwork(manager: ConnectivityManager) {
        fun refresh() {
            val active = manager.activeNetwork
            val capabilities = active?.let { runCatching { manager.getNetworkCapabilities(it) }.getOrNull() }
            publish(factsOf(active, capabilities))
            _metered.value = meteredOf(capabilities)
        }
        manager.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
                override fun onLost(network: Network) = refresh()
            },
        )
    }

    /** Unknown is allowed to try; only a confirmed offline state should suppress a request. */
    override fun canTryInternet(): Boolean = _status.value != NetworkStatus.Offline

    /** Absent capabilities mean an unknown network, which is read as unmetered on purpose. */
    private fun meteredOf(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    private fun publish(facts: NetworkFacts) {
        _status.value = facts.status
        Td.onNetworkChanged(facts)
    }

    private fun factsOf(network: Network?, capabilities: NetworkCapabilities?): NetworkFacts {
        if (network == null || capabilities == null) {
            return NetworkFacts(known = true, hasNetwork = false, internet = false)
        }
        fun has(capability: Int) = capabilities.hasCapability(capability)
        fun over(transport: Int) = capabilities.hasTransport(transport)
        val kind = when {
            over(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WiFi
            over(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                if (has(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) NetworkKind.Mobile else NetworkKind.Roaming
            over(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
            else -> NetworkKind.Other
        }
        return NetworkFacts(
            known = true,
            hasNetwork = true,
            internet = has(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = has(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = has(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            vpn = over(NetworkCapabilities.TRANSPORT_VPN),
            kind = kind,
            id = network.toString(),
        )
    }
}

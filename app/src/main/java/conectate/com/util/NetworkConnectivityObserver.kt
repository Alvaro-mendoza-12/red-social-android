package conectate.com.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkConnectivityObserver @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isConnected: Flow<Boolean> = observeCapabilities(::isUsableConnection)

    val isCellular: Flow<Boolean> = observeCapabilities(::isCellularConnection)

    private fun observeCapabilities(resolve: (NetworkCapabilities) -> Boolean): Flow<Boolean> = callbackFlow {
        val lock = Any()
        // Only this initial snapshot uses synchronous getters. Android callbacks carry
        // ordered capabilities; getters inside a callback can still describe the old network.
        var trackedNetwork = connectivityManager.activeNetwork
        val initialCapabilities = trackedNetwork?.let(connectivityManager::getNetworkCapabilities)
        var callbackObserved = false
        var hasCapabilities = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    callbackObserved = true
                    trackedNetwork = network
                    hasCapabilities = false
                }
                // API 26+ delivers capabilities immediately after availability.
                // On 24/25, query later on the collecting coroutine, and let a real
                // capabilities callback supersede that fallback before it publishes.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) launch {
                    yield()
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    synchronized(lock) {
                        if (trackedNetwork == network && !hasCapabilities) {
                            trySend(capabilities?.let(resolve) ?: false)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    callbackObserved = true
                    if (trackedNetwork == network) {
                        trackedNetwork = null
                        trySend(false)
                    }
                }
            }

            override fun onUnavailable() {
                synchronized(lock) { if (trackedNetwork == null) trySend(false) }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                synchronized(lock) {
                    callbackObserved = true
                    if (network == trackedNetwork) {
                        hasCapabilities = true
                        trySend(resolve(networkCapabilities))
                    }
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        synchronized(lock) {
            if (!callbackObserved) trySend(initialCapabilities?.let(resolve) ?: false)
        }
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun isCellularNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isCellularConnection(capabilities)
    }

    private fun isCellularConnection(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    
    fun isNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return isUsableConnection(capabilities)
    }

    private fun isUsableConnection(capabilities: NetworkCapabilities): Boolean {
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && validated
    }
}




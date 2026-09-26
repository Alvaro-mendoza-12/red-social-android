package conectate.com.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkConnectivityObserver @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isConnected: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(resolveCurrentConnectionState())
            }

            override fun onLost(network: Network) {
                trySend(resolveCurrentConnectionState())
            }
            
            override fun onUnavailable() {
                trySend(false)
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(isUsableConnection(networkCapabilities))
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        // Initial state
        trySend(resolveCurrentConnectionState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    val isCellular: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isCellularNetwork()) }
            override fun onLost(network: Network) { trySend(isCellularNetwork()) }
            override fun onUnavailable() { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(isCellularNetwork())
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        trySend(isCellularNetwork())
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun isCellularNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                connectivityManager.isActiveNetworkMetered)
    }
    
    fun isNetworkAvailable(): Boolean {
        var activeNetwork = connectivityManager.activeNetwork ?: return false
        var capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return isUsableConnection(capabilities)
    }

    private fun resolveCurrentConnectionState(): Boolean {
        var activeNetwork = connectivityManager.activeNetwork ?: return false
        var capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return isUsableConnection(capabilities)
    }

    private fun isUsableConnection(capabilities: NetworkCapabilities): Boolean {
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && validated
    }
}




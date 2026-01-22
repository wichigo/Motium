package com.application.motium.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.application.motium.MotiumApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestionnaire de connexion réseau pour surveiller l'état de la connexion
 * et déclencher des actions lors de reconnexion
 */
class NetworkConnectionManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(isNetworkAvailable())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionType = MutableStateFlow(getConnectionType())
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    // BATTERY OPTIMIZATION (2026-01): Removed unused callbacks onConnectionRestored/onConnectionLost
    // These were never used and would have triggered unnecessary work on network changes.
    // The StateFlow approach is sufficient - observers react when they collect the flow.

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val wasDisconnected = !_isConnected.value
            _isConnected.value = true
            _connectionType.value = getConnectionType()

            // BATTERY OPTIMIZATION: Only log significant changes (disconnected → connected)
            if (wasDisconnected) {
                MotiumApplication.logger.i("Network restored", "NetworkManager")
            }
        }

        override fun onLost(network: Network) {
            // Vérifier s'il y a encore un réseau actif (ex: transition WiFi → 5G)
            val stillConnected = isNetworkAvailable()
            if (stillConnected) {
                // BATTERY OPTIMIZATION: Silent network switch, no logging
                _connectionType.value = getConnectionType()
                return
            }

            _isConnected.value = false
            _connectionType.value = ConnectionType.NONE
            MotiumApplication.logger.w("Network lost", "NetworkManager")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val newType = getConnectionType()
            if (_connectionType.value != newType) {
                // BATTERY OPTIMIZATION: Silent type changes, only update state
                _connectionType.value = newType
            }
        }
    }

    init {
        startNetworkMonitoring()
    }

    private fun startNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            MotiumApplication.logger.i("Network monitoring started", "NetworkManager")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to register network callback: ${e.message}", "NetworkManager", e)
        }
    }

    fun stopNetworkMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            MotiumApplication.logger.i("Network monitoring stopped", "NetworkManager")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to unregister network callback: ${e.message}", "NetworkManager", e)
        }
    }

    // BATTERY OPTIMIZATION (2026-01): Removed setOnConnectionRestored/setOnConnectionLost
    // as they were never used and would have caused unnecessary work on network changes

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun getConnectionType(): ConnectionType {
        val network = connectivityManager.activeNetwork ?: return ConnectionType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return ConnectionType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            else -> ConnectionType.OTHER
        }
    }

    enum class ConnectionType {
        NONE, WIFI, CELLULAR, ETHERNET, OTHER
    }

    companion object {
        @Volatile
        private var instance: NetworkConnectionManager? = null

        fun getInstance(context: Context): NetworkConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: NetworkConnectionManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * BATTERY OPTIMIZATION (2026-01): Cleanup singleton to stop network monitoring.
         * Should be called on user logout or when network monitoring is no longer needed.
         * This prevents the NetworkCallback from waking up the CPU on every network change
         * when the app is not actively being used.
         */
        fun cleanup() {
            synchronized(this) {
                instance?.stopNetworkMonitoring()
                instance = null
                MotiumApplication.logger.i("NetworkConnectionManager singleton cleaned up", "NetworkManager")
            }
        }
    }
}

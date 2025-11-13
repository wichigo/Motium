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

    private var onConnectionRestored: (() -> Unit)? = null
    private var onConnectionLost: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            MotiumApplication.logger.i("✅ Network connection available", "NetworkManager")
            val wasDisconnected = !_isConnected.value
            _isConnected.value = true
            _connectionType.value = getConnectionType()

            if (wasDisconnected) {
                MotiumApplication.logger.i("🔄 Network restored - triggering reconnection", "NetworkManager")
                onConnectionRestored?.invoke()
            }
        }

        override fun onLost(network: Network) {
            MotiumApplication.logger.w("❌ Network connection lost", "NetworkManager")
            _isConnected.value = false
            _connectionType.value = ConnectionType.NONE
            onConnectionLost?.invoke()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val newType = getConnectionType()
            if (_connectionType.value != newType) {
                MotiumApplication.logger.i("🔄 Network type changed to: $newType", "NetworkManager")
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

    fun setOnConnectionRestored(callback: () -> Unit) {
        onConnectionRestored = callback
    }

    fun setOnConnectionLost(callback: () -> Unit) {
        onConnectionLost = callback
    }

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
    }
}

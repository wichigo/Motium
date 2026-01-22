package com.application.motium.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.utils.NetworkConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * BATTERY OPTIMIZATION (2026-01):
 * Ce service a été drastiquement simplifié pour économiser la batterie.
 *
 * CHANGEMENTS:
 * - Suppression de la boucle infinie de refresh de session (toutes les 20 min) → géré par Supabase SDK
 * - Suppression de syncManager.startPeriodicSync() → géré par WorkManager DeltaSyncWorker
 * - Changement de START_STICKY → START_NOT_STICKY (ne redémarre pas automatiquement)
 * - Le service ne fait plus que le refresh de session au changement de réseau
 *
 * La synchronisation périodique est maintenant UNIQUEMENT gérée par WorkManager (DeltaSyncWorker)
 * qui respecte les contraintes Android (Doze mode, App Standby, etc.)
 */
class SupabaseConnectionService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        MotiumApplication.logger.e(
            "❌ Uncaught exception in SupabaseConnectionService coroutine: ${exception.message}",
            "ConnectionService",
            exception
        )
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private lateinit var authRepository: SupabaseAuthRepository
    private lateinit var networkManager: NetworkConnectionManager

    private var networkObserverJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "supabase_connection_channel"
        private const val CHANNEL_NAME = "Supabase Connection"

        fun startService(context: Context) {
            val intent = Intent(context, SupabaseConnectionService::class.java)
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SupabaseConnectionService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MotiumApplication.logger.i("🔗 SupabaseConnectionService created (lightweight mode)", "ConnectionService")

        authRepository = SupabaseAuthRepository.getInstance(this)
        networkManager = NetworkConnectionManager.getInstance(this)

        // BATTERY OPTIMIZATION: Seul le refresh de session au changement de réseau est conservé
        // La sync périodique est gérée par WorkManager (DeltaSyncWorker)
        setupNetworkCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MotiumApplication.logger.i("🔗 SupabaseConnectionService started (lightweight mode)", "ConnectionService")

        // BATTERY OPTIMIZATION: START_NOT_STICKY - ne pas redémarrer automatiquement
        // Le service sera redémarré quand l'utilisateur ouvre l'app
        return START_NOT_STICKY
    }

    private fun setupNetworkCallbacks() {
        networkObserverJob?.cancel()
        networkObserverJob = serviceScope.launch {
            networkManager.isConnected.collectLatest { isConnected ->
                if (isConnected) {
                    // BATTERY OPTIMIZATION: Seulement refresh session, pas de force sync
                    // La sync sera déclenchée par WorkManager selon son planning
                    MotiumApplication.logger.i("✅ Network connected - refreshing session only", "ConnectionService")
                    try {
                        authRepository.refreshSession()
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("Session refresh failed: ${e.message}", "ConnectionService")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        MotiumApplication.logger.i("🔗 SupabaseConnectionService destroyed", "ConnectionService")
        networkObserverJob?.cancel()
        serviceScope.cancel()

        // BATTERY OPTIMIZATION (2026-01): Stop network monitoring to prevent CPU wakeups
        // when the service is destroyed (e.g., on user logout)
        NetworkConnectionManager.cleanup()
    }
}
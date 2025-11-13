package com.application.motium.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.application.motium.MotiumApplication
import com.application.motium.R
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.sync.SupabaseSyncManager
import com.application.motium.utils.NetworkConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Service en avant-plan pour maintenir une connexion permanente avec Supabase
 * Gère la reconnexion automatique, surveillance réseau et synchronisation périodique
 */
class SupabaseConnectionService : Service() {

    // CRASH FIX: Add exception handler to catch all uncaught exceptions in coroutines
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
    private lateinit var syncManager: SupabaseSyncManager
    private lateinit var secureSessionStorage: SecureSessionStorage

    private var reconnectionJob: Job? = null
    private var sessionRefreshJob: Job? = null
    private var sessionHealthCheckJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "supabase_connection_channel"
        private const val CHANNEL_NAME = "Supabase Connection"

        // Intervalle de rafraîchissement de session: 20 minutes (tokens expirent après 60 min)
        private const val SESSION_REFRESH_INTERVAL = 20L * 60 * 1000 // 20 minutes

        // Health check toutes les 2 minutes pour vérifier la session
        private const val SESSION_HEALTH_CHECK_INTERVAL = 2L * 60 * 1000 // 2 minutes

        // Backoff maximum pour retry: 5 minutes
        private const val MAX_RETRY_BACKOFF = 5L * 60 * 1000 // 5 minutes

        fun startService(context: Context) {
            val intent = Intent(context, SupabaseConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SupabaseConnectionService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MotiumApplication.logger.i("🔗 SupabaseConnectionService created", "ConnectionService")

        // Initialiser les gestionnaires
        authRepository = SupabaseAuthRepository.getInstance(this)
        networkManager = NetworkConnectionManager.getInstance(this)
        syncManager = SupabaseSyncManager.getInstance(this)
        secureSessionStorage = SecureSessionStorage(this)

        // Configurer les callbacks de reconnexion réseau
        setupNetworkCallbacks()

        // Démarrer la surveillance de la session
        startSessionMonitoring()

        // DÉSACTIVÉ: Le health check causait des problèmes avec le SecureSessionManager
        // car auth.currentUserOrNull() retourne null même avec une session valide
        // (le UserSession est créé avec user=null lors du load)
        // startSessionHealthCheck()

        // Démarrer la synchronisation périodique
        syncManager.startPeriodicSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MotiumApplication.logger.i("🔗 SupabaseConnectionService started", "ConnectionService")

        // Créer la notification pour le service en avant-plan
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motium - Connected")
            .setContentText("Maintaining connection with server")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Démarrer le service en avant-plan avec le type dataSync pour Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains persistent connection with Supabase"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun setupNetworkCallbacks() {
        // Configurer le callback de reconnexion quand le réseau revient
        networkManager.setOnConnectionRestored {
            MotiumApplication.logger.i("🔄 Network restored - attempting reconnection", "ConnectionService")
            attemptReconnection()
        }

        networkManager.setOnConnectionLost {
            MotiumApplication.logger.w("❌ Network lost - waiting for restoration", "ConnectionService")
            // Annuler les jobs en cours
            reconnectionJob?.cancel()
        }

        // Observer le statut de connexion
        serviceScope.launch {
            networkManager.isConnected.collectLatest { isConnected ->
                if (isConnected) {
                    MotiumApplication.logger.i("✅ Network connected - type: ${networkManager.connectionType.value}", "ConnectionService")
                } else {
                    MotiumApplication.logger.w("❌ Network disconnected", "ConnectionService")
                }
            }
        }
    }

    private fun startSessionMonitoring() {
        // Rafraîchir la session toutes les 20 minutes ou quand le token expire bientôt
        sessionRefreshJob?.cancel()
        sessionRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_INTERVAL)

                try {
                    if (!networkManager.isConnected.value) {
                        MotiumApplication.logger.w("⏸️ Skipping session refresh - no network", "ConnectionService")
                        continue
                    }

                    if (!secureSessionStorage.hasSession()) {
                        MotiumApplication.logger.w("⏸️ No session found - skipping refresh", "ConnectionService")
                        continue
                    }

                    // Vérifier si le token expire bientôt ou est déjà expiré
                    if (secureSessionStorage.isTokenExpired()) {
                        MotiumApplication.logger.w("⚠️ Token expired - urgent refresh required", "ConnectionService")
                        authRepository.refreshSession()
                    } else if (secureSessionStorage.isTokenExpiringSoon(10)) {
                        MotiumApplication.logger.i("🔄 Token expires soon - preventive refresh", "ConnectionService")
                        authRepository.refreshSession()
                    } else {
                        MotiumApplication.logger.d("✅ Token still valid - scheduled refresh", "ConnectionService")
                        authRepository.refreshSession()
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Session refresh failed: ${e.message}", "ConnectionService", e)
                    // Tenter une reconnexion en cas d'échec
                    attemptReconnection()
                }
            }
        }
    }

    private fun attemptReconnection() {
        reconnectionJob?.cancel()
        reconnectionJob = serviceScope.launch {
            // Attendre un peu pour que le réseau se stabilise
            delay(2000)

            var retries = 0

            // RETRY INFINI avec backoff exponentiel plafonné
            while (isActive) {
                try {
                    MotiumApplication.logger.i("🔄 Reconnection attempt ${retries + 1}", "ConnectionService")

                    // Vérifier si on a encore une connexion réseau
                    if (!networkManager.isConnected.value) {
                        MotiumApplication.logger.w("⏸️ Network not available, waiting...", "ConnectionService")
                        delay(30_000) // Attendre 30s avant de revérifier
                        continue
                    }

                    // Valider et rafraîchir la session
                    authRepository.validateCurrentSession()

                    MotiumApplication.logger.i("✅ Reconnection successful after ${retries + 1} attempts", "ConnectionService")

                    // Déclencher une sync immédiate après reconnexion réussie
                    syncManager.forceSyncNow()

                    break

                } catch (e: Exception) {
                    retries++
                    MotiumApplication.logger.e("❌ Reconnection attempt $retries failed: ${e.message}", "ConnectionService", e)

                    // Backoff exponentiel: 2s, 4s, 8s, 16s, 32s, 64s, ... plafonné à 5 minutes
                    val delayMs = minOf(
                        (1 shl retries) * 2000L,
                        MAX_RETRY_BACKOFF
                    )
                    MotiumApplication.logger.i("⏳ Waiting ${delayMs / 1000}s before retry", "ConnectionService")
                    delay(delayMs)
                }
            }
        }
    }

    /**
     * Health check périodique de la session
     * Vérifie toutes les 2 minutes si la session est toujours valide
     */
    private fun startSessionHealthCheck() {
        sessionHealthCheckJob?.cancel()
        sessionHealthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(SESSION_HEALTH_CHECK_INTERVAL)

                try {
                    if (!networkManager.isConnected.value) {
                        MotiumApplication.logger.d("⏸️ Health check skipped - no network", "ConnectionService")
                        continue
                    }

                    if (!authRepository.isUserAuthenticated()) {
                        MotiumApplication.logger.d("⏸️ Health check skipped - user not authenticated", "ConnectionService")
                        continue
                    }

                    // Log des informations de session pour debug
                    secureSessionStorage.debugLogSession()

                    // Vérifier si le token est expiré ou expire bientôt
                    if (secureSessionStorage.isTokenExpired()) {
                        MotiumApplication.logger.e("❌ Health check: Token expired! Immediate refresh required", "ConnectionService")
                        authRepository.refreshSession()
                    } else if (secureSessionStorage.isTokenExpiringSoon(5)) {
                        MotiumApplication.logger.w("⚠️ Health check: Token expires soon, preventive refresh", "ConnectionService")
                        authRepository.refreshSession()
                    } else {
                        MotiumApplication.logger.d("✅ Health check: Session valid", "ConnectionService")
                        authRepository.validateCurrentSession()
                    }

                    // Mettre à jour la notification avec les stats de sync
                    updateNotificationWithStats()
                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Session health check failed: ${e.message}", "ConnectionService", e)
                    // Tenter une reconnexion
                    attemptReconnection()
                }
            }
        }
    }

    /**
     * Met à jour la notification avec les statistiques de synchronisation
     */
    private fun updateNotificationWithStats() {
        try {
            val stats = syncManager.getSyncStats()
            val content = if (stats.pendingOperations > 0) {
                "Connected - ${stats.pendingOperations} operations pending"
            } else {
                "Connected - All data synced"
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Motium - Connected")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating notification: ${e.message}", "ConnectionService", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        MotiumApplication.logger.i("🔗 SupabaseConnectionService destroyed", "ConnectionService")

        // Annuler tous les jobs
        reconnectionJob?.cancel()
        sessionRefreshJob?.cancel()
        sessionHealthCheckJob?.cancel()
        serviceScope.cancel()

        // Arrêter la synchronisation périodique
        syncManager.stopPeriodicSync()

        // Arrêter la surveillance réseau
        networkManager.stopNetworkMonitoring()
    }
}

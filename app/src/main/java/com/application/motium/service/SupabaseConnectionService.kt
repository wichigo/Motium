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
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.sync.SupabaseSyncManager
import com.application.motium.utils.NetworkConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

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
    private lateinit var syncManager: SupabaseSyncManager

    private var sessionRefreshJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "supabase_connection_channel"
        private const val CHANNEL_NAME = "Supabase Connection"
        private const val SESSION_REFRESH_INTERVAL = 20L * 60 * 1000 // 20 minutes

        fun startService(context: Context) {
            val intent = Intent(context, SupabaseConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SupabaseConnectionService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MotiumApplication.logger.i("🔗 SupabaseConnectionService created", "ConnectionService")

        authRepository = SupabaseAuthRepository.getInstance(this)
        networkManager = NetworkConnectionManager.getInstance(this)
        syncManager = SupabaseSyncManager.getInstance(this)

        setupNetworkCallbacks()
        startSessionMonitoring()
        syncManager.startPeriodicSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MotiumApplication.logger.i("🔗 SupabaseConnectionService started", "ConnectionService")

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motium Service")
            .setContentText("Ensuring data is up-to-date.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles background data synchronization."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupNetworkCallbacks() {
        serviceScope.launch {
            networkManager.isConnected.collectLatest { isConnected ->
                if (isConnected) {
                    MotiumApplication.logger.i("✅ Network connected. Triggering session refresh and sync.", "ConnectionService")
                    authRepository.refreshSession()
                    syncManager.forceSyncNow()
                } else {
                    MotiumApplication.logger.w("❌ Network disconnected.", "ConnectionService")
                }
            }
        }
    }

    private fun startSessionMonitoring() {
        sessionRefreshJob?.cancel()
        sessionRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_INTERVAL)
                if (networkManager.isConnected.value) {
                    MotiumApplication.logger.i("⏰ Performing scheduled session refresh.", "ConnectionService")
                    authRepository.refreshSession()
                } else {
                    MotiumApplication.logger.w("⏰ Skipping scheduled session refresh - no network.", "ConnectionService")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        MotiumApplication.logger.i("🔗 SupabaseConnectionService destroyed", "ConnectionService")
        serviceScope.cancel()
        syncManager.stopPeriodicSync()
        // No need to stop network manager if it's a singleton managing its own lifecycle
    }
}
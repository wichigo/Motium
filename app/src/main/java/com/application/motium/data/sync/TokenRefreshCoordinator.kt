package com.application.motium.data.sync

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.supabase.SupabaseAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * FIABILITÉ: Coordinateur central pour tous les refresh de tokens de session.
 *
 * Empêche les refresh simultanés qui peuvent causer des race conditions et
 * assure un intervalle minimum entre les refresh pour économiser batterie et réseau.
 *
 * Usage:
 * - SessionRefreshWorker appelle refreshIfNeeded()
 * - SupabaseConnectionService appelle refreshIfNeeded()
 * - Network reconnect appelle refreshIfNeeded(force = true)
 */
class TokenRefreshCoordinator private constructor(
    private val context: Context
) {
    private val mutex = Mutex()
    private var lastRefreshTime = 0L
    private val refreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val secureSessionStorage = SecureSessionStorage(context)
    private val authRepository = SupabaseAuthRepository.getInstance(context)

    companion object {
        private const val MIN_REFRESH_INTERVAL = 60_000L // 1 minute minimum entre refresh
        private const val PROACTIVE_REFRESH_MARGIN = 5 * 60 * 1000L // 5 minutes avant expiration

        @Volatile
        private var instance: TokenRefreshCoordinator? = null

        fun getInstance(context: Context): TokenRefreshCoordinator {
            return instance ?: synchronized(this) {
                instance ?: TokenRefreshCoordinator(context.applicationContext)
                    .also { instance = it }
            }
        }
    }

    /**
     * FIABILITÉ: Rafraîchit le token si nécessaire.
     *
     * @param force Si true, ignore l'intervalle minimum et force le refresh
     * @return true si le refresh a réussi, false sinon
     */
    suspend fun refreshIfNeeded(force: Boolean = false): Boolean {
        mutex.withLock {
            val now = System.currentTimeMillis()

            // Éviter refresh trop fréquents (sauf si force = true)
            if (!force && (now - lastRefreshTime) < MIN_REFRESH_INTERVAL) {
                MotiumApplication.logger.d(
                    "⏭️ Skipping refresh - last refresh was ${(now - lastRefreshTime) / 1000}s ago (min: ${MIN_REFRESH_INTERVAL / 1000}s)",
                    "TokenRefresh"
                )
                return true
            }

            return try {
                MotiumApplication.logger.i(
                    "🔄 Refreshing token (force=$force, last refresh: ${(now - lastRefreshTime) / 1000}s ago)",
                    "TokenRefresh"
                )

                authRepository.refreshSession()
                lastRefreshTime = now

                MotiumApplication.logger.i("✅ Token refreshed successfully", "TokenRefresh")
                true
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "❌ Token refresh failed: ${e.message}",
                    "TokenRefresh",
                    e
                )
                false
            }
        }
    }

    /**
     * FIABILITÉ: Planifie un refresh proactif AVANT l'expiration du token.
     *
     * Le refresh sera effectué 5 minutes avant l'expiration pour éviter
     * les problèmes de session expirée en pleine utilisation.
     *
     * @param expiresAt Timestamp d'expiration du token (milliseconds)
     */
    fun scheduleProactiveRefresh(expiresAt: Long) {
        val now = System.currentTimeMillis()
        val timeUntilExpiry = expiresAt - now
        val refreshTime = timeUntilExpiry - PROACTIVE_REFRESH_MARGIN

        if (refreshTime > 0) {
            MotiumApplication.logger.i(
                "⏰ Scheduling proactive refresh in ${refreshTime / 1000}s (${PROACTIVE_REFRESH_MARGIN / 1000}s before expiry)",
                "TokenRefresh"
            )

            refreshScope.launch {
                delay(refreshTime)
                MotiumApplication.logger.i("⏰ Proactive refresh triggered", "TokenRefresh")
                refreshIfNeeded(force = true)
            }
        } else {
            MotiumApplication.logger.w(
                "⚠️ Token expires in ${timeUntilExpiry / 1000}s - refreshing immediately",
                "TokenRefresh"
            )

            refreshScope.launch {
                refreshIfNeeded(force = true)
            }
        }
    }

    /**
     * Vérifie si le token va bientôt expirer et nécessite un refresh proactif.
     *
     * @return true si le token expire dans moins de 5 minutes
     */
    fun isTokenExpiringSoon(): Boolean {
        return secureSessionStorage.isTokenExpiringSoon(5)
    }

    /**
     * Obtient le temps restant avant expiration du token en secondes.
     *
     * @return Secondes restantes, ou null si pas de session
     */
    fun getTimeUntilExpiry(): Long? {
        return try {
            val expiresAt = secureSessionStorage.getExpiresAt() ?: return null
            val now = System.currentTimeMillis()
            val timeRemaining = expiresAt - now
            if (timeRemaining > 0) timeRemaining / 1000 else 0
        } catch (e: Exception) {
            null
        }
    }
}

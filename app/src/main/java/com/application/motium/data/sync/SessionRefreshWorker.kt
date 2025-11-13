package com.application.motium.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.motium.MotiumApplication
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.supabase.SupabaseAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker pour rafraîchir la session Supabase en arrière-plan
 * Exécuté périodiquement par WorkManager toutes les 15-30 minutes
 *
 * Garantit que la session reste valide même quand l'app est fermée
 */
class SessionRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val authRepository by lazy { SupabaseAuthRepository.getInstance(applicationContext) }
    private val secureSessionStorage by lazy { SecureSessionStorage(applicationContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("🔄 BackgroundSync: Démarrage du refresh de session en arrière-plan", "SessionRefreshWorker")

            // Vérifier si on a une session valide
            if (!secureSessionStorage.hasSession()) {
                MotiumApplication.logger.i("⏭️ BackgroundSync: Aucune session trouvée, worker ignoré", "SessionRefreshWorker")
                return@withContext Result.success()
            }

            // Vérifier si le token est expiré ou expire bientôt
            if (secureSessionStorage.isTokenExpired()) {
                MotiumApplication.logger.w("❌ BackgroundSync: Token expiré - tentative de rafraîchissement", "SessionRefreshWorker")
            } else if (secureSessionStorage.isTokenExpiringSoon(10)) {
                MotiumApplication.logger.w("⚠️ BackgroundSync: Token expire bientôt - rafraîchissement préventif", "SessionRefreshWorker")
            } else {
                MotiumApplication.logger.i("✅ BackgroundSync: Token encore valide, rafraîchissement non nécessaire", "SessionRefreshWorker")
                return@withContext Result.success()
            }

            // Tenter le rafraîchissement de la session
            try {
                authRepository.refreshSession()

                // Vérifier que le refresh a réussi
                if (secureSessionStorage.hasValidSession()) {
                    MotiumApplication.logger.i("✅ BackgroundSync: Session rafraîchie avec succès en arrière-plan", "SessionRefreshWorker")
                    Result.success()
                } else {
                    MotiumApplication.logger.e("❌ BackgroundSync: Session invalide après refresh", "SessionRefreshWorker")
                    Result.retry()
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ BackgroundSync: Erreur lors du refresh de session: ${e.message}", "SessionRefreshWorker", e)

                // Retry si c'est une erreur réseau temporaire
                if (e.message?.contains("network", ignoreCase = true) == true ||
                    e.message?.contains("timeout", ignoreCase = true) == true) {
                    MotiumApplication.logger.i("🔄 BackgroundSync: Erreur réseau - retry planifié", "SessionRefreshWorker")
                    Result.retry()
                } else {
                    // Échec permanent
                    MotiumApplication.logger.e("❌ BackgroundSync: Échec permanent du refresh", "SessionRefreshWorker")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ BackgroundSync: Erreur fatale dans SessionRefreshWorker: ${e.message}", "SessionRefreshWorker", e)
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "session_refresh_worker"
    }
}

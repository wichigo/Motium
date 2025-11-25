package com.application.motium.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    // FIABILITÉ: Utiliser TokenRefreshCoordinator pour éviter les refresh simultanés
    private val coordinator by lazy { TokenRefreshCoordinator.getInstance(applicationContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("🔄 BackgroundSync: Starting background session refresh", "SessionRefreshWorker")

            // FIABILITÉ: Utiliser le coordinator qui gère les refresh avec mutex et intervalle minimum
            val success = coordinator.refreshIfNeeded()

            if (success) {
                MotiumApplication.logger.i("✅ BackgroundSync: Session refresh completed successfully", "SessionRefreshWorker")
                Result.success()
            } else {
                MotiumApplication.logger.w("⚠️ BackgroundSync: Session refresh failed - will retry", "SessionRefreshWorker")
                Result.retry()
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ BackgroundSync: Error during session refresh: ${e.message}", "SessionRefreshWorker", e)
            // Retry for any exception, as it's likely a temporary network issue.
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "session_refresh_worker"
    }
}

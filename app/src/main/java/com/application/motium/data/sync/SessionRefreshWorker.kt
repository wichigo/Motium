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

    private val authRepository by lazy { SupabaseAuthRepository.getInstance(applicationContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("🔄 BackgroundSync: Starting background session refresh", "SessionRefreshWorker")

            // Directly attempt to refresh the session.
            // The repository will handle the logic of whether a refresh is needed.
            authRepository.refreshSession()

            MotiumApplication.logger.i("✅ BackgroundSync: Session refresh check completed.", "SessionRefreshWorker")
            Result.success()
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

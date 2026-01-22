package com.application.motium.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LicenseRemoteDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that processes expired license unlink requests.
 *
 * This worker runs daily to:
 * 1. Find all licenses with expired unlink_effective_at dates
 * 2. Return those licenses to the pool (clear linked_account_id)
 *
 * When a Pro user requests to unlink/cancel a license:
 * - Lifetime: unlink_effective_at = now (immediate)
 * - Mensuelle: unlink_effective_at = endDate (date de renouvellement)
 * This worker processes those requests when they expire.
 */
class LicenseUnlinkWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val licenseRemoteDataSource by lazy { LicenseRemoteDataSource.getInstance(applicationContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i(
                "🔄 LicenseUnlinkWorker: Processing expired unlink requests",
                "LicenseUnlinkWorker"
            )

            val result = licenseRemoteDataSource.processExpiredUnlinks()

            result.fold(
                onSuccess = { count ->
                    if (count > 0) {
                        MotiumApplication.logger.i(
                            "✅ LicenseUnlinkWorker: Processed $count expired unlinks",
                            "LicenseUnlinkWorker"
                        )
                    } else {
                        MotiumApplication.logger.i(
                            "✅ LicenseUnlinkWorker: No expired unlinks to process",
                            "LicenseUnlinkWorker"
                        )
                    }
                    Result.success()
                },
                onFailure = { error ->
                    MotiumApplication.logger.e(
                        "❌ LicenseUnlinkWorker: Failed to process expired unlinks: ${error.message}",
                        "LicenseUnlinkWorker",
                        error
                    )
                    // Retry on failure
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ LicenseUnlinkWorker: Error during processing: ${e.message}",
                "LicenseUnlinkWorker",
                e
            )
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "license_unlink_worker"
    }
}

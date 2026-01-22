package com.application.motium.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.application.motium.MotiumApplication
import java.util.concurrent.TimeUnit

/**
 * Scheduler for license-related background tasks.
 *
 * Schedules periodic processing of:
 * - Expired license unlink requests (effective at renewal date or immediate for lifetime)
 *
 * The worker runs daily to check if any licenses have passed their
 * unlink_effective_at date and should be returned to the pool.
 */
object LicenseScheduler {

    // Run once per day (24 hours)
    private const val PROCESSING_INTERVAL_HOURS = 24L

    /**
     * Schedule the periodic license processing work.
     * Call this at app startup.
     */
    fun scheduleLicenseProcessing(context: Context) {
        try {
            MotiumApplication.logger.i(
                "⏰ Scheduling license processing worker (every $PROCESSING_INTERVAL_HOURS hours)",
                "LicenseScheduler"
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true) // Don't run when battery is low
                .build()

            val workRequest = PeriodicWorkRequestBuilder<LicenseUnlinkWorker>(
                PROCESSING_INTERVAL_HOURS, TimeUnit.HOURS,
                1, TimeUnit.HOURS // Flex period of 1 hour
            )
                .setConstraints(constraints)
                .addTag(LicenseUnlinkWorker.WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                LicenseUnlinkWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                workRequest
            )

            MotiumApplication.logger.i(
                "✅ License processing worker scheduled successfully",
                "LicenseScheduler"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Failed to schedule license processing: ${e.message}",
                "LicenseScheduler",
                e
            )
        }
    }

    /**
     * Cancel the periodic license processing work.
     */
    fun cancelLicenseProcessing(context: Context) {
        try {
            MotiumApplication.logger.i(
                "🛑 Cancelling license processing worker",
                "LicenseScheduler"
            )
            WorkManager.getInstance(context).cancelUniqueWork(LicenseUnlinkWorker.WORK_NAME)
            MotiumApplication.logger.i(
                "✅ License processing worker cancelled",
                "LicenseScheduler"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Failed to cancel license processing: ${e.message}",
                "LicenseScheduler",
                e
            )
        }
    }

    /**
     * Force immediate execution of the license processing worker.
     * Useful for testing or manual triggers.
     */
    fun forceProcessNow(context: Context) {
        try {
            MotiumApplication.logger.i(
                "🚀 Forcing immediate license processing",
                "LicenseScheduler"
            )

            val immediateWorkRequest = androidx.work.OneTimeWorkRequestBuilder<LicenseUnlinkWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(immediateWorkRequest)
            MotiumApplication.logger.i(
                "✅ Immediate license processing scheduled",
                "LicenseScheduler"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Failed to force license processing: ${e.message}",
                "LicenseScheduler",
                e
            )
        }
    }
}

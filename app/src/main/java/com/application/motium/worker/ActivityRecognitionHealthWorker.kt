package com.application.motium.worker

import android.content.Context
import androidx.work.*
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.service.AutoTrackingDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that monitors the health of ActivityRecognition.
 *
 * This worker runs every hour and checks:
 * 1. If auto-tracking is enabled
 * 2. If we've received activity transitions recently
 * 3. If the keep-alive alarms are firing
 *
 * If the system appears unhealthy (no transitions in 2+ hours during active times),
 * it forces a restart of the ActivityRecognition service.
 *
 * This is particularly important for Samsung devices where One UI can silently
 * kill background services and receivers.
 */
class ActivityRecognitionHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val tripRepository = TripRepository.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.d("ActivityRecognitionHealthWorker: Running health check", TAG)

            // Only check if auto-tracking is enabled
            val autoTrackingEnabled = tripRepository.isAutoTrackingEnabled()
            if (!autoTrackingEnabled) {
                MotiumApplication.logger.d("Auto-tracking disabled, skipping health check", TAG)
                return@withContext Result.success()
            }

            // Only check during typical driving hours (6am - 10pm)
            if (!isDuringActiveHours()) {
                MotiumApplication.logger.d("Outside active hours, skipping health check", TAG)
                return@withContext Result.success()
            }

            // Get diagnostic report
            val diagnostics = AutoTrackingDiagnostics.getDiagnosticReport(applicationContext)

            // Log current status
            MotiumApplication.logger.d(
                "Health check: lastTransition=${diagnostics.lastTransitionAgeMinutes}min ago, " +
                "lastKeepalive=${diagnostics.lastKeepaliveAgeMinutes}min ago, " +
                "transitionsToday=${diagnostics.transitionsToday}",
                TAG
            )

            // Check for issues
            var needsRestart = false
            var restartReason = ""

            // Issue 1: No transitions in 2+ hours during active time
            val lastTransitionAge = diagnostics.lastTransitionAgeMinutes ?: Long.MAX_VALUE
            if (lastTransitionAge > MAX_TRANSITION_AGE_MINUTES && diagnostics.lastTransitionTime != null) {
                needsRestart = true
                restartReason = "No transitions in ${lastTransitionAge}min"
                MotiumApplication.logger.w(
                    "Health issue: No activity transitions in ${lastTransitionAge} minutes",
                    TAG
                )
            }

            // Issue 2: Keep-alive not firing (more than 45 minutes)
            val lastKeepaliveAge = diagnostics.lastKeepaliveAgeMinutes ?: Long.MAX_VALUE
            if (lastKeepaliveAge > MAX_KEEPALIVE_AGE_MINUTES && diagnostics.lastKeepaliveTime != null) {
                needsRestart = true
                restartReason = "Keep-alive stale (${lastKeepaliveAge}min)"
                MotiumApplication.logger.w(
                    "Health issue: Keep-alive not triggered in ${lastKeepaliveAge} minutes",
                    TAG
                )
            }

            // Issue 3: Google Play Services not available
            if (!diagnostics.googlePlayServicesAvailable) {
                MotiumApplication.logger.e(
                    "Health issue: Google Play Services not available!",
                    TAG
                )
                // Don't restart - won't help if Play Services is down
            }

            // Force restart if needed
            if (needsRestart) {
                MotiumApplication.logger.w(
                    "Forcing ActivityRecognition restart: $restartReason",
                    TAG
                )

                // Log to diagnostics
                AutoTrackingDiagnostics.logServiceRestart(applicationContext, restartReason)

                // Force restart on main thread
                withContext(Dispatchers.Main) {
                    try {
                        // First try to re-register
                        ActivityRecognitionService.reregisterActivityRecognition(applicationContext)

                        // Then restart service
                        ActivityRecognitionService.stopService(applicationContext)
                        ActivityRecognitionService.startService(applicationContext)

                        MotiumApplication.logger.i(
                            "ActivityRecognition restarted successfully",
                            TAG
                        )
                    } catch (e: Exception) {
                        MotiumApplication.logger.e(
                            "Failed to restart ActivityRecognition: ${e.message}",
                            TAG,
                            e
                        )
                    }
                }
            } else {
                MotiumApplication.logger.d("Health check passed - no issues detected", TAG)
            }

            Result.success()
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error in ActivityRecognitionHealthWorker: ${e.message}",
                TAG,
                e
            )
            Result.retry()
        }
    }

    /**
     * Check if current time is during typical driving hours (6am - 10pm).
     * No point checking health outside these hours.
     */
    private fun isDuringActiveHours(): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour in ACTIVE_HOURS_START..ACTIVE_HOURS_END
    }

    companion object {
        private const val TAG = "HealthWorker"
        private const val WORK_TAG = "activity_recognition_health"

        // Thresholds
        private const val MAX_TRANSITION_AGE_MINUTES = 120L // 2 hours
        private const val MAX_KEEPALIVE_AGE_MINUTES = 45L // Should fire every 15-30 min

        // Active hours (6am - 10pm)
        private const val ACTIVE_HOURS_START = 6
        private const val ACTIVE_HOURS_END = 22

        /**
         * Schedule health checks every 2 hours.
         * BATTERY OPTIMIZATION (2026-01-22): Passé de 1h à 2h pour réduire la consommation batterie.
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<ActivityRecognitionHealthWorker>(
                2, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .addTag(WORK_TAG)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    10, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            MotiumApplication.logger.i(
                "ActivityRecognitionHealthWorker scheduled (every 2 hours)",
                TAG
            )
        }

        /**
         * Cancel health checks.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
            MotiumApplication.logger.i("ActivityRecognitionHealthWorker cancelled", TAG)
        }

        /**
         * Run health check immediately.
         */
        fun runNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<ActivityRecognitionHealthWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            MotiumApplication.logger.d("ActivityRecognitionHealthWorker triggered immediately", TAG)
        }
    }
}

package com.application.motium.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.data.sync.AutoTrackingScheduleWorker
import com.application.motium.domain.model.TrackingMode
import com.application.motium.worker.ActivityRecognitionHealthWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that starts the auto-tracking service when:
 * - Device boots (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED)
 * - App is updated (MY_PACKAGE_REPLACED)
 *
 * IMPORTANT: The intent-filter for BOOT_COMPLETED must NOT have a data scheme,
 * otherwise the broadcast won't be received.
 */
class AutoStartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoStartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        MotiumApplication.logger.i("📱 Received broadcast: $action", TAG)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                handleBootOrUpdate(context, action)
            }
            else -> {
                MotiumApplication.logger.d("Ignoring action: $action", TAG)
            }
        }
    }

    private fun handleBootOrUpdate(context: Context, action: String) {
        val actionName = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "BOOT_COMPLETED"
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "LOCKED_BOOT_COMPLETED"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "APP_UPDATED"
            else -> action
        }

        MotiumApplication.logger.i("🚀 $actionName detected - checking auto-tracking status", TAG)

        // Use goAsync() to extend the broadcast window for async operations
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tripRepository = TripRepository.getInstance(context)
                val localUserRepository = LocalUserRepository.getInstance(context)
                val workScheduleRepository = WorkScheduleRepository.getInstance(context)
                val userId = localUserRepository.getLoggedInUser()?.id

                // Sync cache from Room first (if possible) to avoid stale prefs
                if (userId != null) {
                    tripRepository.syncAutoTrackingCacheFromRoom(userId)
                }

                val trackingMode = tripRepository.getTrackingMode()
                MotiumApplication.logger.i("Tracking mode at boot: $trackingMode (userId=$userId)", TAG)

                when (trackingMode) {
                    TrackingMode.ALWAYS -> {
                        tripRepository.setAutoTrackingEnabled(true)
                        ActivityRecognitionHealthWorker.schedule(context)
                        startAutoTracking(context)
                    }
                    TrackingMode.WORK_HOURS_ONLY -> {
                        AutoTrackingScheduleWorker.schedule(context)
                        ActivityRecognitionHealthWorker.schedule(context)

                        val shouldTrack = if (userId != null) {
                            workScheduleRepository.shouldAutotrackOfflineFirst(userId)
                        } else {
                            false
                        }
                        tripRepository.setAutoTrackingEnabled(shouldTrack)

                        if (shouldTrack) {
                            startAutoTracking(context)
                        } else {
                            ActivityRecognitionService.stopService(context)
                            MotiumApplication.logger.i(
                                "Auto-tracking is disabled (outside work hours), not starting service",
                                TAG
                            )
                        }

                        // Force immediate evaluation (best effort)
                        AutoTrackingScheduleWorker.runNow(context)
                    }
                    TrackingMode.DISABLED -> {
                        tripRepository.setAutoTrackingEnabled(false)
                        AutoTrackingScheduleWorker.cancel(context)
                        ActivityRecognitionHealthWorker.cancel(context)
                        ActivityRecognitionService.stopService(context)
                        MotiumApplication.logger.i("Auto-tracking disabled (mode DISABLED)", TAG)
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Error in handleBootOrUpdate: ${e.message}",
                    TAG,
                    e
                )
            } finally {
                // Must call finish() when using goAsync()
                pendingResult.finish()
            }
        }
    }

    private fun startAutoTracking(context: Context) {
        try {
            MotiumApplication.logger.i("🎯 Starting ActivityRecognitionService...", TAG)

            // On Android 12+, we're allowed to start foreground services from BOOT_COMPLETED
            ActivityRecognitionService.startService(context)

            MotiumApplication.logger.i(
                "✅ ActivityRecognitionService started successfully after boot",
                TAG
            )
        } catch (e: SecurityException) {
            MotiumApplication.logger.e(
                "❌ SecurityException: Cannot start service - ${e.message}",
                TAG,
                e
            )
            // On Android 12+ this might happen if FGS restrictions apply
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MotiumApplication.logger.w(
                    "Foreground service start blocked by Android 12+ restrictions",
                    TAG
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Failed to start ActivityRecognitionService: ${e.message}",
                TAG,
                e
            )
        }
    }
}

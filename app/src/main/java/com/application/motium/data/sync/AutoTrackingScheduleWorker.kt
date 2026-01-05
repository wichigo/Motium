package com.application.motium.data.sync

import android.content.Context
import androidx.work.*
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.domain.model.TrackingMode
import com.application.motium.service.ActivityRecognitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker qui vérifie périodiquement si l'utilisateur est dans ses horaires professionnels
 * et active/désactive automatiquement l'auto-tracking en conséquence.
 *
 * Ce worker ne s'exécute que si le mode est WORK_HOURS_ONLY.
 */
class AutoTrackingScheduleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val workScheduleRepository = WorkScheduleRepository.getInstance(context)
    private val tripRepository = TripRepository.getInstance(context)
    private val authRepository = SupabaseAuthRepository.getInstance(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.d("AutoTrackingScheduleWorker: Checking tracking mode", "AutoTrackingScheduleWorker")

            // Récupérer l'utilisateur actuel (utiliser localUserRepository pour le bon users.id compatible RLS)
            val userId = localUserRepository.getLoggedInUser()?.id
            if (userId == null) {
                MotiumApplication.logger.w("No user logged in, skipping check", "AutoTrackingScheduleWorker")
                return@withContext Result.success()
            }

            // Vérifier le mode d'auto-tracking
            val settings = workScheduleRepository.getAutoTrackingSettings(userId)
            val trackingMode = settings?.trackingMode ?: TrackingMode.DISABLED
            val currentlyEnabled = tripRepository.isAutoTrackingEnabled()

            when (trackingMode) {
                TrackingMode.ALWAYS -> {
                    // Mode toujours actif: s'assurer que le service tourne
                    if (!currentlyEnabled) {
                        tripRepository.setAutoTrackingEnabled(true)
                        withContext(Dispatchers.Main) {
                            ActivityRecognitionService.startService(applicationContext)
                        }
                        MotiumApplication.logger.i(
                            "✅ ALWAYS mode: Auto-tracking re-enabled",
                            "AutoTrackingScheduleWorker"
                        )
                    }
                }
                TrackingMode.WORK_HOURS_ONLY -> {
                    // Mode horaires pro: vérifier si on est dans les horaires (offline-first)
                    val inWorkHours = workScheduleRepository.isInWorkHoursOfflineFirst(userId)

                    if (currentlyEnabled != inWorkHours) {
                        tripRepository.setAutoTrackingEnabled(inWorkHours)
                        withContext(Dispatchers.Main) {
                            if (inWorkHours) {
                                ActivityRecognitionService.startService(applicationContext)
                                MotiumApplication.logger.i(
                                    "✅ PRO mode: Auto-tracking enabled (in work hours)",
                                    "AutoTrackingScheduleWorker"
                                )
                            } else {
                                ActivityRecognitionService.stopService(applicationContext)
                                MotiumApplication.logger.i(
                                    "✅ PRO mode: Auto-tracking disabled (outside work hours)",
                                    "AutoTrackingScheduleWorker"
                                )
                            }
                        }
                    } else {
                        MotiumApplication.logger.d(
                            "PRO mode: Already ${if (inWorkHours) "enabled" else "disabled"}, no change",
                            "AutoTrackingScheduleWorker"
                        )
                    }
                }
                TrackingMode.DISABLED -> {
                    // Mode désactivé: s'assurer que le service est arrêté
                    if (currentlyEnabled) {
                        tripRepository.setAutoTrackingEnabled(false)
                        withContext(Dispatchers.Main) {
                            ActivityRecognitionService.stopService(applicationContext)
                        }
                        MotiumApplication.logger.i(
                            "✅ DISABLED mode: Auto-tracking stopped",
                            "AutoTrackingScheduleWorker"
                        )
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error in AutoTrackingScheduleWorker: ${e.message}", "AutoTrackingScheduleWorker", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_TAG = "auto_tracking_schedule"

        /**
         * Démarre la vérification périodique des horaires (toutes les 15 minutes)
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<AutoTrackingScheduleWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval de 5 minutes
            )
                .setConstraints(
                    Constraints.Builder()
                        // BATTERY OPTIMIZATION: Ne pas exécuter si batterie faible
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag(WORK_TAG)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    5, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP, // Garder le worker existant si déjà programmé
                workRequest
            )

            MotiumApplication.logger.i("AutoTrackingScheduleWorker scheduled (every 15 minutes)", "AutoTrackingScheduleWorker")
        }

        /**
         * Arrête la vérification périodique
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG)
            MotiumApplication.logger.i("AutoTrackingScheduleWorker cancelled", "AutoTrackingScheduleWorker")
        }

        /**
         * Force une vérification immédiate
         */
        fun runNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<AutoTrackingScheduleWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            MotiumApplication.logger.d("AutoTrackingScheduleWorker triggered immediately", "AutoTrackingScheduleWorker")
        }
    }
}

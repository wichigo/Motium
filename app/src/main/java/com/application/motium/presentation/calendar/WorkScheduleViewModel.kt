package com.application.motium.presentation.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.data.sync.AutoTrackingScheduleWorker
import com.application.motium.domain.model.*
import com.application.motium.service.ActivityRecognitionService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.util.*

class WorkScheduleViewModel(
    private val context: Context,
    private val workScheduleRepository: WorkScheduleRepository = WorkScheduleRepository.getInstance(context),
    private val tripRepository: TripRepository = TripRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
) : ViewModel() {

    // État des créneaux horaires par jour de la semaine (1-7 ISO format)
    private val _schedules = MutableStateFlow<Map<Int, List<TimeSlot>>>(emptyMap())
    val schedules: StateFlow<Map<Int, List<TimeSlot>>> = _schedules.asStateFlow()

    // Mode d'auto-tracking actuel - initialisé depuis le cache local pour éviter le flash
    private val _trackingMode = MutableStateFlow(tripRepository.getTrackingMode())
    val trackingMode: StateFlow<TrackingMode> = _trackingMode.asStateFlow()

    // État de chargement
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Erreur
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // User ID actuel
    private val _userId = MutableStateFlow<String?>(null)

    init {
        // Observer les changements d'authentification
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                val newUserId = authState.user?.id
                if (_userId.value != newUserId) {
                    _userId.value = newUserId
                    if (newUserId != null) {
                        loadWorkSchedules(newUserId)
                        loadAutoTrackingSettings(newUserId)
                    } else {
                        // User logged out, clear data
                        _schedules.value = emptyMap()
                        _trackingMode.value = TrackingMode.DISABLED
                        tripRepository.setTrackingMode(TrackingMode.DISABLED)
                    }
                }
            }
        }
    }

    /**
     * Charge les créneaux horaires depuis Supabase
     */
    fun loadWorkSchedules(userId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                MotiumApplication.logger.i("Loading work schedules for user: $userId", "WorkScheduleViewModel")

                val workSchedules = workScheduleRepository.getWorkSchedules(userId)

                // Grouper par jour de la semaine et convertir en TimeSlot
                val schedulesMap = workSchedules
                    .groupBy { it.dayOfWeek }
                    .mapValues { (_, schedules) ->
                        schedules.map { it.toTimeSlot() }
                    }

                _schedules.value = schedulesMap
                MotiumApplication.logger.i("Loaded ${workSchedules.size} work schedules", "WorkScheduleViewModel")
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading work schedules: ${e.message}", "WorkScheduleViewModel", e)
                _error.value = "Failed to load work schedules: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Charge les paramètres d'auto-tracking depuis Supabase
     */
    fun loadAutoTrackingSettings(userId: String) {
        viewModelScope.launch {
            try {
                MotiumApplication.logger.i("Loading auto-tracking settings for user: $userId", "WorkScheduleViewModel")

                val settings = workScheduleRepository.getAutoTrackingSettings(userId)

                if (settings != null) {
                    _trackingMode.value = settings.trackingMode
                    // Mettre à jour le cache local avec la valeur Supabase (source de vérité)
                    tripRepository.setTrackingMode(settings.trackingMode)
                    MotiumApplication.logger.i("Auto-tracking mode loaded: ${settings.trackingMode}", "WorkScheduleViewModel")

                    // Gérer le mode au démarrage de l'app
                    when (settings.trackingMode) {
                        TrackingMode.ALWAYS -> {
                            // Mode toujours actif: s'assurer que le service tourne
                            tripRepository.setAutoTrackingEnabled(true)
                            ActivityRecognitionService.startService(context)
                            MotiumApplication.logger.i("ALWAYS mode: Auto-tracking service started", "WorkScheduleViewModel")
                        }
                        TrackingMode.WORK_HOURS_ONLY -> {
                            // Mode horaires pro: démarrer le worker
                            AutoTrackingScheduleWorker.schedule(context)
                            AutoTrackingScheduleWorker.runNow(context)
                            MotiumApplication.logger.i("WORK_HOURS_ONLY mode: Worker started", "WorkScheduleViewModel")
                        }
                        TrackingMode.DISABLED -> {
                            // Mode désactivé: ne rien faire
                            MotiumApplication.logger.i("DISABLED mode: Manual control", "WorkScheduleViewModel")
                        }
                    }
                } else {
                    // Pas de settings, garder la valeur du cache local (ou DISABLED si pas de cache)
                    MotiumApplication.logger.i("No auto-tracking settings found in Supabase, keeping local cache value: ${_trackingMode.value}", "WorkScheduleViewModel")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading auto-tracking settings: ${e.message}", "WorkScheduleViewModel", e)
                _error.value = "Failed to load auto-tracking settings: ${e.message}"
            }
        }
    }

    /**
     * Ajoute un nouveau créneau horaire
     */
    fun addWorkSchedule(userId: String, dayOfWeek: Int, timeSlot: TimeSlot) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                MotiumApplication.logger.i("Adding work schedule for day $dayOfWeek", "WorkScheduleViewModel")

                val workSchedule = WorkSchedule(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    dayOfWeek = dayOfWeek,
                    startHour = timeSlot.startHour,
                    startMinute = timeSlot.startMinute,
                    endHour = timeSlot.endHour,
                    endMinute = timeSlot.endMinute,
                    isActive = true,
                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                )

                val success = workScheduleRepository.saveWorkSchedule(workSchedule)

                if (success) {
                    // Recharger les créneaux
                    loadWorkSchedules(userId)
                    MotiumApplication.logger.i("✅ Work schedule added successfully", "WorkScheduleViewModel")
                } else {
                    _error.value = "Failed to add work schedule"
                    MotiumApplication.logger.e("❌ Failed to add work schedule", "WorkScheduleViewModel")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error adding work schedule: ${e.message}", "WorkScheduleViewModel", e)
                _error.value = "Error adding work schedule: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Met à jour un créneau horaire existant
     */
    fun updateWorkSchedule(userId: String, dayOfWeek: Int, timeSlot: TimeSlot) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                MotiumApplication.logger.i("Updating work schedule ${timeSlot.id}", "WorkScheduleViewModel")

                val workSchedule = WorkSchedule(
                    id = timeSlot.id,
                    userId = userId,
                    dayOfWeek = dayOfWeek,
                    startHour = timeSlot.startHour,
                    startMinute = timeSlot.startMinute,
                    endHour = timeSlot.endHour,
                    endMinute = timeSlot.endMinute,
                    isActive = true,
                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()), // Will be ignored by update
                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                )

                val success = workScheduleRepository.updateWorkSchedule(workSchedule)

                if (success) {
                    // Recharger les créneaux
                    loadWorkSchedules(userId)
                    MotiumApplication.logger.i("✅ Work schedule updated successfully", "WorkScheduleViewModel")
                } else {
                    _error.value = "Failed to update work schedule"
                    MotiumApplication.logger.e("❌ Failed to update work schedule", "WorkScheduleViewModel")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error updating work schedule: ${e.message}", "WorkScheduleViewModel", e)
                _error.value = "Error updating work schedule: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Supprime un créneau horaire
     */
    fun deleteWorkSchedule(scheduleId: String, userId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                MotiumApplication.logger.i("Deleting work schedule $scheduleId", "WorkScheduleViewModel")

                val success = workScheduleRepository.deleteWorkSchedule(scheduleId)

                if (success) {
                    // Recharger les créneaux
                    loadWorkSchedules(userId)
                    MotiumApplication.logger.i("✅ Work schedule deleted successfully", "WorkScheduleViewModel")

                    // Vérifier si tous les horaires ont été supprimés
                    checkAndDisableWorkHoursMode(userId)
                } else {
                    _error.value = "Failed to delete work schedule"
                    MotiumApplication.logger.e("❌ Failed to delete work schedule", "WorkScheduleViewModel")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error deleting work schedule: ${e.message}", "WorkScheduleViewModel", e)
                _error.value = "Error deleting work schedule: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Vérifie s'il reste des horaires et désactive WORK_HOURS_ONLY si nécessaire
     */
    private fun checkAndDisableWorkHoursMode(userId: String) {
        viewModelScope.launch {
            try {
                val hasSchedules = _schedules.value.values.any { it.isNotEmpty() }

                if (!hasSchedules && _trackingMode.value == TrackingMode.WORK_HOURS_ONLY) {
                    MotiumApplication.logger.w("⚠️ No schedules remaining, disabling WORK_HOURS_ONLY mode", "WorkScheduleViewModel")
                    updateTrackingMode(userId, TrackingMode.DISABLED)
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error checking work hours mode: ${e.message}", "WorkScheduleViewModel", e)
            }
        }
    }

    /**
     * Change le mode d'auto-tracking
     */
    fun updateTrackingMode(userId: String, newMode: TrackingMode) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                MotiumApplication.logger.i("Updating tracking mode to: $newMode", "WorkScheduleViewModel")

                // Vérification : si mode WORK_HOURS_ONLY, il faut au moins un horaire défini
                if (newMode == TrackingMode.WORK_HOURS_ONLY) {
                    val hasSchedules = _schedules.value.values.any { it.isNotEmpty() }
                    if (!hasSchedules) {
                        _error.value = "Cannot enable work hours tracking: no work schedules defined"
                        MotiumApplication.logger.w("⚠️ Cannot enable WORK_HOURS_ONLY mode without schedules", "WorkScheduleViewModel")
                        _isLoading.value = false
                        return@launch
                    }
                }

                // Charger les settings existants pour réutiliser l'ID s'il existe
                val existingSettings = workScheduleRepository.getAutoTrackingSettings(userId)

                val settings = AutoTrackingSettings(
                    id = existingSettings?.id ?: UUID.randomUUID().toString(),
                    userId = userId,
                    trackingMode = newMode,
                    minTripDistanceMeters = existingSettings?.minTripDistanceMeters ?: 100,
                    minTripDurationSeconds = existingSettings?.minTripDurationSeconds ?: 60,
                    createdAt = existingSettings?.createdAt ?: Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                )

                val success = workScheduleRepository.saveAutoTrackingSettings(settings)

                if (success) {
                    _trackingMode.value = newMode
                    // Mettre à jour le cache local
                    tripRepository.setTrackingMode(newMode)

                    // Synchroniser avec TripRepository pour compatibilité
                    syncWithTripRepository(newMode)

                    MotiumApplication.logger.i("✅ Tracking mode updated successfully", "WorkScheduleViewModel")
                } else {
                    _error.value = "Failed to update tracking mode"
                    MotiumApplication.logger.e("❌ Failed to update tracking mode", "WorkScheduleViewModel")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error updating tracking mode: ${e.message}", "WorkScheduleViewModel", e)
                _error.value = "Error updating tracking mode: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Synchronise avec TripRepository et déclenche la vérification des horaires si nécessaire
     */
    private fun syncWithTripRepository(mode: TrackingMode) {
        when (mode) {
            TrackingMode.ALWAYS -> {
                // Mode toujours actif: activer le tracking en permanence, arrêter le worker
                AutoTrackingScheduleWorker.cancel(context)
                tripRepository.setAutoTrackingEnabled(true)
                ActivityRecognitionService.startService(context)

                MotiumApplication.logger.i(
                    "✅ ALWAYS mode activated: Auto-tracking permanently enabled",
                    "WorkScheduleViewModel"
                )
            }
            TrackingMode.WORK_HOURS_ONLY -> {
                // Mode automatique: démarrer le worker qui vérifie périodiquement les horaires
                AutoTrackingScheduleWorker.schedule(context)

                // Vérifier immédiatement si on est dans les horaires
                AutoTrackingScheduleWorker.runNow(context)

                MotiumApplication.logger.i(
                    "✅ WORK_HOURS_ONLY mode activated: Auto-tracking managed by work schedule",
                    "WorkScheduleViewModel"
                )
            }
            TrackingMode.DISABLED -> {
                // Mode désactivé: arrêter le worker, arrêter le service
                AutoTrackingScheduleWorker.cancel(context)
                tripRepository.setAutoTrackingEnabled(false)
                ActivityRecognitionService.stopService(context)

                MotiumApplication.logger.i(
                    "✅ DISABLED mode activated: User has manual control",
                    "WorkScheduleViewModel"
                )
            }
        }
    }

    /**
     * Vérifie si l'utilisateur est dans ses horaires pro maintenant
     */
    fun isInWorkHours(userId: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val inWorkHours = workScheduleRepository.isInWorkHours(userId)
                callback(inWorkHours)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error checking work hours: ${e.message}", "WorkScheduleViewModel", e)
                callback(false)
            }
        }
    }

    /**
     * Vérifie si l'auto-tracking doit être actif maintenant
     */
    fun shouldAutotrack(userId: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val shouldTrack = workScheduleRepository.shouldAutotrack(userId)
                callback(shouldTrack)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error checking should autotrack: ${e.message}", "WorkScheduleViewModel", e)
                callback(false)
            }
        }
    }

    /**
     * Clear l'erreur
     */
    fun clearError() {
        _error.value = null
    }
}

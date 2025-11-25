package com.application.motium.presentation.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.domain.model.*
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

    // Mode d'auto-tracking actuel
    private val _trackingMode = MutableStateFlow(TrackingMode.DISABLED)
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
                    MotiumApplication.logger.i("Auto-tracking mode loaded: ${settings.trackingMode}", "WorkScheduleViewModel")

                    // Synchroniser avec TripRepository pour compatibilité
                    syncWithTripRepository(settings.trackingMode)
                } else {
                    // Pas de settings, créer des settings par défaut
                    MotiumApplication.logger.i("No auto-tracking settings found, creating default", "WorkScheduleViewModel")
                    _trackingMode.value = TrackingMode.DISABLED
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
     * Change le mode d'auto-tracking
     */
    fun updateTrackingMode(userId: String, newMode: TrackingMode) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                MotiumApplication.logger.i("Updating tracking mode to: $newMode", "WorkScheduleViewModel")

                val settings = AutoTrackingSettings(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    trackingMode = newMode,
                    minTripDistanceMeters = 100,
                    minTripDurationSeconds = 60,
                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                )

                val success = workScheduleRepository.saveAutoTrackingSettings(settings)

                if (success) {
                    _trackingMode.value = newMode

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
     * Synchronise avec TripRepository pour compatibilité avec le bouton de la Home Page
     */
    private fun syncWithTripRepository(mode: TrackingMode) {
        // Convertir TrackingMode en boolean pour TripRepository
        val enabled = mode != TrackingMode.DISABLED
        tripRepository.setAutoTrackingEnabled(enabled)

        MotiumApplication.logger.d("Synced tracking mode with TripRepository: enabled=$enabled", "WorkScheduleViewModel")
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

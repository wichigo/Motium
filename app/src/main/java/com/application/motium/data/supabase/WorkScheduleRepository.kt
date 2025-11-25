package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.WorkSchedule
import com.application.motium.domain.model.AutoTrackingSettings
import com.application.motium.domain.model.TrackingMode
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class WorkScheduleRepository private constructor(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest

    @Serializable
    data class WorkScheduleDto(
        val id: String? = null,
        val user_id: String,
        val day_of_week: Int,
        val start_hour: Int,
        val start_minute: Int,
        val end_hour: Int,
        val end_minute: Int,
        val is_active: Boolean = true,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    @Serializable
    data class AutoTrackingSettingsDto(
        val id: String? = null,
        val user_id: String,
        val tracking_mode: String,
        val min_trip_distance_meters: Int = 100,
        val min_trip_duration_seconds: Int = 60,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    @Serializable
    data class RpcUserIdParam(
        val p_user_id: String
    )

    companion object {
        @Volatile
        private var instance: WorkScheduleRepository? = null

        fun getInstance(context: Context): WorkScheduleRepository {
            return instance ?: synchronized(this) {
                instance ?: WorkScheduleRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Récupère tous les créneaux horaires d'un utilisateur
     */
    suspend fun getWorkSchedules(userId: String): List<WorkSchedule> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching work schedules for user: $userId", "WorkScheduleRepository")

            val response = postgres.from("work_schedules")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeList<WorkScheduleDto>()

            val schedules = response.map { it.toWorkSchedule() }
            MotiumApplication.logger.i("Loaded ${schedules.size} work schedules", "WorkScheduleRepository")
            schedules
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading work schedules: ${e.message}", "WorkScheduleRepository", e)
            emptyList()
        }
    }

    /**
     * Récupère les créneaux d'un jour spécifique
     */
    suspend fun getWorkSchedulesForDay(userId: String, dayOfWeek: Int): List<WorkSchedule> = withContext(Dispatchers.IO) {
        try {
            val response = postgres.from("work_schedules")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("day_of_week", dayOfWeek)
                    }
                }.decodeList<WorkScheduleDto>()

            response.map { it.toWorkSchedule() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading schedules for day $dayOfWeek: ${e.message}", "WorkScheduleRepository", e)
            emptyList()
        }
    }

    /**
     * Sauvegarde un nouveau créneau horaire
     */
    suspend fun saveWorkSchedule(schedule: WorkSchedule): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Saving work schedule for day ${schedule.dayOfWeek}", "WorkScheduleRepository")

            val dto = WorkScheduleDto(
                user_id = schedule.userId,
                day_of_week = schedule.dayOfWeek,
                start_hour = schedule.startHour,
                start_minute = schedule.startMinute,
                end_hour = schedule.endHour,
                end_minute = schedule.endMinute,
                is_active = schedule.isActive
            )

            postgres.from("work_schedules").insert(dto)
            MotiumApplication.logger.i("✅ Work schedule saved successfully", "WorkScheduleRepository")
            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error saving work schedule: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Met à jour un créneau existant
     */
    suspend fun updateWorkSchedule(schedule: WorkSchedule): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating work schedule ${schedule.id}", "WorkScheduleRepository")

            val dto = WorkScheduleDto(
                user_id = schedule.userId,
                day_of_week = schedule.dayOfWeek,
                start_hour = schedule.startHour,
                start_minute = schedule.startMinute,
                end_hour = schedule.endHour,
                end_minute = schedule.endMinute,
                is_active = schedule.isActive
            )

            postgres.from("work_schedules")
                .update(dto) {
                    filter {
                        eq("id", schedule.id)
                    }
                }

            MotiumApplication.logger.i("✅ Work schedule updated successfully", "WorkScheduleRepository")
            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error updating work schedule: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Supprime un créneau horaire
     */
    suspend fun deleteWorkSchedule(scheduleId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting work schedule $scheduleId", "WorkScheduleRepository")

            postgres.from("work_schedules")
                .delete {
                    filter {
                        eq("id", scheduleId)
                    }
                }

            MotiumApplication.logger.i("✅ Work schedule deleted successfully", "WorkScheduleRepository")
            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error deleting work schedule: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Récupère les paramètres d'auto-tracking
     */
    suspend fun getAutoTrackingSettings(userId: String): AutoTrackingSettings? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching auto-tracking settings for user: $userId", "WorkScheduleRepository")

            val response = postgres.from("auto_tracking_settings")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeSingleOrNull<AutoTrackingSettingsDto>()

            val settings = response?.toAutoTrackingSettings()
            if (settings != null) {
                MotiumApplication.logger.i("Auto-tracking mode: ${settings.trackingMode}", "WorkScheduleRepository")
            } else {
                MotiumApplication.logger.i("No auto-tracking settings found, using default", "WorkScheduleRepository")
            }
            settings
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading auto-tracking settings: ${e.message}", "WorkScheduleRepository", e)
            null
        }
    }

    /**
     * Sauvegarde les paramètres d'auto-tracking (upsert)
     */
    suspend fun saveAutoTrackingSettings(settings: AutoTrackingSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Saving auto-tracking settings: ${settings.trackingMode} for user ${settings.userId}", "WorkScheduleRepository")

            val dto = AutoTrackingSettingsDto(
                id = settings.id,
                user_id = settings.userId,
                tracking_mode = settings.trackingMode.name,
                min_trip_distance_meters = settings.minTripDistanceMeters,
                min_trip_duration_seconds = settings.minTripDurationSeconds
            )

            postgres.from("auto_tracking_settings")
                .upsert(dto) {
                    // Utiliser user_id comme clé de conflit (contrainte UNIQUE)
                    onConflict = "user_id"
                }

            MotiumApplication.logger.i("✅ Auto-tracking settings saved successfully", "WorkScheduleRepository")
            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error saving auto-tracking settings: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Vérifie si l'utilisateur est actuellement dans ses horaires professionnels
     */
    suspend fun isInWorkHours(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Appeler la fonction PostgreSQL is_in_work_hours
            val result = postgres.rpc("is_in_work_hours", RpcUserIdParam(userId)).decodeSingle<Boolean>()
            MotiumApplication.logger.d("Is in work hours: $result", "WorkScheduleRepository")
            result
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking work hours: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Vérifie si l'auto-tracking doit être actif maintenant
     */
    suspend fun shouldAutotrack(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Appeler la fonction PostgreSQL should_autotrack
            val result = postgres.rpc("should_autotrack", RpcUserIdParam(userId)).decodeSingle<Boolean>()
            MotiumApplication.logger.d("Should autotrack: $result", "WorkScheduleRepository")
            result
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking should autotrack: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    // Extension functions pour conversion DTO <-> Domain Model
    private fun WorkScheduleDto.toWorkSchedule(): WorkSchedule {
        return WorkSchedule(
            id = id ?: "",
            userId = user_id,
            dayOfWeek = day_of_week,
            startHour = start_hour,
            startMinute = start_minute,
            endHour = end_hour,
            endMinute = end_minute,
            isActive = is_active,
            createdAt = created_at?.let { Instant.parse(it) } ?: Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            updatedAt = updated_at?.let { Instant.parse(it) } ?: Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
    }

    private fun AutoTrackingSettingsDto.toAutoTrackingSettings(): AutoTrackingSettings {
        return AutoTrackingSettings(
            id = id ?: "",
            userId = user_id,
            trackingMode = TrackingMode.valueOf(tracking_mode),
            minTripDistanceMeters = min_trip_distance_meters,
            minTripDurationSeconds = min_trip_duration_seconds,
            createdAt = created_at?.let { Instant.parse(it) } ?: Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            updatedAt = updated_at?.let { Instant.parse(it) } ?: Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
    }
}

package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.WorkScheduleDao
import com.application.motium.data.local.entities.AutoTrackingSettingsEntity
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.WorkScheduleEntity
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.WorkSchedule
import com.application.motium.domain.model.AutoTrackingSettings
import com.application.motium.domain.model.TrackingMode
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Repository for work schedules and auto-tracking settings.
 * Uses offline-first architecture: Room cache first, then sync with Supabase.
 */
class WorkScheduleRepository private constructor(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }
    private val rpcCacheManager by lazy { RpcCacheManager.getInstance(context) }
    private val syncManager by lazy { OfflineFirstSyncManager.getInstance(context) }

    // Room database for offline-first caching
    private val database = MotiumDatabase.getInstance(context)
    private val workScheduleDao: WorkScheduleDao = database.workScheduleDao()

    @Serializable
    data class WorkScheduleDto(
        val id: String? = null,
        val user_id: String,
        val day_of_week: Int,
        val start_hour: Int,
        val start_minute: Int,
        val end_hour: Int,
        val end_minute: Int,
        val is_overnight: Boolean = false,
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
     * Récupère tous les créneaux horaires d'un utilisateur depuis le cache local.
     * Utilise Room en priorité (offline-first).
     */
    suspend fun getWorkSchedules(userId: String): List<WorkSchedule> = withContext(Dispatchers.IO) {
        try {
            // Lire depuis le cache Room d'abord (offline-first)
            val cachedSchedules = workScheduleDao.getWorkSchedulesForUser(userId)
            if (cachedSchedules.isNotEmpty()) {
                MotiumApplication.logger.d("Loaded ${cachedSchedules.size} work schedules from Room cache", "WorkScheduleRepository")
                return@withContext cachedSchedules.map { it.toDomainModel() }
            }

            // Si cache vide, essayer de charger depuis Supabase
            MotiumApplication.logger.i("No cached work schedules, fetching from Supabase for user: $userId", "WorkScheduleRepository")
            val schedules = fetchWorkSchedulesFromSupabase(userId)

            // Mettre en cache localement
            if (schedules.isNotEmpty()) {
                val entities = schedules.map { it.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis()) }
                workScheduleDao.insertWorkSchedules(entities)
                MotiumApplication.logger.i("Cached ${schedules.size} work schedules in Room", "WorkScheduleRepository")
            }

            schedules
        } catch (e: CancellationException) {
            // Re-throw cancellation to respect cooperative cancellation
            MotiumApplication.logger.w("Work schedules fetch cancelled (scope left composition)", "WorkScheduleRepository")
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading work schedules: ${e.message}", "WorkScheduleRepository", e)
            // Fallback to local cache even on error
            try {
                workScheduleDao.getWorkSchedulesForUser(userId).map { it.toDomainModel() }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Fetch work schedules directly from Supabase (for sync).
     */
    private suspend fun fetchWorkSchedulesFromSupabase(userId: String): List<WorkSchedule> {
        return try {
            val response = postgres.from("work_schedules")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeList<WorkScheduleDto>()
            response.map { it.toWorkSchedule() }
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "WorkScheduleRepository")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return try {
                        val response = postgres.from("work_schedules")
                            .select {
                                filter {
                                    eq("user_id", userId)
                                }
                            }.decodeList<WorkScheduleDto>()
                        MotiumApplication.logger.i("Work schedules loaded after token refresh", "WorkScheduleRepository")
                        response.map { it.toWorkSchedule() }
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "WorkScheduleRepository", retryError)
                        emptyList()
                    }
                }
            }
            MotiumApplication.logger.e("Error fetching from Supabase: ${e.message}", "WorkScheduleRepository", e)
            emptyList()
        } catch (e: CancellationException) {
            // Re-throw cancellation to respect cooperative cancellation
            MotiumApplication.logger.w("Supabase fetch cancelled (scope left composition)", "WorkScheduleRepository")
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching from Supabase: ${e.message}", "WorkScheduleRepository", e)
            emptyList()
        }
    }

    /**
     * Récupère les créneaux d'un jour spécifique depuis le cache local.
     */
    suspend fun getWorkSchedulesForDay(userId: String, dayOfWeek: Int): List<WorkSchedule> = withContext(Dispatchers.IO) {
        try {
            // Lire depuis le cache Room (offline-first)
            val cachedSchedules = workScheduleDao.getWorkSchedulesForDay(userId, dayOfWeek)
            if (cachedSchedules.isNotEmpty()) {
                return@withContext cachedSchedules.map { it.toDomainModel() }
            }

            // Si cache vide, essayer Supabase
            val response = postgres.from("work_schedules")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("day_of_week", dayOfWeek)
                    }
                }.decodeList<WorkScheduleDto>()

            val schedules = response.map { it.toWorkSchedule() }

            // Mettre en cache
            if (schedules.isNotEmpty()) {
                val entities = schedules.map { it.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis()) }
                workScheduleDao.insertWorkSchedules(entities)
            }

            schedules
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading schedules for day $dayOfWeek: ${e.message}", "WorkScheduleRepository", e)
            // Fallback vers cache local
            try {
                workScheduleDao.getWorkSchedulesForDay(userId, dayOfWeek).map { it.toDomainModel() }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Sauvegarde un nouveau créneau horaire localement et queue pour sync atomique via sync_changes() RPC.
     */
    suspend fun saveWorkSchedule(schedule: WorkSchedule): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Saving work schedule for day ${schedule.dayOfWeek}", "WorkScheduleRepository")

            // Sauvegarder localement avec PENDING_UPLOAD
            val entity = schedule.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.PENDING_UPLOAD.name)
            workScheduleDao.insertWorkSchedule(entity)
            MotiumApplication.logger.i("Work schedule saved locally", "WorkScheduleRepository")

            // Queue pour sync atomique (version=1 for new record)
            val payload = buildWorkSchedulePayload(schedule, version = 1)
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_WORK_SCHEDULE,
                entityId = schedule.id,
                action = PendingOperationEntity.ACTION_CREATE,
                payload = payload,
                priority = 1
            )
            MotiumApplication.logger.i("Work schedule queued for sync: ${schedule.id}", "WorkScheduleRepository")

            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving work schedule: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Met à jour un créneau existant localement et queue pour sync atomique via sync_changes() RPC.
     */
    suspend fun updateWorkSchedule(schedule: WorkSchedule): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating work schedule ${schedule.id}", "WorkScheduleRepository")

            // Read existing version and increment
            val existingEntity = workScheduleDao.getWorkScheduleById(schedule.id)
            val newVersion = (existingEntity?.version ?: 0) + 1

            // Mettre à jour localement avec PENDING_UPLOAD
            val entity = schedule.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.PENDING_UPLOAD.name)
            workScheduleDao.updateWorkSchedule(entity)
            MotiumApplication.logger.i("Work schedule updated locally (version=$newVersion)", "WorkScheduleRepository")

            // Queue pour sync atomique with version
            val payload = buildWorkSchedulePayload(schedule, version = newVersion)
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_WORK_SCHEDULE,
                entityId = schedule.id,
                action = PendingOperationEntity.ACTION_UPDATE,
                payload = payload,
                priority = 1
            )
            MotiumApplication.logger.i("Work schedule update queued for sync: ${schedule.id}", "WorkScheduleRepository")

            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating work schedule: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Supprime un créneau horaire localement et queue la suppression pour sync atomique.
     */
    suspend fun deleteWorkSchedule(scheduleId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting work schedule $scheduleId", "WorkScheduleRepository")

            // Queue l'opération DELETE AVANT la suppression locale
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_WORK_SCHEDULE,
                entityId = scheduleId,
                action = PendingOperationEntity.ACTION_DELETE,
                payload = null,
                priority = 1
            )

            // Supprimer localement
            workScheduleDao.deleteWorkScheduleById(scheduleId)
            MotiumApplication.logger.i("Work schedule deleted and queued for sync: $scheduleId", "WorkScheduleRepository")

            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting work schedule: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    /**
     * Build JSON payload matching push_work_schedule_change() SQL fields.
     */
    private fun buildWorkSchedulePayload(schedule: WorkSchedule, version: Int): String {
        return buildJsonObject {
            put("day_of_week", schedule.dayOfWeek)
            put("start_hour", schedule.startHour)
            put("start_minute", schedule.startMinute)
            put("end_hour", schedule.endHour)
            put("end_minute", schedule.endMinute)
            put("is_active", schedule.isActive)
            put("is_overnight", schedule.isOvernight)
            put("version", version)
        }.toString()
    }

    /**
     * Récupère les paramètres d'auto-tracking depuis le cache local.
     */
    suspend fun getAutoTrackingSettings(userId: String): AutoTrackingSettings? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching auto-tracking settings for user: $userId", "WorkScheduleRepository")

            // Lire depuis le cache Room d'abord (offline-first)
            val cachedSettings = workScheduleDao.getAutoTrackingSettings(userId)
            if (cachedSettings != null) {
                MotiumApplication.logger.d("Loaded auto-tracking settings from Room cache", "WorkScheduleRepository")
                return@withContext cachedSettings.toDomainModel()
            }

            // Si cache vide, essayer Supabase
            val response = postgres.from("auto_tracking_settings")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeSingleOrNull<AutoTrackingSettingsDto>()

            val settings = response?.toAutoTrackingSettings()
            if (settings != null) {
                MotiumApplication.logger.i("Auto-tracking mode: ${settings.trackingMode}", "WorkScheduleRepository")
                // Mettre en cache
                val entity = settings.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis())
                workScheduleDao.insertAutoTrackingSettings(entity)
                MotiumApplication.logger.i("Cached auto-tracking settings in Room", "WorkScheduleRepository")
            } else {
                MotiumApplication.logger.i("No auto-tracking settings found, using default", "WorkScheduleRepository")
            }
            settings
        } catch (e: CancellationException) {
            // Re-throw cancellation to respect cooperative cancellation
            MotiumApplication.logger.w("Auto-tracking settings fetch cancelled (scope left composition)", "WorkScheduleRepository")
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading auto-tracking settings: ${e.message}", "WorkScheduleRepository", e)
            // Fallback vers cache local
            try {
                workScheduleDao.getAutoTrackingSettings(userId)?.toDomainModel()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Sauvegarde les paramètres d'auto-tracking localement et queue pour sync via sync_changes().
     * Utilise le pattern offline-first avec queueOperation() pour garantir la synchronisation.
     */
    suspend fun saveAutoTrackingSettings(settings: AutoTrackingSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Saving auto-tracking settings: ${settings.trackingMode} for user ${settings.userId}", "WorkScheduleRepository")

            // Sauvegarder localement d'abord (offline-first)
            val entity = settings.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.PENDING_UPLOAD.name)
            workScheduleDao.insertAutoTrackingSettings(entity)
            MotiumApplication.logger.i("✅ Auto-tracking settings saved locally", "WorkScheduleRepository")

            // Build payload for server sync (matches sync_changes RPC structure)
            val payload = buildJsonObject {
                put("tracking_mode", settings.trackingMode.name)
                put("min_trip_distance_meters", settings.minTripDistanceMeters)
                put("min_trip_duration_seconds", settings.minTripDurationSeconds)
            }.toString()

            // Queue for sync via sync_changes() RPC
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_AUTO_TRACKING_SETTINGS,
                entityId = settings.userId, // Use userId as entityId since it's unique per user
                action = PendingOperationEntity.ACTION_UPDATE,
                payload = payload,
                priority = 1 // High priority for settings changes
            )

            MotiumApplication.logger.i(
                "✅ Auto-tracking settings queued for sync (payload=$payload)",
                "WorkScheduleRepository"
            )

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
            val response = postgres.rpc("is_in_work_hours", RpcUserIdParam(userId))
            val result = parseRpcBooleanResponse(response.data)
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
            val response = postgres.rpc("should_autotrack", RpcUserIdParam(userId))
            val result = parseRpcBooleanResponse(response.data)
            MotiumApplication.logger.d("Should autotrack: $result", "WorkScheduleRepository")
            result
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking should autotrack: ${e.message}", "WorkScheduleRepository", e)
            false
        }
    }

    // ==================== OFFLINE-FIRST RPC METHODS ====================

    /**
     * Offline-first version of isInWorkHours.
     * Tries RPC first, caches result, falls back to local calculation on failure.
     *
     * @param userId User ID to check
     * @return true if currently in work hours, false otherwise
     */
    suspend fun isInWorkHoursOfflineFirst(userId: String): Boolean = withContext(Dispatchers.IO) {
        rpcCacheManager.withCache(
            key = "is_in_work_hours_$userId",
            ttlMinutes = 15, // Cache for 15 minutes
            rpcCall = {
                // Try RPC
                val response = postgres.rpc("is_in_work_hours", RpcUserIdParam(userId))
                val result = parseRpcBooleanResponse(response.data)
                MotiumApplication.logger.d("RPC is_in_work_hours: $result", "WorkScheduleRepository")
                result
            },
            fallback = {
                // Fallback to local calculation
                calculateIsInWorkHoursLocally(userId)
            }
        )
    }

    /**
     * Offline-first version of shouldAutotrack.
     * Tries RPC first, caches result, falls back to local calculation on failure.
     *
     * @param userId User ID to check
     * @return true if auto-tracking should be active, false otherwise
     */
    suspend fun shouldAutotrackOfflineFirst(userId: String): Boolean = withContext(Dispatchers.IO) {
        rpcCacheManager.withCache(
            key = "should_autotrack_$userId",
            ttlMinutes = 15, // Cache for 15 minutes
            rpcCall = {
                // Try RPC
                val response = postgres.rpc("should_autotrack", RpcUserIdParam(userId))
                val result = parseRpcBooleanResponse(response.data)
                MotiumApplication.logger.d("RPC should_autotrack: $result", "WorkScheduleRepository")
                result
            },
            fallback = {
                // Fallback to local calculation
                calculateShouldAutotrackLocally(userId)
            }
        )
    }

    /**
     * Calculate if user is currently in work hours using local cached schedules.
     * This is the offline fallback for isInWorkHours RPC.
     */
    private suspend fun calculateIsInWorkHoursLocally(userId: String): Boolean {
        try {
            val schedules = workScheduleDao.getWorkSchedulesForUser(userId)
            if (schedules.isEmpty()) {
                MotiumApplication.logger.d("No work schedules found locally, not in work hours", "WorkScheduleRepository")
                return false
            }

            val now = java.util.Calendar.getInstance()
            val currentDayOfWeek = (now.get(java.util.Calendar.DAY_OF_WEEK) - 1) // Convert to 0-6
            val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(java.util.Calendar.MINUTE)

            // Check if any schedule matches current day and time
            val inWorkHours = schedules.any { schedule ->
                if (schedule.dayOfWeek != currentDayOfWeek || !schedule.isActive) {
                    return@any false
                }

                val isInRange = if (schedule.isOvernight) {
                    // Overnight schedule: start time to 23:59 OR 00:00 to end time
                    val afterStart = (currentHour > schedule.startHour) ||
                            (currentHour == schedule.startHour && currentMinute >= schedule.startMinute)
                    val beforeEnd = (currentHour < schedule.endHour) ||
                            (currentHour == schedule.endHour && currentMinute <= schedule.endMinute)
                    afterStart || beforeEnd
                } else {
                    // Normal schedule: start time to end time
                    val afterStart = (currentHour > schedule.startHour) ||
                            (currentHour == schedule.startHour && currentMinute >= schedule.startMinute)
                    val beforeEnd = (currentHour < schedule.endHour) ||
                            (currentHour == schedule.endHour && currentMinute <= schedule.endMinute)
                    afterStart && beforeEnd
                }

                isInRange
            }

            MotiumApplication.logger.d(
                "Local calculation: isInWorkHours=$inWorkHours (day=$currentDayOfWeek, time=$currentHour:$currentMinute)",
                "WorkScheduleRepository"
            )
            return inWorkHours
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error calculating work hours locally: ${e.message}", "WorkScheduleRepository", e)
            return false
        }
    }

    /**
     * Calculate if auto-tracking should be active using local cached settings and schedules.
     * This is the offline fallback for shouldAutotrack RPC.
     */
    private suspend fun calculateShouldAutotrackLocally(userId: String): Boolean {
        try {
            // Get auto-tracking settings from cache
            val settings = workScheduleDao.getAutoTrackingSettings(userId)
            if (settings == null) {
                MotiumApplication.logger.d("No auto-tracking settings found locally, defaulting to false", "WorkScheduleRepository")
                return false
            }

            val shouldTrack = when (settings.trackingMode) {
                "ALWAYS" -> {
                    MotiumApplication.logger.d("Local: ALWAYS mode, should track", "WorkScheduleRepository")
                    true
                }
                "WORK_HOURS_ONLY" -> {
                    val inWorkHours = calculateIsInWorkHoursLocally(userId)
                    MotiumApplication.logger.d("Local: WORK_HOURS_ONLY mode, in work hours=$inWorkHours", "WorkScheduleRepository")
                    inWorkHours
                }
                "DISABLED" -> {
                    MotiumApplication.logger.d("Local: DISABLED mode, should not track", "WorkScheduleRepository")
                    false
                }
                else -> {
                    MotiumApplication.logger.w("Unknown tracking mode: ${settings.trackingMode}, defaulting to false", "WorkScheduleRepository")
                    false
                }
            }

            return shouldTrack
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error calculating should autotrack locally: ${e.message}", "WorkScheduleRepository", e)
            return false
        }
    }

    /**
     * Parse robuste des réponses RPC booléennes
     * Gère différents formats: true, "true", 1, "1", etc.
     */
    private fun parseRpcBooleanResponse(data: Any?): Boolean {
        return when (data) {
            is Boolean -> data
            is Number -> data.toInt() != 0
            is String -> {
                val cleaned = data.trim().lowercase()
                when (cleaned) {
                    "true", "t", "yes", "y", "1" -> true
                    "false", "f", "no", "n", "0" -> false
                    else -> {
                        // Essayer de parser comme JSON
                        try {
                            cleaned.toBoolean()
                        } catch (e: Exception) {
                            MotiumApplication.logger.w("Unable to parse RPC response as boolean: $data, defaulting to false", "WorkScheduleRepository")
                            false
                        }
                    }
                }
            }
            null -> false
            else -> {
                MotiumApplication.logger.w("Unexpected RPC response type: ${data::class.simpleName}, defaulting to false", "WorkScheduleRepository")
                false
            }
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
            isOvernight = is_overnight,
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

    // ==================== Sync Methods ====================

    /**
     * Synchronise les données depuis Supabase vers le cache local.
     */
    suspend fun syncFromSupabase(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Starting sync from Supabase for user: $userId", "WorkScheduleRepository")

            // Sync work schedules
            val schedules = fetchWorkSchedulesFromSupabase(userId)
            if (schedules.isNotEmpty()) {
                val entities = schedules.map { it.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis()) }
                workScheduleDao.insertWorkSchedules(entities)
                MotiumApplication.logger.i("Synced ${schedules.size} work schedules from Supabase", "WorkScheduleRepository")
            }

            // Sync auto-tracking settings
            try {
                val response = postgres.from("auto_tracking_settings")
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }.decodeSingleOrNull<AutoTrackingSettingsDto>()

                response?.toAutoTrackingSettings()?.let { settings ->
                    val entity = settings.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis())
                    workScheduleDao.insertAutoTrackingSettings(entity)
                    MotiumApplication.logger.i("Synced auto-tracking settings from Supabase", "WorkScheduleRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.w("Could not sync auto-tracking settings: ${e.message}", "WorkScheduleRepository")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing from Supabase: ${e.message}", "WorkScheduleRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Synchronise les données locales vers Supabase.
     */
    suspend fun syncToSupabase(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Starting sync to Supabase for user: $userId", "WorkScheduleRepository")

            // Sync work schedules that need sync
            val schedulesNeedingSync = workScheduleDao.getWorkSchedulesNeedingSync(userId)
            for (entity in schedulesNeedingSync) {
                try {
                    val dto = WorkScheduleDto(
                        id = entity.id.ifEmpty { null },
                        user_id = entity.userId,
                        day_of_week = entity.dayOfWeek,
                        start_hour = entity.startHour,
                        start_minute = entity.startMinute,
                        end_hour = entity.endHour,
                        end_minute = entity.endMinute,
                        is_overnight = entity.isOvernight,
                        is_active = entity.isActive
                    )

                    postgres.from("work_schedules")
                        .upsert(dto) {
                            onConflict = "id"
                        }

                    workScheduleDao.markWorkScheduleAsSynced(entity.id, System.currentTimeMillis())
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to sync work schedule ${entity.id}: ${e.message}", "WorkScheduleRepository")
                }
            }

            // Sync auto-tracking settings if needed
            val settingsNeedingSync = workScheduleDao.getAutoTrackingSettingsNeedingSync(userId)
            if (settingsNeedingSync != null) {
                try {
                    val dto = AutoTrackingSettingsDto(
                        id = settingsNeedingSync.id.ifEmpty { null },
                        user_id = settingsNeedingSync.userId,
                        tracking_mode = settingsNeedingSync.trackingMode,
                        min_trip_distance_meters = settingsNeedingSync.minTripDistanceMeters,
                        min_trip_duration_seconds = settingsNeedingSync.minTripDurationSeconds
                    )

                    postgres.from("auto_tracking_settings")
                        .upsert(dto) {
                            onConflict = "user_id"
                        }

                    workScheduleDao.markAutoTrackingSettingsAsSynced(userId, System.currentTimeMillis())
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to sync auto-tracking settings: ${e.message}", "WorkScheduleRepository")
                }
            }

            MotiumApplication.logger.i("Sync to Supabase completed", "WorkScheduleRepository")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing to Supabase: ${e.message}", "WorkScheduleRepository", e)
            Result.failure(e)
        }
    }
}

package com.application.motium.data.local.dao

import androidx.room.*
import com.application.motium.data.local.entities.AutoTrackingSettingsEntity
import com.application.motium.data.local.entities.WorkScheduleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for work schedule and auto-tracking settings operations.
 * Provides methods to interact with the work_schedules and auto_tracking_settings tables.
 */
@Dao
interface WorkScheduleDao {

    // ==================== Work Schedules ====================

    /**
     * Insert or replace a work schedule in the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkSchedule(schedule: WorkScheduleEntity)

    /**
     * Insert multiple work schedules at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkSchedules(schedules: List<WorkScheduleEntity>)

    /**
     * Update an existing work schedule.
     */
    @Update
    suspend fun updateWorkSchedule(schedule: WorkScheduleEntity)

    /**
     * Delete a work schedule.
     */
    @Delete
    suspend fun deleteWorkSchedule(schedule: WorkScheduleEntity)

    /**
     * Delete a work schedule by ID.
     */
    @Query("DELETE FROM work_schedules WHERE id = :scheduleId")
    suspend fun deleteWorkScheduleById(scheduleId: String)

    /**
     * Get all work schedules for a user.
     */
    @Query("SELECT * FROM work_schedules WHERE userId = :userId ORDER BY dayOfWeek, startHour, startMinute")
    suspend fun getWorkSchedulesForUser(userId: String): List<WorkScheduleEntity>

    /**
     * Get work schedules for a user as Flow.
     */
    @Query("SELECT * FROM work_schedules WHERE userId = :userId ORDER BY dayOfWeek, startHour, startMinute")
    fun getWorkSchedulesForUserFlow(userId: String): Flow<List<WorkScheduleEntity>>

    /**
     * Get work schedules for a specific day.
     */
    @Query("SELECT * FROM work_schedules WHERE userId = :userId AND dayOfWeek = :dayOfWeek AND isActive = 1 ORDER BY startHour, startMinute")
    suspend fun getWorkSchedulesForDay(userId: String, dayOfWeek: Int): List<WorkScheduleEntity>

    /**
     * Get a specific work schedule by ID.
     */
    @Query("SELECT * FROM work_schedules WHERE id = :scheduleId")
    suspend fun getWorkScheduleById(scheduleId: String): WorkScheduleEntity?

    /**
     * Get work schedules that need to be synced.
     */
    @Query("SELECT * FROM work_schedules WHERE userId = :userId AND syncStatus != 'SYNCED'")
    suspend fun getWorkSchedulesNeedingSync(userId: String): List<WorkScheduleEntity>

    /**
     * Mark a work schedule as synced.
     */
    @Query("UPDATE work_schedules SET syncStatus = 'SYNCED', serverUpdatedAt = :timestamp WHERE id = :scheduleId")
    suspend fun markWorkScheduleAsSynced(scheduleId: String, timestamp: Long)

    /**
     * Delete all work schedules for a day.
     */
    @Query("DELETE FROM work_schedules WHERE userId = :userId AND dayOfWeek = :dayOfWeek")
    suspend fun deleteWorkSchedulesForDay(userId: String, dayOfWeek: Int)

    /**
     * Delete all work schedules for a user.
     */
    @Query("DELETE FROM work_schedules WHERE userId = :userId")
    suspend fun deleteAllWorkSchedulesForUser(userId: String)

    /**
     * Delete all work schedules.
     */
    @Query("DELETE FROM work_schedules")
    suspend fun deleteAllWorkSchedules()

    // ==================== Auto-Tracking Settings ====================

    /**
     * Insert or replace auto-tracking settings.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutoTrackingSettings(settings: AutoTrackingSettingsEntity)

    /**
     * Replace auto-tracking settings for a user (ensures single row per user).
     */
    @Transaction
    suspend fun replaceAutoTrackingSettingsForUser(userId: String, settings: AutoTrackingSettingsEntity) {
        deleteAutoTrackingSettingsForUser(userId)
        insertAutoTrackingSettings(settings)
    }

    /**
     * Get auto-tracking settings for a user.
     */
    @Query("SELECT * FROM auto_tracking_settings WHERE userId = :userId LIMIT 1")
    suspend fun getAutoTrackingSettings(userId: String): AutoTrackingSettingsEntity?

    /**
     * Get auto-tracking settings as Flow.
     */
    @Query("SELECT * FROM auto_tracking_settings WHERE userId = :userId LIMIT 1")
    fun getAutoTrackingSettingsFlow(userId: String): Flow<AutoTrackingSettingsEntity?>

    /**
     * Update tracking mode.
     */
    @Query("UPDATE auto_tracking_settings SET trackingMode = :mode, updatedAt = :updatedAt, syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :localUpdatedAt WHERE userId = :userId")
    suspend fun updateTrackingMode(userId: String, mode: String, updatedAt: String, localUpdatedAt: Long = System.currentTimeMillis())

    /**
     * Get auto-tracking settings that need sync.
     */
    @Query("SELECT * FROM auto_tracking_settings WHERE userId = :userId AND syncStatus != 'SYNCED'")
    suspend fun getAutoTrackingSettingsNeedingSync(userId: String): AutoTrackingSettingsEntity?

    /**
     * Mark auto-tracking settings as synced.
     */
    @Query("UPDATE auto_tracking_settings SET syncStatus = 'SYNCED', serverUpdatedAt = :timestamp WHERE userId = :userId")
    suspend fun markAutoTrackingSettingsAsSynced(userId: String, timestamp: Long)

    /**
     * Delete auto-tracking settings for a user.
     */
    @Query("DELETE FROM auto_tracking_settings WHERE userId = :userId")
    suspend fun deleteAutoTrackingSettingsForUser(userId: String)

    /**
     * Delete all auto-tracking settings.
     */
    @Query("DELETE FROM auto_tracking_settings")
    suspend fun deleteAllAutoTrackingSettings()
}

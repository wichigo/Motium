package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.AutoTrackingSettings
import com.application.motium.domain.model.TrackingMode
import com.application.motium.domain.model.toTrackingModeOrDefault
import com.application.motium.domain.model.WorkSchedule
import kotlinx.datetime.Instant

/**
 * Room entity for storing work schedule data locally.
 * Allows offline work schedule management and automatic sync when connection is restored.
 */
@Entity(
    tableName = "work_schedules",
    indices = [
        androidx.room.Index(value = ["syncStatus"]),
        androidx.room.Index(value = ["userId"])
    ]
)
data class WorkScheduleEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val dayOfWeek: Int,            // 1-7 (ISO 8601: 1=Lundi, 7=Dimanche)
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val isOvernight: Boolean,
    val isActive: Boolean,
    val createdAt: String,         // Instant stored as ISO-8601 string
    val updatedAt: String,         // Instant stored as ISO-8601 string
    // ==================== OFFLINE-FIRST SYNC FIELDS ====================
    val syncStatus: String = SyncStatus.SYNCED.name, // SyncStatus enum as String
    val localUpdatedAt: Long = System.currentTimeMillis(), // Local modification timestamp
    val serverUpdatedAt: Long? = null, // Server's updated_at (from Supabase)
    val version: Int = 1,          // Optimistic locking version
    val deletedAt: Long? = null    // Soft delete timestamp (null = not deleted)
)

/**
 * Extension function to convert WorkScheduleEntity to domain WorkSchedule model
 */
fun WorkScheduleEntity.toDomainModel(): WorkSchedule {
    return WorkSchedule(
        id = id,
        userId = userId,
        dayOfWeek = dayOfWeek,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        isOvernight = isOvernight,
        isActive = isActive,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain WorkSchedule to WorkScheduleEntity
 */
fun WorkSchedule.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): WorkScheduleEntity {
    return WorkScheduleEntity(
        id = id,
        userId = userId,
        dayOfWeek = dayOfWeek,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        isOvernight = isOvernight,
        isActive = isActive,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        syncStatus = syncStatus,
        localUpdatedAt = localUpdatedAt,
        serverUpdatedAt = serverUpdatedAt,
        version = version
    )
}

/**
 * Room entity for storing auto-tracking settings locally.
 */
@Entity(
    tableName = "auto_tracking_settings",
    indices = [
        androidx.room.Index(value = ["syncStatus"]),
        androidx.room.Index(value = ["userId"])
    ]
)
data class AutoTrackingSettingsEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val trackingMode: String,      // TrackingMode enum stored as String
    val minTripDistanceMeters: Int,
    val minTripDurationSeconds: Int,
    val createdAt: String,
    val updatedAt: String,
    // ==================== OFFLINE-FIRST SYNC FIELDS ====================
    val syncStatus: String = SyncStatus.SYNCED.name, // SyncStatus enum as String
    val localUpdatedAt: Long = System.currentTimeMillis(), // Local modification timestamp
    val serverUpdatedAt: Long? = null, // Server's updated_at (from Supabase)
    val version: Int = 1,          // Optimistic locking version
    val deletedAt: Long? = null    // Soft delete timestamp (null = not deleted)
)

/**
 * Extension function to convert AutoTrackingSettingsEntity to domain model
 */
fun AutoTrackingSettingsEntity.toDomainModel(): AutoTrackingSettings {
    return AutoTrackingSettings(
        id = id,
        userId = userId,
        trackingMode = trackingMode.toTrackingModeOrDefault(),
        minTripDistanceMeters = minTripDistanceMeters,
        minTripDurationSeconds = minTripDurationSeconds,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain AutoTrackingSettings to entity
 */
fun AutoTrackingSettings.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): AutoTrackingSettingsEntity {
    return AutoTrackingSettingsEntity(
        id = id,
        userId = userId,
        trackingMode = trackingMode.name,
        minTripDistanceMeters = minTripDistanceMeters,
        minTripDurationSeconds = minTripDurationSeconds,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        syncStatus = syncStatus,
        localUpdatedAt = localUpdatedAt,
        serverUpdatedAt = serverUpdatedAt,
        version = version
    )
}

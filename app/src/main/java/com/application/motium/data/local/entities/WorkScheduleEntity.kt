package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.AutoTrackingSettings
import com.application.motium.domain.model.TrackingMode
import com.application.motium.domain.model.WorkSchedule
import kotlinx.datetime.Instant

/**
 * Room entity for storing work schedule data locally.
 * Allows offline work schedule management and automatic sync when connection is restored.
 */
@Entity(tableName = "work_schedules")
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
    val lastSyncedAt: Long?,       // Timestamp of last sync with Supabase
    val needsSync: Boolean         // Flag indicating if schedule needs to be synced
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
fun WorkSchedule.toEntity(lastSyncedAt: Long? = null, needsSync: Boolean = false): WorkScheduleEntity {
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
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

/**
 * Room entity for storing auto-tracking settings locally.
 */
@Entity(tableName = "auto_tracking_settings")
data class AutoTrackingSettingsEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val trackingMode: String,      // TrackingMode enum stored as String
    val minTripDistanceMeters: Int,
    val minTripDurationSeconds: Int,
    val createdAt: String,
    val updatedAt: String,
    val lastSyncedAt: Long?,
    val needsSync: Boolean
)

/**
 * Extension function to convert AutoTrackingSettingsEntity to domain model
 */
fun AutoTrackingSettingsEntity.toDomainModel(): AutoTrackingSettings {
    return AutoTrackingSettings(
        id = id,
        userId = userId,
        trackingMode = TrackingMode.valueOf(trackingMode),
        minTripDistanceMeters = minTripDistanceMeters,
        minTripDurationSeconds = minTripDurationSeconds,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain AutoTrackingSettings to entity
 */
fun AutoTrackingSettings.toEntity(lastSyncedAt: Long? = null, needsSync: Boolean = false): AutoTrackingSettingsEntity {
    return AutoTrackingSettingsEntity(
        id = id,
        userId = userId,
        trackingMode = trackingMode.name,
        minTripDistanceMeters = minTripDistanceMeters,
        minTripDurationSeconds = minTripDurationSeconds,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

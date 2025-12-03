package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.application.motium.data.TripLocation
import com.application.motium.domain.model.TripType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room entity for storing trip data locally.
 * Allows offline trip viewing and automatic sync when connection is restored.
 */
@Entity(tableName = "trips")
@TypeConverters(TripConverters::class)
data class TripEntity(
    @PrimaryKey
    val id: String,
    val userId: String, // Foreign key to user
    val startTime: Long,
    val endTime: Long?,
    val locations: List<TripLocation>, // Stored as JSON string
    val totalDistance: Double,
    val isValidated: Boolean,
    val vehicleId: String?,
    val startAddress: String?,
    val endAddress: String?,
    val notes: String?,
    val tripType: String?, // TripType enum stored as String
    val createdAt: Long,
    val updatedAt: Long,
    val lastSyncedAt: Long?, // Timestamp of last sync with Supabase
    val needsSync: Boolean // Flag indicating if trip needs to be synced
)

/**
 * Type converters for complex types in TripEntity
 */
class TripConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromLocationList(value: List<TripLocation>): String {
        return json.encodeToString(value)
    }

    @TypeConverter
    fun toLocationList(value: String): List<TripLocation> {
        return json.decodeFromString(value)
    }
}

/**
 * Extension function to convert TripEntity to data Trip model
 */
fun TripEntity.toDataModel(): com.application.motium.data.Trip {
    return com.application.motium.data.Trip(
        id = id,
        startTime = startTime,
        endTime = endTime,
        locations = locations,
        totalDistance = totalDistance,
        isValidated = isValidated,
        vehicleId = vehicleId,
        startAddress = startAddress,
        endAddress = endAddress,
        notes = notes,
        tripType = tripType,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

/**
 * Extension function to convert data Trip to TripEntity
 */
fun com.application.motium.data.Trip.toEntity(userId: String): TripEntity {
    return TripEntity(
        id = id,
        userId = userId,
        startTime = startTime,
        endTime = endTime,
        locations = locations,
        totalDistance = totalDistance,
        isValidated = isValidated,
        vehicleId = vehicleId,
        startAddress = startAddress,
        endAddress = endAddress,
        notes = notes,
        tripType = tripType,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

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
@Entity(
    tableName = "trips",
    indices = [
        androidx.room.Index(value = ["syncStatus"]),
        androidx.room.Index(value = ["userId", "startTime"])
    ]
)
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
    val reimbursementAmount: Double?, // Stored mileage reimbursement calculated at save time
    val isWorkHomeTrip: Boolean = false, // Trajet travail-maison (perso uniquement, donne droit aux indemnités)
    val createdAt: Long,
    val updatedAt: Long,
    val matchedRouteCoordinates: String? = null, // CACHE: Map-matched route coordinates as JSON [[lon,lat],...]
    // ==================== OFFLINE-FIRST SYNC FIELDS ====================
    val syncStatus: String = SyncStatus.SYNCED.name, // SyncStatus enum as String
    val localUpdatedAt: Long = System.currentTimeMillis(), // Local modification timestamp
    val serverUpdatedAt: Long? = null, // Server's updated_at (from Supabase)
    val version: Int = 1, // Optimistic locking version
    val deletedAt: Long? = null // Soft delete timestamp (null = not deleted)
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
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = userId,
        matchedRouteCoordinates = matchedRouteCoordinates
    )
}

/**
 * Extension function to convert data Trip to TripEntity
 */
fun com.application.motium.data.Trip.toEntity(
    userId: String,
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): TripEntity {
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
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
        createdAt = createdAt,
        updatedAt = updatedAt,
        matchedRouteCoordinates = matchedRouteCoordinates,
        syncStatus = syncStatus,
        localUpdatedAt = localUpdatedAt,
        serverUpdatedAt = serverUpdatedAt,
        version = version
    )
}

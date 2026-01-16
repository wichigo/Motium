package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.*
import kotlinx.datetime.Instant

/**
 * Room entity for storing vehicle data locally.
 * Allows offline vehicle management and automatic sync when connection is restored.
 */
@Entity(
    tableName = "vehicles",
    indices = [
        androidx.room.Index(value = ["syncStatus"]),
        androidx.room.Index(value = ["userId"])
    ]
)
data class VehicleEntity(
    @PrimaryKey
    val id: String,
    val userId: String, // Foreign key to user
    val name: String,
    val type: String, // VehicleType enum stored as String
    val licensePlate: String?,
    val power: String?, // VehiclePower enum stored as String
    val fuelType: String?, // FuelType enum stored as String
    val mileageRate: Double,
    val isDefault: Boolean,
    val totalMileagePerso: Double,
    val totalMileagePro: Double,
    val totalMileageWorkHome: Double, // Distance travail-maison
    val createdAt: String, // Instant stored as ISO-8601 string
    val updatedAt: String, // Instant stored as ISO-8601 string
    // ==================== OFFLINE-FIRST SYNC FIELDS ====================
    val syncStatus: String = SyncStatus.SYNCED.name, // SyncStatus enum as String
    val localUpdatedAt: Long = System.currentTimeMillis(), // Local modification timestamp
    val serverUpdatedAt: Long? = null, // Server's updated_at (from Supabase)
    val version: Int = 1, // Optimistic locking version
    val deletedAt: Long? = null // Soft delete timestamp (null = not deleted)
)

/**
 * Extension function to convert VehicleEntity to domain Vehicle model
 */
fun VehicleEntity.toDomainModel(): Vehicle {
    return Vehicle(
        id = id,
        userId = userId,
        name = name,
        type = VehicleType.valueOf(type),
        licensePlate = licensePlate,
        power = power?.let { parseVehiclePower(it) },
        fuelType = fuelType?.let { parseFuelType(it) },
        mileageRate = mileageRate,
        isDefault = isDefault,
        totalMileagePerso = totalMileagePerso,
        totalMileagePro = totalMileagePro,
        totalMileageWorkHome = totalMileageWorkHome,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Safe parser for VehiclePower that handles both:
 * - Kotlin enum names: CV_3, CV_4, CV_5, CV_6, CV_7_PLUS
 * - Supabase/DB values: 3CV, 4CV, 5CV, 6CV, 7CV+
 */
private fun parseVehiclePower(value: String): VehiclePower? {
    // Try direct enum name first (Kotlin format)
    return try {
        VehiclePower.valueOf(value)
    } catch (e: IllegalArgumentException) {
        // Try to match by cv property (Supabase format: 3CV, 4CV, 5CV, 6CV, 7CV+)
        VehiclePower.entries.find { it.cv == value }
    }
}

/**
 * Safe parser for FuelType that handles both:
 * - Kotlin enum names: GASOLINE, DIESEL, ELECTRIC, HYBRID, OTHER
 * - Supabase/DB values (if different)
 */
private fun parseFuelType(value: String): FuelType? {
    return try {
        FuelType.valueOf(value)
    } catch (e: IllegalArgumentException) {
        // Try case-insensitive match
        FuelType.entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Extension function to convert domain Vehicle to VehicleEntity
 */
fun Vehicle.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): VehicleEntity {
    return VehicleEntity(
        id = id,
        userId = userId,
        name = name,
        type = type.name,
        licensePlate = licensePlate,
        power = power?.name,
        fuelType = fuelType?.name,
        mileageRate = mileageRate,
        isDefault = isDefault,
        totalMileagePerso = totalMileagePerso,
        totalMileagePro = totalMileagePro,
        totalMileageWorkHome = totalMileageWorkHome,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        syncStatus = syncStatus,
        localUpdatedAt = localUpdatedAt,
        serverUpdatedAt = serverUpdatedAt,
        version = version
    )
}

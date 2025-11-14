package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.*
import kotlinx.datetime.Instant

/**
 * Room entity for storing vehicle data locally.
 * Allows offline vehicle management and automatic sync when connection is restored.
 */
@Entity(tableName = "vehicles")
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
    val createdAt: String, // Instant stored as ISO-8601 string
    val updatedAt: String, // Instant stored as ISO-8601 string
    val lastSyncedAt: Long?, // Timestamp of last sync with Supabase
    val needsSync: Boolean // Flag indicating if vehicle needs to be synced
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
        power = power?.let { VehiclePower.valueOf(it) },
        fuelType = fuelType?.let { FuelType.valueOf(it) },
        mileageRate = mileageRate,
        isDefault = isDefault,
        totalMileagePerso = totalMileagePerso,
        totalMileagePro = totalMileagePro,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain Vehicle to VehicleEntity
 */
fun Vehicle.toEntity(lastSyncedAt: Long? = null, needsSync: Boolean = false): VehicleEntity {
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
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

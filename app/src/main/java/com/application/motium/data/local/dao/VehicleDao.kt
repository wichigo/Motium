package com.application.motium.data.local.dao

import androidx.room.*
import com.application.motium.data.local.entities.VehicleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for vehicle operations.
 * Provides methods to interact with the vehicles table in Room database.
 */
@Dao
interface VehicleDao {

    /**
     * Insert or replace a vehicle in the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleEntity)

    /**
     * Insert multiple vehicles at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicles(vehicles: List<VehicleEntity>)

    /**
     * Update an existing vehicle.
     */
    @Update
    suspend fun updateVehicle(vehicle: VehicleEntity)

    /**
     * Delete a vehicle.
     */
    @Delete
    suspend fun deleteVehicle(vehicle: VehicleEntity)

    /**
     * Delete a vehicle by ID.
     */
    @Query("DELETE FROM vehicles WHERE id = :vehicleId")
    suspend fun deleteVehicleById(vehicleId: String)

    /**
     * Get all vehicles for a user.
     * Returns Flow for reactive updates.
     */
    @Query("SELECT * FROM vehicles WHERE userId = :userId ORDER BY isDefault DESC, name ASC")
    fun getVehiclesForUserFlow(userId: String): Flow<List<VehicleEntity>>

    /**
     * Get all vehicles for a user (one-time fetch).
     */
    @Query("SELECT * FROM vehicles WHERE userId = :userId ORDER BY isDefault DESC, name ASC")
    suspend fun getVehiclesForUser(userId: String): List<VehicleEntity>

    /**
     * Get a specific vehicle by ID.
     */
    @Query("SELECT * FROM vehicles WHERE id = :vehicleId")
    suspend fun getVehicleById(vehicleId: String): VehicleEntity?

    /**
     * Get the default vehicle for a user.
     */
    @Query("SELECT * FROM vehicles WHERE userId = :userId AND isDefault = 1 LIMIT 1")
    suspend fun getDefaultVehicle(userId: String): VehicleEntity?

    /**
     * Get vehicles that need to be synced to Supabase.
     */
    @Query("SELECT * FROM vehicles WHERE userId = :userId AND syncStatus != 'SYNCED'")
    suspend fun getVehiclesNeedingSync(userId: String): List<VehicleEntity>

    /**
     * Mark a vehicle as synced.
     */
    @Query("UPDATE vehicles SET syncStatus = 'SYNCED', serverUpdatedAt = :timestamp WHERE id = :vehicleId")
    suspend fun markVehicleAsSynced(vehicleId: String, timestamp: Long)

    /**
     * Mark a vehicle as needing sync and increment version for optimistic locking.
     */
    @Query("UPDATE vehicles SET syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :timestamp, version = version + 1 WHERE id = :vehicleId")
    suspend fun markVehicleAsNeedingSync(vehicleId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Unset all default vehicles for a user (before setting a new default).
     */
    @Query("UPDATE vehicles SET isDefault = 0 WHERE userId = :userId")
    suspend fun unsetAllDefaultVehicles(userId: String)

    /**
     * Set a vehicle as default.
     */
    @Query("UPDATE vehicles SET isDefault = 1 WHERE id = :vehicleId")
    suspend fun setVehicleAsDefault(vehicleId: String)

    /**
     * Delete all vehicles for a user.
     */
    @Query("DELETE FROM vehicles WHERE userId = :userId")
    suspend fun deleteAllVehiclesForUser(userId: String)

    /**
     * Delete all vehicles (used during complete logout).
     */
    @Query("DELETE FROM vehicles")
    suspend fun deleteAllVehicles()

    /**
     * Update vehicle mileage (personal, professional, and work-home).
     */
    @Query("UPDATE vehicles SET totalMileagePerso = :perso, totalMileagePro = :pro, totalMileageWorkHome = :workHome WHERE id = :vehicleId")
    suspend fun updateVehicleMileage(vehicleId: String, perso: Double, pro: Double, workHome: Double)

    /**
     * Get vehicles with CONFLICT status that require user resolution.
     * These are vehicles where both local and server had changes that couldn't be auto-merged.
     */
    @Query("SELECT * FROM vehicles WHERE userId = :userId AND syncStatus = 'CONFLICT' ORDER BY name ASC")
    fun getConflictVehiclesFlow(userId: String): Flow<List<VehicleEntity>>

    /**
     * Get count of vehicles with CONFLICT status.
     */
    @Query("SELECT COUNT(*) FROM vehicles WHERE userId = :userId AND syncStatus = 'CONFLICT'")
    fun getConflictVehiclesCountFlow(userId: String): Flow<Int>

    /**
     * Resolve a conflict by accepting the local version.
     * Marks the vehicle as PENDING_UPLOAD to sync local changes to server.
     */
    @Query("UPDATE vehicles SET syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :timestamp WHERE id = :vehicleId AND syncStatus = 'CONFLICT'")
    suspend fun resolveConflictKeepLocal(vehicleId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Resolve a conflict by accepting the server version.
     * Marks the vehicle as SYNCED (no upload needed).
     */
    @Query("UPDATE vehicles SET syncStatus = 'SYNCED', localUpdatedAt = :timestamp WHERE id = :vehicleId AND syncStatus = 'CONFLICT'")
    suspend fun resolveConflictKeepServer(vehicleId: String, timestamp: Long = System.currentTimeMillis())
}

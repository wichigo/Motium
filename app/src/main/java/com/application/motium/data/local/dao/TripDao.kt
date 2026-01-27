package com.application.motium.data.local.dao

import androidx.room.*
import com.application.motium.data.local.entities.TripEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for trip operations.
 * Provides methods to interact with the trips table in Room database.
 */
@Dao
interface TripDao {

    /**
     * Insert or replace a trip in the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)

    /**
     * Insert multiple trips at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrips(trips: List<TripEntity>)

    /**
     * Update an existing trip.
     */
    @Update
    suspend fun updateTrip(trip: TripEntity)

    /**
     * Delete a trip.
     */
    @Delete
    suspend fun deleteTrip(trip: TripEntity)

    /**
     * Delete a trip by ID.
     */
    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: String)

    /**
     * Get all trips for a user, ordered by start time descending.
     * Returns Flow for reactive updates.
     */
    @Query("SELECT * FROM trips WHERE userId = :userId ORDER BY startTime DESC")
    fun getTripsForUserFlow(userId: String): Flow<List<TripEntity>>

    /**
     * Get all trips for a user (one-time fetch).
     */
    @Query("SELECT * FROM trips WHERE userId = :userId ORDER BY startTime DESC")
    suspend fun getTripsForUser(userId: String): List<TripEntity>

    /**
     * Get a specific trip by ID.
     */
    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: String): TripEntity?

    /**
     * Get trips that need to be synced to Supabase.
     */
    @Query("SELECT * FROM trips WHERE userId = :userId AND syncStatus != 'SYNCED'")
    suspend fun getTripsNeedingSync(userId: String): List<TripEntity>

    /**
     * Mark a trip as synced.
     * Updates syncStatus to SYNCED so download phase won't trigger conflict resolution.
     */
    @Query("UPDATE trips SET syncStatus = 'SYNCED', serverUpdatedAt = :timestamp WHERE id = :tripId")
    suspend fun markTripAsSynced(tripId: String, timestamp: Long)

    /**
     * Mark a trip as needing sync and increment version for optimistic locking.
     * The version increment enables server-side conflict detection.
     */
    @Query("UPDATE trips SET syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :timestamp, version = version + 1 WHERE id = :tripId")
    suspend fun markTripAsNeedingSync(tripId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Mark trip as conflict (requires manual resolution).
     * Used when server version is newer but local has unsaved changes.
     */
    @Query("UPDATE trips SET syncStatus = 'CONFLICT', localUpdatedAt = :timestamp WHERE id = :tripId")
    suspend fun markTripAsConflict(tripId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Reset trip for re-pull after VERSION_CONFLICT resolution.
     * Updates syncStatus, serverUpdatedAt, localUpdatedAt, and version to match server.
     */
    @Query("""
        UPDATE trips
        SET syncStatus = :syncStatus,
            serverUpdatedAt = :serverUpdatedAt,
            localUpdatedAt = :serverUpdatedAt,
            version = :serverVersion
        WHERE id = :tripId
    """)
    suspend fun resetForPull(
        tripId: String,
        syncStatus: String,
        serverUpdatedAt: Long,
        serverVersion: Int
    )

    /**
     * Delete all trips for a user.
     */
    @Query("DELETE FROM trips WHERE userId = :userId")
    suspend fun deleteAllTripsForUser(userId: String)

    /**
     * Delete all trips (used during complete logout).
     */
    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    /**
     * Get validated trips for a user.
     */
    @Query("SELECT * FROM trips WHERE userId = :userId AND isValidated = 1 ORDER BY startTime DESC")
    suspend fun getValidatedTrips(userId: String): List<TripEntity>

    /**
     * Get trip count for a user.
     */
    @Query("SELECT COUNT(*) FROM trips WHERE userId = :userId")
    suspend fun getTripCount(userId: String): Int

    /**
     * Get trips with CONFLICT status that require user resolution.
     * These are trips where both local and server had changes that couldn't be auto-merged.
     */
    @Query("SELECT * FROM trips WHERE userId = :userId AND syncStatus = 'CONFLICT' ORDER BY startTime DESC")
    fun getConflictTripsFlow(userId: String): Flow<List<TripEntity>>

    /**
     * Get count of trips with CONFLICT status.
     */
    @Query("SELECT COUNT(*) FROM trips WHERE userId = :userId AND syncStatus = 'CONFLICT'")
    fun getConflictTripsCountFlow(userId: String): Flow<Int>

    /**
     * Resolve a conflict by accepting the local version.
     * Marks the trip as PENDING_UPLOAD to sync local changes to server.
     */
    @Query("UPDATE trips SET syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :timestamp WHERE id = :tripId AND syncStatus = 'CONFLICT'")
    suspend fun resolveConflictKeepLocal(tripId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Resolve a conflict by accepting the server version.
     * Marks the trip as SYNCED (no upload needed).
     */
    @Query("UPDATE trips SET syncStatus = 'SYNCED', localUpdatedAt = :timestamp WHERE id = :tripId AND syncStatus = 'CONFLICT'")
    suspend fun resolveConflictKeepServer(tripId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Calculate annual mileage for a specific vehicle and trip type.
     * Returns sum of totalDistance in METERS for validated trips from the start of the year.
     * Note: totalDistance is stored in meters, so divide by 1000 to get kilometers.
     *
     * @param vehicleId The vehicle to calculate mileage for
     * @param tripType The type of trip ("PROFESSIONAL" or "PERSONAL")
     * @param startOfYearMillis Timestamp for the start of the current year
     * @return Sum of totalDistance in meters (divide by 1000 for km)
     */
    @Query("""
        SELECT COALESCE(SUM(totalDistance), 0.0)
        FROM trips
        WHERE vehicleId = :vehicleId
        AND isValidated = 1
        AND tripType = :tripType
        AND startTime >= :startOfYearMillis
    """)
    suspend fun getAnnualMileageForVehicle(vehicleId: String, tripType: String, startOfYearMillis: Long): Double

    /**
     * Get all validated trips for a specific vehicle and trip type from start of year.
     * Used for recalculating reimbursements when vehicle bracket changes.
     *
     * @param vehicleId The vehicle to get trips for
     * @param tripType The type of trip ("PROFESSIONAL" or "PERSONAL")
     * @param startOfYearMillis Timestamp for the start of the current year
     * @return List of trips sorted by start time ascending
     */
    @Query("""
        SELECT * FROM trips
        WHERE vehicleId = :vehicleId
        AND isValidated = 1
        AND tripType = :tripType
        AND startTime >= :startOfYearMillis
        ORDER BY startTime ASC
    """)
    suspend fun getTripsForVehicleAndType(vehicleId: String, tripType: String, startOfYearMillis: Long): List<TripEntity>

    /**
     * Get all validated work-home trips for a specific vehicle from start of year.
     * Work-home trips are personal trips that count towards fiscal mileage allowance.
     *
     * @param vehicleId The vehicle to get trips for
     * @param startOfYearMillis Timestamp for the start of the current year
     * @return List of work-home trips sorted by start time ascending
     */
    @Query("""
        SELECT * FROM trips
        WHERE vehicleId = :vehicleId
        AND isValidated = 1
        AND tripType = 'PERSONAL'
        AND isWorkHomeTrip = 1
        AND startTime >= :startOfYearMillis
        ORDER BY startTime ASC
    """)
    suspend fun getWorkHomeTripsForVehicle(vehicleId: String, startOfYearMillis: Long): List<TripEntity>

    /**
     * Calculate total work-home mileage for a vehicle (without daily cap).
     * The daily cap of 80km should be applied in the repository logic.
     *
     * @param vehicleId The vehicle to calculate mileage for
     * @param startOfYearMillis Timestamp for the start of the current year
     * @return Sum of totalDistance in meters (divide by 1000 for km)
     */
    @Query("""
        SELECT COALESCE(SUM(totalDistance), 0.0)
        FROM trips
        WHERE vehicleId = :vehicleId
        AND isValidated = 1
        AND tripType = 'PERSONAL'
        AND isWorkHomeTrip = 1
        AND startTime >= :startOfYearMillis
    """)
    suspend fun getWorkHomeMileageForVehicle(vehicleId: String, startOfYearMillis: Long): Double
}

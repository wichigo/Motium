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
    @Query("SELECT * FROM trips WHERE userId = :userId AND needsSync = 1")
    suspend fun getTripsNeedingSync(userId: String): List<TripEntity>

    /**
     * Mark a trip as synced.
     */
    @Query("UPDATE trips SET needsSync = 0, lastSyncedAt = :timestamp WHERE id = :tripId")
    suspend fun markTripAsSynced(tripId: String, timestamp: Long)

    /**
     * Mark a trip as needing sync.
     */
    @Query("UPDATE trips SET needsSync = 1 WHERE id = :tripId")
    suspend fun markTripAsNeedingSync(tripId: String)

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
}

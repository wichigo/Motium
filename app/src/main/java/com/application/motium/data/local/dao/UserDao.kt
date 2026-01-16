package com.application.motium.data.local.dao

import androidx.room.*
import com.application.motium.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for user operations.
 * Provides methods to interact with the users table in Room database.
 */
@Dao
interface UserDao {

    /**
     * Insert or replace a user in the database.
     * Used when logging in or syncing user data from Supabase.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    /**
     * Update an existing user in the database.
     */
    @Update
    suspend fun updateUser(user: UserEntity)

    /**
     * Get the currently logged-in user (where isLocallyConnected = true).
     * Returns Flow for reactive updates.
     */
    @Query("SELECT * FROM users WHERE isLocallyConnected = 1 LIMIT 1")
    fun getLoggedInUserFlow(): Flow<UserEntity?>

    /**
     * Get the currently logged-in user (one-time fetch).
     */
    @Query("SELECT * FROM users WHERE isLocallyConnected = 1 LIMIT 1")
    suspend fun getLoggedInUser(): UserEntity?

    /**
     * Get a user by ID.
     */
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): UserEntity?

    /**
     * Check if a user is logged in locally.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE isLocallyConnected = 1)")
    suspend fun hasLoggedInUser(): Boolean

    /**
     * Mark all users as not locally connected (logout).
     */
    @Query("UPDATE users SET isLocallyConnected = 0")
    suspend fun logoutAllUsers()

    /**
     * Delete a specific user.
     */
    @Delete
    suspend fun deleteUser(user: UserEntity)

    /**
     * Delete all users from the database.
     * Used during complete logout/app reset.
     */
    @Query("DELETE FROM users")
    suspend fun deleteAllUsers()

    /**
     * Update the last synced timestamp for a user.
     */
    @Query("UPDATE users SET lastSyncedAt = :timestamp WHERE id = :userId")
    suspend fun updateLastSyncedAt(userId: String, timestamp: Long)

    /**
     * Update sync status for offline-first synchronization.
     * Used after successfully uploading user profile to Supabase.
     */
    @Query("""
        UPDATE users
        SET syncStatus = :syncStatus,
            serverUpdatedAt = :serverUpdatedAt,
            localUpdatedAt = :serverUpdatedAt
        WHERE id = :userId
    """)
    suspend fun updateSyncStatus(
        userId: String,
        syncStatus: String,
        serverUpdatedAt: Long
    )
}

package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.application.motium.data.local.entities.LinkedUserEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for linked users (employees linked to Pro accounts).
 * Provides reactive queries via Flow for offline-first access.
 */
@Dao
interface LinkedUserDao {

    /**
     * Get all linked users for a Pro account (reactive Flow).
     * Used by UI to automatically update when data changes.
     */
    @Query("SELECT * FROM linked_users WHERE proAccountId = :proAccountId ORDER BY userName ASC")
    fun getByProAccount(proAccountId: String): Flow<List<LinkedUserEntity>>

    /**
     * Get all linked users for a Pro account (one-shot).
     * Used for checking cache before network request.
     */
    @Query("SELECT * FROM linked_users WHERE proAccountId = :proAccountId ORDER BY userName ASC")
    suspend fun getByProAccountOnce(proAccountId: String): List<LinkedUserEntity>

    /**
     * Get a specific linked user by link ID.
     */
    @Query("SELECT * FROM linked_users WHERE linkId = :linkId")
    suspend fun getByLinkId(linkId: String): LinkedUserEntity?

    /**
     * Get a linked user by user ID.
     */
    @Query("SELECT * FROM linked_users WHERE userId = :userId")
    suspend fun getByUserId(userId: String): LinkedUserEntity?

    /**
     * Count linked users for a Pro account.
     */
    @Query("SELECT COUNT(*) FROM linked_users WHERE proAccountId = :proAccountId")
    suspend fun countByProAccount(proAccountId: String): Int

    /**
     * Insert or update linked users.
     * Uses UPSERT strategy (INSERT OR REPLACE).
     */
    @Upsert
    suspend fun upsert(entity: LinkedUserEntity)

    /**
     * Insert or update multiple linked users.
     */
    @Upsert
    suspend fun upsertAll(entities: List<LinkedUserEntity>)

    /**
     * Delete a linked user by link ID.
     */
    @Query("DELETE FROM linked_users WHERE linkId = :linkId")
    suspend fun deleteByLinkId(linkId: String)

    /**
     * Delete all linked users for a Pro account.
     * Used when refreshing the full list.
     */
    @Query("DELETE FROM linked_users WHERE proAccountId = :proAccountId")
    suspend fun deleteByProAccount(proAccountId: String)

    /**
     * Delete all linked users.
     * Used when user logs out.
     */
    @Query("DELETE FROM linked_users")
    suspend fun deleteAll()

    /**
     * Update sync status for a linked user.
     */
    @Query("UPDATE linked_users SET syncStatus = :status WHERE linkId = :linkId")
    suspend fun updateSyncStatus(linkId: String, status: String)
}

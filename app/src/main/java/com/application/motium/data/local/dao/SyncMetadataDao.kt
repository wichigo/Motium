package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.application.motium.data.local.entities.SyncMetadataEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for sync metadata operations.
 * Tracks last sync timestamps per entity type for delta synchronization.
 */
@Dao
interface SyncMetadataDao {

    // ==================== INSERT/UPDATE ====================

    /**
     * Insert or update sync metadata for an entity type.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SyncMetadataEntity)

    // ==================== QUERY ====================

    /**
     * Get sync metadata for a specific entity type.
     */
    @Query("SELECT * FROM sync_metadata WHERE entityType = :entityType")
    suspend fun getByEntityType(entityType: String): SyncMetadataEntity?

    /**
     * Get the last sync timestamp for an entity type.
     * Returns null if never synced.
     */
    @Query("SELECT lastSyncTimestamp FROM sync_metadata WHERE entityType = :entityType")
    suspend fun getLastSyncTimestamp(entityType: String): Long?

    /**
     * Get all sync metadata entries.
     */
    @Query("SELECT * FROM sync_metadata")
    suspend fun getAll(): List<SyncMetadataEntity>

    /**
     * Get all sync metadata as a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM sync_metadata")
    fun getAllFlow(): Flow<List<SyncMetadataEntity>>

    // ==================== UPDATE ====================

    /**
     * Update the last sync timestamp after successful delta sync.
     * Also clears any error and resets syncInProgress.
     */
    @Query("""
        UPDATE sync_metadata
        SET lastSyncTimestamp = :timestamp,
            syncInProgress = 0,
            lastSyncError = NULL,
            totalSynced = totalSynced + :syncedCount
        WHERE entityType = :entityType
    """)
    suspend fun updateLastSyncTimestamp(entityType: String, timestamp: Long, syncedCount: Int = 0)

    /**
     * Update the last full sync timestamp.
     */
    @Query("""
        UPDATE sync_metadata
        SET lastFullSyncTimestamp = :timestamp,
            lastSyncTimestamp = :timestamp,
            syncInProgress = 0,
            lastSyncError = NULL,
            totalSynced = :totalCount
        WHERE entityType = :entityType
    """)
    suspend fun updateLastFullSyncTimestamp(entityType: String, timestamp: Long, totalCount: Int)

    /**
     * Set sync in progress flag.
     */
    @Query("UPDATE sync_metadata SET syncInProgress = :inProgress WHERE entityType = :entityType")
    suspend fun setSyncInProgress(entityType: String, inProgress: Boolean)

    /**
     * Set sync error.
     */
    @Query("UPDATE sync_metadata SET lastSyncError = :error, syncInProgress = 0 WHERE entityType = :entityType")
    suspend fun setSyncError(entityType: String, error: String?)

    /**
     * Check if a sync is in progress for an entity type.
     */
    @Query("SELECT syncInProgress FROM sync_metadata WHERE entityType = :entityType")
    suspend fun isSyncInProgress(entityType: String): Boolean?

    // ==================== DELETE ====================

    @Query("DELETE FROM sync_metadata WHERE entityType = :entityType")
    suspend fun deleteByEntityType(entityType: String)

    @Query("DELETE FROM sync_metadata")
    suspend fun deleteAll()

    // ==================== INITIALIZATION ====================

    /**
     * Initialize metadata for an entity type if it doesn't exist.
     * Does nothing if already exists.
     */
    @Query("""
        INSERT OR IGNORE INTO sync_metadata
        (entityType, lastSyncTimestamp, lastFullSyncTimestamp, syncInProgress, totalSynced, lastSyncError)
        VALUES (:entityType, 0, 0, 0, 0, NULL)
    """)
    suspend fun initializeIfNotExists(entityType: String)
}

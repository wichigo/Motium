package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.application.motium.data.local.entities.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for pending sync operations.
 * Provides CRUD operations and query methods for the sync queue.
 */
@Dao
interface PendingOperationDao {

    // ==================== INSERT ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(operations: List<PendingOperationEntity>)

    /**
     * Atomically replace any existing operation for the same entity.
     * This prevents race conditions between delete and insert operations.
     *
     * WHY @Transaction is critical here:
     * Without @Transaction, if two coroutines call this method simultaneously for the same entity,
     * the following race condition can occur:
     *   Thread A: deleteByEntity(id, type)  -- deletes existing
     *   Thread B: deleteByEntity(id, type)  -- no-op (already deleted)
     *   Thread A: insert(operationA)        -- inserts A
     *   Thread B: insert(operationB)        -- inserts B (DUPLICATE!)
     *
     * With @Transaction, Room wraps delete+insert in a single database transaction,
     * ensuring atomicity and preventing duplicate operations in the sync queue.
     *
     * @param entityId ID of the entity
     * @param entityType Type of the entity
     * @param operation New operation to insert
     */
    @Transaction
    suspend fun replaceOperation(entityId: String, entityType: String, operation: PendingOperationEntity) {
        deleteByEntity(entityId, entityType)
        insert(operation)
    }

    // ==================== DELETE ====================

    @Delete
    suspend fun delete(operation: PendingOperationEntity)

    @Query("DELETE FROM pending_operations WHERE id = :operationId")
    suspend fun deleteById(operationId: String)

    /**
     * Delete all operations for a specific entity.
     * Useful when the entity is deleted or synced successfully.
     */
    @Query("DELETE FROM pending_operations WHERE entityId = :entityId AND entityType = :entityType")
    suspend fun deleteByEntity(entityId: String, entityType: String)

    /**
     * Delete all operations for a specific entity type.
     * Useful for cleanup or testing.
     */
    @Query("DELETE FROM pending_operations WHERE entityType = :entityType")
    suspend fun deleteByEntityType(entityType: String)

    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()

    // ==================== QUERY ====================

    /**
     * Get all pending operations ordered by priority (desc) then creation time (asc).
     */
    @Query("SELECT * FROM pending_operations ORDER BY priority DESC, createdAt ASC")
    suspend fun getAll(): List<PendingOperationEntity>

    /**
     * Get all pending operations as a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM pending_operations ORDER BY priority DESC, createdAt ASC")
    fun getAllFlow(): Flow<List<PendingOperationEntity>>

    /**
     * Get pending operations for a specific entity type.
     */
    @Query("SELECT * FROM pending_operations WHERE entityType = :entityType ORDER BY createdAt ASC")
    suspend fun getByEntityType(entityType: String): List<PendingOperationEntity>

    /**
     * Get pending operations for a specific entity.
     */
    @Query("SELECT * FROM pending_operations WHERE entityId = :entityId AND entityType = :entityType")
    suspend fun getByEntity(entityId: String, entityType: String): List<PendingOperationEntity>

    /**
     * Get pending operations by entity type and action.
     * Useful for processing specific action types (e.g., REQUEST_UNLINK).
     */
    @Query("SELECT * FROM pending_operations WHERE entityType = :entityType AND action = :action ORDER BY createdAt ASC")
    suspend fun getByEntityTypeAndAction(entityType: String, action: String): List<PendingOperationEntity>

    /**
     * Get the total count of pending operations.
     */
    @Query("SELECT COUNT(*) FROM pending_operations")
    suspend fun getCount(): Int

    /**
     * Get the total count as a Flow for reactive UI updates.
     */
    @Query("SELECT COUNT(*) FROM pending_operations")
    fun getCountFlow(): Flow<Int>

    /**
     * Get count by entity type.
     */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE entityType = :entityType")
    suspend fun getCountByType(entityType: String): Int

    // ==================== RETRY LOGIC ====================

    /**
     * Get operations that are ready for retry.
     * Filters out operations that:
     * - Have exceeded max retry count (5)
     * - Are still within backoff period
     *
     * @param backoffThreshold Minimum timestamp for lastAttemptAt to be considered ready
     * @param batchSize Maximum number of operations to return
     */
    @Query("""
        SELECT * FROM pending_operations
        WHERE retryCount < 5
        AND (lastAttemptAt IS NULL OR lastAttemptAt < :backoffThreshold)
        ORDER BY priority DESC, createdAt ASC
        LIMIT :batchSize
    """)
    suspend fun getOperationsReadyForRetry(
        backoffThreshold: Long,
        batchSize: Int = 50
    ): List<PendingOperationEntity>

    /**
     * Mark an operation as retried with error info.
     */
    @Query("""
        UPDATE pending_operations
        SET retryCount = retryCount + 1,
            lastAttemptAt = :timestamp,
            lastError = :error
        WHERE id = :operationId
    """)
    suspend fun markRetried(operationId: String, timestamp: Long, error: String?)

    /**
     * Reset retry count for an operation (e.g., after network restoration).
     */
    @Query("UPDATE pending_operations SET retryCount = 0, lastAttemptAt = NULL, lastError = NULL WHERE id = :operationId")
    suspend fun resetRetry(operationId: String)

    /**
     * Increment retry count for an operation.
     */
    @Query("UPDATE pending_operations SET retryCount = retryCount + 1, lastAttemptAt = :timestamp WHERE id = :operationId")
    suspend fun incrementRetryCount(operationId: String, timestamp: Long)

    /**
     * Get operations that have failed (exceeded max retries).
     */
    @Query("SELECT * FROM pending_operations WHERE retryCount >= 5 ORDER BY createdAt ASC")
    suspend fun getFailedOperations(): List<PendingOperationEntity>

    /**
     * Get count of failed operations.
     */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE retryCount >= 5")
    suspend fun getFailedCount(): Int

    /**
     * Get count of failed operations as a Flow for reactive UI updates.
     */
    @Query("SELECT COUNT(*) FROM pending_operations WHERE retryCount >= 5")
    fun getFailedCountFlow(): kotlinx.coroutines.flow.Flow<Int>
}

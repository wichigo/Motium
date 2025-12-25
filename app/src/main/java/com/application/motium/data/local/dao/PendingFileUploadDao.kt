package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.application.motium.data.local.entities.PendingFileUploadEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for pending file upload operations.
 * Provides CRUD operations and query methods for the file upload queue.
 */
@Dao
interface PendingFileUploadDao {

    // ==================== INSERT ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(upload: PendingFileUploadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(uploads: List<PendingFileUploadEntity>)

    // ==================== DELETE ====================

    @Delete
    suspend fun delete(upload: PendingFileUploadEntity)

    @Query("DELETE FROM pending_file_uploads WHERE id = :uploadId")
    suspend fun deleteById(uploadId: String)

    /**
     * Delete all uploads for a specific expense.
     * Useful when the expense is deleted or fully synced.
     */
    @Query("DELETE FROM pending_file_uploads WHERE expenseId = :expenseId")
    suspend fun deleteByExpenseId(expenseId: String)

    @Query("DELETE FROM pending_file_uploads")
    suspend fun deleteAll()

    // ==================== QUERY ====================

    /**
     * Get all pending uploads ordered by creation time.
     */
    @Query("SELECT * FROM pending_file_uploads ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingFileUploadEntity>

    /**
     * Get all pending uploads as a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM pending_file_uploads ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PendingFileUploadEntity>>

    /**
     * Get upload by ID.
     */
    @Query("SELECT * FROM pending_file_uploads WHERE id = :uploadId")
    suspend fun getById(uploadId: String): PendingFileUploadEntity?

    /**
     * Get pending upload for a specific expense.
     * Returns null if no pending upload exists.
     */
    @Query("SELECT * FROM pending_file_uploads WHERE expenseId = :expenseId LIMIT 1")
    suspend fun getByExpenseId(expenseId: String): PendingFileUploadEntity?

    /**
     * Get all pending uploads (not yet completed).
     * Used by sync worker to process queued uploads.
     */
    @Query("""
        SELECT * FROM pending_file_uploads
        WHERE status IN ('PENDING', 'FAILED')
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingUploads(): List<PendingFileUploadEntity>

    /**
     * Get uploads that are ready for retry.
     * Filters out uploads that:
     * - Are already completed
     * - Have exceeded max retry count
     * - Are still within backoff period
     *
     * @param backoffThreshold Minimum timestamp for lastAttemptAt to be considered ready
     * @param batchSize Maximum number of uploads to return
     */
    @Query("""
        SELECT * FROM pending_file_uploads
        WHERE status != 'COMPLETED'
        AND retryCount < ${PendingFileUploadEntity.MAX_RETRY_COUNT}
        AND (lastAttemptAt IS NULL OR lastAttemptAt < :backoffThreshold)
        ORDER BY createdAt ASC
        LIMIT :batchSize
    """)
    suspend fun getUploadsReadyForRetry(
        backoffThreshold: Long,
        batchSize: Int = 20
    ): List<PendingFileUploadEntity>

    /**
     * Get count of pending uploads.
     */
    @Query("SELECT COUNT(*) FROM pending_file_uploads WHERE status != 'COMPLETED'")
    suspend fun getPendingCount(): Int

    /**
     * Get count of pending uploads as a Flow for reactive UI updates.
     */
    @Query("SELECT COUNT(*) FROM pending_file_uploads WHERE status != 'COMPLETED'")
    fun getPendingCountFlow(): Flow<Int>

    // ==================== UPDATE STATUS ====================

    /**
     * Update upload status.
     */
    @Query("UPDATE pending_file_uploads SET status = :status WHERE id = :uploadId")
    suspend fun updateStatus(uploadId: String, status: String)

    /**
     * Mark upload as completed with the uploaded URL.
     */
    @Query("""
        UPDATE pending_file_uploads
        SET status = 'COMPLETED',
            uploadedUrl = :uploadedUrl
        WHERE id = :uploadId
    """)
    suspend fun markCompleted(uploadId: String, uploadedUrl: String)

    /**
     * Mark upload as retried with error info.
     */
    @Query("""
        UPDATE pending_file_uploads
        SET retryCount = retryCount + 1,
            lastAttemptAt = :timestamp,
            lastError = :error,
            status = 'FAILED'
        WHERE id = :uploadId
    """)
    suspend fun markRetried(uploadId: String, timestamp: Long, error: String?)

    /**
     * Reset retry count for an upload (e.g., after network restoration).
     */
    @Query("""
        UPDATE pending_file_uploads
        SET retryCount = 0,
            lastAttemptAt = NULL,
            lastError = NULL,
            status = 'PENDING'
        WHERE id = :uploadId
    """)
    suspend fun resetRetry(uploadId: String)

    /**
     * Get uploads that have failed permanently (exceeded max retries).
     */
    @Query("""
        SELECT * FROM pending_file_uploads
        WHERE retryCount >= ${PendingFileUploadEntity.MAX_RETRY_COUNT}
        AND status != 'COMPLETED'
        ORDER BY createdAt ASC
    """)
    suspend fun getFailedUploads(): List<PendingFileUploadEntity>

    /**
     * Get count of permanently failed uploads.
     */
    @Query("""
        SELECT COUNT(*) FROM pending_file_uploads
        WHERE retryCount >= ${PendingFileUploadEntity.MAX_RETRY_COUNT}
        AND status != 'COMPLETED'
    """)
    suspend fun getFailedCount(): Int
}

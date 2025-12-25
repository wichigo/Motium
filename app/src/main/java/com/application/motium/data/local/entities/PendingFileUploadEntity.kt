package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a pending file upload operation for expense receipts.
 * Enables offline-first file uploads - users can save expenses with photos offline,
 * and the photos will be uploaded automatically when network becomes available.
 *
 * This entity stores the local file URI and tracks the upload status separately
 * from the expense entity itself, allowing for independent retry logic.
 */
@Entity(
    tableName = "pending_file_uploads",
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class PendingFileUploadEntity(
    /**
     * Unique identifier for this upload operation (UUID)
     */
    @PrimaryKey
    val id: String,

    /**
     * ID of the expense this file is attached to
     */
    val expenseId: String,

    /**
     * Local file:// URI of the image to upload.
     * Points to a file in app's cache or files directory.
     */
    val localUri: String,

    /**
     * Public URL of the uploaded file (nullable until upload completes).
     * Set after successful upload to Supabase Storage.
     */
    val uploadedUrl: String? = null,

    /**
     * Upload status: PENDING, UPLOADING, COMPLETED, FAILED
     */
    val status: String = STATUS_PENDING,

    /**
     * Timestamp when upload was queued
     */
    val createdAt: Long,

    /**
     * Number of retry attempts made.
     * Operations with retryCount >= MAX_RETRY_COUNT are considered permanently failed.
     */
    val retryCount: Int = 0,

    /**
     * Timestamp of last upload attempt.
     * Used for exponential backoff calculation.
     */
    val lastAttemptAt: Long? = null,

    /**
     * Last error message if upload failed.
     * Useful for debugging and user feedback.
     */
    val lastError: String? = null
) {
    companion object {
        // Status constants
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"

        // Max retry count before operation is considered permanently failed
        const val MAX_RETRY_COUNT = 5

        /**
         * Calculate exponential backoff delay in milliseconds.
         * Formula: min(2^retryCount * 2000ms, 5 minutes)
         */
        fun calculateBackoffMs(retryCount: Int): Long {
            val baseDelayMs = 2000L // 2 seconds
            val maxDelayMs = 5 * 60 * 1000L // 5 minutes
            val exponentialDelay = (1L shl retryCount) * baseDelayMs
            return minOf(exponentialDelay, maxDelayMs)
        }
    }

    /**
     * Check if this upload is ready for retry based on backoff.
     */
    fun isReadyForRetry(): Boolean {
        if (retryCount >= MAX_RETRY_COUNT) return false
        if (status == STATUS_COMPLETED) return false
        val lastAttempt = lastAttemptAt ?: return true
        val backoffMs = calculateBackoffMs(retryCount)
        return System.currentTimeMillis() >= lastAttempt + backoffMs
    }
}

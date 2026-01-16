package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a pending sync operation to be executed when network is available.
 * Stored in Room for persistence across app restarts.
 *
 * This replaces the SharedPreferences-based PendingSyncQueue with a more reliable
 * Room-based implementation that survives app kills and provides query capabilities.
 */
@Entity(
    tableName = "pending_operations",
    indices = [
        Index(value = ["entityType", "entityId"]),
        Index(value = ["createdAt"])
    ]
)
data class PendingOperationEntity(
    /**
     * Unique identifier for this operation (UUID)
     */
    @PrimaryKey
    val id: String,

    /**
     * Type of entity: TRIP, VEHICLE, EXPENSE, USER, WORK_SCHEDULE, COMPANY_LINK
     */
    val entityType: String,

    /**
     * ID of the entity being operated on
     */
    val entityId: String,

    /**
     * Operation type: CREATE, UPDATE, DELETE
     */
    val action: String,

    /**
     * Optional JSON-serialized entity data for CREATE/UPDATE operations.
     * Null for DELETE operations.
     */
    val payload: String? = null,

    /**
     * Timestamp when operation was queued
     */
    val createdAt: Long,

    /**
     * Number of retry attempts made.
     * Operations with retryCount >= 5 are considered failed.
     */
    val retryCount: Int = 0,

    /**
     * Timestamp of last retry attempt.
     * Used for exponential backoff calculation.
     */
    val lastAttemptAt: Long? = null,

    /**
     * Last error message if sync failed.
     * Useful for debugging and user feedback.
     */
    val lastError: String? = null,

    /**
     * Priority for processing order.
     * 0 = normal, higher values = more urgent.
     * DELETE operations typically have higher priority to free resources.
     */
    val priority: Int = 0
) {
    companion object {
        // Entity types
        const val TYPE_TRIP = "TRIP"
        const val TYPE_VEHICLE = "VEHICLE"
        const val TYPE_EXPENSE = "EXPENSE"
        const val TYPE_USER = "USER"
        const val TYPE_WORK_SCHEDULE = "WORK_SCHEDULE"
        const val TYPE_COMPANY_LINK = "COMPANY_LINK"
        const val TYPE_LICENSE = "LICENSE"
        const val TYPE_PRO_ACCOUNT = "PRO_ACCOUNT"
        const val TYPE_STRIPE_SUBSCRIPTION = "STRIPE_SUBSCRIPTION"
        const val TYPE_CONSENT = "CONSENT"

        // Action types
        const val ACTION_CREATE = "CREATE"
        const val ACTION_UPDATE = "UPDATE"
        const val ACTION_DELETE = "DELETE"

        // Max retry count before operation is considered failed
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
     * Check if this operation is ready for retry based on backoff.
     */
    fun isReadyForRetry(): Boolean {
        if (retryCount >= MAX_RETRY_COUNT) return false
        val lastAttempt = lastAttemptAt ?: return true
        val backoffMs = calculateBackoffMs(retryCount)
        return System.currentTimeMillis() >= lastAttempt + backoffMs
    }
}

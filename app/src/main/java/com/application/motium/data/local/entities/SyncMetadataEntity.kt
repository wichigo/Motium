package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores sync metadata per entity type for delta synchronization.
 * Tracks when each entity type was last synchronized to enable incremental updates.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    /**
     * Entity type: TRIP, VEHICLE, EXPENSE, USER, WORK_SCHEDULE, COMPANY_LINK
     */
    @PrimaryKey
    val entityType: String,

    /**
     * Timestamp of last successful delta sync (download from server).
     * Used to query: WHERE updated_at > lastSyncTimestamp
     */
    val lastSyncTimestamp: Long,

    /**
     * Timestamp of last full sync (complete refresh from server).
     * Full syncs are performed periodically or on first login.
     */
    val lastFullSyncTimestamp: Long,

    /**
     * Flag indicating if a sync is currently in progress for this entity type.
     * Prevents concurrent syncs for the same entity type.
     */
    val syncInProgress: Boolean = false,

    /**
     * Statistics: total number of entities synced.
     * Useful for debugging and monitoring.
     */
    val totalSynced: Int = 0,

    /**
     * Last sync error message, if any.
     * Cleared on successful sync.
     */
    val lastSyncError: String? = null
) {
    companion object {
        // Entity types (same as PendingOperationEntity)
        const val TYPE_TRIP = "TRIP"
        const val TYPE_VEHICLE = "VEHICLE"
        const val TYPE_EXPENSE = "EXPENSE"
        const val TYPE_USER = "USER"
        const val TYPE_WORK_SCHEDULE = "WORK_SCHEDULE"
        const val TYPE_AUTO_TRACKING_SETTINGS = "AUTO_TRACKING_SETTINGS"
        const val TYPE_COMPANY_LINK = "COMPANY_LINK"
        const val TYPE_LICENSE = "LICENSE"
        const val TYPE_PRO_ACCOUNT = "PRO_ACCOUNT"
        const val TYPE_STRIPE_SUBSCRIPTION = "STRIPE_SUBSCRIPTION"
        const val TYPE_CONSENT = "CONSENT"

        /**
         * Create initial metadata for a new entity type with timestamp 0 (never synced).
         */
        fun createInitial(entityType: String): SyncMetadataEntity {
            return SyncMetadataEntity(
                entityType = entityType,
                lastSyncTimestamp = 0L,
                lastFullSyncTimestamp = 0L,
                syncInProgress = false,
                totalSynced = 0,
                lastSyncError = null
            )
        }
    }

    /**
     * Check if a full sync is needed (e.g., never synced or too old).
     * @param maxAgeMs Maximum age in milliseconds before full sync is required
     */
    fun needsFullSync(maxAgeMs: Long = 24 * 60 * 60 * 1000L): Boolean {
        if (lastFullSyncTimestamp == 0L) return true
        return System.currentTimeMillis() - lastFullSyncTimestamp > maxAgeMs
    }
}

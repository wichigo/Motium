package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for GDPR consent storage (offline-first).
 * Stores user consent decisions with version tracking and sync status.
 *
 * Synced with Supabase table: user_consents
 */
@Entity(
    tableName = "consents",
    indices = [
        Index(value = ["userId", "consentType"], unique = true),
        Index(value = ["syncStatus"]),
        Index(value = ["userId"])
    ]
)
data class ConsentEntity(
    /**
     * Unique identifier (UUID)
     */
    @PrimaryKey
    val id: String,

    /**
     * User ID this consent belongs to
     */
    val userId: String,

    /**
     * Type of consent: location_tracking, data_collection, company_data_sharing, analytics, marketing
     */
    val consentType: String,

    /**
     * Whether consent is granted (true) or revoked (false)
     */
    val granted: Boolean,

    /**
     * Version of the consent policy (e.g., "1.0")
     */
    val version: Int,

    /**
     * Timestamp when consent was granted (null if never granted)
     */
    val grantedAt: Long?,

    /**
     * Timestamp when consent was revoked (null if never revoked)
     */
    val revokedAt: Long?,

    /**
     * Timestamp when this record was created locally
     */
    val createdAt: Long,

    /**
     * Timestamp when this record was last updated locally
     */
    val updatedAt: Long,

    /**
     * Sync status: SYNCED, PENDING_UPLOAD, PENDING_DELETE, CONFLICT, ERROR
     */
    val syncStatus: String = SyncStatus.SYNCED.name,

    /**
     * Timestamp of last local update (for conflict resolution)
     */
    val localUpdatedAt: Long,

    /**
     * Timestamp of last server update (for delta sync)
     */
    val serverUpdatedAt: Long? = null
)

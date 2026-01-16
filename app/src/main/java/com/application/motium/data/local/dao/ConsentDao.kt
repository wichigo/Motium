package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.application.motium.data.local.entities.ConsentEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for GDPR consent management in Room database.
 * Provides offline-first access to user consent data.
 */
@Dao
interface ConsentDao {

    /**
     * Get all consents for a user as Flow (reactive).
     * UI observes this to react to consent changes.
     */
    @Query("SELECT * FROM consents WHERE userId = :userId ORDER BY consentType ASC")
    fun getConsentsForUser(userId: String): Flow<List<ConsentEntity>>

    /**
     * Get a specific consent by type for a user as Flow.
     */
    @Query("SELECT * FROM consents WHERE userId = :userId AND consentType = :consentType LIMIT 1")
    fun getConsentByType(userId: String, consentType: String): Flow<ConsentEntity?>

    /**
     * Get all consents for a user once (non-reactive, for one-time reads).
     */
    @Query("SELECT * FROM consents WHERE userId = :userId")
    suspend fun getConsentsForUserOnce(userId: String): List<ConsentEntity>

    /**
     * Get a specific consent by type once (non-reactive).
     */
    @Query("SELECT * FROM consents WHERE userId = :userId AND consentType = :consentType LIMIT 1")
    suspend fun getConsentByTypeOnce(userId: String, consentType: String): ConsentEntity?

    /**
     * Upsert a consent (insert or update).
     * Uses @Upsert for automatic conflict resolution.
     */
    @Upsert
    suspend fun upsert(consent: ConsentEntity)

    /**
     * Upsert multiple consents at once.
     */
    @Upsert
    suspend fun upsertAll(consents: List<ConsentEntity>)

    /**
     * Update consent granted status and timestamps.
     * Marks as PENDING_UPLOAD for sync.
     */
    @Query("""
        UPDATE consents
        SET granted = :granted,
            grantedAt = CASE WHEN :granted = 1 THEN :now ELSE grantedAt END,
            revokedAt = CASE WHEN :granted = 0 THEN :now ELSE revokedAt END,
            updatedAt = :now,
            localUpdatedAt = :now,
            syncStatus = 'PENDING_UPLOAD'
        WHERE userId = :userId AND consentType = :consentType
    """)
    suspend fun updateConsent(userId: String, consentType: String, granted: Boolean, now: Long)

    /**
     * Get all consents pending sync.
     * Used by sync worker to upload changes.
     */
    @Query("SELECT * FROM consents WHERE syncStatus != 'SYNCED'")
    suspend fun getPendingSync(): List<ConsentEntity>

    /**
     * Update sync status after successful upload.
     */
    @Query("UPDATE consents SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    /**
     * Mark consent as synced with server timestamp.
     */
    @Query("""
        UPDATE consents
        SET syncStatus = 'SYNCED',
            serverUpdatedAt = :serverUpdatedAt
        WHERE id = :id
    """)
    suspend fun markAsSynced(id: String, serverUpdatedAt: Long)

    /**
     * Delete all consents for a user.
     * Used during logout or account deletion.
     */
    @Query("DELETE FROM consents WHERE userId = :userId")
    suspend fun deleteByUser(userId: String)

    /**
     * Delete all consents.
     * Used during complete database reset.
     */
    @Query("DELETE FROM consents")
    suspend fun deleteAll()

    /**
     * Check if user has granted a specific consent type.
     */
    @Query("SELECT granted FROM consents WHERE userId = :userId AND consentType = :consentType LIMIT 1")
    suspend fun isConsentGranted(userId: String, consentType: String): Boolean?
}

package com.application.motium.data.local.dao

import androidx.room.*
import com.application.motium.data.local.entities.CompanyLinkEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for company link operations.
 * Provides methods to interact with the company_links table.
 */
@Dao
interface CompanyLinkDao {

    // ==================== Insert Operations ====================

    /**
     * Insert or replace a company link in the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompanyLink(link: CompanyLinkEntity)

    /**
     * Insert multiple company links at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompanyLinks(links: List<CompanyLinkEntity>)

    // ==================== Update Operations ====================

    /**
     * Update an existing company link.
     */
    @Update
    suspend fun updateCompanyLink(link: CompanyLinkEntity)

    /**
     * Update sharing preferences for a company link.
     */
    @Query("""
        UPDATE company_links
        SET shareProfessionalTrips = :sharePro,
            sharePersonalTrips = :sharePerso,
            sharePersonalInfo = :shareInfo,
            shareExpenses = :shareExpenses,
            updatedAt = :updatedAt,
            needsSync = 1
        WHERE id = :linkId
    """)
    suspend fun updateSharingPreferences(
        linkId: String,
        sharePro: Boolean,
        sharePerso: Boolean,
        shareInfo: Boolean,
        shareExpenses: Boolean,
        updatedAt: String
    )

    /**
     * Update status for a company link.
     */
    @Query("""
        UPDATE company_links
        SET status = :status,
            unlinkedAt = :unlinkedAt,
            updatedAt = :updatedAt,
            needsSync = 1
        WHERE id = :linkId
    """)
    suspend fun updateStatus(
        linkId: String,
        status: String,
        unlinkedAt: String?,
        updatedAt: String
    )

    // ==================== Delete Operations ====================

    /**
     * Delete a company link by ID.
     */
    @Query("DELETE FROM company_links WHERE id = :linkId")
    suspend fun deleteCompanyLinkById(linkId: String)

    /**
     * Delete all company links for a user.
     */
    @Query("DELETE FROM company_links WHERE userId = :userId")
    suspend fun deleteAllCompanyLinksForUser(userId: String)

    /**
     * Delete all company links.
     */
    @Query("DELETE FROM company_links")
    suspend fun deleteAllCompanyLinks()

    // ==================== Query Operations ====================

    /**
     * Get all company links for a user, ordered by linked date (most recent first).
     */
    @Query("SELECT * FROM company_links WHERE userId = :userId ORDER BY linkedAt DESC")
    suspend fun getCompanyLinksForUser(userId: String): List<CompanyLinkEntity>

    /**
     * Get company links for a user as Flow for reactive updates.
     */
    @Query("SELECT * FROM company_links WHERE userId = :userId ORDER BY linkedAt DESC")
    fun getCompanyLinksForUserFlow(userId: String): Flow<List<CompanyLinkEntity>>

    /**
     * Get only active company links for a user.
     */
    @Query("SELECT * FROM company_links WHERE userId = :userId AND status = 'ACTIVE' ORDER BY linkedAt DESC")
    suspend fun getActiveCompanyLinks(userId: String): List<CompanyLinkEntity>

    /**
     * Get active company links as Flow.
     */
    @Query("SELECT * FROM company_links WHERE userId = :userId AND status = 'ACTIVE' ORDER BY linkedAt DESC")
    fun getActiveCompanyLinksFlow(userId: String): Flow<List<CompanyLinkEntity>>

    /**
     * Get a specific company link by ID.
     */
    @Query("SELECT * FROM company_links WHERE id = :linkId")
    suspend fun getCompanyLinkById(linkId: String): CompanyLinkEntity?

    /**
     * Check if user is linked to a specific Pro account.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM company_links WHERE userId = :userId AND linkedProAccountId = :proAccountId AND status = 'ACTIVE')")
    suspend fun isLinkedToProAccount(userId: String, proAccountId: String): Boolean

    /**
     * Get count of active company links for a user.
     */
    @Query("SELECT COUNT(*) FROM company_links WHERE userId = :userId AND status = 'ACTIVE'")
    suspend fun getActiveLinksCount(userId: String): Int

    // ==================== Sync Operations ====================

    /**
     * Get company links that need to be synced to Supabase.
     */
    @Query("SELECT * FROM company_links WHERE userId = :userId AND needsSync = 1")
    suspend fun getCompanyLinksNeedingSync(userId: String): List<CompanyLinkEntity>

    /**
     * Mark a company link as synced.
     */
    @Query("UPDATE company_links SET needsSync = 0, lastSyncedAt = :timestamp WHERE id = :linkId")
    suspend fun markCompanyLinkAsSynced(linkId: String, timestamp: Long)

    /**
     * Mark multiple company links as synced.
     */
    @Query("UPDATE company_links SET needsSync = 0, lastSyncedAt = :timestamp WHERE id IN (:linkIds)")
    suspend fun markCompanyLinksAsSynced(linkIds: List<String>, timestamp: Long)
}

package com.application.motium.domain.repository

import com.application.motium.domain.model.License
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for offline-first license management.
 * Uses local Room database as source of truth with background sync to Supabase.
 */
interface LicenseRepository {
    /**
     * Get all licenses for a Pro account (reactive Flow).
     */
    fun getLicensesByProAccount(proAccountId: String): Flow<List<License>>

    /**
     * Get active license for a user (if any).
     */
    fun getLicenseForUser(userId: String): Flow<License?>

    /**
     * Get available licenses in the pool (not assigned).
     */
    fun getAvailableLicenses(proAccountId: String): Flow<List<License>>

    /**
     * Check if user has an active license.
     */
    fun isUserLicensed(userId: String): Flow<Boolean>

    /**
     * Assign a license to a user (offline-first with sync).
     */
    suspend fun assignLicense(licenseId: String, userId: String)

    /**
     * Unassign a license from a user (offline-first with sync).
     */
    suspend fun unassignLicense(licenseId: String)

    /**
     * Get a license by ID (once, not reactive).
     */
    suspend fun getLicenseById(licenseId: String): License?

    /**
     * Get licenses pending sync.
     */
    suspend fun getPendingSync(): List<License>

    /**
     * Request to unlink a license (offline-first with sync).
     */
    suspend fun requestUnlink(licenseId: String, proAccountId: String)

    /**
     * Cancel an unlink request (offline-first with sync).
     */
    suspend fun cancelUnlinkRequest(licenseId: String, proAccountId: String)

    /**
     * Cancel a license (offline-first with sync).
     */
    suspend fun cancelLicense(licenseId: String)

    /**
     * Assign a license from the pool to a linked account (offline-first with sync).
     */
    suspend fun assignLicenseToAccount(licenseId: String, proAccountId: String, linkedAccountId: String)

    /**
     * Assign a license to the Pro account owner (offline-first with sync).
     */
    suspend fun assignLicenseToOwner(proAccountId: String, ownerUserId: String)
}

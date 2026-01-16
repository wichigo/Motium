package com.application.motium.data.repository

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.dao.LicenseDao
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.preferences.ProLicenseCache
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.domain.model.License
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Cache-first license manager for offline-resilient Pro license verification.
 *
 * Pattern: Room DB → ProLicenseCache → Network (background refresh)
 *
 * - Reads always return cached data immediately (Room DB first)
 * - Network refresh happens in background (non-blocking)
 * - If offline and cache expired, still returns last known state (graceful degradation)
 *
 * This prevents Pro features from being blocked when offline or network slow.
 */
class LicenseCacheManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LicenseCacheManager"

        @Volatile
        private var instance: LicenseCacheManager? = null

        fun getInstance(context: Context): LicenseCacheManager {
            return instance ?: synchronized(this) {
                instance ?: LicenseCacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val licenseDao: LicenseDao by lazy {
        com.application.motium.data.local.MotiumDatabase.getInstance(context).licenseDao()
    }

    private val proLicenseCache: ProLicenseCache by lazy {
        ProLicenseCache.getInstance(context)
    }

    private val licenseRemoteDataSource: LicenseRemoteDataSource by lazy {
        LicenseRemoteDataSource.getInstance(context)
    }

    // Background scope for non-blocking refreshes
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Get available licenses (not assigned) - cache-first pattern.
     * Returns Room DB data immediately, refreshes in background.
     */
    fun getAvailableLicenses(proAccountId: String): Flow<List<License>> {
        return licenseDao.getAvailableLicenses(proAccountId)
            .map { entities -> entities.map { it.toDomain() } }
            .onStart {
                // Trigger background refresh (non-blocking)
                refreshLicensesInBackground(proAccountId)
            }
    }

    /**
     * Get license for a specific user - cache-first pattern.
     * Returns Room DB data immediately, refreshes in background.
     */
    fun getLicenseForUser(userId: String): Flow<License?> {
        return licenseDao.getActiveLicenseForUser(userId)
            .map { it?.toDomain() }
            .onStart {
                // Trigger background refresh for this user's license
                refreshUserLicenseInBackground(userId)
            }
    }

    /**
     * Get license for a specific account in a Pro account - cache-first pattern.
     * Returns Room DB data immediately, refreshes in background.
     */
    fun getLicenseForAccount(proAccountId: String, accountId: String): Flow<License?> {
        // Query Room DB for license assigned to this account
        return licenseDao.getLicensesByProAccount(proAccountId)
            .map { entities ->
                entities.firstOrNull { it.linkedAccountId == accountId && it.status == "active" }?.toDomain()
            }
            .onStart {
                // Trigger background refresh
                refreshLicensesInBackground(proAccountId)
            }
    }

    /**
     * Get all licenses for a Pro account - cache-first pattern.
     */
    fun getLicensesByProAccount(proAccountId: String): Flow<List<License>> {
        return licenseDao.getLicensesByProAccount(proAccountId)
            .map { entities -> entities.map { it.toDomain() } }
            .onStart {
                refreshLicensesInBackground(proAccountId)
            }
    }

    /**
     * Refresh licenses from network in background (non-blocking).
     * Updates local Room DB if successful, logs error if fails (but doesn't block UI).
     *
     * IMPORTANT: Does NOT overwrite local changes that are pending sync (syncStatus != SYNCED).
     * This prevents race conditions where background refresh overwrites user's recent actions.
     */
    suspend fun refreshLicensesInBackground(proAccountId: String) {
        backgroundScope.launch {
            try {
                MotiumApplication.logger.d("Background refresh: fetching licenses for $proAccountId", TAG)

                val result = licenseRemoteDataSource.getLicenses(proAccountId)
                result.onSuccess { licenses ->
                    var syncedCount = 0
                    var skippedCount = 0

                    // Update local Room DB - but ONLY if no local changes pending
                    for (license in licenses) {
                        val entity = license.toEntity(
                            syncStatus = SyncStatus.SYNCED.name,
                            localUpdatedAt = System.currentTimeMillis(),
                            serverUpdatedAt = System.currentTimeMillis(),
                            version = 1
                        )

                        // Check if local version has pending changes
                        val localLicense = licenseDao.getByIdOnce(license.id)

                        if (localLicense == null || localLicense.syncStatus == SyncStatus.SYNCED.name) {
                            // No local changes pending - safe to update from server
                            licenseDao.upsert(entity)
                            syncedCount++
                        } else {
                            // Local changes pending - skip server update to avoid overwriting
                            MotiumApplication.logger.d(
                                "Skipping server update for license ${license.id} - local changes pending (syncStatus=${localLicense.syncStatus})",
                                TAG
                            )
                            skippedCount++
                        }
                    }

                    MotiumApplication.logger.i(
                        "Background refresh: $syncedCount licenses updated, $skippedCount skipped (pending local changes)",
                        TAG
                    )

                    // Update ProLicenseCache if user has an active license
                    licenses.firstOrNull { it.linkedAccountId != null && it.isActive }?.let { activeLicense ->
                        activeLicense.linkedAccountId?.let { userId ->
                            proLicenseCache.saveLicenseState(
                                userId = userId,
                                isLicensed = true,
                                proAccountId = proAccountId,
                                licenseId = activeLicense.id,
                                licenseStatus = activeLicense.status.name
                            )
                        }
                    }
                }

                result.onFailure { e ->
                    MotiumApplication.logger.w(
                        "Background refresh failed (using cached data): ${e.message}",
                        TAG
                    )
                }
            } catch (e: Exception) {
                MotiumApplication.logger.w(
                    "Background refresh exception (using cached data): ${e.message}",
                    TAG
                )
            }
        }
    }

    /**
     * Refresh license for a specific user in background.
     */
    private suspend fun refreshUserLicenseInBackground(userId: String) {
        backgroundScope.launch {
            try {
                // We need to know the proAccountId to fetch licenses
                // For now, we'll fetch from local DB and update if needed
                val cachedLicense = licenseDao.getActiveLicenseForUser(userId)

                // If we have a cached license, try to refresh all licenses for that Pro account
                // The Flow will automatically update when the DB changes
                MotiumApplication.logger.d("User license check: userId=$userId", TAG)
            } catch (e: Exception) {
                MotiumApplication.logger.w(
                    "User license refresh exception (using cached data): ${e.message}",
                    TAG
                )
            }
        }
    }

    /**
     * Force immediate refresh (blocking) - use sparingly, only when needed.
     * Returns Result with updated licenses or error.
     *
     * IMPORTANT: Does NOT overwrite local changes that are pending sync (syncStatus != SYNCED).
     */
    suspend fun forceRefresh(proAccountId: String): Result<List<License>> {
        return try {
            val result = licenseRemoteDataSource.getLicenses(proAccountId)

            result.onSuccess { licenses ->
                var syncedCount = 0
                var skippedCount = 0

                // Update local Room DB - but ONLY if no local changes pending
                for (license in licenses) {
                    val entity = license.toEntity(
                        syncStatus = SyncStatus.SYNCED.name,
                        localUpdatedAt = System.currentTimeMillis(),
                        serverUpdatedAt = System.currentTimeMillis(),
                        version = 1
                    )

                    val localLicense = licenseDao.getByIdOnce(license.id)

                    if (localLicense == null || localLicense.syncStatus == SyncStatus.SYNCED.name) {
                        licenseDao.upsert(entity)
                        syncedCount++
                    } else {
                        MotiumApplication.logger.d(
                            "Force refresh: skipping license ${license.id} - local changes pending",
                            TAG
                        )
                        skippedCount++
                    }
                }

                MotiumApplication.logger.i(
                    "Force refresh: $syncedCount licenses synced, $skippedCount skipped",
                    TAG
                )
            }

            result
        } catch (e: Exception) {
            MotiumApplication.logger.e("Force refresh failed: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Get cached license state from ProLicenseCache (for offline fallback).
     * Returns null if cache invalid/expired.
     */
    fun getCachedLicenseStatus(userId: String): Boolean? {
        return proLicenseCache.getValidCachedLicenseStatus(userId)
    }

    /**
     * Check if ProLicenseCache is valid for a user.
     */
    fun isCacheValid(userId: String): Boolean {
        return proLicenseCache.isCacheValid(userId)
    }

    /**
     * Get one-time snapshot of licenses (for immediate checks without Flow).
     */
    suspend fun getLicensesByProAccountOnce(proAccountId: String): List<License> {
        return licenseDao.getLicensesByProAccountOnce(proAccountId).map { it.toDomain() }
    }

    /**
     * Get one-time snapshot of available licenses.
     */
    suspend fun getAvailableLicensesOnce(proAccountId: String): List<License> {
        val all = licenseDao.getLicensesByProAccountOnce(proAccountId)
        return all.filter { it.linkedAccountId == null && it.status == "active" }
            .map { it.toDomain() }
    }
}

package com.application.motium.data.repository

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.dao.LicenseDao
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.preferences.ProLicenseCache
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.domain.model.License
import com.application.motium.utils.TrustedTimeProvider
import kotlinx.coroutines.CancellationException
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

        // BATTERY OPTIMIZATION (2026-01): Rate-limit background refreshes to prevent redundant network calls
        // When multiple UI components collect the same Flow, each would trigger a refresh
        // This cooldown ensures we don't refresh more than once per minute
        private const val BACKGROUND_REFRESH_COOLDOWN_MS = 60_000L // 1 minute

        @Volatile
        private var instance: LicenseCacheManager? = null

        fun getInstance(context: Context): LicenseCacheManager {
            return instance ?: synchronized(this) {
                instance ?: LicenseCacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // BATTERY OPTIMIZATION (2026-01): Track last refresh time per proAccountId to rate-limit
    private val lastRefreshTimeByAccount = mutableMapOf<String, Long>()

    private val licenseDao: LicenseDao by lazy {
        com.application.motium.data.local.MotiumDatabase.getInstance(context).licenseDao()
    }

    private val proLicenseCache: ProLicenseCache by lazy {
        ProLicenseCache.getInstance(context)
    }

    private val licenseRemoteDataSource: LicenseRemoteDataSource by lazy {
        LicenseRemoteDataSource.getInstance(context)
    }

    private val trustedTimeProvider: TrustedTimeProvider by lazy {
        TrustedTimeProvider.getInstance(context)
    }

    // BATTERY OPTIMIZATION (2026-01): Use lazy scope that's only created when needed
    // The SupervisorJob allows individual refreshes to fail without affecting others
    private val backgroundJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(Dispatchers.IO + backgroundJob)

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
     *
     * IMPORTANT: Also validates end_date locally to prevent offline abuse.
     * If the license is expired (end_date < now), it's filtered out even in offline mode.
     * Uses TrustedTimeProvider to detect clock manipulation attacks.
     */
    fun getLicenseForUser(userId: String): Flow<License?> {
        return licenseDao.getActiveLicenseForUser(userId)
            .map { entity ->
                val license = entity?.toDomain()
                // Fix P1: Local validation of end_date to prevent offline abuse
                // Even if cached, expired licenses should not grant access
                // Fix P0: Use trusted time to detect clock manipulation
                if (license != null && isLicenseExpiredTrusted(license)) {
                    MotiumApplication.logger.w(
                        "License ${license.id} for user $userId is expired (end_date: ${license.endDate}), filtering out",
                        TAG
                    )
                    null
                } else {
                    license
                }
            }
            .onStart {
                // Trigger background refresh for this user's license
                refreshUserLicenseInBackground(userId)
            }
    }

    /**
     * Get license for a specific account in a Pro account - cache-first pattern.
     * Returns Room DB data immediately, refreshes in background.
     *
     * IMPORTANT: Also validates end_date locally to prevent offline abuse.
     * Uses TrustedTimeProvider to detect clock manipulation attacks.
     */
    fun getLicenseForAccount(proAccountId: String, accountId: String): Flow<License?> {
        // Query Room DB for license assigned to this account
        return licenseDao.getLicensesByProAccount(proAccountId)
            .map { entities ->
                val license = entities.firstOrNull {
                    it.linkedAccountId == accountId && it.status == "active"
                }?.toDomain()

                // Fix P1: Local validation of end_date to prevent offline abuse
                // Fix P0: Use trusted time to detect clock manipulation
                if (license != null && isLicenseExpiredTrusted(license)) {
                    MotiumApplication.logger.w(
                        "License ${license.id} for account $accountId is expired (end_date: ${license.endDate}), filtering out",
                        TAG
                    )
                    null
                } else {
                    license
                }
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
     *
     * BATTERY OPTIMIZATION (2026-01): Rate-limited to prevent redundant network calls when
     * multiple UI components collect the same Flow simultaneously.
     */
    suspend fun refreshLicensesInBackground(proAccountId: String) {
        // BATTERY OPTIMIZATION (2026-01): Check if we've refreshed recently
        val now = System.currentTimeMillis()
        val lastRefresh = lastRefreshTimeByAccount[proAccountId] ?: 0L
        if (now - lastRefresh < BACKGROUND_REFRESH_COOLDOWN_MS) {
            MotiumApplication.logger.d(
                "Background refresh skipped for $proAccountId: cooldown active (${(BACKGROUND_REFRESH_COOLDOWN_MS - (now - lastRefresh)) / 1000}s remaining)",
                TAG
            )
            return
        }
        lastRefreshTimeByAccount[proAccountId] = now

        backgroundScope.launch {
            try {
                MotiumApplication.logger.d("Background refresh: fetching licenses for $proAccountId", TAG)

                val result = licenseRemoteDataSource.getLicenses(proAccountId)
                result.onSuccess { licenses ->
                    var syncedCount = 0
                    var skippedCount = 0
                    var deletedCount = 0

                    // Update trusted time anchor from server response
                    // Use the most recent updatedAt from the licenses as server time reference
                    licenses.maxByOrNull { it.updatedAt }?.let { mostRecent ->
                        trustedTimeProvider.updateServerTime(mostRecent.updatedAt.toEpochMilliseconds())
                        MotiumApplication.logger.d(
                            "Trusted time anchor updated from license sync: ${mostRecent.updatedAt}",
                            TAG
                        )
                    }

                    // FIX: Delete local licenses that no longer exist on server
                    // Get all local licenses for this pro account
                    val serverLicenseIds = licenses.map { it.id }.toSet()
                    val localLicenses = licenseDao.getLicensesByProAccountOnce(proAccountId)
                    for (localLicense in localLicenses) {
                        if (localLicense.id !in serverLicenseIds) {
                            // License exists locally but not on server - delete it
                            // Only delete if not pending sync (to avoid deleting user's local changes)
                            if (localLicense.syncStatus == SyncStatus.SYNCED.name) {
                                licenseDao.deleteById(localLicense.id)
                                deletedCount++
                                MotiumApplication.logger.i(
                                    "Deleted orphan license ${localLicense.id} (no longer exists on server)",
                                    TAG
                                )
                            } else {
                                MotiumApplication.logger.w(
                                    "License ${localLicense.id} not on server but has pending changes - skipping delete",
                                    TAG
                                )
                            }
                        }
                    }

                    // Update local Room DB - but ONLY if no local changes pending
                    for (license in licenses) {
                        val entity = license.toEntity(
                            syncStatus = SyncStatus.SYNCED.name,
                            localUpdatedAt = System.currentTimeMillis(),
                            serverUpdatedAt = license.updatedAt.toEpochMilliseconds(),
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
                        "Background refresh: $syncedCount licenses updated, $skippedCount skipped, $deletedCount deleted (orphans)",
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
                    // Ignore CancellationException - expected during navigation
                    if (e !is CancellationException) {
                        MotiumApplication.logger.w(
                            "Background refresh failed (using cached data): ${e.message}",
                            TAG
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Expected during navigation - silently ignore
                MotiumApplication.logger.d("Background refresh cancelled (expected)", TAG)
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
                var deletedCount = 0

                // Update trusted time anchor from server response
                licenses.maxByOrNull { it.updatedAt }?.let { mostRecent ->
                    trustedTimeProvider.updateServerTime(mostRecent.updatedAt.toEpochMilliseconds())
                    MotiumApplication.logger.d(
                        "Trusted time anchor updated from force refresh: ${mostRecent.updatedAt}",
                        TAG
                    )
                }

                // FIX: Delete local licenses that no longer exist on server
                val serverLicenseIds = licenses.map { it.id }.toSet()
                val localLicenses = licenseDao.getLicensesByProAccountOnce(proAccountId)
                for (localLicense in localLicenses) {
                    if (localLicense.id !in serverLicenseIds) {
                        if (localLicense.syncStatus == SyncStatus.SYNCED.name) {
                            licenseDao.deleteById(localLicense.id)
                            deletedCount++
                            MotiumApplication.logger.i(
                                "Force refresh: deleted orphan license ${localLicense.id}",
                                TAG
                            )
                        }
                    }
                }

                // Update local Room DB - but ONLY if no local changes pending
                for (license in licenses) {
                    val entity = license.toEntity(
                        syncStatus = SyncStatus.SYNCED.name,
                        localUpdatedAt = System.currentTimeMillis(),
                        serverUpdatedAt = license.updatedAt.toEpochMilliseconds(),
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
                    "Force refresh: $syncedCount licenses synced, $skippedCount skipped, $deletedCount deleted",
                    TAG
                )
            }

            result
        } catch (e: CancellationException) {
            // Re-throw CancellationException to respect coroutine cancellation
            // This is expected behavior during navigation - not an error
            MotiumApplication.logger.d("Force refresh cancelled (expected during navigation)", TAG)
            throw e
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
     * Includes licenses with status 'available' (in pool) or 'active' without assignment.
     */
    suspend fun getAvailableLicensesOnce(proAccountId: String): List<License> {
        val all = licenseDao.getLicensesByProAccountOnce(proAccountId)
        return all.filter { it.linkedAccountId == null && (it.status == "available" || it.status == "active") }
            .map { it.toDomain() }
    }

    /**
     * Check if a license is expired using TrustedTimeProvider.
     * Uses fail-secure approach: if clock manipulation is suspected, treat as expired.
     *
     * @param license The license to check
     * @return true if expired OR clock manipulation suspected (fail-secure)
     */
    private fun isLicenseExpiredTrusted(license: License): Boolean {
        val endDate = license.endDate ?: return false  // No end date = never expires
        return trustedTimeProvider.isExpiredFailSecure(endDate.toEpochMilliseconds())
    }

    /**
     * Update the trusted time anchor from a server response.
     * Call this after successful network operations.
     *
     * @param serverTimeMs Server timestamp in milliseconds
     */
    fun updateTrustedTimeAnchor(serverTimeMs: Long) {
        trustedTimeProvider.updateServerTime(serverTimeMs)
    }

    /**
     * Check if the current time is trusted (no clock manipulation detected).
     */
    fun isTimeTrusted(): Boolean {
        return trustedTimeProvider.isTimeTrusted()
    }

    /**
     * BATTERY OPTIMIZATION: Cancel all background tasks and clear rate-limit cache.
     * Call this when the user logs out or the app is being terminated.
     */
    fun cleanup() {
        backgroundJob.cancel()
        lastRefreshTimeByAccount.clear()
        MotiumApplication.logger.d("LicenseCacheManager cleanup: background scope cancelled, rate-limit cache cleared", TAG)
    }
}

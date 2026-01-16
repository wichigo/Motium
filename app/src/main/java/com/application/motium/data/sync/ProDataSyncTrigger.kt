package com.application.motium.data.sync

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.ProAccountEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.repository.LicenseCacheManager
import com.application.motium.data.repository.OfflineFirstLinkedUserRepository
import com.application.motium.data.supabase.ProAccountRemoteDataSource
import com.application.motium.utils.NetworkConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Triggers immediate sync of Pro-related data after login for Enterprise users.
 *
 * This ensures Room DB has Pro account, licenses, and linked users data
 * before the user navigates to Pro screens, enabling offline access.
 *
 * Call [syncProData] after successful login for Enterprise users.
 */
class ProDataSyncTrigger private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ProDataSyncTrigger"

        @Volatile
        private var instance: ProDataSyncTrigger? = null

        fun getInstance(context: Context): ProDataSyncTrigger {
            return instance ?: synchronized(this) {
                instance ?: ProDataSyncTrigger(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val proAccountDao = database.proAccountDao()

    private val proAccountRemoteDataSource by lazy {
        ProAccountRemoteDataSource.getInstance(context)
    }

    private val licenseCacheManager by lazy {
        LicenseCacheManager.getInstance(context)
    }

    private val offlineFirstLinkedUserRepo by lazy {
        OfflineFirstLinkedUserRepository.getInstance(context)
    }

    private val networkManager by lazy {
        NetworkConnectionManager.getInstance(context)
    }

    /**
     * Sync all Pro-related data for an Enterprise user.
     *
     * Steps:
     * 1. Fetch and cache ProAccount from Supabase
     * 2. Fetch and cache Licenses (in parallel)
     * 3. Fetch and cache Linked Users (in parallel)
     *
     * @param userId The logged-in user's ID
     * @return true if sync completed successfully (or gracefully skipped due to offline)
     */
    suspend fun syncProData(userId: String): Boolean = withContext(Dispatchers.IO) {
        // Check network connectivity
        if (!networkManager.isConnected.value) {
            MotiumApplication.logger.w(
                "Offline - skipping Pro data sync, will use cached data",
                TAG
            )
            return@withContext true // Don't block login if offline
        }

        try {
            MotiumApplication.logger.i("Starting Pro data sync for user: $userId", TAG)

            // Step 1: Sync ProAccount
            val proAccountResult = syncProAccount(userId)
            if (proAccountResult == null) {
                MotiumApplication.logger.w("No Pro account found for user $userId", TAG)
                return@withContext true // Not an error - user might not have Pro account yet
            }

            val proAccountId = proAccountResult
            MotiumApplication.logger.i("Synced Pro account: $proAccountId", TAG)

            // Step 2 & 3: Sync Licenses and LinkedUsers in parallel
            val licensesDeferred = async {
                try {
                    licenseCacheManager.refreshLicensesInBackground(proAccountId)
                    true
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to sync licenses: ${e.message}", TAG)
                    false
                }
            }

            val linkedUsersDeferred = async {
                try {
                    val result = offlineFirstLinkedUserRepo.forceRefresh(proAccountId)
                    result.isSuccess
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to sync linked users: ${e.message}", TAG)
                    false
                }
            }

            // Wait for both
            val licensesSuccess = licensesDeferred.await()
            val linkedUsersSuccess = linkedUsersDeferred.await()

            MotiumApplication.logger.i(
                "Pro data sync complete: licenses=${if (licensesSuccess) "OK" else "FAILED"}, " +
                        "linkedUsers=${if (linkedUsersSuccess) "OK" else "FAILED"}",
                TAG
            )

            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Pro data sync failed: ${e.message}", TAG, e)
            false // Don't block login on sync failure
        }
    }

    /**
     * Fetch ProAccount from Supabase and cache in Room.
     *
     * @return ProAccount ID if found, null otherwise
     */
    private suspend fun syncProAccount(userId: String): String? {
        return try {
            val result = proAccountRemoteDataSource.getProAccount(userId)

            result.getOrNull()?.let { dto ->
                // Convert DTO to Entity and save to Room
                val entity = ProAccountEntity(
                    id = dto.id,
                    userId = dto.userId,
                    companyName = dto.companyName,
                    siret = dto.siret,
                    vatNumber = dto.vatNumber,
                    legalForm = dto.legalForm,
                    billingAddress = dto.billingAddress,
                    billingEmail = dto.billingEmail,
                    billingDay = dto.billingDay,
                    departments = dto.departments?.toString() ?: "[]",
                    createdAt = dto.createdAt?.let { parseTimestamp(it) }
                        ?: System.currentTimeMillis(),
                    updatedAt = dto.updatedAt?.let { parseTimestamp(it) }
                        ?: System.currentTimeMillis(),
                    syncStatus = SyncStatus.SYNCED.name,
                    localUpdatedAt = System.currentTimeMillis(),
                    serverUpdatedAt = dto.updatedAt?.let { parseTimestamp(it) },
                    version = 1
                )

                proAccountDao.upsert(entity)
                MotiumApplication.logger.d("Pro account cached in Room: ${dto.id}", TAG)

                dto.id
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to sync ProAccount: ${e.message}", TAG, e)
            null
        }
    }

    /**
     * Parse ISO timestamp string to Long (milliseconds).
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            Instant.parse(timestamp).toEpochMilliseconds()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

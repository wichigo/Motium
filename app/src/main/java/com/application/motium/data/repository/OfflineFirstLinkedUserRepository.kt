package com.application.motium.data.repository

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.LinkedUserDao
import com.application.motium.data.local.entities.toDto
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline-first repository for linked users (employees linked to Pro accounts).
 *
 * Uses cache-first pattern:
 * 1. Returns data from Room immediately (via Flow)
 * 2. Triggers background refresh from Supabase
 * 3. Updates Room with fresh data
 * 4. UI automatically updates via Flow subscription
 *
 * This enables the LinkedAccountsScreen to work offline.
 */
class OfflineFirstLinkedUserRepository private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "OfflineFirstLinkedUserRepo"

        @Volatile
        private var instance: OfflineFirstLinkedUserRepository? = null

        fun getInstance(context: Context): OfflineFirstLinkedUserRepository {
            return instance ?: synchronized(this) {
                instance ?: OfflineFirstLinkedUserRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val linkedUserDao: LinkedUserDao = database.linkedUserDao()
    private val linkedAccountRemoteDataSource: LinkedAccountRemoteDataSource by lazy {
        LinkedAccountRemoteDataSource.getInstance(context)
    }

    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== FLOW-BASED QUERIES (CACHE-FIRST) ====================

    /**
     * Get linked users for a Pro account with cache-first pattern.
     *
     * Returns Room data immediately, triggers background refresh,
     * and emits updated data when Room is updated.
     */
    fun getLinkedUsers(proAccountId: String): Flow<List<LinkedUserDto>> {
        return linkedUserDao.getByProAccount(proAccountId)
            .map { entities -> entities.map { it.toDto() } }
            .onStart {
                // Trigger background refresh (non-blocking)
                refreshLinkedUsersInBackground(proAccountId)
            }
    }

    // ==================== ONE-SHOT QUERIES ====================

    /**
     * Get cached linked users without triggering refresh.
     * Use for immediate display without network delay.
     */
    suspend fun getCachedLinkedUsers(proAccountId: String): List<LinkedUserDto> {
        return linkedUserDao.getByProAccountOnce(proAccountId).map { it.toDto() }
    }

    /**
     * Check if we have cached linked users for a Pro account.
     */
    suspend fun hasCachedLinkedUsers(proAccountId: String): Boolean {
        return linkedUserDao.countByProAccount(proAccountId) > 0
    }

    // ==================== SYNC OPERATIONS ====================

    /**
     * Force immediate sync of linked users from Supabase.
     * Blocks until complete. Use sparingly.
     *
     * @return Result with list of linked users if successful
     */
    suspend fun forceRefresh(proAccountId: String): Result<List<LinkedUserDto>> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Force refreshing linked users for Pro account: $proAccountId", TAG)

            val result = linkedAccountRemoteDataSource.getLinkedUsers(proAccountId)

            result.onSuccess { users ->
                // Update Room with fresh data
                val entities = users.map { it.toEntity(proAccountId) }
                linkedUserDao.upsertAll(entities)

                MotiumApplication.logger.i(
                    "Force refresh completed: ${users.size} linked users cached",
                    TAG
                )
            }

            result.onFailure { e ->
                MotiumApplication.logger.e(
                    "Force refresh failed: ${e.message}",
                    TAG,
                    e
                )
            }

            result
        } catch (e: Exception) {
            MotiumApplication.logger.e("Force refresh exception: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Refresh linked users in background (non-blocking).
     * Called automatically when subscribing to getLinkedUsers() Flow.
     */
    private fun refreshLinkedUsersInBackground(proAccountId: String) {
        backgroundScope.launch {
            try {
                val result = linkedAccountRemoteDataSource.getLinkedUsers(proAccountId)

                result.onSuccess { users ->
                    val entities = users.map { it.toEntity(proAccountId) }
                    linkedUserDao.upsertAll(entities)

                    MotiumApplication.logger.d(
                        "Background refresh: ${users.size} linked users updated",
                        TAG
                    )
                }

                result.onFailure { e ->
                    // Don't log error for network issues - expected when offline
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

    // ==================== CACHE MANAGEMENT ====================

    /**
     * Clear cached linked users for a Pro account.
     * Use when Pro account is deleted or user logs out.
     */
    suspend fun clearCache(proAccountId: String) {
        linkedUserDao.deleteByProAccount(proAccountId)
        MotiumApplication.logger.d("Cleared linked users cache for Pro account: $proAccountId", TAG)
    }

    /**
     * Clear all cached linked users.
     * Use on logout.
     */
    suspend fun clearAllCache() {
        linkedUserDao.deleteAll()
        MotiumApplication.logger.d("Cleared all linked users cache", TAG)
    }
}

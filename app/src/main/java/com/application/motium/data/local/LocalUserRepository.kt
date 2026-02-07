package com.application.motium.data.local

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.UserEntity
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local repository for user operations using Room database.
 * Handles all local user data persistence.
 */
class LocalUserRepository(context: Context) {

    private val appContext = context.applicationContext
    private val database = MotiumDatabase.getInstance(context)
    private val userDao = database.userDao()

    companion object {
        @Volatile
        private var instance: LocalUserRepository? = null

        fun getInstance(context: Context): LocalUserRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalUserRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Save or update user in local database.
     * Used when logging in or syncing from Supabase.
     */
    suspend fun saveUser(user: User, isLocallyConnected: Boolean = true) {
        val entity = user.toEntity(
            lastSyncedAt = System.currentTimeMillis(),
            isLocallyConnected = isLocallyConnected
        )
        userDao.insertUser(entity)
    }

    /**
     * Save user data from server without triggering sync.
     * Used after successful upload to Supabase to mark as SYNCED.
     * Does NOT queue a pending operation - prevents re-queueing loop.
     */
    suspend fun saveUserFromServer(user: User, syncStatus: SyncStatus = SyncStatus.SYNCED) {
        val entity = user.toEntity(
            lastSyncedAt = System.currentTimeMillis(),
            isLocallyConnected = true
        ).copy(
            syncStatus = syncStatus.name,
            localUpdatedAt = System.currentTimeMillis(),
            serverUpdatedAt = System.currentTimeMillis()
        )
        userDao.insertUser(entity)
    }

    /**
     * Get the currently logged-in user as Flow (reactive).
     */
    fun getLoggedInUserFlow(): Flow<User?> {
        return userDao.getLoggedInUserFlow().map { it?.toDomainModel() }
    }

    /**
     * Get the currently logged-in user (one-time fetch).
     */
    suspend fun getLoggedInUser(): User? {
        return userDao.getLoggedInUser()?.toDomainModel()
    }

    /**
     * Get user by ID.
     */
    suspend fun getUserById(userId: String): User? {
        return userDao.getUserById(userId)?.toDomainModel()
    }

    /**
     * Check if a user is logged in locally.
     */
    suspend fun hasLoggedInUser(): Boolean {
        return userDao.hasLoggedInUser()
    }

    /**
     * Update user data.
     * Marks the user as PENDING_UPLOAD and queues a sync operation for offline-first sync.
     */
    suspend fun updateUser(user: User) {
        // First get the current entity to retrieve the latest version
        val currentEntity = userDao.getUserById(user.id)
        val newVersion = (currentEntity?.version ?: 0) + 1

        val entity = user.toEntity(
            lastSyncedAt = System.currentTimeMillis(),
            isLocallyConnected = true
        ).copy(
            syncStatus = SyncStatus.PENDING_UPLOAD.name,
            localUpdatedAt = System.currentTimeMillis(),
            version = newVersion
        )
        userDao.updateUser(entity)

        // Queue pending sync operation for uploading user preferences to Supabase
        queueUserSyncOperation(user, newVersion)
    }

    /**
     * Queue a pending sync operation for user profile update.
     * Uses OfflineFirstSyncManager to trigger immediate sync if online.
     * Builds a payload with user fields that can be updated via sync_changes().
     */
    private suspend fun queueUserSyncOperation(user: User, version: Int) {
        val syncManager = OfflineFirstSyncManager.getInstance(appContext)

        // Build payload with user fields allowed by push_user_change()
        // Allowed: name, phone_number, address, profile_photo_url, favorite_colors, consider_full_distance
        val userPayload = buildJsonObject {
            put("name", user.name)
            put("phone_number", user.phoneNumber ?: "")
            put("address", user.address ?: "")
            put("profile_photo_url", user.profilePhotoUrl ?: "")
            put("consider_full_distance", user.considerFullDistance)
            // favorite_colors is a list - build as JSON array
            if (user.favoriteColors.isNotEmpty()) {
                put("favorite_colors", buildJsonArray {
                    user.favoriteColors.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
            // Version for optimistic locking - server checks this
            put("version", version)
        }.toString()

        MotiumApplication.logger.i(
            "Queueing USER update with payload: consider_full_distance=${user.considerFullDistance}, has_profile_photo=${!user.profilePhotoUrl.isNullOrBlank()}, version=$version",
            "LocalUserRepository"
        )

        syncManager.queueOperation(
            entityType = PendingOperationEntity.TYPE_USER,
            entityId = user.id,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = userPayload,
            priority = 1 // Trigger immediate sync for user preference changes
        )
    }

    /**
     * Update last synced timestamp.
     */
    suspend fun updateLastSyncedAt(userId: String) {
        userDao.updateLastSyncedAt(userId, System.currentTimeMillis())
    }

    /**
     * Logout user locally (marks as not connected, does not delete).
     */
    suspend fun logoutUser() {
        userDao.logoutAllUsers()
    }

    /**
     * Delete user completely from local database.
     * Used during manual logout.
     */
    suspend fun deleteUser(userId: String) {
        val user = userDao.getUserById(userId)
        user?.let { userDao.deleteUser(it) }
    }

    /**
     * Delete all user data.
     * Used during complete app reset/logout.
     */
    suspend fun deleteAllUsers() {
        userDao.deleteAllUsers()
    }
}

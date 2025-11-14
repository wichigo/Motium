package com.application.motium.data.local

import android.content.Context
import com.application.motium.data.local.entities.UserEntity
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local repository for user operations using Room database.
 * Handles all local user data persistence.
 */
class LocalUserRepository(context: Context) {

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
     */
    suspend fun updateUser(user: User) {
        val entity = user.toEntity(
            lastSyncedAt = System.currentTimeMillis(),
            isLocallyConnected = true
        )
        userDao.updateUser(entity)
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

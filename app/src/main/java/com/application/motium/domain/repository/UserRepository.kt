package com.application.motium.domain.repository

import com.application.motium.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUserById(userId: String): Flow<User?>
    suspend fun getUserByEmail(email: String): User?
    suspend fun getCurrentUser(): User?
    suspend fun insertUser(user: User)
    suspend fun updateUser(user: User)
    suspend fun updateMonthlyTripCount(userId: String, count: Int)
    suspend fun deleteUser(user: User)
}
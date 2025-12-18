package com.application.motium.domain.repository

import com.application.motium.domain.model.AuthResult
import com.application.motium.domain.model.AuthState
import com.application.motium.domain.model.AuthUser
import com.application.motium.domain.model.LoginRequest
import com.application.motium.domain.model.RegisterRequest
import com.application.motium.domain.model.User
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val authState: Flow<AuthState>

    suspend fun signUp(request: RegisterRequest): AuthResult<AuthUser>
    suspend fun signIn(request: LoginRequest): AuthResult<AuthUser>
    suspend fun signInWithGoogle(idToken: String): AuthResult<AuthUser>
    suspend fun signOut(): AuthResult<Unit>
    suspend fun getCurrentAuthUser(): AuthUser?
    suspend fun isUserAuthenticated(): Boolean
    suspend fun sendPasswordResetEmail(email: String): AuthResult<Unit>
    suspend fun confirmEmail(token: String): AuthResult<Unit>

    suspend fun createUserProfile(authUser: AuthUser, name: String, isEnterprise: Boolean = false, organizationName: String = ""): AuthResult<User>
    suspend fun createUserProfileWithTrial(
        userId: String,
        name: String,
        isProfessional: Boolean = false,
        organizationName: String = "",
        verifiedPhone: String,
        deviceFingerprintId: String? = null
    ): AuthResult<User>
    suspend fun getUserProfile(userId: String): AuthResult<User>
    suspend fun updateUserProfile(user: User): AuthResult<User>

    // Mise à jour des credentials
    suspend fun updateEmail(newEmail: String): AuthResult<Unit>
    suspend fun updatePassword(newPassword: String): AuthResult<Unit>
}
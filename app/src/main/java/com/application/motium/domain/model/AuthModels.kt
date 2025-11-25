package com.application.motium.domain.model

import kotlinx.serialization.Serializable

data class LoginRequest(
    val email: String,
    val password: String
)

data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String,
    val role: UserRole = UserRole.INDIVIDUAL
)

@Serializable
data class AuthUser(
    val id: String,
    val email: String?,
    val isEmailConfirmed: Boolean = false,
    val provider: String? = null
)

data class AuthState(
    val isAuthenticated: Boolean = false,
    val authUser: AuthUser? = null,
    val user: User? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCriticalError: Boolean = false  // SÉCURITÉ: Erreur non récupérable (échec du chiffrement, etc.)
)

sealed class AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : AuthResult<Nothing>()
    data object Loading : AuthResult<Nothing>()
}

data class AuthError(
    val message: String,
    val code: String? = null
)
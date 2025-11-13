package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.SyncScheduler
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.AuthRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import com.application.motium.MotiumApplication
import kotlinx.serialization.json.jsonObject

class SupabaseAuthRepository(private val context: Context) : AuthRepository {

    private val client = SupabaseClient.client
    private val auth: Auth = client.auth
    private val postgres = client.postgrest
    private val secureSessionStorage = SecureSessionStorage(context)

    companion object {
        @Volatile
        private var instance: SupabaseAuthRepository? = null
        fun getInstance(context: Context): SupabaseAuthRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseAuthRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _authState = MutableStateFlow(AuthState(isLoading = true))
    override val authState: Flow<AuthState> = _authState.asStateFlow()

    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionMutex = Mutex()

    @Serializable
    data class UserProfile(
        val id: String? = null,
        val auth_id: String,
        val name: String,
        val email: String,
        val role: String,
        val organization_id: String? = null,
        val organization_name: String? = null,
        val subscription_type: String = "FREE",
        val subscription_expires_at: String? = null,
        val monthly_trip_count: Int = 0,
        val created_at: String,
        val updated_at: String
    )

    init {
        sessionScope.launch {
            initializeAndRestoreSession()
        }
    }

    private suspend fun initializeAndRestoreSession() {
        sessionMutex.withLock {
            MotiumApplication.logger.i("🚀 Initializing optimistic session...", "SupabaseAuth")
            val restoredSession = secureSessionStorage.restoreSession()

            if (restoredSession != null) {
                MotiumApplication.logger.i("✅ Optimistic login for ${restoredSession.userEmail}.", "SupabaseAuth")
                val optimisticAuthUser = AuthUser(
                    id = restoredSession.userId,
                    email = restoredSession.userEmail,
                    isEmailConfirmed = true
                )
                _authState.value = AuthState(isAuthenticated = true, authUser = optimisticAuthUser, user = null, isLoading = false)

                sessionScope.launch {
                    refreshSession()
                }
            } else {
                MotiumApplication.logger.i("ℹ️ No persistent session found. User is logged out.", "SupabaseAuth")
                _authState.value = AuthState(isLoading = false, isAuthenticated = false)
            }
        }
    }

    suspend fun refreshSession() {
        try {
            val refreshToken = secureSessionStorage.getRefreshToken()
            if (refreshToken != null) {
                MotiumApplication.logger.i("🔄 Attempting to refresh session in background...", "SupabaseAuth")
                auth.refreshSession(refreshToken)
                saveCurrentSessionSecurely()
                updateAuthState()
                MotiumApplication.logger.i("✅ Session refreshed and validated successfully.", "SupabaseAuth")
            } else {
                signOut()
            }
        } catch (e: Exception) {
            val isPermanentAuthError = e is RestException && (e.statusCode == 401 || e.statusCode == 400)
            if (isPermanentAuthError) {
                MotiumApplication.logger.e("❌ PERMANENT AUTH ERROR on refresh: ${e.message}. Logging out.", "SupabaseAuth", e)
                signOut()
            } else {
                MotiumApplication.logger.w("⚠️ Temporary network error on refresh: ${e.message}. User remains logged in optimistically.", "SupabaseAuth")
            }
        }
    }

    override suspend fun signUp(request: RegisterRequest): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            auth.signUpWith(Email) {
                email = request.email
                password = request.password
            }
            val authUser = getCurrentAuthUser() ?: throw Exception("Failed to get user info after signup")
            saveCurrentSessionSecurely()
            updateAuthState()
            AuthResult.Success(authUser)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Signup failed", e)
        }
    }

    override suspend fun signIn(request: LoginRequest): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            auth.signInWith(Email) {
                email = request.email
                password = request.password
            }
            val authUser = getCurrentAuthUser() ?: throw Exception("Failed to get user info after signin")
            saveCurrentSessionSecurely()
            SyncScheduler.scheduleSyncWork(context)
            updateAuthState()
            AuthResult.Success(authUser)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Login failed", e)
        }
    }

    override suspend fun signOut(): AuthResult<Unit> {
        return try {
            auth.signOut()
            secureSessionStorage.manualLogout()
            SyncScheduler.cancelSyncWork(context)
            _authState.value = AuthState(isAuthenticated = false, isLoading = false)
            MotiumApplication.logger.i("✅ User signed out successfully.", "SupabaseAuth")
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Sign out failed", e)
        }
    }

    private suspend fun saveCurrentSessionSecurely() {
        val currentSession = auth.currentSessionOrNull()
        val currentUser = auth.currentUserOrNull()

        if (currentSession != null && currentUser != null && currentSession.refreshToken != null) {
            val expiresInSeconds = currentSession.expiresIn
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)

            val sessionData = SecureSessionStorage.SessionData(
                accessToken = currentSession.accessToken,
                refreshToken = currentSession.refreshToken!!,
                expiresAt = expiresAt,
                userId = currentUser.id,
                userEmail = currentUser.email ?: ""
            )
            secureSessionStorage.saveSession(sessionData)
        }
    }

    override suspend fun signInWithGoogle(idToken: String): AuthResult<AuthUser> = AuthResult.Error("Google Sign-In not yet implemented")

    override suspend fun getCurrentAuthUser(): AuthUser? {
        val supabaseUser = auth.currentUserOrNull()
        return supabaseUser?.let {
            AuthUser(
                id = it.id,
                email = it.email,
                isEmailConfirmed = it.emailConfirmedAt != null,
                provider = it.appMetadata?.get("provider")?.jsonObject?.toString()
            )
        }
    }

    override suspend fun isUserAuthenticated(): Boolean = _authState.value.isAuthenticated

    override suspend fun sendPasswordResetEmail(email: String): AuthResult<Unit> {
        return try {
            auth.resetPasswordForEmail(email)
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to send reset email", e)
        }
    }

    override suspend fun confirmEmail(token: String): AuthResult<Unit> = AuthResult.Error("Email confirmation not implemented")

    override suspend fun createUserProfile(authUser: AuthUser, name: String, isEnterprise: Boolean, organizationName: String): AuthResult<User> {
        return try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
            val role = if (isEnterprise) "ENTERPRISE" else "INDIVIDUAL"
            val organizationId = if (isEnterprise) java.util.UUID.randomUUID().toString() else null

            val userProfile = UserProfile(
                auth_id = authUser.id, name = name, email = authUser.email ?: "", role = role,
                organization_id = organizationId,
                organization_name = if (isEnterprise) organizationName else null,
                created_at = now, updated_at = now
            )
            
            postgres.from("users").insert(userProfile)
            val createdProfile = postgres.from("users").select { filter { UserProfile::auth_id eq authUser.id } }.decodeSingle<UserProfile>()
            
            val user = createdProfile.toDomainUser()
            updateAuthState()
            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to create user profile", e)
        }
    }

    override suspend fun getUserProfile(userId: String): AuthResult<User> {
        return try {
            val authId = auth.currentUserOrNull()?.id ?: return AuthResult.Error("User not authenticated")
            val userProfile = postgres.from("users").select { filter { UserProfile::auth_id eq authId } }.decodeSingle<UserProfile>()
            AuthResult.Success(userProfile.toDomainUser())
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to get user profile", e)
        }
    }

    override suspend fun updateUserProfile(user: User): AuthResult<User> {
        return try {
            val userProfile = user.toUserProfile()
            postgres.from("users").update(userProfile) { filter { UserProfile::id eq user.id } }
            updateAuthState()
            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to update user profile", e)
        }
    }

    private suspend fun updateAuthState() {
        val authUser = getCurrentAuthUser()
        if (authUser == null) {
            _authState.value = AuthState(isAuthenticated = false, isLoading = false)
            return
        }

        val userProfileResult = getUserProfile(authUser.id)
        val user = if (userProfileResult is AuthResult.Success) userProfileResult.data else null
        
        _authState.value = AuthState(
            isAuthenticated = true,
            authUser = authUser,
            user = user,
            isLoading = false,
            error = if (user == null) "Failed to load user profile." else null
        )
    }
    
    private fun UserProfile.toDomainUser(): User = User(
        id = id ?: auth_id,
        name = name, email = email, role = UserRole.valueOf(role),
        organizationId = organization_id, organizationName = organization_name,
        subscription = Subscription(type = SubscriptionType.valueOf(subscription_type), expiresAt = subscription_expires_at?.let { Instant.parse(it) }),
        monthlyTripCount = monthly_trip_count,
        createdAt = Instant.parse(created_at), updatedAt = Instant.parse(updated_at)
    )

    private fun User.toUserProfile(): UserProfile = UserProfile(
        id = id, auth_id = id, name = name, email = email, role = role.name,
        organization_id = organizationId, organization_name = organizationName,
        subscription_type = subscription.type.name,
        subscription_expires_at = subscription.expiresAt?.toString(),
        monthly_trip_count = monthlyTripCount,
        created_at = createdAt.toString(), updated_at = updatedAt.toString()
    )
}
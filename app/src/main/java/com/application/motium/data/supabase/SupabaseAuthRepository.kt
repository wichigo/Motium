package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject

class SupabaseAuthRepository(private val context: Context) : AuthRepository {

    private val client = SupabaseClient.client
    private val auth: Auth = client.auth
    private val postgres = client.postgrest
    private val secureSessionStorage = SecureSessionStorage(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)

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
            MotiumApplication.logger.i("🚀 Initializing offline-first session...", "SupabaseAuth")

            // ÉTAPE 1: Charger l'utilisateur depuis la base de données locale (Room)
            val localUser = localUserRepository.getLoggedInUser()

            if (localUser != null) {
                // Utilisateur trouvé localement - Afficher l'UI immédiatement (offline-first)
                MotiumApplication.logger.i("✅ Utilisateur local trouvé: ${localUser.email}. Chargement offline...", "SupabaseAuth")

                val authUser = AuthUser(
                    id = localUser.id,
                    email = localUser.email,
                    isEmailConfirmed = true
                )

                // Définir l'état authentifié avec les données locales
                _authState.value = AuthState(
                    isAuthenticated = true,
                    authUser = authUser,
                    user = localUser,
                    isLoading = false
                )

                // ÉTAPE 2: Rafraîchir la session DANS le mutex lock (pas dans un nouveau coroutine)
                // SÉCURITÉ: Exécution synchrone avec timeout pour éviter race conditions
                try {
                    withTimeout(10_000L) { // Timeout 10 secondes
                        refreshSessionSafe()
                    }
                } catch (e: TimeoutCancellationException) {
                    MotiumApplication.logger.w(
                        "⏱️ Session refresh timeout - keeping local session (offline mode)",
                        "SupabaseAuth"
                    )
                } catch (e: Exception) {
                    MotiumApplication.logger.w(
                        "⚠️ Session refresh failed: ${e.message} - keeping local session (offline mode)",
                        "SupabaseAuth"
                    )
                }
            } else {
                // Pas d'utilisateur local - Vérifier l'ancienne méthode de stockage (migration)
                val restoredSession = secureSessionStorage.restoreSession()

                if (restoredSession != null) {
                    MotiumApplication.logger.i("⚠️ Ancienne session trouvée, migration en cours...", "SupabaseAuth")
                    // Essayer de restaurer depuis Supabase et migrer vers Room
                    tryMigrateOldSession(restoredSession)
                } else {
                    MotiumApplication.logger.i("ℹ️ Aucun utilisateur local. Utilisateur déconnecté.", "SupabaseAuth")
                    _authState.value = AuthState(isLoading = false, isAuthenticated = false)
                }
            }
        }
    }

    /**
     * SÉCURITÉ: Rafraîchit la session de manière sûre sans race condition.
     * Cette fonction s'exécute dans le sessionMutex lock.
     */
    private suspend fun refreshSessionSafe() {
        val refreshToken = secureSessionStorage.getRefreshToken()
        if (refreshToken != null) {
            MotiumApplication.logger.i("🔄 Refreshing session safely...", "SupabaseAuth")
            auth.refreshSession(refreshToken)
            saveCurrentSessionSecurely()
            syncUserProfileFromSupabase()
            MotiumApplication.logger.i("✅ Session refreshed successfully", "SupabaseAuth")
        }
    }

    /**
     * Migre une ancienne session vers le nouveau système Room.
     */
    private suspend fun tryMigrateOldSession(session: SecureSessionStorage.SessionData) {
        try {
            auth.refreshSession(session.refreshToken)
            saveCurrentSessionSecurely()

            // Récupérer le profil utilisateur et le sauvegarder dans Room
            val authUser = getCurrentAuthUser()
            if (authUser != null) {
                val userProfileResult = getUserProfile(authUser.id)
                if (userProfileResult is AuthResult.Success) {
                    localUserRepository.saveUser(userProfileResult.data, isLocallyConnected = true)
                    _authState.value = AuthState(
                        isAuthenticated = true,
                        authUser = authUser,
                        user = userProfileResult.data,
                        isLoading = false
                    )

                    // CLEANUP: Nettoyer l'ancien stockage non chiffré après migration réussie
                    try {
                        context.getSharedPreferences("supabase_session_fallback", Context.MODE_PRIVATE)
                            .edit().clear().apply()
                        MotiumApplication.logger.i(
                            "🗑️ Cleaned up old unencrypted session storage after successful migration",
                            "SupabaseAuth"
                        )
                    } catch (e: Exception) {
                        MotiumApplication.logger.w(
                            "⚠️ Failed to clean up old storage: ${e.message}",
                            "SupabaseAuth"
                        )
                    }

                    MotiumApplication.logger.i("✅ Migration réussie", "SupabaseAuth")
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Échec de la migration: ${e.message}", "SupabaseAuth", e)
            _authState.value = AuthState(isLoading = false, isAuthenticated = false)
        }
    }

    /**
     * Synchronise le profil utilisateur depuis Supabase vers la base de données locale.
     */
    private suspend fun syncUserProfileFromSupabase() {
        try {
            val authUser = getCurrentAuthUser()
            if (authUser != null) {
                val userProfileResult = getUserProfile(authUser.id)
                if (userProfileResult is AuthResult.Success) {
                    localUserRepository.saveUser(userProfileResult.data, isLocallyConnected = true)
                    MotiumApplication.logger.i("✅ Profil utilisateur synchronisé depuis Supabase", "SupabaseAuth")

                    // Mettre à jour l'état d'authentification avec les dernières données
                    _authState.value = _authState.value.copy(user = userProfileResult.data)
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w("⚠️ Échec de la synchronisation du profil: ${e.message}", "SupabaseAuth")
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

            // Authentification avec Supabase
            auth.signInWith(Email) {
                email = request.email
                password = request.password
            }

            val authUser = getCurrentAuthUser() ?: throw Exception("Failed to get user info after signin")

            // Sauvegarder les tokens de session
            saveCurrentSessionSecurely()

            // Récupérer le profil utilisateur depuis Supabase
            val userProfileResult = getUserProfile(authUser.id)
            val user = if (userProfileResult is AuthResult.Success) userProfileResult.data else null

            // CRITIQUE: Sauvegarder l'utilisateur dans la base de données locale Room pour l'accès offline
            if (user != null) {
                localUserRepository.saveUser(user, isLocallyConnected = true)
                MotiumApplication.logger.i("✅ Utilisateur sauvegardé dans la base locale: ${user.email}", "SupabaseAuth")
            }

            // Planifier la synchronisation en arrière-plan
            SyncScheduler.scheduleSyncWork(context)

            // Mettre à jour l'état de l'UI
            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = authUser,
                user = user,
                isLoading = false
            )

            AuthResult.Success(authUser)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Login failed", e)
        }
    }

    override suspend fun signOut(): AuthResult<Unit> {
        return try {
            MotiumApplication.logger.i("👋 Déconnexion manuelle initiée...", "SupabaseAuth")

            // Se déconnecter de Supabase (peut échouer si offline - c'est OK)
            try {
                auth.signOut()
            } catch (e: Exception) {
                MotiumApplication.logger.w("⚠️ Échec de la déconnexion Supabase (offline?): ${e.message}", "SupabaseAuth")
            }

            // Effacer le stockage de session sécurisé
            secureSessionStorage.manualLogout()

            // CRITIQUE: Supprimer TOUTES les données locales de la base de données Room
            MotiumDatabase.clearAllData(context)
            MotiumApplication.logger.i("🗑️ Toutes les données locales supprimées (Room)", "SupabaseAuth")

            // SECURITY: Nettoyer TOUTES les SharedPreferences contenant des données utilisateur
            try {
                // Liste de toutes les SharedPreferences à nettoyer
                val prefsToClean = listOf(
                    "motium_trips",              // Trips data (+ last_user_id)
                    "pending_sync_queue",        // File de synchronisation
                    "ActivityRecognitionPrefs",  // Service de reconnaissance d'activité
                    "supabase_session_fallback", // Session Supabase fallback (si utilisé)
                    "theme_prefs"                // Theme preferences (couleurs favorites)
                )

                var clearedCount = 0
                prefsToClean.forEach { prefsName ->
                    try {
                        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
                        clearedCount++
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("⚠️ Failed to clear $prefsName: ${e.message}", "SupabaseAuth")
                    }
                }

                MotiumApplication.logger.i("🗑️ Cleared $clearedCount SharedPreferences files", "SupabaseAuth")
            } catch (e: Exception) {
                MotiumApplication.logger.e("⚠️ Failed to clear SharedPreferences: ${e.message}", "SupabaseAuth", e)
            }

            // Annuler la synchronisation en arrière-plan
            SyncScheduler.cancelSyncWork(context)

            // Mettre à jour l'état de l'UI
            _authState.value = AuthState(isAuthenticated = false, isLoading = false)

            MotiumApplication.logger.i("✅ Utilisateur déconnecté avec succès. Données locales effacées.", "SupabaseAuth")
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur de déconnexion: ${e.message}", "SupabaseAuth", e)
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
                provider = it.appMetadata?.get("provider")?.toString()
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

            // Sauvegarder l'utilisateur dans la base de données locale
            localUserRepository.saveUser(user, isLocallyConnected = true)
            MotiumApplication.logger.i("✅ Profil utilisateur sauvegardé dans la base locale", "SupabaseAuth")

            // Mettre à jour l'état de l'UI
            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = authUser,
                user = user,
                isLoading = false
            )

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
            // Mettre à jour dans Supabase
            val userProfile = user.toUserProfile()
            postgres.from("users").update(userProfile) { filter { UserProfile::id eq user.id } }

            // Mettre à jour dans la base de données locale
            localUserRepository.updateUser(user)
            MotiumApplication.logger.i("✅ Profil utilisateur mis à jour dans la base locale", "SupabaseAuth")

            // Mettre à jour l'état d'authentification
            _authState.value = _authState.value.copy(user = user)

            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to update user profile", e)
        }
    }

    private suspend fun updateAuthState() {
        MotiumApplication.logger.d("🔍 updateAuthState() called", "SupabaseAuth")
        val authUser = getCurrentAuthUser()
        MotiumApplication.logger.d("   authUser: ${authUser?.email}", "SupabaseAuth")

        if (authUser == null) {
            // ⚠️ CRITICAL: Dans une architecture offline-first, ne JAMAIS déconnecter l'utilisateur
            // si Supabase Auth retourne null temporairement (race condition pendant le refresh).
            // Vérifier d'abord si un utilisateur local existe dans Room.
            val localUser = localUserRepository.getLoggedInUser()
            if (localUser != null) {
                MotiumApplication.logger.w("⚠️ authUser is null but local user exists - keeping user authenticated", "SupabaseAuth")
                // Garder l'utilisateur connecté avec les données locales
                return
            }

            // Si pas d'utilisateur local non plus, alors vraiment déconnecté
            MotiumApplication.logger.w("⚠️ authUser and local user both null - setting isAuthenticated = false", "SupabaseAuth")
            _authState.value = AuthState(isAuthenticated = false, isLoading = false)
            return
        }

        val userProfileResult = getUserProfile(authUser.id)
        MotiumApplication.logger.d("   userProfileResult: ${if (userProfileResult is AuthResult.Success) "Success" else "Error"}", "SupabaseAuth")
        val user = if (userProfileResult is AuthResult.Success) userProfileResult.data else null

        _authState.value = AuthState(
            isAuthenticated = true,
            authUser = authUser,
            user = user,
            isLoading = false,
            error = if (user == null) "Failed to load user profile." else null
        )
        MotiumApplication.logger.d("✅ updateAuthState() completed - isAuthenticated: true, user: ${user?.email}", "SupabaseAuth")
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
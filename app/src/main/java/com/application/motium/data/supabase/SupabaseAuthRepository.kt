package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.SyncScheduler
import com.application.motium.domain.model.AuthResult
import com.application.motium.domain.model.AuthState
import com.application.motium.domain.model.AuthUser
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.LoginRequest
import com.application.motium.domain.model.RegisterRequest
import com.application.motium.domain.model.Subscription
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.domain.model.User
import com.application.motium.domain.model.UserRole
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
        val stripe_customer_id: String? = null,
        val stripe_subscription_id: String? = null,
        val monthly_trip_count: Int = 0,
        // Pro link fields
        val linked_pro_account_id: String? = null,
        val link_status: String? = null,
        val invitation_token: String? = null,
        val invited_at: String? = null,
        val link_activated_at: String? = null,
        // Sharing preferences
        val share_professional_trips: Boolean = true,
        val share_personal_trips: Boolean = false,
        val share_vehicle_info: Boolean = true,
        val share_expenses: Boolean = false,
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
     * Tente une reconnexion silencieuse avec les credentials stockés.
     * Retourne true si la reconnexion a réussi, false sinon.
     */
    private suspend fun trySilentReAuthentication(): Boolean {
        val credentials = secureSessionStorage.getCredentials()
        if (credentials == null) {
            MotiumApplication.logger.i("ℹ️ Pas de credentials stockés pour reconnexion silencieuse", "SupabaseAuth")
            return false
        }

        return try {
            MotiumApplication.logger.i("🔄 Tentative de reconnexion silencieuse pour ${credentials.email}...", "SupabaseAuth")

            withTimeout(15_000L) {
                auth.signInWith(Email) {
                    email = credentials.email
                    password = credentials.password
                }
            }

            val authUser = getCurrentAuthUser()
            if (authUser != null) {
                saveCurrentSessionSecurely()

                val userProfileResult = getUserProfile(authUser.id)
                val user = if (userProfileResult is AuthResult.Success) userProfileResult.data else null

                if (user != null) {
                    localUserRepository.saveUser(user, isLocallyConnected = true)
                }

                _authState.value = AuthState(
                    isAuthenticated = true,
                    authUser = authUser,
                    user = user,
                    isLoading = false
                )

                MotiumApplication.logger.i("✅ Reconnexion silencieuse réussie pour ${credentials.email}", "SupabaseAuth")
                true
            } else {
                MotiumApplication.logger.w("⚠️ authUser null après reconnexion silencieuse", "SupabaseAuth")
                false
            }
        } catch (e: TimeoutCancellationException) {
            MotiumApplication.logger.w("⏱️ Timeout lors de la reconnexion silencieuse", "SupabaseAuth")
            false
        } catch (e: Exception) {
            MotiumApplication.logger.w("⚠️ Échec de la reconnexion silencieuse: ${e.message}", "SupabaseAuth")
            // Si le mot de passe a changé, effacer les credentials obsolètes
            if (e.message?.contains("Invalid login credentials", ignoreCase = true) == true) {
                secureSessionStorage.clearCredentials()
                MotiumApplication.logger.i("🗑️ Credentials obsolètes effacés", "SupabaseAuth")
            }
            false
        }
    }

    /**
     * Migre une ancienne session vers le nouveau système Room.
     * Si le refresh échoue, tente une reconnexion silencieuse avec les credentials stockés.
     */
    private suspend fun tryMigrateOldSession(session: SecureSessionStorage.SessionData) {
        try {
            // TIMEOUT: Éviter que la migration ne bloque indéfiniment
            withTimeout(15_000L) {
                auth.refreshSession(session.refreshToken)
            }
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
                    return // Migration réussie, on sort
                } else {
                    MotiumApplication.logger.w("⚠️ Échec de récupération du profil, tentative de reconnexion silencieuse...", "SupabaseAuth")
                }
            } else {
                MotiumApplication.logger.w("⚠️ authUser null après refresh, tentative de reconnexion silencieuse...", "SupabaseAuth")
            }

            // Fallback: Tentative de reconnexion silencieuse
            if (trySilentReAuthentication()) {
                return
            }

            // Si tout échoue, marquer comme déconnecté
            _authState.value = AuthState(isLoading = false, isAuthenticated = false)

        } catch (e: TimeoutCancellationException) {
            MotiumApplication.logger.w("⏱️ Timeout lors de la migration, tentative de reconnexion silencieuse...", "SupabaseAuth")
            if (trySilentReAuthentication()) {
                return
            }
            _authState.value = AuthState(isLoading = false, isAuthenticated = false)
        } catch (e: Exception) {
            MotiumApplication.logger.w("⚠️ Échec de la migration: ${e.message}, tentative de reconnexion silencieuse...", "SupabaseAuth")
            if (trySilentReAuthentication()) {
                return
            }
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

            // Sauvegarder les credentials pour la reconnexion automatique silencieuse
            secureSessionStorage.saveCredentials(request.email, request.password, "email")

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

            // Effacer le stockage de session sécurisé ET les credentials
            secureSessionStorage.manualLogout()
            secureSessionStorage.clearCredentials()

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
        subscription = Subscription(
            type = SubscriptionType.valueOf(subscription_type),
            expiresAt = subscription_expires_at?.let { Instant.parse(it) },
            stripeCustomerId = stripe_customer_id,
            stripeSubscriptionId = stripe_subscription_id
        ),
        monthlyTripCount = monthly_trip_count,
        // Pro link fields
        linkedProAccountId = linked_pro_account_id,
        linkStatus = link_status?.let { LinkStatus.valueOf(it.uppercase()) },
        invitationToken = invitation_token,
        invitedAt = invited_at?.let { Instant.parse(it) },
        linkActivatedAt = link_activated_at?.let { Instant.parse(it) },
        // Sharing preferences
        shareProfessionalTrips = share_professional_trips,
        sharePersonalTrips = share_personal_trips,
        shareVehicleInfo = share_vehicle_info,
        shareExpenses = share_expenses,
        createdAt = Instant.parse(created_at), updatedAt = Instant.parse(updated_at)
    )

    private fun User.toUserProfile(): UserProfile = UserProfile(
        id = id, auth_id = id, name = name, email = email, role = role.name,
        organization_id = organizationId, organization_name = organizationName,
        subscription_type = subscription.type.name,
        subscription_expires_at = subscription.expiresAt?.toString(),
        stripe_customer_id = subscription.stripeCustomerId,
        stripe_subscription_id = subscription.stripeSubscriptionId,
        monthly_trip_count = monthlyTripCount,
        // Pro link fields
        linked_pro_account_id = linkedProAccountId,
        link_status = linkStatus?.name?.lowercase(),
        invitation_token = invitationToken,
        invited_at = invitedAt?.toString(),
        link_activated_at = linkActivatedAt?.toString(),
        // Sharing preferences
        share_professional_trips = shareProfessionalTrips,
        share_personal_trips = sharePersonalTrips,
        share_vehicle_info = shareVehicleInfo,
        share_expenses = shareExpenses,
        created_at = createdAt.toString(), updated_at = updatedAt.toString()
    )

    /**
     * Get the current user's Pro account ID if they are a Pro user.
     * Returns null if the user is not a Pro user or not authenticated.
     */
    suspend fun getCurrentProAccountId(): String? {
        return try {
            val currentUser = _authState.value.user ?: return null

            // Check if user is Enterprise/Pro role
            if (currentUser.role != UserRole.ENTERPRISE) {
                MotiumApplication.logger.d("User is not a Pro user", "SupabaseAuth")
                return null
            }

            // Query pro_accounts table for this user
            val proAccounts = postgres.from("pro_accounts")
                .select {
                    filter {
                        eq("user_id", currentUser.id)
                    }
                }
                .decodeList<ProAccountDto>()

            val proAccountId = proAccounts.firstOrNull()?.id
            MotiumApplication.logger.d("Pro account ID: $proAccountId", "SupabaseAuth")
            proAccountId
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting Pro account ID: ${e.message}", "SupabaseAuth", e)
            null
        }
    }
}

/**
 * DTO for pro_accounts table
 */
@Serializable
data class ProAccountDto(
    val id: String,
    @kotlinx.serialization.SerialName("user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("company_name")
    val companyName: String,
    val siret: String? = null,
    @kotlinx.serialization.SerialName("vat_number")
    val vatNumber: String? = null,
    @kotlinx.serialization.SerialName("legal_form")
    val legalForm: String? = null,
    @kotlinx.serialization.SerialName("billing_address")
    val billingAddress: String? = null,
    @kotlinx.serialization.SerialName("billing_email")
    val billingEmail: String? = null,
    @kotlinx.serialization.SerialName("stripe_customer_id")
    val stripeCustomerId: String? = null,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String,
    @kotlinx.serialization.SerialName("updated_at")
    val updatedAt: String
)
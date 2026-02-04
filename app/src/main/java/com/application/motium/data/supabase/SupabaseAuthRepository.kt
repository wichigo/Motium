package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.SyncScheduler
import com.application.motium.data.sync.TokenRefreshCoordinator
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import com.application.motium.domain.model.AuthResult
import com.application.motium.domain.model.AuthState
import com.application.motium.domain.model.AuthUser
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
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import com.application.motium.MotiumApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.days

class SupabaseAuthRepository(private val context: Context) : AuthRepository {

    private val client = SupabaseClient.client
    private val auth: Auth = client.auth
    private val postgres = client.postgrest
    private val secureSessionStorage = SecureSessionStorage(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }
    private val emailRepository by lazy { EmailRepository.getInstance(context) }
    private val database by lazy { MotiumDatabase.getInstance(context) }
    private val proAccountDao by lazy { database.proAccountDao() }

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

    // BATTERY OPTIMIZATION: Rate-limiting for session refresh to prevent excessive API calls
    private var lastRefreshTimestamp: Long = 0L
    private val MIN_REFRESH_INTERVAL_MS = 30_000L // Minimum 30 seconds between refreshes

    /**
     * Response from check_trial_abuse() RPC function
     */
    @Serializable
    data class TrialAbuseCheckResult(
        val allowed: Boolean,
        val reason: String? = null,
        val message: String? = null,
        val existing_email: String? = null
    )

    /**
     * Request parameters for check_trial_abuse() RPC function
     */
    @Serializable
    data class TrialAbuseCheckRequest(
        val p_email: String,
        val p_device_fingerprint: String
    )

    /**
     * Request parameters for create_user_profile_on_signup() RPC function
     * This function bypasses RLS to create user profile even when email is not confirmed yet
     */
    @Serializable
    data class CreateUserProfileRequest(
        val p_auth_id: String,
        val p_name: String,
        val p_email: String,
        val p_role: String = "INDIVIDUAL",
        val p_device_fingerprint_id: String? = null
    )

    /**
     * Response from create_user_profile_on_signup() RPC function
     */
    @Serializable
    data class CreateUserProfileResult(
        val success: Boolean,
        val user_id: String? = null,
        val error: String? = null,
        val message: String? = null
    )

    @Serializable
    data class UserProfile(
        val id: String? = null,
        val auth_id: String,
        val name: String,
        val email: String,
        val role: String,
        val subscription_type: String = "TRIAL",
        val subscription_expires_at: String? = null,
        val trial_started_at: String? = null,
        val trial_ends_at: String? = null,
        val stripe_customer_id: String? = null,
        val stripe_subscription_id: String? = null,
        val device_fingerprint_id: String? = null,
        // User preferences
        val phone_number: String = "",
        val address: String = "",
        val consider_full_distance: Boolean = false,
        val favorite_colors: List<String> = emptyList(), // JSON array of color strings
        val version: Int = 1, // Optimistic locking version (synced from server)
        // Note: Pro link fields (linked_pro_account_id, link_status, sharing preferences, etc.)
        // are now managed in the company_links table
        val created_at: String,
        val updated_at: String
    )

    /**
     * DTO for stripe_subscriptions table - used to fetch cancel_at_period_end status.
     * Only includes fields needed for the cancellation status check.
     */
    @Serializable
    data class StripeSubscriptionDto(
        val id: String,
        val user_id: String? = null,
        val status: String? = null,
        val cancel_at_period_end: Boolean = false
    )

    /**
     * Data class for UPDATE operations only.
     * Does NOT include id, auth_id, or created_at to avoid corrupting these fields.
     * Supabase RLS policy checks auth.uid() = auth_id, so we must NOT send auth_id in updates.
     */
    @Serializable
    data class UserProfileUpdate(
        val name: String,
        val email: String,
        val role: String,
        val subscription_type: String = "TRIAL",
        val subscription_expires_at: String? = null,
        val trial_started_at: String? = null,
        val trial_ends_at: String? = null,
        val stripe_customer_id: String? = null,
        val stripe_subscription_id: String? = null,
        val device_fingerprint_id: String? = null,
        val phone_number: String = "",
        val address: String = "",
        val consider_full_distance: Boolean = false,
        val favorite_colors: List<String> = emptyList(),
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

            // ÉTAPE 0: CRITIQUE - Attendre que le SDK Supabase ait fini de charger la session depuis SecureSessionManager
            // Sans cela, auth.refreshSession() échoue avec "Session not found" car le SDK n'a pas encore chargé la session
            try {
                withTimeout(5_000L) {
                    auth.awaitInitialization()
                }
                MotiumApplication.logger.i("✅ Supabase Auth SDK initialized", "SupabaseAuth")
            } catch (e: TimeoutCancellationException) {
                MotiumApplication.logger.w("⚠️ Supabase Auth SDK initialization timeout - continuing with local data", "SupabaseAuth")
            } catch (e: Exception) {
                MotiumApplication.logger.w("⚠️ Supabase Auth SDK initialization error: ${e.message}", "SupabaseAuth")
            }

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
                // IMPORTANT: initialSyncDone = false pour éviter navigation vers trial_expired avant sync
                _authState.value = AuthState(
                    isAuthenticated = true,
                    authUser = authUser,
                    user = localUser,
                    isLoading = false,
                    initialSyncDone = false
                )

                // ÉTAPE 2: Rafraîchir la session DANS le mutex lock (pas dans un nouveau coroutine)
                // SÉCURITÉ: Exécution synchrone avec timeout pour éviter race conditions
                try {
                    withTimeout(10_000L) { // Timeout 10 secondes
                        refreshSessionSafe()
                    }
                    // Sync réussie - marquer comme terminée
                    _authState.value = _authState.value.copy(initialSyncDone = true)
                    MotiumApplication.logger.i("✅ Initial sync completed successfully", "SupabaseAuth")
                } catch (e: TimeoutCancellationException) {
                    MotiumApplication.logger.w(
                        "⏱️ Session refresh timeout - keeping local session (offline mode)",
                        "SupabaseAuth"
                    )
                    // Timeout = sync terminée (mode offline), on utilise les données locales
                    _authState.value = _authState.value.copy(initialSyncDone = true)
                } catch (e: Exception) {
                    MotiumApplication.logger.w(
                        "⚠️ Session refresh failed: ${e.message} - keeping local session (offline mode)",
                        "SupabaseAuth"
                    )
                    // Échec = sync terminée (mode offline), on utilise les données locales
                    _authState.value = _authState.value.copy(initialSyncDone = true)
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
                    _authState.value = AuthState(isLoading = false, isAuthenticated = false, initialSyncDone = true)
                }
            }
        }
    }

    /**
     * SÉCURITÉ: Rafraîchit la session de manière sûre sans race condition.
     * Cette fonction s'exécute dans le sessionMutex lock.
     *
     * IMPORTANT: Cette méthode suppose que auth.awaitInitialization() a déjà été appelée
     * pour que le SDK ait chargé la session depuis SecureSessionManager.
     *
     * Si le refresh échoue (ex: migration JWT HS256 -> ES256), tente une reconnexion silencieuse.
     */
    private suspend fun refreshSessionSafe() {
        // Vérifier d'abord si le SDK a une session chargée
        val currentSession = auth.currentSessionOrNull()

        if (currentSession != null) {
            // Cas idéal: Le SDK a déjà une session - utiliser refreshCurrentSession()
            try {
                MotiumApplication.logger.i("🔄 Refreshing session via SDK (session loaded)...", "SupabaseAuth")
                auth.refreshCurrentSession()
                saveCurrentSessionSecurely()
                syncUserProfileFromSupabase()
                MotiumApplication.logger.i("✅ Session refreshed successfully via SDK", "SupabaseAuth")
                return
            } catch (e: Exception) {
                MotiumApplication.logger.w(
                    "⚠️ SDK session refresh failed: ${e.message}. Trying with stored refresh token...",
                    "SupabaseAuth"
                )
                // Fallback vers la méthode manuelle
            }
        }

        // Fallback: Utiliser le refresh token stocké manuellement
        val refreshToken = secureSessionStorage.getRefreshToken()
        if (refreshToken != null) {
            try {
                MotiumApplication.logger.i("🔄 Refreshing session with stored token...", "SupabaseAuth")
                auth.refreshSession(refreshToken)
                saveCurrentSessionSecurely()
                syncUserProfileFromSupabase()
                MotiumApplication.logger.i("✅ Session refreshed successfully with stored token", "SupabaseAuth")
            } catch (e: Exception) {
                // Refresh failed - could be network or auth error
                MotiumApplication.logger.w(
                    "⚠️ Session refresh failed: ${e.message}. Staying in offline mode with local session.",
                    "SupabaseAuth"
                )

                // OFFLINE-FIRST: Do NOT trigger silent re-authentication here as it causes
                // Samsung Pass/Password Manager loops on some devices.
                // We trust the local user data (Room) which is already loaded.

                // If it's a permanent error (invalid grant/token), the next explicit action
                // by the user will fail and trigger a clean logout/login flow.
            }
        } else {
            // Pas de refresh token - signaler que l'utilisateur doit se reconnecter
            MotiumApplication.logger.w(
                "⚠️ No session in SDK and no stored refresh token - user needs to re-login (needsRelogin=true)",
                "SupabaseAuth"
            )
            _authState.value = _authState.value.copy(needsRelogin = true)
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
                if (userProfileResult is AuthResult.Success) {
                    val user = userProfileResult.data
                    localUserRepository.saveUser(user, isLocallyConnected = true)

                    _authState.value = AuthState(
                        isAuthenticated = true,
                        authUser = authUser,
                        user = user,
                        isLoading = false,
                        initialSyncDone = true
                    )

                    MotiumApplication.logger.i("✅ Reconnexion silencieuse réussie pour ${credentials.email}", "SupabaseAuth")
                    return true
                } else {
                    MotiumApplication.logger.w("⚠️ Profil non trouvé lors de la reconnexion silencieuse", "SupabaseAuth")
                    return false
                }
            } else {
                MotiumApplication.logger.w("⚠️ authUser null après reconnexion silencieuse", "SupabaseAuth")
                return false
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
                        isLoading = false,
                        initialSyncDone = true
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
            _authState.value = AuthState(isLoading = false, isAuthenticated = false, initialSyncDone = true)

        } catch (e: TimeoutCancellationException) {
            MotiumApplication.logger.w("⏱️ Timeout lors de la migration, tentative de reconnexion silencieuse...", "SupabaseAuth")
            if (trySilentReAuthentication()) {
                return
            }
            _authState.value = AuthState(isLoading = false, isAuthenticated = false, initialSyncDone = true)
        } catch (e: Exception) {
            MotiumApplication.logger.w("⚠️ Échec de la migration: ${e.message}, tentative de reconnexion silencieuse...", "SupabaseAuth")
            if (trySilentReAuthentication()) {
                return
            }
            _authState.value = AuthState(isLoading = false, isAuthenticated = false, initialSyncDone = true)
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

    /**
     * OFFLINE-FIRST: Vérifie si l'utilisateur est authentifié en priorisant le cache local.
     * Cette méthode ne fait JAMAIS d'appel réseau bloquant.
     *
     * @return true si un utilisateur est connecté localement, même sans connexion réseau
     */
    suspend fun isUserAuthenticatedOfflineFirst(): Boolean {
        // Priorité 1: Vérifier le cache local Room (rapide, offline-friendly)
        val localUser = localUserRepository.getLoggedInUser()
        if (localUser != null) {
            MotiumApplication.logger.d("✅ User authenticated (local cache): ${localUser.email}", "SupabaseAuth")
            return true
        }

        // Priorité 2: Vérifier l'état actuel (déjà chargé en mémoire)
        if (_authState.value.isAuthenticated) {
            return true
        }

        // Priorité 3: Vérifier Supabase si online (fire-and-forget, ne bloque pas)
        return try {
            auth.currentUserOrNull() != null
        } catch (e: Exception) {
            // Erreur réseau = pas de cache local = non authentifié
            MotiumApplication.logger.d("⚠️ Network error checking auth, no local cache: ${e.message}", "SupabaseAuth")
            false
        }
    }

    suspend fun refreshSession() {
        // BATTERY OPTIMIZATION: Rate-limit refresh calls to prevent excessive API usage
        val now = System.currentTimeMillis()
        val timeSinceLastRefresh = now - lastRefreshTimestamp
        if (timeSinceLastRefresh < MIN_REFRESH_INTERVAL_MS) {
            MotiumApplication.logger.d(
                "⏳ Session refresh skipped (rate-limited, ${timeSinceLastRefresh}ms since last refresh)",
                "SupabaseAuth"
            )
            return
        }
        lastRefreshTimestamp = now

        try {
            // Priorité 1: Utiliser la session SDK si disponible (méthode recommandée)
            val currentSession = auth.currentSessionOrNull()
            if (currentSession != null) {
                MotiumApplication.logger.i("🔄 Refreshing session via SDK...", "SupabaseAuth")
                auth.refreshCurrentSession()
                saveCurrentSessionSecurely()
                updateAuthState()
                MotiumApplication.logger.i("✅ Session refreshed via SDK successfully.", "SupabaseAuth")
                return
            }

            // Priorité 2: Utiliser le refresh token stocké manuellement
            val refreshToken = secureSessionStorage.getRefreshToken()
            if (refreshToken != null) {
                MotiumApplication.logger.i("🔄 Refreshing session with stored token...", "SupabaseAuth")
                auth.refreshSession(refreshToken)
                saveCurrentSessionSecurely()
                updateAuthState()
                MotiumApplication.logger.i("✅ Session refreshed with stored token successfully.", "SupabaseAuth")
            } else {
                // OFFLINE-FIRST: Pas de refresh token - tenter une reconnexion silencieuse
                MotiumApplication.logger.w(
                    "⚠️ No refresh token - attempting silent re-authentication...",
                    "SupabaseAuth"
                )
                val reAuthSuccess = trySilentReAuthentication()
                if (reAuthSuccess) {
                    MotiumApplication.logger.i("✅ Silent re-authentication successful", "SupabaseAuth")
                    return
                }

                // Échec de la réauth silencieuse - vérifier si utilisateur local existe
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    MotiumApplication.logger.w(
                        "⚠️ Silent re-auth failed but local user exists - keeping session (offline mode, needsRelogin=true)",
                        "SupabaseAuth"
                    )
                    // Garder l'utilisateur connecté avec les données locales MAIS signaler qu'il doit se reconnecter
                    // pour activer le sync (typiquement: utilisateur Google sans refresh token)
                    _authState.value = _authState.value.copy(needsRelogin = true)
                    return
                }
                // Pas de token ET pas d'utilisateur local = vraiment déconnecté
                signOut()
            }
        } catch (e: Exception) {
            // OFFLINE-FIRST: Distinguer les erreurs permanentes des erreurs réseau temporaires
            val isPermanentAuthError = e is RestException && (e.statusCode == 401 || e.statusCode == 400)

            if (isPermanentAuthError) {
                // Erreur d'authentification permanente (token invalide, révoqué, etc.)
                // MAIS vérifier d'abord si on a un utilisateur local avant de déconnecter
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    // Tenter une reconnexion silencieuse avant de forcer la déconnexion
                    MotiumApplication.logger.w(
                        "⚠️ Auth error but local user exists - attempting silent re-auth...",
                        "SupabaseAuth"
                    )
                    val reAuthSuccess = trySilentReAuthentication()
                    if (reAuthSuccess) {
                        MotiumApplication.logger.i("✅ Silent re-authentication successful after auth error", "SupabaseAuth")
                        return
                    }
                }

                MotiumApplication.logger.e("❌ PERMANENT AUTH ERROR on refresh: ${e.message}. Logging out.", "SupabaseAuth", e)
                signOut()
            } else {
                // Erreur réseau temporaire (timeout, DNS, no internet, etc.)
                // NE JAMAIS déconnecter sur une erreur réseau
                MotiumApplication.logger.w(
                    "⚠️ Temporary network error on refresh: ${e.message}. User remains logged in (offline-first).",
                    "SupabaseAuth"
                )
                // L'utilisateur reste connecté avec ses données locales
            }
        }
    }

    /**
     * Refresh session specifically for DeltaSyncWorker.
     * Returns true if session is valid and sync can proceed, false otherwise.
     *
     * Unlike refreshSession(), this method:
     * - Returns a boolean instead of throwing/catching
     * - Does NOT call signOut() on failure (to preserve local data)
     * - Is designed to be called before sync operations
     */
    suspend fun refreshSessionForSync(): Boolean {
        return try {
            // Priorité 1: Utiliser la session SDK si disponible (méthode recommandée)
            val currentSession = auth.currentSessionOrNull()
            if (currentSession != null) {
                MotiumApplication.logger.i("🔄 Refreshing session for sync via SDK...", "SupabaseAuth")
                auth.refreshCurrentSession()
                saveCurrentSessionSecurely()

                val refreshedSession = auth.currentSessionOrNull()
                if (refreshedSession?.user != null) {
                    MotiumApplication.logger.i("✅ Session refreshed for sync via SDK - user: ${refreshedSession.user?.email}", "SupabaseAuth")
                    return true
                }
            }

            // Priorité 2: Utiliser le refresh token stocké manuellement
            val refreshToken = secureSessionStorage.getRefreshToken()
            if (refreshToken == null) {
                MotiumApplication.logger.w("⚠️ No refresh token for sync - trying silent re-authentication...", "SupabaseAuth")
                // Priorité 3: Tenter une reconnexion silencieuse avec les credentials stockés
                val reAuthSuccess = trySilentReAuthentication()
                if (reAuthSuccess) {
                    MotiumApplication.logger.i("✅ Silent re-authentication successful for sync", "SupabaseAuth")
                    return true
                }
                MotiumApplication.logger.w("⚠️ Silent re-authentication failed - no refresh token and no valid credentials", "SupabaseAuth")
                return false
            }

            MotiumApplication.logger.i("🔄 Refreshing session for sync with stored token...", "SupabaseAuth")
            auth.refreshSession(refreshToken)

            // CRITICAL FIX: After auth.refreshSession(), the SDK should have the session in memory.
            // If auth.currentSessionOrNull() returns null, the SDK didn't properly load the session.
            // In this case, we need to wait for the SDK to process the refresh and try again.
            var refreshedSession = auth.currentSessionOrNull()
            if (refreshedSession == null) {
                MotiumApplication.logger.w("⚠️ SDK session is null after refresh - waiting and retrying...", "SupabaseAuth")
                // Give the SDK time to propagate the session (race condition workaround)
                kotlinx.coroutines.delay(100)
                refreshedSession = auth.currentSessionOrNull()
            }

            // Save session to secure storage regardless of SDK state
            saveCurrentSessionSecurely()

            // Verify we got a valid user session (not just anon key)
            if (refreshedSession?.accessToken != null && refreshedSession.accessToken.isNotBlank()) {
                // Check if it's a real user token (not the anon key)
                val isRealUserToken = !refreshedSession.accessToken.contains("\"role\": \"anon\"") &&
                    !refreshedSession.accessToken.contains("\"role\":\"anon\"")
                if (isRealUserToken) {
                    MotiumApplication.logger.i("✅ Session refreshed for sync - SDK has valid user token", "SupabaseAuth")
                    return true
                }
            }

            // SDK doesn't have valid session - check secure storage as fallback
            val storedSession = secureSessionStorage.restoreSession()
            if (storedSession != null && storedSession.accessToken.isNotBlank()) {
                // Verify the stored token is a user token (not anon key by checking for 'sub' claim)
                try {
                    val parts = storedSession.accessToken.split(".")
                    if (parts.size == 3) {
                        val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
                        val hasUserSub = payload.contains("\"sub\"") && !payload.contains("\"role\":\"anon\"")
                        if (hasUserSub) {
                            MotiumApplication.logger.w(
                                "⚠️ SDK session invalid but secure storage has valid user token - forcing SDK to use stored session",
                                "SupabaseAuth"
                            )
                            // Force the SDK to use our stored session by importing it
                            try {
                                val userSession = io.github.jan.supabase.auth.user.UserSession(
                                    accessToken = storedSession.accessToken,
                                    refreshToken = storedSession.refreshToken,
                                    expiresIn = ((storedSession.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
                                    tokenType = storedSession.tokenType,
                                    user = null
                                )
                                auth.importSession(userSession)
                                MotiumApplication.logger.i("✅ Imported stored session into SDK - sync can proceed", "SupabaseAuth")
                                return true
                            } catch (importError: Exception) {
                                MotiumApplication.logger.w(
                                    "⚠️ Failed to import session into SDK: ${importError.message} - sync may fail",
                                    "SupabaseAuth"
                                )
                            }
                            return true // Proceed anyway, SDK might use the session
                        }
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to decode stored token: ${e.message}", "SupabaseAuth")
                }
            }

            MotiumApplication.logger.w("⚠️ Session refresh succeeded but no valid user token available", "SupabaseAuth")
            false
        } catch (e: Exception) {
            // Don't sign out on network errors - just return false to skip sync
            MotiumApplication.logger.w(
                "⚠️ Session refresh for sync failed: ${e.message}. Sync will be skipped.",
                "SupabaseAuth"
            )
            false
        }
    }

    override suspend fun signUp(request: RegisterRequest): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)

            // Capture the result from signUpWith - it contains user info even without session
            val signUpResult = withTimeout(15_000L) {
                auth.signUpWith(Email) {
                    email = request.email
                    password = request.password
                }
            }

            MotiumApplication.logger.i("📝 signUpWith completed, result: $signUpResult", "SupabaseAuth")

            // IMPORTANT: After signup with email confirmation required, there's NO session!
            // auth.currentUserOrNull() will return null.
            // We need to use the signUpResult or try to get user info differently.

            // First try: get from current session (works if email confirmation is disabled)
            var authUser = getCurrentAuthUser()

            if (authUser == null && signUpResult != null) {
                // Second try: construct from signUpResult
                // signUpResult is a UserInfo object containing user id and email
                MotiumApplication.logger.i("📝 No session after signup (email confirmation required), using signUpResult", "SupabaseAuth")
                authUser = AuthUser(
                    id = signUpResult.id,
                    email = signUpResult.email ?: request.email,
                    isEmailConfirmed = signUpResult.emailConfirmedAt != null,
                    provider = "email"
                )
            }

            if (authUser == null) {
                throw Exception("Failed to get user info after signup - no result from Supabase")
            }

            MotiumApplication.logger.i("✅ signUp successful: userId=${authUser.id}, email=${authUser.email}", "SupabaseAuth")

            // Try to save session if one exists (might not with email confirmation)
            try {
                saveCurrentSessionSecurely()
            } catch (e: Exception) {
                MotiumApplication.logger.w("Could not save session after signup (expected if email confirmation required): ${e.message}", "SupabaseAuth")
            }

            // NOTE: Do NOT call updateAuthState() here!
            // The user profile doesn't exist yet in the database - it will be created
            // by createUserProfileWithTrial() which is called after signUp().
            AuthResult.Success(authUser)
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Signup failed"
            MotiumApplication.logger.w("⚠️ signUp caught exception: $errorMessage", "SupabaseAuth")

            // IMPORTANT: Supabase rate limiting can throw errors EVEN when the account was created
            // Check if the user was actually created despite the error
            val isRateLimitError = errorMessage.contains("rate_limit", ignoreCase = true) ||
                    errorMessage.contains("too_many_requests", ignoreCase = true) ||
                    errorMessage.contains("over_email_send_rate_limit", ignoreCase = true)

            if (isRateLimitError) {
                MotiumApplication.logger.i("🔄 Rate limit error detected, checking if account was created anyway...", "SupabaseAuth")
                delay(500)

                // Try to get the auth user from session
                val authUser = try {
                    getCurrentAuthUser()
                } catch (e2: Exception) {
                    MotiumApplication.logger.w("Could not get auth user after rate limit: ${e2.message}", "SupabaseAuth")
                    null
                }

                if (authUser != null) {
                    MotiumApplication.logger.i("✅ Account WAS created despite rate limit error! Proceeding...", "SupabaseAuth")
                    try { saveCurrentSessionSecurely() } catch (_: Exception) {}
                    return AuthResult.Success(authUser)
                } else {
                    MotiumApplication.logger.w("❌ Account was NOT created after rate limit error", "SupabaseAuth")
                }
            }

            _authState.value = _authState.value.copy(isLoading = false, error = errorMessage)
            AuthResult.Error(errorMessage, e)
        }
    }

    override suspend fun signIn(request: LoginRequest): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)

            // CLEANUP: Clear stale pending operations before sign-in
            // This prevents RLS violations if previous user had pending sync operations
            try {
                val count = database.pendingOperationDao().getCount()
                if (count > 0) {
                    MotiumApplication.logger.i(
                        "Clearing $count stale pending operations before email sign-in",
                        "SupabaseAuth"
                    )
                    database.pendingOperationDao().deleteAll()
                }
            } catch (e: Exception) {
                MotiumApplication.logger.w("Failed to clear pending operations: ${e.message}", "SupabaseAuth")
            }

            // Authentification avec Supabase
            withTimeout(15_000L) {
                auth.signInWith(Email) {
                    email = request.email
                    password = request.password
                }
            }

            val authUser = getCurrentAuthUser() ?: throw Exception("Failed to get user info after signin")

            // SECURITY: Check if email is confirmed before allowing full login
            // Users with unconfirmed emails should not be able to use the app
            if (!authUser.isEmailConfirmed) {
                MotiumApplication.logger.w(
                    "⚠️ Email not confirmed for ${authUser.email} - blocking login",
                    "SupabaseAuth"
                )
                // Sign out the user to prevent session persistence
                try { auth.signOut() } catch (e: Exception) {}
                _authState.value = _authState.value.copy(isLoading = false)
                return AuthResult.Error("EMAIL_NOT_VERIFIED:${authUser.email}:${authUser.id}")
            }

            // Sauvegarder les tokens de session
            saveCurrentSessionSecurely()

            // Récupérer le profil utilisateur depuis Supabase
            val userProfileResult = getUserProfile(authUser.id)

            val user = if (userProfileResult is AuthResult.Error) {
                // Profile doesn't exist - this is the first login after email confirmation
                // Create the profile now using pending registration info
                MotiumApplication.logger.i("📝 First login after email confirmation - creating profile for: ${authUser.email}", "SupabaseAuth")

                val pendingInfo = getPendingRegistrationInfo(authUser.email ?: request.email)
                val profileResult = createUserProfileWithTrial(
                    userId = authUser.id,
                    name = pendingInfo.name,
                    isProfessional = pendingInfo.isProfessional,
                    organizationName = pendingInfo.organizationName,
                    verifiedPhone = "",
                    deviceFingerprintId = null
                )

                when (profileResult) {
                    is AuthResult.Success -> {
                        MotiumApplication.logger.i("✅ Profile created successfully at first login", "SupabaseAuth")
                        // Clear pending info after successful profile creation
                        clearPendingRegistrationInfo()
                        profileResult.data
                    }
                    is AuthResult.Error -> {
                        MotiumApplication.logger.e("❌ Failed to create profile at first login: ${profileResult.message}", "SupabaseAuth")
                        try { auth.signOut() } catch (e: Exception) {}
                        return AuthResult.Error("Impossible de créer votre profil. Veuillez réessayer. (${profileResult.message})")
                    }
                    AuthResult.Loading -> throw Exception("Unexpected loading state")
                }
            } else {
                (userProfileResult as AuthResult.Success).data
            }

            // CRITIQUE: Sauvegarder l'utilisateur dans la base de données locale Room pour l'accès offline
            localUserRepository.saveUser(user, isLocallyConnected = true)
            MotiumApplication.logger.i("✅ Utilisateur sauvegardé dans la base locale: ${user.email}", "SupabaseAuth")

            // Sauvegarder les credentials pour la reconnexion automatique silencieuse
            secureSessionStorage.saveCredentials(request.email, request.password, "email")

            // Planifier la synchronisation en arrière-plan
            SyncScheduler.scheduleSyncWork(context)

            // Mettre à jour l'état de l'UI
            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = authUser,
                user = user,
                isLoading = false,
                initialSyncDone = true
            )

            return AuthResult.Success(authUser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ signIn caught exception: ${e.message}", "SupabaseAuth", e)

            val errorMessageLower = e.message?.lowercase() ?: ""

            // Check if the error is about email not confirmed
            // Supabase may return this error directly instead of allowing login
            if (errorMessageLower.contains("email") && (errorMessageLower.contains("confirm") || errorMessageLower.contains("verif"))) {
                MotiumApplication.logger.w("⚠️ Email confirmation error detected: ${e.message}", "SupabaseAuth")
                _authState.value = _authState.value.copy(isLoading = false, error = null)
                return AuthResult.Error("EMAIL_NOT_VERIFIED:${request.email}:")
            }

            // Check for invalid credentials error (wrong email/password)
            // Supabase returns "invalid_credentials" or similar messages
            if (errorMessageLower.contains("invalid") && errorMessageLower.contains("credentials") ||
                errorMessageLower.contains("invalid login credentials") ||
                errorMessageLower.contains("invalid_credentials")) {
                val userFriendlyMessage = "Identifiant ou mot de passe erroné"
                _authState.value = _authState.value.copy(isLoading = false, error = userFriendlyMessage)
                return AuthResult.Error(userFriendlyMessage, e)
            }

            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Login failed", e)
        }
    }

    /**
     * Data class for pending registration info stored between signup and first login.
     */
    private data class PendingRegistrationInfo(
        val name: String,
        val isProfessional: Boolean,
        val organizationName: String
    )

    /**
     * Gets pending registration info from SharedPreferences.
     * Falls back to email-based defaults if no pending info exists.
     */
    private fun getPendingRegistrationInfo(email: String): PendingRegistrationInfo {
        val prefs = context.getSharedPreferences("pending_registration", Context.MODE_PRIVATE)
        val storedEmail = prefs.getString("email", null)

        return if (storedEmail == email) {
            PendingRegistrationInfo(
                name = prefs.getString("name", null) ?: email.split("@").firstOrNull() ?: "User",
                isProfessional = prefs.getBoolean("isProfessional", false),
                organizationName = prefs.getString("organizationName", null) ?: ""
            )
        } else {
            // No pending info or different email - use defaults
            MotiumApplication.logger.w("No pending registration info for $email, using defaults", "SupabaseAuth")
            PendingRegistrationInfo(
                name = email.split("@").firstOrNull() ?: "User",
                isProfessional = false,
                organizationName = ""
            )
        }
    }

    /**
     * Clears pending registration info after successful profile creation.
     */
    private fun clearPendingRegistrationInfo() {
        val prefs = context.getSharedPreferences("pending_registration", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        MotiumApplication.logger.i("🗑️ Cleared pending registration info", "SupabaseAuth")
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
            _authState.value = AuthState(isAuthenticated = false, isLoading = false, initialSyncDone = true)

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

        if (currentSession == null) {
            MotiumApplication.logger.w("⚠️ saveCurrentSessionSecurely: No current session from SDK", "SupabaseAuth")
            return
        }
        if (currentUser == null) {
            MotiumApplication.logger.w("⚠️ saveCurrentSessionSecurely: No current user from SDK", "SupabaseAuth")
            return
        }
        if (currentSession.refreshToken == null) {
            MotiumApplication.logger.w("⚠️ saveCurrentSessionSecurely: Session has no refresh token! User: ${currentUser.email}", "SupabaseAuth")
            // Toujours sauvegarder le access token même sans refresh token
            // Cela permet au moins de faire des appels tant que le token est valide
            val expiresInSeconds = currentSession.expiresIn
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
            val sessionData = SecureSessionStorage.SessionData(
                accessToken = currentSession.accessToken,
                refreshToken = "", // Pas de refresh token - sera détecté au prochain refresh
                expiresAt = expiresAt,
                userId = currentUser.id,
                userEmail = currentUser.email ?: ""
            )
            secureSessionStorage.saveSession(sessionData)
            return
        }

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

    override suspend fun signInWithGoogle(idToken: String): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)

            // CLEANUP: Clear stale pending operations before sign-in
            // This prevents RLS violations if previous user had pending sync operations
            try {
                val count = database.pendingOperationDao().getCount()
                if (count > 0) {
                    MotiumApplication.logger.i(
                        "Clearing $count stale pending operations before Google sign-in",
                        "SupabaseAuth"
                    )
                    database.pendingOperationDao().deleteAll()
                }
            } catch (e: Exception) {
                MotiumApplication.logger.w("Failed to clear pending operations: ${e.message}", "SupabaseAuth")
            }

            // Sign in via Supabase Auth Google provider
            withTimeout(15_000L) {
                auth.signInWith(IDToken) {
                    this.idToken = idToken
                    provider = Google
                }
            }

            val authUser = getCurrentAuthUser()
                ?: throw Exception("Failed to get user info after Google sign-in")

            // DEBUG: Log session state after Google Sign-In
            val sessionAfterGoogle = auth.currentSessionOrNull()
            MotiumApplication.logger.i(
                "🔍 After Google Sign-In: hasSession=${sessionAfterGoogle != null}, " +
                "hasRefreshToken=${sessionAfterGoogle?.refreshToken != null}, " +
                "expiresIn=${sessionAfterGoogle?.expiresIn}",
                "SupabaseAuth"
            )

            saveCurrentSessionSecurely()

            // Récupérer ou créer profil utilisateur
            val userProfileResult = getUserProfile(authUser.id)

            val user = if (userProfileResult is AuthResult.Error) {
                // Premier sign-in Google - créer profil avec essai 14 jours
                MotiumApplication.logger.i("Creating profile for Google user", "SupabaseAuth")

                val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
                val trialEnds = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                    .plus(14.days).toString()

                // Step 1: Create profile first (without device_fingerprint_id)
                val userProfile = UserProfile(
                    auth_id = authUser.id,
                    name = authUser.email?.split("@")?.firstOrNull() ?: "User",
                    email = authUser.email ?: "",
                    role = "INDIVIDUAL",
                    subscription_type = "TRIAL",
                    trial_started_at = now,
                    trial_ends_at = trialEnds,
                    device_fingerprint_id = null,
                    created_at = now,
                    updated_at = now
                )

                postgres.from("users").insert(userProfile)
                val createdProfile = postgres.from("users")
                    .select { filter { UserProfile::auth_id eq authUser.id } }
                    .decodeSingle<UserProfile>()

                val createdUser = createdProfile.toDomainUser()

                // Step 2: Now register device (profile exists, FK constraint satisfied)
                val deviceFingerprintRepo = DeviceFingerprintRepository.getInstance(context)
                try {
                    deviceFingerprintRepo.registerDevice(createdUser.id)
                        .onSuccess { fingerprintId ->
                            // Step 3: Update user profile with device_fingerprint_id
                            try {
                                val userProfileUpdate = UserProfileUpdate(
                                    name = createdUser.name,
                                    email = createdUser.email,
                                    role = createdUser.role.name,
                                    subscription_type = "TRIAL",
                                    trial_started_at = now,
                                    trial_ends_at = trialEnds,
                                    device_fingerprint_id = fingerprintId,
                                    updated_at = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
                                )
                                postgres.from("users").update(userProfileUpdate) {
                                    filter { UserProfile::id eq createdUser.id }
                                }
                                MotiumApplication.logger.i(
                                    "Device fingerprint linked for Google user: $fingerprintId",
                                    "SupabaseAuth"
                                )
                            } catch (e: Exception) {
                                MotiumApplication.logger.w(
                                    "Failed to update profile with fingerprint: ${e.message}",
                                    "SupabaseAuth"
                                )
                            }
                        }
                } catch (e: Exception) {
                    MotiumApplication.logger.w(
                        "Failed to register device for Google user: ${e.message}",
                        "SupabaseAuth"
                    )
                }

                createdUser
            } else {
                (userProfileResult as AuthResult.Success).data
            }

            // Sauvegarder localement pour offline-first
            localUserRepository.saveUser(user, isLocallyConnected = true)
            MotiumApplication.logger.i("✅ Google user saved: ${user.email}", "SupabaseAuth")

            // Sauvegarder credentials avec provider type
            secureSessionStorage.saveCredentials(user.email, "", "google")

            SyncScheduler.scheduleSyncWork(context)

            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = authUser,
                user = user,
                isLoading = false,
                initialSyncDone = true
            )

            AuthResult.Success(authUser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Google sign-in failed: ${e.message}", "SupabaseAuth", e)
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Google sign-in failed", e)
        }
    }

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
            // Use custom email flow via Resend
            val result = emailRepository.requestPasswordReset(email)
            result.fold(
                onSuccess = {
                    MotiumApplication.logger.i("Password reset email sent to $email", "SupabaseAuth")
                    AuthResult.Success(Unit)
                },
                onFailure = { e ->
                    // If user not found, return success to prevent email enumeration
                    if (e.message?.contains("User not found") == true) {
                        MotiumApplication.logger.w("Password reset requested for unknown email: $email", "SupabaseAuth")
                        AuthResult.Success(Unit)
                    } else {
                        AuthResult.Error(e.message ?: "Failed to send reset email", e)
                    }
                }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Password reset error: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to send reset email", e)
        }
    }

    /**
     * Reset password using token (called after user clicks link in email)
     */
    suspend fun resetPasswordWithToken(token: String, newPassword: String): AuthResult<Unit> {
        return try {
            val result = emailRepository.resetPassword(token, newPassword)
            result.fold(
                onSuccess = {
                    MotiumApplication.logger.i("Password reset successful", "SupabaseAuth")
                    AuthResult.Success(Unit)
                },
                onFailure = { e ->
                    AuthResult.Error(e.message ?: "Failed to reset password", e)
                }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Password reset error: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to reset password", e)
        }
    }

    override suspend fun confirmEmail(token: String): AuthResult<Unit> = AuthResult.Error("Email confirmation not implemented")

    /**
     * Resend email confirmation to a user who hasn't verified their email yet.
     * Uses Supabase's native resend functionality.
     *
     * @param email The email address to send confirmation to
     */
    suspend fun resendConfirmationEmail(email: String) {
        MotiumApplication.logger.i("📧 Resending confirmation email to: $email", "SupabaseAuth")
        auth.resendEmail(io.github.jan.supabase.auth.OtpType.Email.SIGNUP, email)
        MotiumApplication.logger.i("✅ Confirmation email resent to: $email", "SupabaseAuth")
    }

    override suspend fun createUserProfile(authUser: AuthUser, name: String, isEnterprise: Boolean, organizationName: String): AuthResult<User> {
        return try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
            val role = if (isEnterprise) "ENTERPRISE" else "INDIVIDUAL"

            val userProfile = UserProfile(
                auth_id = authUser.id, name = name, email = authUser.email ?: "", role = role,
                created_at = now, updated_at = now
            )

            postgres.from("users").insert(userProfile)
            val createdProfile = postgres.from("users").select { filter { UserProfile::auth_id eq authUser.id } }.decodeSingle<UserProfile>()

            val user = createdProfile.toDomainUser()

            // Si c'est un compte ENTERPRISE, créer un pro_account avec le nom de l'entreprise
            // Note: pro_accounts.user_id est une FK vers public.users.id
            if (isEnterprise && organizationName.isNotBlank()) {
                try {
                    val proAccountRepo = ProAccountRemoteDataSource.getInstance(context)
                    proAccountRepo.createProAccount(
                        userId = user.id,
                        companyName = organizationName
                    )
                    MotiumApplication.logger.i("✅ Compte Pro créé pour ${user.email}", "SupabaseAuth")
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Erreur création compte Pro: ${e.message}", "SupabaseAuth", e)
                    // Continue même si la création du pro_account échoue - l'utilisateur pourra le compléter plus tard
                }
            }

            // Sauvegarder l'utilisateur dans la base de données locale
            localUserRepository.saveUser(user, isLocallyConnected = true)
            MotiumApplication.logger.i("✅ Profil utilisateur sauvegardé dans la base locale", "SupabaseAuth")

            // Mettre à jour l'état de l'UI
            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = authUser,
                user = user,
                isLoading = false,
                initialSyncDone = true  // Inscription = sync terminée
            )

            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to create user profile", e)
        }
    }

    /**
     * Create user profile with 14-day trial subscription.
     * Used during registration after phone verification.
     */
    override suspend fun createUserProfileWithTrial(
        userId: String,
        name: String,
        isProfessional: Boolean,
        organizationName: String,
        verifiedPhone: String,
        deviceFingerprintId: String?
    ): AuthResult<User> {
        return try {
            // Get current auth user email
            val authUser = auth.currentUserOrNull()
            val email = authUser?.email ?: ""

            MotiumApplication.logger.i("📝 createUserProfileWithTrial START: userId=$userId, name=$name, authUser=${authUser?.id}, email=$email", "SupabaseAuth")

            if (authUser == null) {
                MotiumApplication.logger.e("❌ authUser is NULL after signup! Cannot create profile.", "SupabaseAuth")
                return AuthResult.Error("Session invalide après inscription. Veuillez réessayer.")
            }

            if (email.isBlank()) {
                MotiumApplication.logger.e("❌ Email is blank! authUser.email=${authUser.email}", "SupabaseAuth")
                return AuthResult.Error("Email invalide. Veuillez réessayer.")
            }

            // SECURITY FIX: Check for trial abuse BEFORE creating profile
            // This prevents: Gmail aliases, device fingerprint reuse, disposable emails
            try {
                // RPC returns a single JSON object like {"allowed": true, ...}
                // decodeSingleOrNull expects a list wrapper, so we use decodeAs for direct object
                val abuseCheckResult = postgres.rpc(
                    "check_trial_abuse",
                    TrialAbuseCheckRequest(
                        p_email = email,
                        p_device_fingerprint = deviceFingerprintId ?: ""
                    )
                ).decodeAs<TrialAbuseCheckResult>()

                if (!abuseCheckResult.allowed) {
                    val reason = abuseCheckResult.reason ?: "UNKNOWN"
                    val message = abuseCheckResult.message ?: "Trial abuse detected"
                    MotiumApplication.logger.w(
                        "Trial abuse detected for $email: $reason - $message",
                        "SupabaseAuth"
                    )
                    return AuthResult.Error("$message (code: $reason)")
                }

                MotiumApplication.logger.i("✅ Trial abuse check PASSED for $email", "SupabaseAuth")
            } catch (e: Exception) {
                // SECURITY FIX: Fail-secure instead of fail-open
                // If we can't verify trial abuse, we MUST block registration to prevent abuse
                // The only exception is temporary network errors which may be retried
                val isNetworkError = e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("network", ignoreCase = true) == true ||
                        e.message?.contains("connect", ignoreCase = true) == true ||
                        e.message?.contains("unreachable", ignoreCase = true) == true

                if (isNetworkError) {
                    // Network error - block registration but with a user-friendly message
                    MotiumApplication.logger.w(
                        "Trial abuse check failed due to network error: ${e.message}. Blocking registration (fail-secure).",
                        "SupabaseAuth"
                    )
                    return AuthResult.Error(
                        "Impossible de vérifier votre éligibilité à l'essai gratuit. " +
                        "Veuillez vérifier votre connexion internet et réessayer."
                    )
                } else {
                    // Server error or other error - block registration
                    MotiumApplication.logger.e(
                        "Trial abuse check RPC failed: ${e.message}. Blocking registration (fail-secure).",
                        "SupabaseAuth",
                        e
                    )
                    return AuthResult.Error(
                        "Une erreur est survenue lors de la vérification. Veuillez réessayer dans quelques instants."
                    )
                }
            }

            val role = if (isProfessional) "ENTERPRISE" else "INDIVIDUAL"

            // FIX: Use RPC function with SECURITY DEFINER to bypass RLS
            // This is necessary because after signUp(), the user's email is not yet confirmed,
            // so auth.uid() returns NULL in RLS policies, blocking the INSERT.
            // The RPC function also auto-confirms the email in auth.users.
            MotiumApplication.logger.i("📝 Calling create_user_profile_on_signup RPC with: userId=$userId, name=$name, email=$email, role=$role, fingerprint=$deviceFingerprintId", "SupabaseAuth")

            val createResult = try {
                postgres.rpc(
                    "create_user_profile_on_signup",
                    CreateUserProfileRequest(
                        p_auth_id = userId,
                        p_name = name,
                        p_email = email,
                        p_role = role,
                        p_device_fingerprint_id = deviceFingerprintId
                    )
                ).decodeAs<CreateUserProfileResult>()
            } catch (rpcError: Exception) {
                MotiumApplication.logger.e("❌ RPC call threw exception: ${rpcError::class.simpleName} - ${rpcError.message}", "SupabaseAuth", rpcError)
                return AuthResult.Error("Erreur lors de la création du profil: ${rpcError.message}")
            }

            MotiumApplication.logger.i("📝 RPC result: $createResult", "SupabaseAuth")

            if (!createResult.success) {
                val errorMsg = createResult.message ?: createResult.error ?: "Failed to create user profile"
                MotiumApplication.logger.e("❌ create_user_profile_on_signup failed: $errorMsg (result=$createResult)", "SupabaseAuth")
                return AuthResult.Error(errorMsg)
            }

            MotiumApplication.logger.i("✅ User profile created via RPC: user_id=${createResult.user_id}", "SupabaseAuth")

            // Fetch the created profile to get all fields
            val createdProfile = postgres.from("users")
                .select { filter { UserProfile::auth_id eq userId } }
                .decodeSingle<UserProfile>()

            val user = createdProfile.toDomainUser()

            // If it's an ENTERPRISE account, create a pro_account
            // Note: Trial period is tracked in users table, not pro_accounts
            if (isProfessional && organizationName.isNotBlank()) {
                try {
                    val proAccountRepo = ProAccountRemoteDataSource.getInstance(context)
                    proAccountRepo.createProAccount(
                        userId = user.id,
                        companyName = organizationName
                    )
                    MotiumApplication.logger.i("✅ Pro account created for ${user.email}", "SupabaseAuth")
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Error creating pro account: ${e.message}", "SupabaseAuth", e)
                }
            }

            // Save user to local database
            localUserRepository.saveUser(user, isLocallyConnected = true)
            MotiumApplication.logger.i("✅ User profile with trial saved locally", "SupabaseAuth")

            // Update UI state
            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = AuthUser(id = userId, email = email, isEmailConfirmed = true),
                user = user,
                isLoading = false,
                initialSyncDone = true  // Inscription = sync terminée
            )

            AuthResult.Success(user)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to create user profile with trial: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to create user profile", e)
        }
    }

    override suspend fun getUserProfile(userId: String): AuthResult<User> {
        return try {
            val authId = auth.currentUserOrNull()?.id ?: return AuthResult.Error("User not authenticated")
            val userProfile = withTimeout(15_000L) {
                postgres.from("users").select { filter { UserProfile::auth_id eq authId } }.decodeSingle<UserProfile>()
            }

            // Fetch cancel_at_period_end from stripe_subscriptions table
            var cancelAtPeriodEnd = false
            if (userProfile.id != null) {
                try {
                    val subscriptions = postgres.from("stripe_subscriptions")
                        .select { filter { eq("user_id", userProfile.id) } }
                        .decodeList<StripeSubscriptionDto>()
                    // Find active subscription (active, trialing, or past_due status)
                    val activeSubscription = subscriptions.firstOrNull {
                        it.status in listOf("active", "trialing", "past_due")
                    }
                    cancelAtPeriodEnd = activeSubscription?.cancel_at_period_end ?: false
                    MotiumApplication.logger.d("📊 getUserProfile: cancel_at_period_end=$cancelAtPeriodEnd (found ${subscriptions.size} subscriptions)", "SupabaseAuth")
                } catch (e: Exception) {
                    MotiumApplication.logger.w("⚠️ Failed to fetch cancel_at_period_end: ${e.message}", "SupabaseAuth")
                    // Ignore - cancel_at_period_end defaults to false
                }
            }

            AuthResult.Success(userProfile.toDomainUser(cancelAtPeriodEnd))
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to get user profile", e)
        }
    }

    override suspend fun updateUserProfile(user: User): AuthResult<User> {
        return try {
            // Check if user is authenticated before making the request
            val currentUser = auth.currentUserOrNull()
            if (currentUser == null) {
                MotiumApplication.logger.w("No authenticated user, skipping users update", "SupabaseAuth")
                return AuthResult.Error("User not authenticated")
            }

            // Mettre à jour dans Supabase
            // IMPORTANT: Utiliser UserProfileUpdate (sans id/auth_id) pour éviter de corrompre auth_id
            // La RLS policy vérifie auth.uid() = auth_id, donc on ne doit PAS modifier auth_id
            val userProfileUpdate = user.toUserProfileUpdate()
            postgres.from("users").update(userProfileUpdate) {
                filter { UserProfile::id eq user.id }
            }

            // Mettre à jour dans la base de données locale SANS re-queuer une sync
            // FIX: Utiliser saveUserFromServer() au lieu de updateUser() pour eviter la boucle de re-queueing
            localUserRepository.saveUserFromServer(user, com.application.motium.data.local.entities.SyncStatus.SYNCED)
            MotiumApplication.logger.i("✅ Profil utilisateur synchronise et marque SYNCED (consider_full_distance=${user.considerFullDistance})", "SupabaseAuth")

            // Mettre à jour l'état d'authentification
            _authState.value = _authState.value.copy(user = user)

            AuthResult.Success(user)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to update user profile: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to update user profile", e)
        }
    }

    override suspend fun updateEmail(newEmail: String): AuthResult<Unit> {
        return try {
            auth.updateUser {
                email = newEmail
            }
            MotiumApplication.logger.i("✅ Email update request sent to: $newEmail", "SupabaseAuth")
            // Note: Supabase enverra un email de confirmation à la nouvelle adresse
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to update email: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to update email", e)
        }
    }

    override suspend fun updatePassword(newPassword: String): AuthResult<Unit> {
        return try {
            auth.updateUser {
                password = newPassword
            }
            MotiumApplication.logger.i("✅ Password updated successfully", "SupabaseAuth")
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to update password: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to update password", e)
        }
    }

    /**
     * Rafraîchit l'état d'authentification depuis Supabase.
     * Appeler après un paiement réussi pour mettre à jour le statut d'abonnement.
     */
    override suspend fun refreshAuthState() {
        MotiumApplication.logger.i("🔄 refreshAuthState() called - forcing state update", "SupabaseAuth")
        updateAuthState()
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
            _authState.value = AuthState(isAuthenticated = false, isLoading = false, initialSyncDone = true)
            return
        }

        val userProfileResult = getUserProfile(authUser.id)
        MotiumApplication.logger.d("   userProfileResult: ${if (userProfileResult is AuthResult.Success) "Success" else "Error"}", "SupabaseAuth")
        
        if (userProfileResult is AuthResult.Success) {
            val user = userProfileResult.data

            // EXPIRED check: Force logout if subscription has expired
            // This catches trial expiry, subscription cancellation, and license revocation
            if (user.subscription.type == SubscriptionType.EXPIRED) {
                MotiumApplication.logger.i(
                    "🔴 Subscription EXPIRED detected during auth refresh - forcing logout",
                    "SupabaseAuth"
                )
                signOut()
                return
            }

            _authState.value = AuthState(
                isAuthenticated = true,
                authUser = authUser,
                user = user,
                isLoading = false,
                initialSyncDone = true
            )
            MotiumApplication.logger.d("✅ updateAuthState() completed - isAuthenticated: true, user: ${user.email}", "SupabaseAuth")
        } else {
            // OFFLINE-FIRST: Si l'appel réseau échoue, vérifier si on a un utilisateur local
            val localUser = localUserRepository.getLoggedInUser()
            if (localUser != null) {
                MotiumApplication.logger.w("⚠️ updateAuthState() network fail but local user found - keeping authenticated", "SupabaseAuth")
                _authState.value = AuthState(
                    isAuthenticated = true,
                    authUser = authUser,
                    user = localUser,
                    isLoading = false,
                    initialSyncDone = true
                )
                return
            }

            MotiumApplication.logger.e("❌ updateAuthState() failed to load profile and no local user - setting isAuthenticated = false", "SupabaseAuth")
            // Si on ne peut pas charger le profil ni en local ni en ligne, on ne peut pas considérer l'utilisateur comme pleinement connecté
            _authState.value = AuthState(
                isAuthenticated = false,
                authUser = authUser,
                user = null,
                isLoading = false,
                error = "Impossible de charger votre profil.",
                initialSyncDone = true
            )
        }
    }
    
    private fun UserProfile.toDomainUser(cancelAtPeriodEnd: Boolean = false): User {
        val resolvedId = id ?: auth_id
        MotiumApplication.logger.d("🔍 toDomainUser: UserProfile.id=$id, auth_id=$auth_id, resolvedId=$resolvedId, cancelAtPeriodEnd=$cancelAtPeriodEnd", "SupabaseAuth")

        fun parseInstantSafe(dateStr: String?): Instant? {
            if (dateStr.isNullOrBlank()) return null
            return try {
                Instant.parse(dateStr)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to parse date: $dateStr", "SupabaseAuth", e)
                null
            }
        }

        return User(
            id = resolvedId,
            name = name,
            email = email,
            role = try { UserRole.valueOf(role) } catch (e: Exception) { UserRole.INDIVIDUAL },
            subscription = Subscription(
                type = SubscriptionType.fromString(subscription_type),
                expiresAt = parseInstantSafe(subscription_expires_at),
                trialStartedAt = parseInstantSafe(trial_started_at),
                trialEndsAt = parseInstantSafe(trial_ends_at),
                stripeCustomerId = stripe_customer_id,
                stripeSubscriptionId = stripe_subscription_id,
                cancelAtPeriodEnd = cancelAtPeriodEnd
            ),
            phoneNumber = phone_number,
            address = address,
            deviceFingerprintId = device_fingerprint_id,
            considerFullDistance = consider_full_distance,
            favoriteColors = favorite_colors,
            version = version,
            createdAt = parseInstantSafe(created_at) ?: Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            updatedAt = parseInstantSafe(updated_at) ?: Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
    }

    private fun User.toUserProfile(): UserProfile = UserProfile(
        id = id,
        auth_id = id,
        name = name,
        email = email,
        role = role.name,
        subscription_type = subscription.type.name,
        subscription_expires_at = subscription.expiresAt?.toString(),
        trial_started_at = subscription.trialStartedAt?.toString(),
        trial_ends_at = subscription.trialEndsAt?.toString(),
        stripe_customer_id = subscription.stripeCustomerId,
        stripe_subscription_id = subscription.stripeSubscriptionId,
        phone_number = phoneNumber,
        address = address,
        device_fingerprint_id = deviceFingerprintId,
        consider_full_distance = considerFullDistance,
        favorite_colors = favoriteColors,
        version = version,
        created_at = createdAt.toString(),
        updated_at = updatedAt.toString()
    )

    /**
     * Convert User to UserProfileUpdate for UPDATE operations.
     * Does NOT include id, auth_id, or created_at to avoid corrupting these immutable fields.
     */
    private fun User.toUserProfileUpdate(): UserProfileUpdate = UserProfileUpdate(
        name = name,
        email = email,
        role = role.name,
        subscription_type = subscription.type.name,
        subscription_expires_at = subscription.expiresAt?.toString(),
        trial_started_at = subscription.trialStartedAt?.toString(),
        trial_ends_at = subscription.trialEndsAt?.toString(),
        stripe_customer_id = subscription.stripeCustomerId,
        stripe_subscription_id = subscription.stripeSubscriptionId,
        phone_number = phoneNumber,
        address = address,
        device_fingerprint_id = deviceFingerprintId,
        consider_full_distance = considerFullDistance,
        favorite_colors = favoriteColors,
        updated_at = kotlinx.datetime.Instant.fromEpochMilliseconds(java.lang.System.currentTimeMillis()).toString()
    )

    /**
     * OFFLINE-FIRST: Get the current user's Pro account ID if they are a Pro user.
     *
     * Priority:
     * 1. Check Room cache first (instant, works offline)
     * 2. If not in cache, fetch from Supabase and cache the result
     *
     * Returns null if the user is not a Pro user or not authenticated.
     */
    suspend fun getCurrentProAccountId(): String? {
        val currentUser = _authState.value.user ?: return null

        // Check if user is Enterprise/Pro role
        if (currentUser.role != UserRole.ENTERPRISE) {
            MotiumApplication.logger.d("User is not a Pro user", "SupabaseAuth")
            return null
        }

        // OFFLINE-FIRST: Check Room cache first
        val cachedProAccount = proAccountDao.getByUserIdOnce(currentUser.id)
        if (cachedProAccount != null) {
            MotiumApplication.logger.d("✅ Pro account ID from Room cache: ${cachedProAccount.id}", "SupabaseAuth")
            return cachedProAccount.id
        }

        // Not in cache - try to fetch from Supabase
        MotiumApplication.logger.d("🔍 Pro account not in cache, fetching from Supabase for user: ${currentUser.id}", "SupabaseAuth")

        // Check if user is authenticated before making the request
        val authUser = auth.currentUserOrNull()
        if (authUser == null) {
            MotiumApplication.logger.w("No authenticated user, skipping pro_accounts fetch", "SupabaseAuth")
            return null
        }

        return try {
            val proAccounts = postgres.from("pro_accounts")
                .select {
                    filter {
                        eq("user_id", currentUser.id)
                    }
                }
                .decodeList<ProAccountDto>()

            val serverProAccount = proAccounts.firstOrNull()

            if (serverProAccount != null) {
                // Cache the result in Room for future offline access
                val entity = com.application.motium.data.local.entities.ProAccountEntity(
                    id = serverProAccount.id,
                    userId = serverProAccount.userId,
                    companyName = serverProAccount.companyName,
                    siret = serverProAccount.siret,
                    vatNumber = serverProAccount.vatNumber,
                    legalForm = serverProAccount.legalForm,
                    billingAddress = serverProAccount.billingAddress,
                    billingEmail = serverProAccount.billingEmail,
                    billingDay = serverProAccount.billingDay,
                    departments = serverProAccount.departments?.toString() ?: "[]",
                    createdAt = serverProAccount.createdAt?.let { Instant.parse(it).toEpochMilliseconds() }
                        ?: System.currentTimeMillis(),
                    updatedAt = serverProAccount.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() }
                        ?: System.currentTimeMillis(),
                    syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name,
                    serverUpdatedAt = serverProAccount.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() }
                )
                proAccountDao.upsert(entity)
                MotiumApplication.logger.i("✅ Pro account fetched and cached in Room: ${serverProAccount.id}", "SupabaseAuth")
                serverProAccount.id
            } else {
                MotiumApplication.logger.d("No pro_account found for user ${currentUser.id}", "SupabaseAuth")
                null
            }
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired for getCurrentProAccountId, refreshing token...", "SupabaseAuth")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return try {
                        val proAccounts = postgres.from("pro_accounts")
                            .select {
                                filter {
                                    eq("user_id", currentUser.id)
                                }
                            }
                            .decodeList<ProAccountDto>()
                        val proAccountId = proAccounts.firstOrNull()?.id
                        MotiumApplication.logger.i("✅ Got Pro account ID after token refresh: $proAccountId", "SupabaseAuth")
                        proAccountId
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "SupabaseAuth", retryError)
                        null
                    }
                }
            }
            MotiumApplication.logger.e("Error getting Pro account ID: ${e.message}", "SupabaseAuth", e)
            null
        } catch (e: Exception) {
            // Network error - return null gracefully (offline mode)
            MotiumApplication.logger.w("⚠️ Could not fetch Pro account from Supabase (offline?): ${e.message}", "SupabaseAuth")
            null
        }
    }
}
// ProAccountDto is defined in ProAccountRemoteDataSource.kt
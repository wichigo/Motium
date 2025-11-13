package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.SyncScheduler
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.AuthRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(kotlin.time.ExperimentalTime::class)
class SupabaseAuthRepository(private val context: Context) : AuthRepository {

    private val client = SupabaseClient.client
    private val auth: Auth = client.auth
    private val postgres = client.postgrest
    private val secureSessionStorage = SecureSessionStorage(context)

    // Identifiants de développement pour la connexion automatique
    companion object {
        private const val DEV_EMAIL = "wyldelphegreg@gmail.com"
        private const val DEV_PASSWORD = "password123" // TEMPORAIREMENT DÉSACTIVÉ - À CORRIGER
        private const val DEV_USER_ID = "134da308-52aa-48a3-b619-c3e2500610ec"
        private const val AUTO_LOGIN_ENABLED = false // DÉSACTIVÉ car identifiants invalides

        fun isDevelopmentMode(): Boolean {
            return BuildConfig.DEBUG
        }

        // Singleton pattern
        @Volatile
        private var instance: SupabaseAuthRepository? = null

        fun getInstance(context: Context): SupabaseAuthRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseAuthRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val _authState = MutableStateFlow(
        AuthState(
            isLoading = true, // Démarrer en mode chargement
            isAuthenticated = false,
            authUser = null,
            user = null,
            error = null
        )
    )
    override val authState: Flow<AuthState> = _authState.asStateFlow()

    // CoroutineScope pour le rafraîchissement de session (DOIT être avant le bloc init)
    private val sessionRefreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Mutex pour éviter les validations concurrentes de session (race condition fix)
    private val sessionValidationMutex = Mutex()

    @Serializable
    data class UserProfile(
        val id: String? = null, // UUID auto-généré par PostgreSQL, optionnel à l'insertion
        val auth_id: String? = null, // Référence à auth.users(id), optionnel pour les updates
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
        // Initialize auth state asynchronously and set up session persistence
        // ⚠️ NE JAMAIS utiliser runBlocking ici - cela bloque le thread principal et cause des ANR!
        // L'initialisation se fait en arrière-plan de manière non-bloquante
        sessionRefreshScope.launch {
            // Vérifier si une session existe déjà
            initializeAuthSession()
        }

        // Configurer un rafraîchissement automatique de la session toutes les 45 minutes
        // (les tokens Supabase expirent généralement après 1 heure)
        startSessionRefreshTimer()
    }

    /**
     * Démarre un timer qui rafraîchit la session toutes les 15 minutes
     * pour éviter l'expiration du token (qui expire généralement après 1 heure)
     * Note: Double couche de sécurité avec SupabaseConnectionService (20min)
     */
    private fun startSessionRefreshTimer() {
        sessionRefreshScope.launch {
            while (true) {
                // Attendre 15 minutes (agressif pour garantir session valide)
                delay(15 * 60 * 1000L)

                try {
                    // Vérifier si l'utilisateur est toujours connecté
                    if (auth.currentUserOrNull() != null) {
                        MotiumApplication.logger.i("🔄 Rafraîchissement automatique de la session (15min timer)...", "SessionRefresh")
                        refreshSession()
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.w("⚠️ Erreur lors du rafraîchissement automatique: ${e.message}", "SessionRefresh")
                    // Tentative de reconnexion avec retry
                    attemptReconnection()
                }
            }
        }
    }

    /**
     * Tente de reconnecter en cas d'échec de rafraîchissement
     */
    private suspend fun attemptReconnection() {
        var retries = 0
        val maxRetries = 3

        while (retries < maxRetries) {
            delay((1 shl retries) * 2000L) // Délai exponentiel: 2s, 4s, 8s

            try {
                MotiumApplication.logger.i("🔄 Tentative de reconnexion ${retries + 1}/$maxRetries", "SessionRefresh")
                validateCurrentSession()
                MotiumApplication.logger.i("✅ Reconnexion réussie", "SessionRefresh")
                break
            } catch (e: Exception) {
                retries++
                MotiumApplication.logger.e("❌ Reconnexion échouée (${retries}/$maxRetries): ${e.message}", "SessionRefresh", e)
            }
        }
    }

    /**
     * Force le rafraîchissement de la session Supabase
     * À appeler quand l'app revient au premier plan ou périodiquement
     */
    suspend fun refreshSession() {
        try {
            val currentUser = auth.currentUserOrNull()
            if (currentUser != null) {
                MotiumApplication.logger.i("🔄 Rafraîchissement de session pour: ${currentUser.email}", "SessionRefresh")

                // Vérifier si le token expire bientôt (5 minutes avant expiration)
                if (secureSessionStorage.isTokenExpiringSoon(5)) {
                    MotiumApplication.logger.w("⚠️ Token expire bientôt - rafraîchissement prioritaire", "SessionRefresh")
                }

                // Forcer le rafraîchissement de la session
                auth.refreshCurrentSession()

                // Sauvegarder la nouvelle session de manière sécurisée
                saveCurrentSessionSecurely()

                MotiumApplication.logger.i("✅ Session rafraîchie avec succès", "SessionRefresh")

                // Mettre à jour l'état d'authentification
                updateAuthState()
            } else {
                MotiumApplication.logger.w("⚠️ Tentative de rafraîchissement sans utilisateur connecté", "SessionRefresh")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors du rafraîchissement de la session: ${e.message}", "SessionRefresh", e)

            // Tenter une reconnexion au lieu de nettoyer immédiatement
            try {
                MotiumApplication.logger.i("🔄 Tentative de reconnexion après échec de rafraîchissement", "SessionRefresh")
                validateCurrentSession()
            } catch (e2: Exception) {
                // Si la reconnexion échoue aussi, alors nettoyer
                MotiumApplication.logger.e("❌ Reconnexion échouée, nettoyage de la session", "SessionRefresh", e2)
                secureSessionStorage.clearSession()
                _authState.value = AuthState(
                    isAuthenticated = false,
                    authUser = null,
                    user = null,
                    isLoading = false,
                    error = "Session expirée - veuillez vous reconnecter"
                )
            }
        }
    }

    /**
     * Sauvegarde la session Supabase actuelle de manière sécurisée avec EncryptedSharedPreferences
     */
    private suspend fun saveCurrentSessionSecurely() {
        try {
            val currentSession = auth.currentSessionOrNull()
            val currentUser = auth.currentUserOrNull()

            if (currentSession != null && currentUser != null) {
                // Calculer le timestamp d'expiration (tokens Supabase expirent après 60 minutes)
                val expiresInSeconds = currentSession.expiresIn ?: 3600L // Default 1 heure
                val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)

                val sessionData = SecureSessionStorage.SessionData(
                    accessToken = currentSession.accessToken,
                    refreshToken = currentSession.refreshToken ?: "",
                    expiresAt = expiresAt,
                    userId = currentUser.id,
                    userEmail = currentUser.email ?: "",
                    tokenType = currentSession.tokenType ?: "Bearer",
                    lastRefreshTime = System.currentTimeMillis()
                )

                secureSessionStorage.saveSession(sessionData)
                MotiumApplication.logger.i("✅ Session sauvegardée de manière sécurisée (expire dans ${expiresInSeconds / 60} min)", "SessionRefresh")
            } else {
                MotiumApplication.logger.w("⚠️ Impossible de sauvegarder la session - session ou utilisateur null", "SessionRefresh")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de la sauvegarde sécurisée de la session: ${e.message}", "SessionRefresh", e)
        }
    }

    /**
     * Vérifie la validité de la session actuelle
     * À appeler au démarrage de l'app ou quand elle revient au premier plan
     * Protégé par mutex pour éviter les validations concurrentes
     */
    suspend fun validateCurrentSession() = sessionValidationMutex.withLock {
        try {
            MotiumApplication.logger.d("🔒 Session validation - mutex acquired", "SessionValidation")
            val currentUser = auth.currentUserOrNull()

            if (currentUser != null) {
                // La session Supabase existe
                MotiumApplication.logger.i("Validation de session: utilisateur détecté ${currentUser.email}", "SessionValidation")

                // Vérifier si le token expire bientôt ou est déjà expiré
                val needsRefresh = secureSessionStorage.isTokenExpired() || secureSessionStorage.isTokenExpiringSoon(5)

                if (secureSessionStorage.isTokenExpired()) {
                    MotiumApplication.logger.w("⚠️ Token expiré - rafraîchissement urgent", "SessionValidation")
                } else if (secureSessionStorage.isTokenExpiringSoon(5)) {
                    MotiumApplication.logger.w("⚠️ Token expire bientôt - rafraîchissement préventif", "SessionValidation")
                } else {
                    MotiumApplication.logger.i("✅ Token encore valide - pas de refresh nécessaire", "SessionValidation")
                }

                // Ne rafraîchir QUE si le token est expiré ou expire bientôt
                if (needsRefresh) {
                    try {
                        auth.refreshCurrentSession()
                        saveCurrentSessionSecurely()
                        updateAuthState()
                        MotiumApplication.logger.i("Session validée et rafraîchie avec succès", "SessionValidation")
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Impossible de rafraîchir la session: ${e.message}", "SessionValidation", e)

                    // Distinguer les vraies erreurs d'authentification des erreurs réseau temporaires
                    val isAuthError = e.message?.contains("refresh_token", ignoreCase = true) == true ||
                                     e.message?.contains("invalid", ignoreCase = true) == true ||
                                     e.message?.contains("expired", ignoreCase = true) == true ||
                                     e.message?.contains("401", ignoreCase = true) == true ||
                                     e.message?.contains("unauthorized", ignoreCase = true) == true

                    if (isAuthError) {
                        // Vraie erreur d'authentification - déconnecter l'utilisateur
                        MotiumApplication.logger.e("❌ Erreur d'authentification détectée - déconnexion", "SessionValidation")
                        secureSessionStorage.clearSession()
                        _authState.value = AuthState(
                            isAuthenticated = false,
                            authUser = null,
                            user = null,
                            isLoading = false,
                            error = "Session expirée - veuillez vous reconnecter"
                        )
                    } else {
                        // Erreur réseau temporaire - garder la session
                        MotiumApplication.logger.w("⚠️ Erreur temporaire lors du refresh (probablement réseau) - session conservée", "SessionValidation")
                        // Ne pas changer authState, garder l'utilisateur connecté
                        // Le refresh sera retenté lors de la prochaine validation
                    }
                    }
                }
            } else {
                // Pas de session Supabase active
                // Ne pas nettoyer immédiatement - vérifier si c'est vraiment une déconnexion ou juste un problème temporaire
                MotiumApplication.logger.i("Validation de session: aucun utilisateur connecté", "SessionValidation")

                // Ne nettoyer la session locale que si elle n'est plus valide depuis longtemps
                // Cela évite de nettoyer pendant les rafraîchissements temporaires
                if (!secureSessionStorage.hasValidSession()) {
                    MotiumApplication.logger.i("Session locale également invalide - nettoyage complet", "SessionValidation")
                    secureSessionStorage.clearSession()
                } else {
                    MotiumApplication.logger.w("Session Supabase null mais session locale valide - conservation de la session locale", "SessionValidation")
                }

                _authState.value = AuthState(
                    isAuthenticated = false,
                    authUser = null,
                    user = null,
                    isLoading = false,
                    error = null
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Erreur lors de la validation de session: ${e.message}", "SessionValidation", e)
            secureSessionStorage.clearSession()
            _authState.value = AuthState(
                isAuthenticated = false,
                authUser = null,
                user = null,
                isLoading = false,
                error = null
            )
        }
    }

    private suspend fun initializeAuthSession() = sessionValidationMutex.withLock {
        try {
            MotiumApplication.logger.d("🔒 Session initialization - mutex acquired", "SupabaseAuthRepository")

            // Vérifier d'abord si on a une session sécurisée sauvegardée
            val savedSession = secureSessionStorage.restoreSession()

            if (savedSession != null) {
                MotiumApplication.logger.i("📦 Session sécurisée trouvée pour: ${savedSession.userEmail}", "SupabaseAuthRepository")

                try {
                    // Vérifier si le token est déjà expiré
                    val isExpired = secureSessionStorage.isTokenExpired()

                    if (isExpired) {
                        // Token expiré: utiliser le refresh token pour obtenir une nouvelle session
                        MotiumApplication.logger.w("⚠️ Token expiré - rafraîchissement avec refresh token", "SupabaseAuthRepository")

                        // Utiliser directement le refresh token pour obtenir une nouvelle session
                        val newSession = auth.refreshSession(refreshToken = savedSession.refreshToken)

                        // Sauvegarder la nouvelle session
                        saveCurrentSessionSecurely()

                        MotiumApplication.logger.i("✅ Session rafraîchie avec succès depuis token expiré", "SupabaseAuthRepository")
                    } else {
                        // Token encore valide: importer la session dans le SDK
                        val expiresInSeconds = ((savedSession.expiresAt - System.currentTimeMillis()) / 1000).toLong()

                        MotiumApplication.logger.i("🔄 Importing session into Supabase SDK (expiresIn: ${expiresInSeconds}s)", "SupabaseAuthRepository")

                        // Créer un UserSession pour l'import
                        val userSession = io.github.jan.supabase.auth.user.UserSession(
                            accessToken = savedSession.accessToken,
                            refreshToken = savedSession.refreshToken,
                            expiresIn = expiresInSeconds,
                            tokenType = savedSession.tokenType,
                            user = null
                        )

                        // Importer la session dans le SDK
                        auth.importSession(userSession)
                        MotiumApplication.logger.i("✅ Session imported successfully into Supabase SDK", "SupabaseAuthRepository")

                        // Si le token expire bientôt (< 5 minutes), rafraîchir préventivement
                        if (expiresInSeconds < 300) {
                            MotiumApplication.logger.i("🔄 Token expire bientôt - rafraîchissement préventif", "SupabaseAuthRepository")
                            auth.refreshCurrentSession()
                            saveCurrentSessionSecurely()
                        }
                    }

                    updateAuthState()
                    MotiumApplication.logger.i("✅ Session restored successfully", "SupabaseAuthRepository")
                    return@withLock

                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Erreur lors de la restauration de session: ${e.message}", "SupabaseAuthRepository", e)
                    // En cas d'échec, nettoyer et laisser l'utilisateur se reconnecter
                    secureSessionStorage.clearSession()
                }
            }

            // Supabase charge automatiquement la session depuis le stockage
            val currentUser = auth.currentUserOrNull()

            if (currentUser != null) {
                // Session Supabase valide détectée
                MotiumApplication.logger.i("Session Supabase valide trouvée pour: ${currentUser.email}", "SupabaseAuthRepository")

                // Sauvegarder la session de manière sécurisée
                saveCurrentSessionSecurely()

                // L'utilisateur est connecté
                updateAuthState()
            } else {
                // Pas de session Supabase
                MotiumApplication.logger.i("Aucune session Supabase trouvée", "SupabaseAuthRepository")

                // En mode développement, tenter la connexion automatique (SI ACTIVÉE)
                if (isDevelopmentMode() && AUTO_LOGIN_ENABLED) {
                    MotiumApplication.logger.i("Mode développement détecté - tentative de connexion automatique", "SupabaseAuthRepository")
                    tryDevelopmentAutoLogin()
                } else {
                    if (isDevelopmentMode() && !AUTO_LOGIN_ENABLED) {
                        MotiumApplication.logger.w("Auto-login désactivé - utiliser la connexion manuelle", "SupabaseAuthRepository")
                    }
                    secureSessionStorage.clearSession()
                    _authState.value = AuthState(
                        isAuthenticated = false,
                        authUser = null,
                        user = null,
                        isLoading = false,
                        error = null
                    )
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w("Erreur lors de l'initialisation de la session: ${e.message}", "SupabaseAuthRepository")

            // En mode développement, tenter la connexion automatique même en cas d'erreur (SI ACTIVÉE)
            if (isDevelopmentMode() && AUTO_LOGIN_ENABLED) {
                MotiumApplication.logger.i("Mode développement - tentative de connexion automatique après erreur", "SupabaseAuthRepository")
                tryDevelopmentAutoLogin()
            } else {
                // En cas d'erreur, nettoyer toutes les sessions
                secureSessionStorage.clearSession()
                _authState.value = AuthState(
                    isAuthenticated = false,
                    authUser = null,
                    user = null,
                    isLoading = false,
                    error = null
                )
            }
        }
    }

    /**
     * Tentative de connexion automatique en mode développement
     * Permet de maintenir la connexion même après réinstallation de l'app
     */
    private suspend fun tryDevelopmentAutoLogin() {
        try {
            MotiumApplication.logger.i("🔧 Tentative de connexion automatique de développement avec: $DEV_EMAIL", "DevAutoLogin")

            val result = auth.signInWith(Email) {
                email = DEV_EMAIL
                password = DEV_PASSWORD
            }

            val authUser = auth.currentUserOrNull()
            if (authUser != null) {
                MotiumApplication.logger.i("✅ Connexion automatique de développement réussie! Utilisateur: ${authUser.email}", "DevAutoLogin")

                // Sauvegarder la session de manière sécurisée
                saveCurrentSessionSecurely()

                // Mettre à jour l'état d'authentification
                updateAuthState()
            } else {
                MotiumApplication.logger.w("❌ Connexion automatique échouée - utilisateur null", "DevAutoLogin")
                _authState.value = AuthState(
                    isAuthenticated = false,
                    authUser = null,
                    user = null,
                    isLoading = false,
                    error = "Connexion automatique de développement échouée"
                )
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de la connexion automatique de développement: ${e.message}", "DevAutoLogin", e)
            _authState.value = AuthState(
                isAuthenticated = false,
                authUser = null,
                user = null,
                isLoading = false,
                error = "Erreur de connexion automatique: ${e.message}"
            )
        }
    }

    override suspend fun signUp(request: RegisterRequest): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)

            MotiumApplication.logger.i("📝 Tentative d'inscription pour: ${request.email}", "SupabaseAuth")

            auth.signUpWith(Email) {
                email = request.email
                password = request.password
                // Ne pas envoyer de métadonnées ici - cela peut causer une erreur 500
                // Les métadonnées seront ajoutées lors de la création du profil utilisateur
            }

            MotiumApplication.logger.i("✅ Inscription réussie dans Supabase Auth", "SupabaseAuth")

            val authUser = auth.currentUserOrNull()?.let { userInfo ->
                AuthUser(
                    id = userInfo.id,
                    email = userInfo.email,
                    isEmailConfirmed = userInfo.emailConfirmedAt != null,
                    provider = "email"
                )
            } ?: throw Exception("Failed to get user info after signup")

            // Sauvegarder la session de manière sécurisée
            saveCurrentSessionSecurely()

            updateAuthState()
            MotiumApplication.logger.i("✅ Session sauvegardée pour: ${authUser.email}", "SupabaseAuth")
            AuthResult.Success(authUser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de l'inscription: ${e.message}", "SupabaseAuth", e)
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

            val authUser = auth.currentUserOrNull()?.let { userInfo ->
                AuthUser(
                    id = userInfo.id,
                    email = userInfo.email,
                    isEmailConfirmed = userInfo.emailConfirmedAt != null,
                    provider = "email"
                )
            } ?: throw Exception("Failed to get user info after signin")

            // Sauvegarder la session de manière sécurisée
            saveCurrentSessionSecurely()

            // Démarrer la synchronisation périodique en arrière-plan
            SyncScheduler.scheduleSyncWork(context)

            updateAuthState()
            AuthResult.Success(authUser)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Login failed", e)
        }
    }

    override suspend fun signInWithGoogle(idToken: String): AuthResult<AuthUser> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)

            // TODO: Implement Google Sign-In with Supabase
            // Currently disabled due to API compatibility issues
            throw Exception("Google Sign-In not yet implemented")

        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            AuthResult.Error(e.message ?: "Google sign-in failed", e)
        }
    }

    override suspend fun signOut(): AuthResult<Unit> {
        return try {
            auth.signOut()
            // Effacer la session sécurisée
            secureSessionStorage.clearSession()
            // Annuler la synchronisation périodique en arrière-plan
            SyncScheduler.cancelSyncWork(context)
            updateAuthState()
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Sign out failed", e)
        }
    }

    override suspend fun getCurrentAuthUser(): AuthUser? {
        // Try to get user from Supabase session first
        val supabaseUser = auth.currentUserOrNull()?.let { userInfo ->
            AuthUser(
                id = userInfo.id,
                email = userInfo.email,
                isEmailConfirmed = userInfo.emailConfirmedAt != null,
                provider = userInfo.appMetadata?.get("provider")?.toString()
            )
        }

        // If no Supabase session, fallback to secure session data
        return if (supabaseUser != null) {
            supabaseUser
        } else if (secureSessionStorage.hasValidSession()) {
            val userId = secureSessionStorage.getUserId()
            val userEmail = secureSessionStorage.getUserEmail()

            if (userId != null && userEmail != null) {
                MotiumApplication.logger.d(
                    "No Supabase session but valid secure session - returning user from secure data",
                    "SupabaseAuthRepository"
                )
                AuthUser(
                    id = userId,
                    email = userEmail,
                    isEmailConfirmed = true, // Assume confirmed since they logged in previously
                    provider = "email"
                )
            } else {
                MotiumApplication.logger.w(
                    "Valid secure session but missing user data (id or email)",
                    "SupabaseAuthRepository"
                )
                null
            }
        } else {
            null
        }
    }

    override suspend fun isUserAuthenticated(): Boolean {
        // Vérifier d'abord la session Supabase
        val hasSupabaseSession = auth.currentUserOrNull() != null

        // Si pas de session Supabase, vérifier la session sécurisée comme fallback
        // Cela évite de considérer l'utilisateur comme déconnecté pendant les rafraîchissements temporaires
        return if (hasSupabaseSession) {
            true
        } else {
            // Fallback: si session sécurisée valide, l'importer dans le SDK
            val hasSecureSession = secureSessionStorage.hasValidSession()
            if (hasSecureSession) {
                MotiumApplication.logger.d("No Supabase session but valid secure session - importing into SDK", "SupabaseAuthRepository")

                try {
                    // Récupérer la session sécurisée
                    val savedSession = secureSessionStorage.restoreSession()

                    if (savedSession != null) {
                        // Vérifier si le token est déjà expiré
                        val isExpired = secureSessionStorage.isTokenExpired()

                        if (isExpired) {
                            // Token expiré: utiliser le refresh token pour obtenir une nouvelle session
                            MotiumApplication.logger.w("⚠️ Token expiré - rafraîchissement avec refresh token", "SupabaseAuthRepository")

                            val newSession = auth.refreshSession(refreshToken = savedSession.refreshToken)
                            saveCurrentSessionSecurely()

                            MotiumApplication.logger.i("✅ Session rafraîchie avec succès depuis token expiré", "SupabaseAuthRepository")
                        } else {
                            // Token encore valide: importer la session dans le SDK
                            val expiresInSeconds = ((savedSession.expiresAt - System.currentTimeMillis()) / 1000).toLong()

                            MotiumApplication.logger.i("🔄 Importing session into Supabase SDK (expiresIn: ${expiresInSeconds}s)", "SupabaseAuthRepository")

                            // Créer un UserSession pour l'import
                            val userSession = io.github.jan.supabase.auth.user.UserSession(
                                accessToken = savedSession.accessToken,
                                refreshToken = savedSession.refreshToken,
                                expiresIn = expiresInSeconds,
                                tokenType = savedSession.tokenType,
                                user = null
                            )

                            // Importer la session dans le SDK
                            auth.importSession(userSession)
                            MotiumApplication.logger.i("✅ Session imported successfully into Supabase SDK", "SupabaseAuthRepository")

                            // Si le token expire bientôt (< 5 minutes), rafraîchir préventivement
                            if (expiresInSeconds < 300) {
                                MotiumApplication.logger.i("🔄 Token expire bientôt - rafraîchissement préventif", "SupabaseAuthRepository")
                                auth.refreshCurrentSession()
                                saveCurrentSessionSecurely()
                            }
                        }

                        updateAuthState()
                        true
                    } else {
                        // Pas de session sécurisée valide finalement
                        false
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Erreur lors de l'import de session: ${e.message}", "SupabaseAuthRepository", e)
                    // En cas d'échec, nettoyer et retourner false
                    secureSessionStorage.clearSession()
                    false
                }
            } else {
                false
            }
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): AuthResult<Unit> {
        return try {
            auth.resetPasswordForEmail(email)
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to send reset email", e)
        }
    }

    override suspend fun confirmEmail(token: String): AuthResult<Unit> {
        return try {
            // Note: API might have changed in v3.x
            // This is a placeholder implementation
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Email confirmation failed", e)
        }
    }

    override suspend fun createUserProfile(authUser: AuthUser, name: String, isEnterprise: Boolean, organizationName: String): AuthResult<User> {
        return try {
            // Utiliser la date/heure actuelle au format ISO-8601
            val nowInstant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val now = nowInstant.toString()

            MotiumApplication.logger.i("📝 Création du profil utilisateur pour: ${authUser.email}", "SupabaseAuth")
            MotiumApplication.logger.i("   - Nom: $name", "SupabaseAuth")
            MotiumApplication.logger.i("   - Rôle: ${if (isEnterprise) "ENTERPRISE" else "INDIVIDUAL"}", "SupabaseAuth")

            // Generate organization ID if user is enterprise
            val organizationId = if (isEnterprise) {
                java.util.UUID.randomUUID().toString()
            } else {
                null
            }

            val userProfile = UserProfile(
                auth_id = authUser.id,
                name = name,
                email = authUser.email ?: "",
                role = if (isEnterprise) "ENTERPRISE" else "INDIVIDUAL",
                organization_id = organizationId,
                organization_name = if (isEnterprise && organizationName.isNotEmpty()) organizationName else null,
                created_at = now,
                updated_at = now
            )

            MotiumApplication.logger.i("📤 Insertion dans la table users...", "SupabaseAuth")
            postgres.from("users").insert(userProfile)
            MotiumApplication.logger.i("✅ Profil utilisateur inséré, récupération de l'ID généré...", "SupabaseAuth")

            // Récupérer l'enregistrement créé pour obtenir l'ID auto-généré
            val createdProfile = postgres.from("users")
                .select {
                    filter {
                        UserProfile::auth_id eq authUser.id
                    }
                }
                .decodeSingle<UserProfile>()

            MotiumApplication.logger.i("✅ Profil utilisateur créé avec ID: ${createdProfile.id}", "SupabaseAuth")

            val user = User(
                id = createdProfile.id ?: authUser.id, // Utiliser l'ID généré ou fallback sur auth_id
                name = name,
                email = authUser.email ?: "",
                role = if (isEnterprise) UserRole.ENTERPRISE else UserRole.INDIVIDUAL,
                organizationId = organizationId,
                organizationName = if (isEnterprise && organizationName.isNotEmpty()) organizationName else null,
                subscription = Subscription(SubscriptionType.FREE, null),
                monthlyTripCount = 0,
                createdAt = nowInstant,
                updatedAt = nowInstant
            )

            updateAuthState()
            AuthResult.Success(user)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de la création du profil: ${e.message}", "SupabaseAuth", e)
            AuthResult.Error(e.message ?: "Failed to create user profile", e)
        }
    }

    override suspend fun getUserProfile(userId: String): AuthResult<User> {
        return try {
            // Récupérer le profil de l'utilisateur actuellement connecté
            // auth.uid() dans Supabase correspond à l'auth_id
            val currentAuthUser = auth.currentUserOrNull()

            if (currentAuthUser == null) {
                MotiumApplication.logger.e("❌ No authenticated user", "SupabaseAuthRepository")
                return AuthResult.Error("User not authenticated")
            }

            // Chercher par auth_id (qui correspond à auth.uid() dans les politiques RLS)
            val userProfile = postgres.from("users")
                .select {
                    filter {
                        UserProfile::auth_id eq currentAuthUser.id
                    }
                }
                .decodeSingle<UserProfile>()

            MotiumApplication.logger.d("✅ User profile loaded: ${userProfile.email}", "SupabaseAuthRepository")
            val user = userProfile.toDomainUser()
            AuthResult.Success(user)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error fetching user profile: ${e.message}", "SupabaseAuthRepository", e)
            AuthResult.Error(e.message ?: "Failed to get user profile", e)
        }
    }

    override suspend fun updateUserProfile(user: User): AuthResult<User> {
        return try {
            val userProfile = user.toUserProfile()
            postgres.from("users")
                .update(userProfile) {
                    filter {
                        UserProfile::id eq user.id
                    }
                }

            updateAuthState()
            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to update user profile", e)
        }
    }

    /**
     * Met à jour le compteur de trajets mensuels pour l'utilisateur
     */
    suspend fun updateMonthlyTripCount(userId: String, newCount: Int): AuthResult<Unit> {
        return try {
            @Serializable
            data class MonthlyTripCountUpdate(
                val monthly_trip_count: Int
            )

            postgres.from("users")
                .update(MonthlyTripCountUpdate(monthly_trip_count = newCount)) {
                    filter {
                        UserProfile::id eq userId
                    }
                }

            // Rafraîchir l'état d'authentification pour avoir les données à jour
            updateAuthState()
            AuthResult.Success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Erreur lors de la mise à jour du compteur de trajets: ${e.message}", "SupabaseAuthRepository", e)
            AuthResult.Error(e.message ?: "Failed to update monthly trip count", e)
        }
    }

    /**
     * Récupère l'utilisateur actuellement connecté avec son profil complet
     * Retourne null si aucun utilisateur n'est connecté
     */
    suspend fun getCurrentUser(): User? {
        val authUser = getCurrentAuthUser() ?: return null

        return try {
            val result = getUserProfile(authUser.id)
            if (result is AuthResult.Success) {
                result.data
            } else {
                null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Erreur lors de la récupération de l'utilisateur: ${e.message}", "SupabaseAuthRepository", e)
            null
        }
    }

    private suspend fun updateAuthState() {
        val authUser = getCurrentAuthUser()
        MotiumApplication.logger.d("🔄 updateAuthState - authUser: ${authUser?.email}", "SupabaseAuth")

        val user = authUser?.let {
            try {
                val result = getUserProfile(it.id)
                when (result) {
                    is AuthResult.Success -> {
                        MotiumApplication.logger.i("✅ User profile loaded - Role: ${result.data.role}", "SupabaseAuth")
                        result.data
                    }
                    is AuthResult.Error -> {
                        MotiumApplication.logger.e("❌ Failed to load user profile: ${result.message}", "SupabaseAuth")
                        null
                    }
                    is AuthResult.Loading -> {
                        MotiumApplication.logger.d("⏳ User profile loading...", "SupabaseAuth")
                        null
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Exception loading user profile: ${e.message}", "SupabaseAuth", e)
                null
            }
        }

        _authState.value = AuthState(
            isAuthenticated = authUser != null,
            authUser = authUser,
            user = user,
            isLoading = false,
            error = null
        )

        MotiumApplication.logger.d("🔄 AuthState updated - isAuth: ${authUser != null}, role: ${user?.role?.name}", "SupabaseAuth")
    }

    private fun UserProfile.toDomainUser(): User {
        return User(
            id = id ?: auth_id ?: "", // Utiliser id si disponible, sinon auth_id, sinon chaîne vide
            name = name,
            email = email,
            role = UserRole.valueOf(role),
            organizationId = organization_id,
            organizationName = organization_name,
            subscription = Subscription(
                type = SubscriptionType.valueOf(subscription_type),
                expiresAt = subscription_expires_at?.let { kotlinx.datetime.Instant.parse(it) }
            ),
            monthlyTripCount = monthly_trip_count,
            createdAt = kotlinx.datetime.Instant.parse(created_at),
            updatedAt = kotlinx.datetime.Instant.parse(updated_at)
        )
    }

    private fun User.toUserProfile(): UserProfile {
        return UserProfile(
            id = id,
            name = name,
            email = email,
            role = role.name,
            organization_id = organizationId,
            organization_name = organizationName,
            subscription_type = subscription.type.name,
            subscription_expires_at = subscription.expiresAt?.toString(),
            monthly_trip_count = monthlyTripCount,
            created_at = createdAt.toString(),
            updated_at = updatedAt.toString()
        )
    }
}
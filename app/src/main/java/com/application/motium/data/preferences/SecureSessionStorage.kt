package com.application.motium.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.application.motium.MotiumApplication

/**
 * Stockage sécurisé des sessions Supabase avec EncryptedSharedPreferences
 * Implémente le chiffrement AES256 pour protéger les tokens d'authentification
 *
 * AMÉLIORATION : Système de persistence permanente pour maintenir la connexion utilisateur
 * même après fermeture/réouverture de l'application. Seule la déconnexion manuelle
 * peut terminer la session.
 */
class SecureSessionStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "supabase_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        MotiumApplication.logger.e("❌ Erreur création EncryptedSharedPreferences, fallback vers SharedPreferences standard", "SecureSession", e)
        // Fallback vers SharedPreferences standard si EncryptedSharedPreferences échoue
        context.getSharedPreferences("supabase_session_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_LAST_REFRESH_TIME = "last_refresh_time"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_SESSION_CREATED_AT = "session_created_at"
        private const val KEY_AUTO_LOGIN_ENABLED = "auto_login_enabled"
        private const val KEY_PERSISTENT_SESSION = "persistent_session"
        private const val KEY_LOGIN_METHOD = "login_method"
        private const val KEY_LAST_VALIDATION_TIME = "last_validation_time"
        private const val KEY_SESSION_REFRESH_COUNT = "session_refresh_count"
    }

    /**
     * Données de session Supabase étendues pour la persistence permanente
     */
    data class SessionData(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long, // Timestamp Unix en millisecondes
        val userId: String,
        val userEmail: String,
        val tokenType: String = "Bearer",
        val lastRefreshTime: Long = System.currentTimeMillis(),
        val sessionCreatedAt: Long = System.currentTimeMillis(),
        val loginMethod: String = "email", // "email", "google", etc.
        val isPersistent: Boolean = true // Toujours true pour la persistence permanente
    )

    /**
     * Sauvegarde la session de manière sécurisée avec persistence permanente
     */
    fun saveSession(session: SessionData) {
        try {
            val currentTime = System.currentTimeMillis()

            encryptedPrefs.edit()
                .putString(KEY_ACCESS_TOKEN, session.accessToken)
                .putString(KEY_REFRESH_TOKEN, session.refreshToken)
                .putLong(KEY_EXPIRES_AT, session.expiresAt)
                .putString(KEY_USER_ID, session.userId)
                .putString(KEY_USER_EMAIL, session.userEmail)
                .putString(KEY_TOKEN_TYPE, session.tokenType)
                .putLong(KEY_LAST_REFRESH_TIME, session.lastRefreshTime)
                .putLong(KEY_SESSION_CREATED_AT, session.sessionCreatedAt)
                .putString(KEY_LOGIN_METHOD, session.loginMethod)
                .putBoolean(KEY_PERSISTENT_SESSION, session.isPersistent)
                .putBoolean(KEY_AUTO_LOGIN_ENABLED, true) // Toujours activer l'auto-login
                .putLong(KEY_LAST_VALIDATION_TIME, currentTime)
                .apply()

            MotiumApplication.logger.i(
                "✅ Session persistante sauvegardée (expire dans ${(session.expiresAt - currentTime) / 1000 / 60} min)",
                "SecureSession"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur sauvegarde session sécurisée: ${e.message}", "SecureSession", e)
        }
    }

    /**
     * Récupère la session sauvegardée avec vérification de persistence
     */
    fun restoreSession(): SessionData? {
        return try {
            // Vérifier d'abord si la session persistante est activée
            if (!isPersistentSessionEnabled()) {
                MotiumApplication.logger.i("⏸️ Session persistante désactivée", "SecureSession")
                return null
            }

            val accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
            val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
            val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
            val userId = encryptedPrefs.getString(KEY_USER_ID, null)
            val userEmail = encryptedPrefs.getString(KEY_USER_EMAIL, null)
            val tokenType = encryptedPrefs.getString(KEY_TOKEN_TYPE, "Bearer") ?: "Bearer"
            val lastRefreshTime = encryptedPrefs.getLong(KEY_LAST_REFRESH_TIME, 0)
            val sessionCreatedAt =
                encryptedPrefs.getLong(KEY_SESSION_CREATED_AT, System.currentTimeMillis())
            val loginMethod = encryptedPrefs.getString(KEY_LOGIN_METHOD, "email") ?: "email"

            if (accessToken != null && refreshToken != null && userId != null && userEmail != null) {
                // Marquer la dernière validation
                updateLastValidationTime()

                val sessionAge =
                    (System.currentTimeMillis() - sessionCreatedAt) / 1000 / 60 // minutes
                MotiumApplication.logger.i(
                    "✅ Session persistante restaurée (âge: ${sessionAge}min, expire dans ${(expiresAt - System.currentTimeMillis()) / 1000 / 60} min)",
                    "SecureSession"
                )
                SessionData(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    userId = userId,
                    userEmail = userEmail,
                    tokenType = tokenType,
                    lastRefreshTime = lastRefreshTime,
                    sessionCreatedAt = sessionCreatedAt,
                    loginMethod = loginMethod,
                    isPersistent = true
                )
            } else {
                MotiumApplication.logger.w("⚠️ Session incomplète trouvée, certaines données manquent", "SecureSession")
                null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur restauration session: ${e.message}", "SecureSession", e)
            null
        }
    }

    /**
     * Vérifie si la session persistante est activée
     */
    fun isPersistentSessionEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_PERSISTENT_SESSION, true) &&
                encryptedPrefs.getBoolean(KEY_AUTO_LOGIN_ENABLED, true)
    }

    /**
     * Active/désactive la session persistante (pour la déconnexion manuelle uniquement)
     */
    fun setPersistentSessionEnabled(enabled: Boolean) {
        encryptedPrefs.edit()
            .putBoolean(KEY_PERSISTENT_SESSION, enabled)
            .putBoolean(KEY_AUTO_LOGIN_ENABLED, enabled)
            .apply()

        if (enabled) {
            MotiumApplication.logger.i("✅ Session persistante activée", "SecureSession")
        } else {
            MotiumApplication.logger.i("🔒 Session persistante désactivée", "SecureSession")
        }
    }

    /**
     * Vérifie si le token va expirer bientôt avec logique améliorée
     */
    fun isTokenExpiringSoon(thresholdMinutes: Int = 5): Boolean {
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
        if (expiresAt == 0L) {
            MotiumApplication.logger.w("⚠️ Aucune date d'expiration trouvée", "SecureSession")
            return true
        }

        val now = System.currentTimeMillis()
        val timeUntilExpiry = expiresAt - now
        val thresholdMillis = thresholdMinutes * 60 * 1000L

        val expiringSoon = timeUntilExpiry < thresholdMillis

        if (expiringSoon) {
            MotiumApplication.logger.w(
                "⚠️ Token expire bientôt (dans ${timeUntilExpiry / 1000 / 60} min)",
                "SecureSession"
            )
        }

        return expiringSoon
    }

    /**
     * Vérifie si le token est déjà expiré
     */
    fun isTokenExpired(): Boolean {
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
        if (expiresAt == 0L) return true

        val isExpired = System.currentTimeMillis() >= expiresAt

        if (isExpired) {
            MotiumApplication.logger.e("❌ Token expiré", "SecureSession")
        }

        return isExpired
    }

    /**
     * Met à jour uniquement le timestamp de dernier refresh
     */
    fun updateLastRefreshTime() {
        val currentTime = System.currentTimeMillis()
        val refreshCount = encryptedPrefs.getInt(KEY_SESSION_REFRESH_COUNT, 0) + 1

        encryptedPrefs.edit()
            .putLong(KEY_LAST_REFRESH_TIME, currentTime)
            .putInt(KEY_SESSION_REFRESH_COUNT, refreshCount)
            .apply()

        MotiumApplication.logger.d(
            "🔄 Refresh timestamp mis à jour (refresh #$refreshCount)",
            "SecureSession"
        )
    }

    /**
     * Met à jour le timestamp de dernière validation
     */
    fun updateLastValidationTime() {
        encryptedPrefs.edit()
            .putLong(KEY_LAST_VALIDATION_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Récupère le temps écoulé depuis le dernier refresh en minutes
     */
    fun getMinutesSinceLastRefresh(): Long {
        val lastRefresh = encryptedPrefs.getLong(KEY_LAST_REFRESH_TIME, 0)
        if (lastRefresh == 0L) return Long.MAX_VALUE

        return (System.currentTimeMillis() - lastRefresh) / 1000 / 60
    }

    /**
     * Récupère le temps écoulé depuis la dernière validation en minutes
     */
    fun getMinutesSinceLastValidation(): Long {
        val lastValidation = encryptedPrefs.getLong(KEY_LAST_VALIDATION_TIME, 0)
        if (lastValidation == 0L) return Long.MAX_VALUE

        return (System.currentTimeMillis() - lastValidation) / 1000 / 60
    }

    /**
     * Récupère l'âge de la session en heures
     */
    fun getSessionAgeInHours(): Long {
        val sessionCreated = encryptedPrefs.getLong(KEY_SESSION_CREATED_AT, 0)
        if (sessionCreated == 0L) return Long.MAX_VALUE

        return (System.currentTimeMillis() - sessionCreated) / 1000 / 60 / 60
    }

    /**
     * Récupère le nombre de fois que la session a été rafraîchie
     */
    fun getSessionRefreshCount(): Int {
        return encryptedPrefs.getInt(KEY_SESSION_REFRESH_COUNT, 0)
    }

    /**
     * Vérifie si une session existe (même expirée) - utile pour la persistence
     */
    fun hasSession(): Boolean {
        val accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        return accessToken != null && refreshToken != null
    }

    /**
     * Vérifie si la session est valide (existe et non expirée)
     */
    fun hasValidSession(): Boolean {
        return hasSession() && !isTokenExpired() && isPersistentSessionEnabled()
    }

    /**
     * Vérifie si la session peut être restaurée (même si expirée, tant qu'on a un refresh token)
     */
    fun canRestoreSession(): Boolean {
        val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        return refreshToken != null && isPersistentSessionEnabled()
    }

    /**
     * Efface toutes les données de session (SEULE méthode pour déconnecter définitivement)
     */
    fun clearSession() {
        try {
            encryptedPrefs.edit().clear().apply()
            MotiumApplication.logger.i(
                "🗑️ Session persistante effacée définitivement",
                "SecureSession"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur effacement session: ${e.message}", "SecureSession", e)
        }
    }

    /**
     * Déconnexion manuelle - désactive la persistence et efface la session
     */
    fun manualLogout() {
        try {
            MotiumApplication.logger.i("👋 Déconnexion manuelle initiée", "SecureSession")
            setPersistentSessionEnabled(false)
            clearSession()
            MotiumApplication.logger.i("✅ Déconnexion manuelle terminée", "SecureSession")
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Erreur lors de la déconnexion manuelle: ${e.message}",
                "SecureSession",
                e
            )
        }
    }

    /**
     * Récupère le refresh token
     */
    fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Récupère l'access token
     */
    fun getAccessToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Récupère l'ID utilisateur
     */
    fun getUserId(): String? {
        return encryptedPrefs.getString(KEY_USER_ID, null)
    }

    /**
     * Récupère l'email utilisateur
     */
    fun getUserEmail(): String? {
        return encryptedPrefs.getString(KEY_USER_EMAIL, null)
    }

    /**
     * Récupère la méthode de connexion utilisée
     */
    fun getLoginMethod(): String {
        return encryptedPrefs.getString(KEY_LOGIN_METHOD, "email") ?: "email"
    }

    /**
     * Log des informations de debug sur la session avec statistiques étendues
     */
    fun debugLogSession() {
        val hasSession = hasSession()
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
        val lastRefresh = encryptedPrefs.getLong(KEY_LAST_REFRESH_TIME, 0)
        val sessionCreated = encryptedPrefs.getLong(KEY_SESSION_CREATED_AT, 0)
        val userId = getUserId()
        val userEmail = getUserEmail()
        val loginMethod = getLoginMethod()
        val refreshCount = getSessionRefreshCount()

        val now = System.currentTimeMillis()
        val minutesUntilExpiry = if (expiresAt > 0) (expiresAt - now) / 1000 / 60 else -1
        val minutesSinceRefresh = if (lastRefresh > 0) (now - lastRefresh) / 1000 / 60 else -1
        val sessionAgeHours =
            if (sessionCreated > 0) (now - sessionCreated) / 1000 / 60 / 60 else -1

        MotiumApplication.logger.d(
            """
            📊 Session Persistante Debug Info:
              - Has session: $hasSession
              - Persistent enabled: ${isPersistentSessionEnabled()}
              - Can restore: ${canRestoreSession()}
              - User: $userEmail ($userId)
              - Login method: $loginMethod
              - Session age: ${sessionAgeHours}h
              - Expires in: $minutesUntilExpiry minutes
              - Last refresh: $minutesSinceRefresh minutes ago
              - Refresh count: $refreshCount
              - Is expired: ${isTokenExpired()}
              - Expires soon: ${isTokenExpiringSoon()}
            """.trimIndent(),
            "SecureSession"
        )
    }
}

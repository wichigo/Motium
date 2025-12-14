package com.application.motium.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.application.motium.MotiumApplication

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
        MotiumApplication.logger.e(
            "❌ CRITICAL: Cannot create encrypted session storage - device may be compromised or corrupted",
            "SecureSession",
            e
        )

        // Nettoyer tout fallback existant (ne jamais l'utiliser)
        try {
            context.getSharedPreferences("supabase_session_fallback", Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (ignored: Exception) {}

        throw IllegalStateException(
            "Cannot initialize secure session storage. Please reinstall the app.",
            e
        )
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_SESSION_CREATED_AT = "session_created_at"
        private const val KEY_PERSISTENT_SESSION_FLAG = "persistent_session_flag"
        // Credentials for silent re-authentication
        private const val KEY_AUTH_EMAIL = "auth_email"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        private const val KEY_AUTH_METHOD = "auth_method" // "email", "google", etc.

        // JWT Migration tracking - increment this when JWT signing algorithm changes
        private const val KEY_JWT_MIGRATION_VERSION = "jwt_migration_version"
        private const val CURRENT_JWT_VERSION = 2 // v1 = HS256 (legacy), v2 = ES256 (P-256)
    }

    /**
     * Credentials data for silent re-authentication.
     * Only stored for email/password auth (OAuth uses different flow).
     */
    data class AuthCredentials(
        val email: String,
        val password: String,
        val authMethod: String = "email"
    )

    data class SessionData(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val userEmail: String,
        val tokenType: String = "Bearer",
        val lastRefreshTime: Long = System.currentTimeMillis(),
        val sessionCreatedAt: Long = System.currentTimeMillis()
    )

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
                .putLong(KEY_SESSION_CREATED_AT, session.sessionCreatedAt)
                .putBoolean(KEY_PERSISTENT_SESSION_FLAG, true) // Explicitly set session as persistent
                .apply()
            MotiumApplication.logger.i("✅ Persistent session saved (expires in ${(session.expiresAt - currentTime) / 1000 / 60} min)", "SecureSession")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error saving secure session: ${e.message}", "SecureSession", e)
        }
    }

    fun restoreSession(): SessionData? {
        if (!encryptedPrefs.getBoolean(KEY_PERSISTENT_SESSION_FLAG, false)) {
            MotiumApplication.logger.i("ℹ️ No persistent session flag found. User is not logged in.", "SecureSession")
            return null
        }

        return try {
            val accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
            val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
            val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
            val userId = encryptedPrefs.getString(KEY_USER_ID, null)
            val userEmail = encryptedPrefs.getString(KEY_USER_EMAIL, null)
            val tokenType = encryptedPrefs.getString(KEY_TOKEN_TYPE, "Bearer") ?: "Bearer"
            val sessionCreatedAt = encryptedPrefs.getLong(KEY_SESSION_CREATED_AT, System.currentTimeMillis())

            if (accessToken != null && refreshToken != null && userId != null && userEmail != null) {
                MotiumApplication.logger.i("✅ Persistent session restored for user: $userEmail", "SecureSession")
                SessionData(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                    userId = userId,
                    userEmail = userEmail,
                    tokenType = tokenType,
                    sessionCreatedAt = sessionCreatedAt
                )
            } else {
                MotiumApplication.logger.w("⚠️ Incomplete session data found. Clearing for safety.", "SecureSession")
                clearSession()
                null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error restoring session: ${e.message}. Clearing for safety.", "SecureSession", e)
            clearSession()
            null
        }
    }

    fun clearSession() {
        try {
            encryptedPrefs.edit().clear().apply()
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error clearing session: ${e.message}", "SecureSession", e)
        }
    }

    fun manualLogout() {
        try {
            MotiumApplication.logger.i("👋 Manual logout initiated. Clearing persistent session.", "SecureSession")
            clearSession()
            MotiumApplication.logger.i("✅ Manual logout complete.", "SecureSession")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error during manual logout: ${e.message}", "SecureSession", e)
        }
    }

    fun getRefreshToken(): String? = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    fun getUserId(): String? = encryptedPrefs.getString(KEY_USER_ID, null)
    fun getUserEmail(): String? = encryptedPrefs.getString(KEY_USER_EMAIL, null)
    fun getExpiresAt(): Long? {
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
        return if (expiresAt > 0) expiresAt else null
    }
    fun isTokenExpired(): Boolean = System.currentTimeMillis() >= encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
    fun hasSession(): Boolean = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null) != null
    fun hasValidSession(): Boolean = hasSession() && !isTokenExpired()
    fun isTokenExpiringSoon(minutes: Int): Boolean {
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
        return (expiresAt - System.currentTimeMillis()) < minutes * 60 * 1000
    }

    // ==================== Credentials for Silent Re-Authentication ====================

    /**
     * Save credentials for silent re-authentication.
     * Only call this for email/password authentication (not OAuth).
     */
    fun saveCredentials(email: String, password: String, authMethod: String = "email") {
        try {
            encryptedPrefs.edit()
                .putString(KEY_AUTH_EMAIL, email)
                .putString(KEY_AUTH_PASSWORD, password)
                .putString(KEY_AUTH_METHOD, authMethod)
                .apply()
            MotiumApplication.logger.i("✅ Credentials saved for silent re-auth", "SecureSession")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error saving credentials: ${e.message}", "SecureSession", e)
        }
    }

    /**
     * Retrieve stored credentials for silent re-authentication.
     * Returns null if no credentials are stored or if auth method is not email/password.
     */
    fun getCredentials(): AuthCredentials? {
        return try {
            val email = encryptedPrefs.getString(KEY_AUTH_EMAIL, null)
            val password = encryptedPrefs.getString(KEY_AUTH_PASSWORD, null)
            val authMethod = encryptedPrefs.getString(KEY_AUTH_METHOD, "email") ?: "email"

            if (email != null && password != null && authMethod == "email") {
                AuthCredentials(email, password, authMethod)
            } else {
                null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error retrieving credentials: ${e.message}", "SecureSession", e)
            null
        }
    }

    /**
     * Check if we have stored credentials for silent re-authentication.
     */
    fun hasStoredCredentials(): Boolean {
        return try {
            val email = encryptedPrefs.getString(KEY_AUTH_EMAIL, null)
            val password = encryptedPrefs.getString(KEY_AUTH_PASSWORD, null)
            val authMethod = encryptedPrefs.getString(KEY_AUTH_METHOD, "email")
            email != null && password != null && authMethod == "email"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear stored credentials (called on explicit logout).
     */
    fun clearCredentials() {
        try {
            encryptedPrefs.edit()
                .remove(KEY_AUTH_EMAIL)
                .remove(KEY_AUTH_PASSWORD)
                .remove(KEY_AUTH_METHOD)
                .apply()
            MotiumApplication.logger.i("🗑️ Credentials cleared", "SecureSession")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error clearing credentials: ${e.message}", "SecureSession", e)
        }
    }

    /**
     * Update the auth method (useful when switching between email/OAuth).
     */
    fun setAuthMethod(method: String) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_AUTH_METHOD, method)
                .apply()
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error setting auth method: ${e.message}", "SecureSession", e)
        }
    }

    fun getAuthMethod(): String = encryptedPrefs.getString(KEY_AUTH_METHOD, "email") ?: "email"

    // ==================== JWT Migration ====================

    /**
     * Check if JWT migration is needed (signing algorithm changed from HS256 to ES256).
     * Returns true if stored session was created with an older JWT version.
     */
    fun needsJwtMigration(): Boolean {
        val storedVersion = encryptedPrefs.getInt(KEY_JWT_MIGRATION_VERSION, 1) // Default to v1 (legacy HS256)
        val needsMigration = storedVersion < CURRENT_JWT_VERSION && hasSession()
        if (needsMigration) {
            MotiumApplication.logger.w(
                "⚠️ JWT migration needed: stored version $storedVersion < current version $CURRENT_JWT_VERSION",
                "SecureSession"
            )
        }
        return needsMigration
    }

    /**
     * Mark JWT migration as complete (called after successful token refresh).
     */
    fun markJwtMigrationComplete() {
        try {
            encryptedPrefs.edit()
                .putInt(KEY_JWT_MIGRATION_VERSION, CURRENT_JWT_VERSION)
                .apply()
            MotiumApplication.logger.i(
                "✅ JWT migration complete - now using ES256 (P-256) signed tokens",
                "SecureSession"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error marking JWT migration: ${e.message}", "SecureSession", e)
        }
    }

    /**
     * Get the current stored JWT version.
     */
    fun getStoredJwtVersion(): Int = encryptedPrefs.getInt(KEY_JWT_MIGRATION_VERSION, 1)

    /**
     * Force invalidate the current session for migration purposes.
     * Keeps credentials for silent re-auth but clears tokens.
     */
    fun invalidateSessionForMigration() {
        try {
            MotiumApplication.logger.w(
                "🔄 Invalidating session for JWT migration (keeping credentials for re-auth)",
                "SecureSession"
            )
            // Keep credentials but clear session tokens
            encryptedPrefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_TOKEN_TYPE)
                .remove(KEY_SESSION_CREATED_AT)
                .remove(KEY_PERSISTENT_SESSION_FLAG)
                // Keep refresh token for potential refresh attempt
                // Keep user info for UX
                // Keep credentials for silent re-auth
                .apply()
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error invalidating session: ${e.message}", "SecureSession", e)
        }
    }
}
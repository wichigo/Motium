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
        MotiumApplication.logger.e("❌ Error creating EncryptedSharedPreferences, falling back to standard SharedPreferences", "SecureSession", e)
        context.getSharedPreferences("supabase_session_fallback", Context.MODE_PRIVATE)
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
    }

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
    fun isTokenExpired(): Boolean = System.currentTimeMillis() >= encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
    fun hasSession(): Boolean = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null) != null
    fun hasValidSession(): Boolean = hasSession() && !isTokenExpired()
    fun isTokenExpiringSoon(minutes: Int): Boolean {
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0)
        return (expiresAt - System.currentTimeMillis()) < minutes * 60 * 1000
    }
}
package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.preferences.SecureSessionStorage
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SecureSessionManager(context: Context) : SessionManager {

    private val secureStorage = SecureSessionStorage(context.applicationContext)

    override suspend fun deleteSession() {
        withContext(Dispatchers.IO) {
            try {
                MotiumApplication.logger.d("SecureSessionManager: Deleting session", "SecureSessionManager")
                secureStorage.clearSession()
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error deleting session: ${e.message}", "SecureSessionManager", e)
            }
        }
    }

    override suspend fun loadSession(): UserSession? {
        return withContext(Dispatchers.IO) {
            try {
                // Check if JWT migration is needed (HS256 -> ES256)
                if (secureStorage.needsJwtMigration()) {
                    MotiumApplication.logger.w(
                        "SecureSessionManager: JWT migration detected (HS256 -> ES256). " +
                        "Forcing token refresh on next request.",
                        "SecureSessionManager"
                    )
                    // Return session with expired time to force refresh
                    val savedSession = secureStorage.restoreSession()
                    if (savedSession != null) {
                        return@withContext UserSession(
                            accessToken = savedSession.accessToken,
                            refreshToken = savedSession.refreshToken,
                            expiresIn = -1, // Force refresh by marking as expired
                            tokenType = savedSession.tokenType,
                            user = null
                        )
                    }
                }

                val savedSession = secureStorage.restoreSession()

                if (savedSession != null) {
                    val expiresInMs = savedSession.expiresAt - System.currentTimeMillis()
                    val expiresInSeconds = (expiresInMs / 1000).toLong()

                    // Log the state but do not return null if expired.
                    // The Supabase client needs the refresh token to attempt a refresh.
                    if (expiresInSeconds <= 0) {
                        MotiumApplication.logger.w("SecureSessionManager: Token is expired. Passing to Supabase client for refresh.", "SecureSessionManager")
                    } else {
                        MotiumApplication.logger.d("SecureSessionManager: Loaded session (expires in ${expiresInSeconds}s)", "SecureSessionManager")
                    }

                    UserSession(
                        accessToken = savedSession.accessToken,
                        refreshToken = savedSession.refreshToken,
                        expiresIn = expiresInSeconds,
                        tokenType = savedSession.tokenType,
                        user = null // User object is handled by Supabase client
                    )
                } else {
                    MotiumApplication.logger.d("SecureSessionManager: No session found", "SecureSessionManager")
                    null
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading session: ${e.message}", "SecureSessionManager", e)
                null
            }
        }
    }

    override suspend fun saveSession(session: UserSession) {
        withContext(Dispatchers.IO) {
            try {
                val expiresAtMs = System.currentTimeMillis() + (session.expiresIn * 1000)

                MotiumApplication.logger.d(
                    "SecureSessionManager: Saving session (expires in ${session.expiresIn}s)",
                    "SecureSessionManager"
                )

                var userId: String? = session.user?.id
                var userEmail: String? = session.user?.email

                // If user info is not in the session, extract it from the JWT
                if (userId.isNullOrBlank() || userEmail.isNullOrBlank()) {
                    try {
                        val parts = session.accessToken.split(".")
                        if (parts.size == 3) {
                            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
                            val jsonPayload = JSONObject(payload)
                            if (userId.isNullOrBlank()) {
                                userId = jsonPayload.optString("sub", null)
                                MotiumApplication.logger.d("Extracted userId from JWT: $userId", "SecureSessionManager")
                            }
                            if (userEmail.isNullOrBlank()) {
                                userEmail = jsonPayload.optString("email", null)
                                MotiumApplication.logger.d("Extracted email from JWT: $userEmail", "SecureSessionManager")
                            }
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("Failed to extract user info from JWT: ${e.message}", "SecureSessionManager")
                    }
                }

                val finalUserId = userId ?: ""
                val finalUserEmail = userEmail ?: ""

                if (finalUserId.isBlank()) {
                    MotiumApplication.logger.e("CRITICAL: Could not determine userId before saving session.", "SecureSessionManager")
                    return@withContext
                }

                val sessionData = SecureSessionStorage.SessionData(
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken ?: "", // Ensure non-null
                    expiresAt = expiresAtMs,
                    tokenType = session.tokenType,
                    userEmail = finalUserEmail,
                    userId = finalUserId
                )

                secureStorage.saveSession(sessionData)

                // Mark JWT migration as complete if it was pending
                // This means we successfully got a new ES256 signed token
                if (secureStorage.getStoredJwtVersion() < 2) {
                    secureStorage.markJwtMigrationComplete()
                }

                MotiumApplication.logger.i(
                    "SecureSessionManager: Session saved successfully (user: $finalUserId, email: $finalUserEmail)",
                    "SecureSessionManager"
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error saving session: ${e.message}", "SecureSessionManager", e)
            }
        }
    }
}
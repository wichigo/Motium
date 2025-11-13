package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.preferences.SecureSessionStorage
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SessionManager personnalise pour Supabase qui utilise SecureSessionStorage (chiffre)
 * au lieu de SharedPreferences (non chiffre).
 *
 * Cela permet au SDK Supabase de persister automatiquement les sessions
 * de maniere securisee sans conflit avec notre systeme de gestion de session.
 */
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
                val savedSession = secureStorage.restoreSession()

                if (savedSession != null) {
                    // Calculer le temps d'expiration restant
                    val expiresInMs = savedSession.expiresAt - System.currentTimeMillis()
                    val expiresInSeconds = (expiresInMs / 1000).toLong()

                    // Si le token est expire, ne pas le retourner
                    if (expiresInSeconds <= 0) {
                        MotiumApplication.logger.w("SecureSessionManager: Token expired, returning null", "SecureSessionManager")
                        return@withContext null
                    }

                    MotiumApplication.logger.d(
                        "SecureSessionManager: Loaded session (expires in ${expiresInSeconds}s)",
                        "SecureSessionManager"
                    )

                    // Convertir notre SavedSession en UserSession du SDK
                    UserSession(
                        accessToken = savedSession.accessToken,
                        refreshToken = savedSession.refreshToken,
                        expiresIn = expiresInSeconds,
                        tokenType = savedSession.tokenType,
                        user = null
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
                // Calculer le timestamp d'expiration
                val expiresAtMs = System.currentTimeMillis() + (session.expiresIn * 1000)

                MotiumApplication.logger.d(
                    "SecureSessionManager: Saving session (expires in ${session.expiresIn}s)",
                    "SecureSessionManager"
                )

                // IMPORTANT: Obtenir userId et userEmail de differentes sources
                // Priorite: session.user > storage existant > extraction du JWT
                var userId = session.user?.id
                var userEmail = session.user?.email

                // Si user est null, essayer de recuperer depuis le storage
                if (userId.isNullOrBlank()) {
                    userId = secureStorage.getUserId()
                }
                if (userEmail.isNullOrBlank()) {
                    userEmail = secureStorage.getUserEmail()
                }

                // Si toujours vide, extraire du JWT access token
                if (userId.isNullOrBlank() || userEmail.isNullOrBlank()) {
                    try {
                        // Decoder le JWT pour extraire sub (userId) et email
                        val parts = session.accessToken.split(".")
                        if (parts.size == 3) {
                            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
                            // Parse JSON basique pour extraire sub et email
                            val subMatch = Regex("\"sub\":\"([^\"]+)\"").find(payload)
                            val emailMatch = Regex("\"email\":\"([^\"]+)\"").find(payload)

                            if (userId.isNullOrBlank() && subMatch != null) {
                                userId = subMatch.groupValues[1]
                                MotiumApplication.logger.d("Extracted userId from JWT: $userId", "SecureSessionManager")
                            }
                            if (userEmail.isNullOrBlank() && emailMatch != null) {
                                userEmail = emailMatch.groupValues[1]
                                MotiumApplication.logger.d("Extracted email from JWT: $userEmail", "SecureSessionManager")
                            }
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("Failed to extract user info from JWT: ${e.message}", "SecureSessionManager")
                    }
                }

                // Valeurs finales (ne jamais stocker vide)
                val finalUserId = userId ?: ""
                val finalUserEmail = userEmail ?: ""

                // Creer un SessionData
                val sessionData = SecureSessionStorage.SessionData(
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken,
                    expiresAt = expiresAtMs,
                    tokenType = session.tokenType,
                    userEmail = finalUserEmail,
                    userId = finalUserId
                )

                // Sauvegarder avec SecureSessionStorage
                secureStorage.saveSession(sessionData)

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

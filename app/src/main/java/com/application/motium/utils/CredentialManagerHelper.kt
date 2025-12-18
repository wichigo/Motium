package com.application.motium.utils

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.application.motium.MotiumApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for managing credentials using Android Credential Manager API.
 * Supports Samsung Pass, Google Password Manager, Bitwarden, and other credential providers.
 */
class CredentialManagerHelper private constructor(context: Context) {

    private val credentialManager = CredentialManager.create(context)

    companion object {
        @Volatile
        private var instance: CredentialManagerHelper? = null

        fun getInstance(context: Context): CredentialManagerHelper {
            return instance ?: synchronized(this) {
                instance ?: CredentialManagerHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Result class for credential operations
     */
    sealed class CredentialResult {
        data class Success(val email: String, val password: String) : CredentialResult()
        data object Cancelled : CredentialResult()
        data object NoCredentials : CredentialResult()
        data class Error(val message: String) : CredentialResult()
    }

    /**
     * Save credentials after successful login.
     * This will trigger the system's "Save password?" prompt.
     *
     * @param activity The activity context (required for UI)
     * @param email User's email
     * @param password User's password
     * @return true if saved successfully, false otherwise
     */
    suspend fun saveCredentials(
        activity: android.app.Activity,
        email: String,
        password: String
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            MotiumApplication.logger.i("Attempting to save credentials for $email", "CredentialManager")

            val createPasswordRequest = CreatePasswordRequest(
                id = email,
                password = password
            )

            credentialManager.createCredential(
                context = activity,
                request = createPasswordRequest
            )

            MotiumApplication.logger.i("Credentials saved successfully for $email", "CredentialManager")
            true
        } catch (e: CreateCredentialCancellationException) {
            MotiumApplication.logger.d("User cancelled credential save", "CredentialManager")
            false
        } catch (e: CreateCredentialException) {
            MotiumApplication.logger.e("Failed to save credentials: ${e.message}", "CredentialManager", e)
            false
        } catch (e: Exception) {
            MotiumApplication.logger.e("Unexpected error saving credentials: ${e.message}", "CredentialManager", e)
            false
        }
    }

    /**
     * Retrieve saved credentials for autofill.
     * This will show the credential picker if multiple accounts are saved.
     *
     * @param activity The activity context (required for UI)
     * @return CredentialResult with email/password or error state
     */
    suspend fun getCredentials(
        activity: android.app.Activity
    ): CredentialResult = withContext(Dispatchers.Main) {
        try {
            MotiumApplication.logger.i("Attempting to retrieve saved credentials", "CredentialManager")

            val getCredentialRequest = GetCredentialRequest(
                credentialOptions = listOf(GetPasswordOption())
            )

            val result = credentialManager.getCredential(
                context = activity,
                request = getCredentialRequest
            )

            when (val credential = result.credential) {
                is PasswordCredential -> {
                    MotiumApplication.logger.i("Credentials retrieved for ${credential.id}", "CredentialManager")
                    CredentialResult.Success(
                        email = credential.id,
                        password = credential.password
                    )
                }
                else -> {
                    MotiumApplication.logger.w("Unexpected credential type: ${credential.type}", "CredentialManager")
                    CredentialResult.Error("Type d'identifiant non supporté")
                }
            }
        } catch (e: GetCredentialCancellationException) {
            MotiumApplication.logger.d("User cancelled credential retrieval", "CredentialManager")
            CredentialResult.Cancelled
        } catch (e: NoCredentialException) {
            MotiumApplication.logger.d("No saved credentials found", "CredentialManager")
            CredentialResult.NoCredentials
        } catch (e: GetCredentialException) {
            MotiumApplication.logger.e("Failed to retrieve credentials: ${e.message}", "CredentialManager", e)
            CredentialResult.Error(e.message ?: "Erreur inconnue")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Unexpected error retrieving credentials: ${e.message}", "CredentialManager", e)
            CredentialResult.Error(e.message ?: "Erreur inconnue")
        }
    }

    /**
     * Clear saved credentials on logout.
     * This signals to credential providers that the user has logged out.
     */
    suspend fun clearCredentialState(): Boolean = withContext(Dispatchers.Main) {
        try {
            MotiumApplication.logger.i("Clearing credential state", "CredentialManager")
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            MotiumApplication.logger.i("Credential state cleared", "CredentialManager")
            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to clear credential state: ${e.message}", "CredentialManager", e)
            false
        }
    }
}

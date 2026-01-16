package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * API-DIRECT: Envoi emails - Opération serveur uniquement.
 *
 * Repository for sending transactional emails via Supabase Edge Function.
 * Uses Resend API on the backend.
 *
 * All email operations are non-blocking and fire-and-forget:
 * - Errors are logged but don't fail the main flow
 * - If RESEND_API_KEY is not configured, emails are silently skipped
 *
 * Not suitable for offline-first because:
 * - Email sending is a server-side operation only
 * - No local state to cache or sync
 */
class EmailRepository private constructor(private val context: Context) {

    private val supabaseClient = SupabaseClient.client

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "EmailRepository"

        @Volatile
        private var instance: EmailRepository? = null

        fun getInstance(context: Context): EmailRepository {
            return instance ?: synchronized(this) {
                instance ?: EmailRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Send welcome email after successful registration.
     * Non-blocking - errors are logged but don't fail the main flow.
     *
     * @param email User's email address
     * @param name User's display name
     * @param accountType "INDIVIDUAL" or "ENTERPRISE"
     */
    suspend fun sendWelcomeEmail(
        email: String,
        name: String,
        accountType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        sendEmail(
            template = "welcome",
            recipientEmail = email,
            data = mapOf(
                "name" to name,
                "email" to email,
                "account_type" to accountType
            )
        )
    }

    /**
     * Send collaborator invitation email.
     * Called after creating invitation in company_links table.
     *
     * @param email Employee's email address
     * @param employeeName Employee's full name
     * @param companyName Company name
     * @param department Optional department name
     * @param invitationToken Token for accepting invitation
     */
    suspend fun sendCollaboratorInvitation(
        email: String,
        employeeName: String,
        companyName: String,
        department: String? = null,
        invitationToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        sendEmail(
            template = "collaborator_invitation",
            recipientEmail = email,
            data = mapOf(
                "employee_name" to employeeName,
                "company_name" to companyName,
                "department" to (department ?: ""),
                "invitation_token" to invitationToken
            )
        )
    }

    /**
     * Internal method to send email via Edge Function.
     */
    private suspend fun sendEmail(
        template: String,
        recipientEmail: String,
        data: Map<String, String>
    ): Result<Unit> {
        return try {
            val accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: return Result.failure(Exception("Not authenticated"))

            val requestBody = buildJsonObject {
                put("email", recipientEmail)
                put("template", template)
                put("data", buildJsonObject {
                    data.forEach { (key, value) -> put(key, value) }
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/send-gdpr-email")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                MotiumApplication.logger.i("Email sent successfully: $template to $recipientEmail", TAG)
                Result.success(Unit)
            } else {
                val error = response.body?.string() ?: "Unknown error"
                MotiumApplication.logger.w("Email sending failed: $error", TAG)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error sending email: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Request a password reset via Edge Function.
     * The Edge Function handles token creation and email sending.
     *
     * @param email User's email address
     * @return Success if request was processed (even if email doesn't exist, for security)
     */
    suspend fun requestPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("email", email)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/request-password-reset")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                MotiumApplication.logger.i("Password reset request sent for $email", TAG)
                Result.success(Unit)
            } else {
                val errorMessage = try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
                    json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                } catch (e: Exception) {
                    responseBody
                }
                MotiumApplication.logger.e("Password reset request failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error requesting password reset: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Reset password using token.
     * Validates token and updates password via Edge Function.
     *
     * @param token Password reset token
     * @param newPassword New password to set
     */
    suspend fun resetPassword(token: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("token", token)
                put("new_password", newPassword)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/reset-password")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                MotiumApplication.logger.i("Password reset successful", TAG)
                Result.success(Unit)
            } else {
                // Parse error message from response
                val errorMessage = try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
                    json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                } catch (e: Exception) {
                    responseBody
                }
                MotiumApplication.logger.e("Password reset failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error resetting password: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    // ============================================================
    // Email Verification
    // ============================================================

    /**
     * Request email verification.
     * Creates a token and sends verification email via Edge Function.
     *
     * @param userId User's ID
     * @param email Email address to verify
     * @param name User's display name (optional)
     */
    suspend fun requestEmailVerification(
        userId: String,
        email: String,
        name: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("user_id", userId)
                put("email", email)
                name?.let { put("name", it) }
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/request-email-verification")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                MotiumApplication.logger.i("Email verification request sent for $email", TAG)
                Result.success(Unit)
            } else {
                val errorMessage = parseErrorMessage(responseBody)
                MotiumApplication.logger.e("Email verification request failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error requesting email verification: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Verify email using token.
     * Called when user clicks verification link.
     *
     * @param token Email verification token
     * @return Result with email address if successful
     */
    suspend fun verifyEmail(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("token", token)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/verify-email")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
                val success = json.jsonObject["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val email = json.jsonObject["email"]?.jsonPrimitive?.content ?: ""
                val alreadyVerified = json.jsonObject["already_verified"]?.jsonPrimitive?.content?.toBoolean() ?: false

                if (success) {
                    MotiumApplication.logger.i("Email verified: $email", TAG)
                    Result.success(email)
                } else if (alreadyVerified) {
                    MotiumApplication.logger.i("Email already verified: $email", TAG)
                    Result.success(email)
                } else {
                    val error = json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    Result.failure(Exception(error))
                }
            } else {
                val errorMessage = parseErrorMessage(responseBody)
                MotiumApplication.logger.e("Email verification failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error verifying email: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    // ============================================================
    // Unlink Confirmation
    // ============================================================

    /**
     * Request unlink confirmation.
     * Creates a token and sends confirmation email to the initiator.
     *
     * @param companyLinkId ID of the company_link to unlink
     * @param initiatedBy "employee" or "pro_account"
     * @param initiatorEmail Email of the person initiating the unlink
     */
    suspend fun requestUnlinkConfirmation(
        companyLinkId: String,
        initiatedBy: String,
        initiatorEmail: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val requestBody = buildJsonObject {
                put("company_link_id", companyLinkId)
                put("initiated_by", initiatedBy)
                put("initiator_email", initiatorEmail)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/request-unlink-confirmation")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                MotiumApplication.logger.i("Unlink confirmation request sent for link $companyLinkId", TAG)
                Result.success(Unit)
            } else {
                val errorMessage = parseErrorMessage(responseBody)
                MotiumApplication.logger.e("Unlink confirmation request failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error requesting unlink confirmation: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Confirm unlink using token.
     * Performs the actual unlinking after confirmation.
     *
     * @param token Unlink confirmation token
     * @return Result with success status
     */
    suspend fun confirmUnlink(token: String): Result<UnlinkResult> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("token", token)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/confirm-unlink")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
                val success = json.jsonObject["success"]?.jsonPrimitive?.content?.toBoolean() ?: false

                if (success) {
                    val employeeName = json.jsonObject["employee_name"]?.jsonPrimitive?.content ?: ""
                    val companyName = json.jsonObject["company_name"]?.jsonPrimitive?.content ?: ""
                    MotiumApplication.logger.i("Unlink confirmed: $employeeName from $companyName", TAG)
                    Result.success(UnlinkResult(employeeName, companyName))
                } else {
                    val error = json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    Result.failure(Exception(error))
                }
            } else {
                val errorMessage = parseErrorMessage(responseBody)
                MotiumApplication.logger.e("Unlink confirmation failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error confirming unlink: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Parse error message from JSON response.
     */
    private fun parseErrorMessage(responseBody: String): String {
        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
            json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
        } catch (e: Exception) {
            responseBody.ifBlank { "Unknown error" }
        }
    }

    /**
     * Result of successful unlink operation.
     */
    data class UnlinkResult(
        val employeeName: String,
        val companyName: String
    )

    // ============================================================
    // Invitation Acceptance
    // ============================================================

    /**
     * Accept a collaborator invitation using token.
     * Validates the token and activates the company link.
     *
     * @param token Invitation token from the deep link
     * @param userId Optional user ID of the person accepting
     * @return Result with invitation details if successful
     */
    suspend fun acceptInvitation(
        token: String,
        userId: String? = null
    ): Result<InvitationResult> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("token", token)
                userId?.let { put("user_id", it) }
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/accept-invitation")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
            val success = json.jsonObject["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
            val requiresLogin = json.jsonObject["requires_login"]?.jsonPrimitive?.content?.toBoolean() ?: false
            val invitationEmail = json.jsonObject["invitation_email"]?.jsonPrimitive?.content

            // Handle requires_login response (user needs to log in first)
            if (requiresLogin || json.jsonObject["error_code"]?.jsonPrimitive?.content == "requires_login") {
                MotiumApplication.logger.i("Invitation requires login: $invitationEmail", TAG)
                return@withContext Result.success(InvitationResult(
                    companyLinkId = "",
                    companyName = "",
                    department = null,
                    proAccountId = "",
                    alreadyAccepted = false,
                    requiresLogin = true,
                    invitationEmail = invitationEmail
                ))
            }

            if (response.isSuccessful && success) {
                val companyLinkId = json.jsonObject["company_link_id"]?.jsonPrimitive?.content ?: ""
                val companyName = json.jsonObject["company_name"]?.jsonPrimitive?.content ?: ""
                val department = json.jsonObject["department"]?.jsonPrimitive?.content
                val proAccountId = json.jsonObject["pro_account_id"]?.jsonPrimitive?.content ?: ""
                val alreadyAccepted = json.jsonObject["already_accepted"]?.jsonPrimitive?.content?.toBoolean() ?: false

                MotiumApplication.logger.i("Invitation accepted: $companyName", TAG)
                Result.success(InvitationResult(
                    companyLinkId = companyLinkId,
                    companyName = companyName,
                    department = department,
                    proAccountId = proAccountId,
                    alreadyAccepted = alreadyAccepted
                ))
            } else {
                val error = json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                val errorCode = json.jsonObject["error_code"]?.jsonPrimitive?.content ?: "unknown"
                MotiumApplication.logger.e("Accept invitation failed: $error ($errorCode)", TAG)
                Result.failure(InvitationException(error, errorCode, invitationEmail))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error accepting invitation: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Result of successful invitation acceptance.
     */
    data class InvitationResult(
        val companyLinkId: String,
        val companyName: String,
        val department: String?,
        val proAccountId: String,
        val alreadyAccepted: Boolean = false,
        val requiresLogin: Boolean = false,
        val invitationEmail: String? = null
    )

    // ============================================================
    // Initial Password Setup (for invited users without account)
    // ============================================================

    /**
     * Set initial password for a new user invited via company link.
     * This creates a new user account and activates the invitation.
     *
     * @param invitationToken The invitation token from the deep link
     * @param email User's email (must match invitation email)
     * @param password New password to set
     * @param name User's display name (optional)
     * @param phone User's phone number (optional)
     * @return Result with company info if successful
     */
    suspend fun setInitialPassword(
        invitationToken: String,
        email: String,
        password: String,
        name: String? = null,
        phone: String? = null
    ): Result<SetPasswordResult> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("invitation_token", invitationToken)
                put("email", email)
                put("password", password)
                name?.let { put("name", it) }
                phone?.let { put("phone", it) }
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/set-initial-password")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(responseBody)
                val success = json.jsonObject["success"]?.jsonPrimitive?.content?.toBoolean() ?: false

                if (success) {
                    val userId = json.jsonObject["user_id"]?.jsonPrimitive?.content ?: ""
                    val companyLinkId = json.jsonObject["company_link_id"]?.jsonPrimitive?.content ?: ""
                    val companyName = json.jsonObject["company_name"]?.jsonPrimitive?.content ?: ""

                    MotiumApplication.logger.i("Initial password set for $email", TAG)
                    Result.success(SetPasswordResult(
                        userId = userId,
                        companyLinkId = companyLinkId,
                        companyName = companyName
                    ))
                } else {
                    val error = json.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    val errorCode = json.jsonObject["error_code"]?.jsonPrimitive?.content ?: "unknown"
                    Result.failure(SetPasswordException(error, errorCode))
                }
            } else {
                val errorMessage = parseErrorMessage(responseBody)
                MotiumApplication.logger.e("Set initial password failed: $errorMessage", TAG)
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error setting initial password: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Result of successful initial password setup.
     */
    data class SetPasswordResult(
        val userId: String,
        val companyLinkId: String,
        val companyName: String
    )

    /**
     * Exception with error code for set password failures.
     */
    class SetPasswordException(
        message: String,
        val errorCode: String
    ) : Exception(message)

    /**
     * Exception with error code for invitation failures.
     */
    class InvitationException(
        message: String,
        val errorCode: String,
        val invitationEmail: String? = null
    ) : Exception(message)
}

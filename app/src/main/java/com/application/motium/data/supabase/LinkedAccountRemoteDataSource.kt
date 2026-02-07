package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.SharingPreferences
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * REMOTE DATA SOURCE - Direct API access to Supabase.
 * Manages linked users (Individual users linked to Pro accounts).
 * Data is stored in the company_links table, with user details from users table.
 */
class LinkedAccountRemoteDataSource private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }
    private val emailRepository by lazy { EmailRepository.getInstance(context) }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var instance: LinkedAccountRemoteDataSource? = null

        fun getInstance(context: Context): LinkedAccountRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: LinkedAccountRemoteDataSource(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get all users linked to a Pro account
     * Queries company_links table with linked_pro_account_id filter
     */
    suspend fun getLinkedUsers(proAccountId: String): Result<List<LinkedUserDto>> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching linked users for Pro account: $proAccountId", "LinkedAccountRepo")

            // Query company_links with embedded user data
            // Using simple join syntax - PostgREST auto-detects the FK relationship
            val response = supabaseClient.from("company_links")
                .select(Columns.raw("*, users(id, name, email, phone_number, subscription_type)")) {
                    filter {
                        eq("linked_pro_account_id", proAccountId)
                    }
                }
                .decodeList<CompanyLinkWithUserDto>()
                .map { it.toLinkedUserDto() }

            MotiumApplication.logger.i("Found ${response.size} linked users for Pro account $proAccountId", "LinkedAccountRepo")
            Result.success(response)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "LinkedAccountRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("company_links")
                            .select(Columns.raw("*, users(id, name, email, phone_number, subscription_type)")) {
                                filter {
                                    eq("linked_pro_account_id", proAccountId)
                                }
                            }
                            .decodeList<CompanyLinkWithUserDto>()
                            .map { it.toLinkedUserDto() }
                        MotiumApplication.logger.i("Linked users loaded after token refresh", "LinkedAccountRepo")
                        Result.success(response)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LinkedAccountRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting linked users: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked users: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get a linked user by user ID (returns link info + user details)
     */
    suspend fun getLinkedUserById(userId: String): Result<LinkedUserDto> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("company_links")
                .select(Columns.raw("*, users(id, name, email, phone_number, subscription_type)")) {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeSingle<CompanyLinkWithUserDto>()

            Result.success(response.toLinkedUserDto())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get a linked user by link ID
     */
    suspend fun getLinkedUserByLinkId(linkId: String): Result<LinkedUserDto> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("company_links")
                .select(Columns.raw("*, users(id, name, email, phone_number, subscription_type)")) {
                    filter {
                        eq("id", linkId)
                    }
                }
                .decodeSingle<CompanyLinkWithUserDto>()

            Result.success(response.toLinkedUserDto())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked user by link ID: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Invite a user by email - creates entry in company_links
     */
    suspend fun inviteUser(proAccountId: String, companyName: String, email: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            // Generate invitation token
            val invitationToken = java.util.UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // First check if user exists
            val existingUsers = supabaseClient.from("users")
                .select() {
                    filter {
                        eq("email", email)
                    }
                }
                .decodeList<MinimalUserDto>()

            val userId: String
            if (existingUsers.isNotEmpty()) {
                userId = existingUsers.first().id
            } else {
                // User doesn't exist - cannot create invitation without user
                return@withContext Result.failure(Exception("User with email $email not found"))
            }

            // Create company_link entry
            val linkInsert = CompanyLinkInsertDto(
                user_id = userId,
                linked_pro_account_id = proAccountId,
                company_name = companyName,
                status = "PENDING",
                invitation_token = invitationToken,
                linked_at = now
            )

            supabaseClient.from("company_links").insert(linkInsert)

            MotiumApplication.logger.i("Invitation sent to $email", "LinkedAccountRepo")
            Result.success(invitationToken)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inviting user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Invite a user with additional details (full name, phone, department)
     * Uses Edge Function to bypass RLS - can invite users who don't have an account yet
     */
    suspend fun inviteUserWithDetails(
        proAccountId: String,
        companyName: String,
        email: String,
        fullName: String,
        phone: String? = null,
        department: String? = null
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            // Build request body
            val requestBody = buildString {
                append("{")
                append("\"pro_account_id\":\"$proAccountId\",")
                append("\"company_name\":\"${companyName.replace("\"", "\\\"")}\",")
                append("\"email\":\"$email\",")
                append("\"full_name\":\"${fullName.replace("\"", "\\\"")}\"")
                if (phone != null) append(",\"phone\":\"$phone\"")
                if (department != null) append(",\"department\":\"${department.replace("\"", "\\\"")}\"")
                append("}")
            }.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/send-invitation")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                val success = jsonResponse["success"]?.jsonPrimitive?.boolean ?: false

                if (success) {
                    val invitationToken = jsonResponse["invitation_token"]?.jsonPrimitive?.content
                    val userExists = jsonResponse["user_exists"]?.jsonPrimitive?.boolean ?: false
                    MotiumApplication.logger.i(
                        "Invitation created for $email (user exists: $userExists)",
                        "LinkedAccountRepo"
                    )
                    Result.success(invitationToken)
                } else {
                    val error = jsonResponse["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    val retryAfterMinutes = jsonResponse["retry_after_minutes"]?.jsonPrimitive?.content?.toIntOrNull()
                    MotiumApplication.logger.e("Invitation failed: $error", "LinkedAccountRepo")
                    Result.failure(Exception(translateInvitationError(error, retryAfterMinutes)))
                }
            } else {
                val error = try {
                    val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                    val baseError = jsonResponse["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    val retryAfterMinutes = jsonResponse["retry_after_minutes"]?.jsonPrimitive?.content?.toIntOrNull()
                    translateInvitationError(baseError, retryAfterMinutes)
                } catch (e: Exception) {
                    responseBody.ifBlank { "Server error (${response.code})" }
                }
                MotiumApplication.logger.e("Invitation request failed: $error", "LinkedAccountRepo")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inviting user with details: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Translate error codes to user-friendly messages
     */
    private fun translateInvitationError(error: String, retryAfterMinutes: Int? = null): String {
        return when (error) {
            "user_already_linked" -> "Cet utilisateur est déjà lié à votre entreprise"
            "invitation_already_pending" -> "Une invitation est déjà en attente pour cet email"
            "invitation_recently_sent" -> {
                if (retryAfterMinutes != null && retryAfterMinutes > 0) {
                    "Invitation deja envoyee recemment. Reessayez dans $retryAfterMinutes min."
                } else {
                    "Invitation deja envoyee recemment. Merci d'attendre avant de renvoyer."
                }
            }
            "email_send_failed" -> "L'invitation a ete enregistree mais l'envoi du mail a echoue"
            "Missing required fields" -> "Veuillez remplir tous les champs obligatoires"
            else -> error
        }
    }

    /**
     * Revoke access for a linked user (Pro-initiated)
     */
    suspend fun revokeUser(linkId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = java.time.Instant.now().toString()

            supabaseClient.from("company_links")
                .update({
                    set("status", "REVOKED")
                    set("unlinked_at", now)
                }) {
                    filter {
                        eq("id", linkId)
                    }
                }

            MotiumApplication.logger.i("Link $linkId access revoked", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error revoking link: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Revoke access by user ID (finds the link first)
     */
    suspend fun revokeUserByUserId(userId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = java.time.Instant.now().toString()

            supabaseClient.from("company_links")
                .update({
                    set("status", "REVOKED")
                    set("unlinked_at", now)
                }) {
                    filter {
                        eq("user_id", userId)
                        eq("linked_pro_account_id", proAccountId)
                    }
                }

            MotiumApplication.logger.i("User $userId access revoked from Pro $proAccountId", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error revoking user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Accept an invitation - updates the link status in company_links
     */
    suspend fun acceptInvitation(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val now = java.time.Instant.now().toString()

            supabaseClient.from("company_links")
                .update({
                    set("status", "ACTIVE")
                    set("invitation_token", null as String?)
                    set("linked_activated_at", now)
                }) {
                    filter {
                        eq("invitation_token", token)
                        eq("status", "PENDING")
                    }
                }

            MotiumApplication.logger.i("Invitation accepted", "LinkedAccountRepo")
            Result.success(true)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error accepting invitation: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Update sharing preferences for a company link
     */
    suspend fun updateSharingPreferences(
        linkId: String,
        preferences: SharingPreferences
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("company_links")
                .update({
                    set("share_professional_trips", preferences.shareProfessionalTrips)
                    set("share_personal_trips", preferences.sharePersonalTrips)
                    set("share_personal_info", preferences.shareVehicleInfo) // mapped to share_personal_info in DB
                    set("share_expenses", preferences.shareExpenses)
                }) {
                    filter {
                        eq("id", linkId)
                    }
                }

            MotiumApplication.logger.i("Sharing preferences updated for link $linkId", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating sharing preferences: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Update sharing preferences by user ID
     */
    suspend fun updateSharingPreferencesByUserId(
        userId: String,
        proAccountId: String,
        preferences: SharingPreferences
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("company_links")
                .update({
                    set("share_professional_trips", preferences.shareProfessionalTrips)
                    set("share_personal_trips", preferences.sharePersonalTrips)
                    set("share_personal_info", preferences.shareVehicleInfo)
                    set("share_expenses", preferences.shareExpenses)
                }) {
                    filter {
                        eq("user_id", userId)
                        eq("linked_pro_account_id", proAccountId)
                    }
                }

            MotiumApplication.logger.i("Sharing preferences updated for user $userId", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating sharing preferences: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Update department for a company link
     * @param linkId The company_links.id
     * @param department The new department name (nullable to remove)
     */
    suspend fun updateLinkDepartment(
        linkId: String,
        department: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("company_links")
                .update({
                    set("department", department)
                    set("updated_at", java.time.Instant.now().toString())
                }) {
                    filter {
                        eq("id", linkId)
                    }
                }

            MotiumApplication.logger.i("Department updated for link $linkId: $department", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating department: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Resend invitation email to a pending user
     */
    suspend fun resendInvitation(
        companyLinkId: String,
        companyName: String,
        email: String,
        userName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val currentInvite = supabaseClient.from("company_links")
                .select(Columns.raw("id, status, invitation_token, invitation_expires_at, created_at, updated_at")) {
                    filter { eq("id", companyLinkId) }
                }
                .decodeSingle<ResendInvitationStateDto>()

            if (currentInvite.status != "PENDING") {
                return@withContext Result.failure(Exception("Cette invitation n'est plus en attente"))
            }

            val now = java.time.Instant.now()
            val parsedExpiresAt = currentInvite.invitation_expires_at?.let {
                runCatching { java.time.Instant.parse(it) }.getOrNull()
            }
            val parsedUpdatedAt = currentInvite.updated_at?.let {
                runCatching { java.time.Instant.parse(it) }.getOrNull()
            }
            val parsedCreatedAt = currentInvite.created_at?.let {
                runCatching { java.time.Instant.parse(it) }.getOrNull()
            }

            // Approximate original send time from expiry (expiry is set to now + 7 days when invite is sent).
            val derivedSentAt = parsedExpiresAt?.minusSeconds(7L * 24 * 60 * 60)
            val lastSentAt = listOfNotNull(parsedUpdatedAt, derivedSentAt, parsedCreatedAt).maxOrNull()

            if (lastSentAt != null) {
                val nextAllowedAt = lastSentAt.plusSeconds(60L * 60)
                if (now.isBefore(nextAllowedAt)) {
                    val remainingMinutes = java.time.Duration.between(now, nextAllowedAt).toMinutes().coerceAtLeast(1)
                    return@withContext Result.failure(
                        Exception("Invitation deja envoyee recemment. Reessayez dans $remainingMinutes min.")
                    )
                }
            }

            val hasValidToken = !currentInvite.invitation_token.isNullOrBlank() &&
                parsedExpiresAt != null &&
                parsedExpiresAt.isAfter(now)

            val tokenToSend = if (hasValidToken) {
                currentInvite.invitation_token!!
            } else {
                java.util.UUID.randomUUID().toString()
            }

            // Only rotate token when it's missing/expired. Otherwise keep it to avoid invalidating old links.
            if (!hasValidToken) {
                val refreshedExpiresAt = now.plusSeconds(7L * 24 * 60 * 60).toString()
                supabaseClient.from("company_links")
                    .update({
                        set("invitation_token", tokenToSend)
                        set("invitation_expires_at", refreshedExpiresAt)
                        set("updated_at", now.toString())
                    }) {
                        filter {
                            eq("id", companyLinkId)
                            eq("status", "PENDING")
                        }
                    }
            }

            // Send the invitation email via send-gdpr-email Edge Function
            // (same as what send-invitation does internally)
            val escapedUserName = userName.replace("\"", "\\\"")
            val escapedCompanyName = companyName.replace("\"", "\\\"")
            val requestBody = buildString {
                append("{")
                append("\"email\":\"$email\",")
                append("\"template\":\"collaborator_invitation\",")
                append("\"data\":{")
                append("\"employee_name\":\"$escapedUserName\",")
                append("\"company_name\":\"$escapedCompanyName\",")
                append("\"invitation_token\":\"$tokenToSend\"")
                append("}")
                append("}")
            }.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/send-gdpr-email")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                // Track the last successful send timestamp for cooldown enforcement.
                supabaseClient.from("company_links")
                    .update({
                        set("updated_at", now.toString())
                    }) {
                        filter {
                            eq("id", companyLinkId)
                            eq("status", "PENDING")
                        }
                    }
                MotiumApplication.logger.i("Invitation resent to $email", "LinkedAccountRepo")
                Result.success(Unit)
            } else {
                val error = try {
                    val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                    jsonResponse["error"]?.jsonPrimitive?.content ?: "Unknown error"
                } catch (e: Exception) {
                    responseBody.ifBlank { "Server error (${response.code})" }
                }
                MotiumApplication.logger.e("Failed to resend invitation: $error", "LinkedAccountRepo")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error resending invitation: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a linked account completely.
     * This removes the company_link entry from the database.
     *
     * Note: If the user had a license assigned, the caller should handle
     * setting the license to pending_unlink before calling this method.
     */
    suspend fun deleteLinkedAccount(companyLinkId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("company_links")
                .delete {
                    filter {
                        eq("id", companyLinkId)
                    }
                }

            MotiumApplication.logger.i("Company link $companyLinkId deleted", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting company link: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Request unlink from Pro account (user-initiated).
     * This initiates the confirmation flow via Edge Function.
     * The actual unlink happens only after email confirmation.
     *
     * @param linkId The company_link ID
     * @param initiatedBy "employee" for user-initiated, "pro_account" for Pro-initiated
     * @param initiatorEmail Email of the person requesting the unlink
     * @return Result with success if confirmation email was sent
     */
    suspend fun requestUnlinkConfirmation(
        linkId: String,
        initiatedBy: String = "employee",
        initiatorEmail: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val requestBody = buildString {
                append("{")
                append("\"company_link_id\":\"$linkId\",")
                append("\"initiated_by\":\"$initiatedBy\",")
                append("\"initiator_email\":\"${initiatorEmail.replace("\"", "\\\"")}\"")
                append("}")
            }.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/request-unlink-confirmation")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                val success = jsonResponse["success"]?.jsonPrimitive?.boolean ?: false

                if (success) {
                    MotiumApplication.logger.i(
                        "Unlink confirmation requested for link $linkId (initiated by: $initiatedBy)",
                        "LinkedAccountRepo"
                    )
                    Result.success(Unit)
                } else {
                    val error = jsonResponse["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    MotiumApplication.logger.e("Unlink confirmation request failed: $error", "LinkedAccountRepo")
                    Result.failure(Exception(error))
                }
            } else {
                val error = try {
                    val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                    jsonResponse["error"]?.jsonPrimitive?.content ?: "Unknown error"
                } catch (e: Exception) {
                    responseBody.ifBlank { "Server error (${response.code})" }
                }
                MotiumApplication.logger.e("Unlink confirmation request failed: $error", "LinkedAccountRepo")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error requesting unlink confirmation: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * @deprecated Use requestUnlinkConfirmation() instead for proper confirmation flow.
     * This method is kept for backward compatibility but should not be used directly.
     * Direct status updates bypass the email confirmation flow required for GDPR compliance.
     */
    @Deprecated(
        message = "Use requestUnlinkConfirmation() for proper confirmation flow",
        replaceWith = ReplaceWith("requestUnlinkConfirmation(linkId, \"employee\", initiatorEmail)")
    )
    suspend fun unlinkFromPro(linkId: String): Result<Unit> = withContext(Dispatchers.IO) {
        MotiumApplication.logger.w(
            "DEPRECATED: unlinkFromPro() called directly. Use requestUnlinkConfirmation() instead.",
            "LinkedAccountRepo"
        )
        // Return failure to prevent direct updates - caller should use requestUnlinkConfirmation
        Result.failure(Exception("Direct unlink not supported. Use requestUnlinkConfirmation() for email confirmation flow."))
    }

    /**
     * @deprecated Use requestUnlinkConfirmation() instead for proper confirmation flow.
     * This method is kept for backward compatibility but should not be used directly.
     * Direct status updates bypass the email confirmation flow required for GDPR compliance.
     */
    @Deprecated(
        message = "Use requestUnlinkConfirmation() for proper confirmation flow",
        replaceWith = ReplaceWith("requestUnlinkConfirmation(linkId, \"employee\", initiatorEmail)")
    )
    suspend fun unlinkFromProByUserId(userId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        MotiumApplication.logger.w(
            "DEPRECATED: unlinkFromProByUserId() called directly. Use requestUnlinkConfirmation() instead.",
            "LinkedAccountRepo"
        )
        // Return failure to prevent direct updates - caller should use requestUnlinkConfirmation
        Result.failure(Exception("Direct unlink not supported. Use requestUnlinkConfirmation() for email confirmation flow."))
    }
}

// ==================== DTOs ====================

/**
 * DTO for company_links with embedded user data from foreign key join
 */
@Serializable
data class CompanyLinkWithUserDto(
    val id: String,
    val user_id: String? = null,  // Nullable for pending invitations
    val linked_pro_account_id: String,
    val company_name: String,
    val department: String? = null,
    val status: String,
    val share_professional_trips: Boolean = true,
    val share_personal_trips: Boolean = false,
    val share_personal_info: Boolean = true,
    val share_expenses: Boolean = false,
    val invitation_token: String? = null,
    val invitation_email: String? = null,  // Email for pending invitations (user not yet created)
    val linked_at: String? = null,
    val linked_activated_at: String? = null,
    val unlinked_at: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val users: EmbeddedUserDto? = null  // Embedded user data from join
) {
    fun toLinkedUserDto(): LinkedUserDto = LinkedUserDto(
        linkId = id,
        userId = user_id,
        userName = users?.name,
        userEmail = users?.email ?: invitation_email ?: "",  // Fallback to invitation_email for pending
        userPhone = users?.phone_number,
        personalSubscriptionType = users?.subscription_type,
        department = department,
        linkStatus = status,
        invitedAt = linked_at,
        linkActivatedAt = linked_activated_at,
        shareProfessionalTrips = share_professional_trips,
        sharePersonalTrips = share_personal_trips,
        shareVehicleInfo = share_personal_info,
        shareExpenses = share_expenses
    )
}

/**
 * Embedded user data from foreign key join
 */
@Serializable
data class EmbeddedUserDto(
    val id: String,
    val name: String? = null,
    val email: String,
    val phone_number: String? = null,
    val subscription_type: String? = null
)

/**
 * DTO for inserting a new company link
 */
@Serializable
data class CompanyLinkInsertDto(
    val user_id: String? = null,               // Nullable: null for invitations to non-existing users
    val linked_pro_account_id: String,
    val company_name: String,
    val department: String? = null,
    val status: String = "PENDING",
    val share_professional_trips: Boolean = true,
    val share_personal_trips: Boolean = false,
    val share_personal_info: Boolean = true,
    val share_expenses: Boolean = false,
    val invitation_token: String? = null,
    val invitation_email: String? = null,      // Email of invited user (before account creation)
    val invitation_expires_at: String? = null, // When invitation expires
    val linked_at: String? = null
)

/**
 * Minimal user DTO for checking user existence
 */
@Serializable
data class MinimalUserDto(
    val id: String,
    val email: String,
    val name: String? = null
)

@Serializable
data class ResendInvitationStateDto(
    val id: String,
    val status: String,
    val invitation_token: String? = null,
    val invitation_expires_at: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

/**
 * DTO representing a linked user with their link info
 */
@Serializable
data class LinkedUserDto(
    @SerialName("link_id")
    val linkId: String,
    @SerialName("user_id")
    val userId: String? = null,  // Nullable for pending invitations
    @SerialName("user_name")
    val userName: String? = null,
    @SerialName("user_email")
    val userEmail: String,
    @SerialName("user_phone")
    val userPhone: String? = null,
    val personalSubscriptionType: String? = null, // Individual subscription type from users.subscription_type
    val department: String? = null,
    @SerialName("link_status")
    val linkStatus: String?,
    @SerialName("invited_at")
    val invitedAt: String?,
    @SerialName("link_activated_at")
    val linkActivatedAt: String?,
    @SerialName("share_professional_trips")
    val shareProfessionalTrips: Boolean = true,
    @SerialName("share_personal_trips")
    val sharePersonalTrips: Boolean = false,
    @SerialName("share_vehicle_info")
    val shareVehicleInfo: Boolean = true,
    @SerialName("share_expenses")
    val shareExpenses: Boolean = false
) {
    val displayName: String
        get() = userName ?: userEmail

    val status: LinkStatus
        get() = linkStatus?.let {
            try { LinkStatus.valueOf(it.uppercase()) } catch (e: Exception) { LinkStatus.PENDING }
        } ?: LinkStatus.PENDING

    val isActive: Boolean
        get() = status == LinkStatus.ACTIVE

    fun toSharingPreferences(): SharingPreferences = SharingPreferences(
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        shareVehicleInfo = shareVehicleInfo,
        shareExpenses = shareExpenses
    )
}

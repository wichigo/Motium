package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.SharingPreferences
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository for managing linked users (Individual users linked to Pro accounts)
 * Data is stored in the company_links table, with user details from users table.
 */
class LinkedAccountRepository private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        @Volatile
        private var instance: LinkedAccountRepository? = null

        fun getInstance(context: Context): LinkedAccountRepository {
            return instance ?: synchronized(this) {
                instance ?: LinkedAccountRepository(context.applicationContext).also { instance = it }
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
                .select(Columns.raw("*, users(id, name, email, phone_number)")) {
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
                            .select(Columns.raw("*, users(id, name, email, phone_number)")) {
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
                .select(Columns.raw("*, users(id, name, email, phone_number)")) {
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
                .select(Columns.raw("*, users(id, name, email, phone_number)")) {
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
     * Creates user if not exists, then creates company_link
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
                // Update user info if provided
                supabaseClient.from("users")
                    .update({
                        set("name", fullName)
                        if (phone != null) set("phone_number", phone)
                    }) {
                        filter {
                            eq("id", userId)
                        }
                    }
                MotiumApplication.logger.i("Updated existing user $email", "LinkedAccountRepo")
            } else {
                // User doesn't exist - create a placeholder user
                val userInsert = mapOf(
                    "email" to email,
                    "name" to fullName,
                    "phone_number" to (phone ?: ""),
                    "role" to "INDIVIDUAL",
                    "subscription_type" to "FREE"
                )
                supabaseClient.from("users").insert(userInsert)

                // Get the newly created user ID
                val newUser = supabaseClient.from("users")
                    .select() {
                        filter {
                            eq("email", email)
                        }
                    }
                    .decodeSingle<MinimalUserDto>()
                userId = newUser.id
                MotiumApplication.logger.i("Created placeholder user $email", "LinkedAccountRepo")
            }

            // Create company_link entry
            val linkInsert = CompanyLinkInsertDto(
                user_id = userId,
                linked_pro_account_id = proAccountId,
                company_name = companyName,
                department = department,
                status = "PENDING",
                invitation_token = invitationToken,
                linked_at = now
            )

            supabaseClient.from("company_links").insert(linkInsert)

            MotiumApplication.logger.i("Invitation created for $email in company_links", "LinkedAccountRepo")
            Result.success(invitationToken)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inviting user with details: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
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
     * Unlink user from Pro account (user-initiated)
     */
    suspend fun unlinkFromPro(linkId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = java.time.Instant.now().toString()

            supabaseClient.from("company_links")
                .update({
                    set("status", "INACTIVE")
                    set("unlinked_at", now)
                }) {
                    filter {
                        eq("id", linkId)
                    }
                }

            MotiumApplication.logger.i("Link $linkId unlinked from Pro", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error unlinking from Pro: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Unlink by user ID
     */
    suspend fun unlinkFromProByUserId(userId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = java.time.Instant.now().toString()

            supabaseClient.from("company_links")
                .update({
                    set("status", "INACTIVE")
                    set("unlinked_at", now)
                }) {
                    filter {
                        eq("user_id", userId)
                        eq("linked_pro_account_id", proAccountId)
                    }
                }

            MotiumApplication.logger.i("User $userId unlinked from Pro $proAccountId", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error unlinking user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }
}

// ==================== DTOs ====================

/**
 * DTO for company_links with embedded user data from foreign key join
 */
@Serializable
data class CompanyLinkWithUserDto(
    val id: String,
    val user_id: String,
    val linked_pro_account_id: String,
    val company_name: String,
    val department: String? = null,
    val status: String,
    val share_professional_trips: Boolean = true,
    val share_personal_trips: Boolean = false,
    val share_personal_info: Boolean = true,
    val share_expenses: Boolean = false,
    val invitation_token: String? = null,
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
        userEmail = users?.email ?: "",
        userPhone = users?.phone_number,
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
    val phone_number: String? = null
)

/**
 * DTO for inserting a new company link
 */
@Serializable
data class CompanyLinkInsertDto(
    val user_id: String,
    val linked_pro_account_id: String,
    val company_name: String,
    val department: String? = null,
    val status: String = "PENDING",
    val share_professional_trips: Boolean = true,
    val share_personal_trips: Boolean = false,
    val share_personal_info: Boolean = true,
    val share_expenses: Boolean = false,
    val invitation_token: String? = null,
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

/**
 * DTO representing a linked user with their link info
 */
@Serializable
data class LinkedUserDto(
    @SerialName("link_id")
    val linkId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("user_name")
    val userName: String?,
    @SerialName("user_email")
    val userEmail: String,
    @SerialName("user_phone")
    val userPhone: String? = null,
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

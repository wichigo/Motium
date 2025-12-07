package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.SharingPreferences
import com.application.motium.domain.model.User
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository for managing linked users (Individual users linked to Pro accounts)
 * Data is stored directly on the users table (no separate linked_accounts table)
 */
class LinkedAccountRepository private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context
) {
    private val supabaseClient = SupabaseClient.client

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
     * Queries users table directly with linked_pro_account_id filter
     */
    suspend fun getLinkedUsers(proAccountId: String): Result<List<LinkedUserDto>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("users")
                .select() {
                    filter {
                        eq("linked_pro_account_id", proAccountId)
                    }
                }
                .decodeList<UserDto>()
                .map { it.toLinkedUserDto() }

            MotiumApplication.logger.i("Found ${response.size} linked users for Pro account", "LinkedAccountRepo")
            Result.success(response)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked users: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get a linked user by ID
     */
    suspend fun getLinkedUserById(userId: String): Result<LinkedUserDto> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("users")
                .select() {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingle<UserDto>()

            Result.success(response.toLinkedUserDto())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Invite a user by email - updates the user's link fields
     */
    suspend fun inviteUser(proAccountId: String, email: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            // Generate invitation token
            val invitationToken = java.util.UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            // Update user with invitation
            supabaseClient.from("users")
                .update({
                    set("linked_pro_account_id", proAccountId)
                    set("link_status", "pending")
                    set("invitation_token", invitationToken)
                    set("invited_at", now)
                }) {
                    filter {
                        eq("email", email)
                    }
                }

            MotiumApplication.logger.i("Invitation sent to $email", "LinkedAccountRepo")
            Result.success(invitationToken)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inviting user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Revoke access for a linked user
     */
    suspend fun revokeUser(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("users")
                .update({
                    set("link_status", "revoked")
                }) {
                    filter {
                        eq("id", userId)
                    }
                }

            MotiumApplication.logger.i("User $userId access revoked", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error revoking user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Accept an invitation - updates the user's link status
     */
    suspend fun acceptInvitation(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val now = java.time.Instant.now().toString()

            supabaseClient.from("users")
                .update({
                    set("link_status", "active")
                    set("invitation_token", null as String?)
                    set("link_activated_at", now)
                }) {
                    filter {
                        eq("invitation_token", token)
                        eq("link_status", "pending")
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
     * Update sharing preferences for the current user
     */
    suspend fun updateSharingPreferences(
        userId: String,
        preferences: SharingPreferences
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("users")
                .update({
                    set("share_professional_trips", preferences.shareProfessionalTrips)
                    set("share_personal_trips", preferences.sharePersonalTrips)
                    set("share_vehicle_info", preferences.shareVehicleInfo)
                    set("share_expenses", preferences.shareExpenses)
                }) {
                    filter {
                        eq("id", userId)
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
    suspend fun unlinkFromPro(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("users")
                .update({
                    set("linked_pro_account_id", null as String?)
                    set("link_status", null as String?)
                    set("invitation_token", null as String?)
                    set("invited_at", null as String?)
                    set("link_activated_at", null as String?)
                }) {
                    filter {
                        eq("id", userId)
                    }
                }

            MotiumApplication.logger.i("User $userId unlinked from Pro", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error unlinking user: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }
}

/**
 * DTO returned by the get_linked_users RPC function
 */
@Serializable
data class LinkedUserDto(
    @SerialName("user_id")
    val userId: String,
    @SerialName("user_name")
    val userName: String?,
    @SerialName("user_email")
    val userEmail: String,
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

/**
 * Minimal DTO for users table queries
 */
@Serializable
data class UserDto(
    val id: String,
    val name: String?,
    val email: String,
    @SerialName("linked_pro_account_id")
    val linkedProAccountId: String? = null,
    @SerialName("link_status")
    val linkStatus: String? = null,
    @SerialName("invited_at")
    val invitedAt: String? = null,
    @SerialName("link_activated_at")
    val linkActivatedAt: String? = null,
    @SerialName("share_professional_trips")
    val shareProfessionalTrips: Boolean = true,
    @SerialName("share_personal_trips")
    val sharePersonalTrips: Boolean = false,
    @SerialName("share_vehicle_info")
    val shareVehicleInfo: Boolean = true,
    @SerialName("share_expenses")
    val shareExpenses: Boolean = false
) {
    fun toLinkedUserDto(): LinkedUserDto = LinkedUserDto(
        userId = id,
        userName = name,
        userEmail = email,
        linkStatus = linkStatus,
        invitedAt = invitedAt,
        linkActivatedAt = linkActivatedAt,
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        shareVehicleInfo = shareVehicleInfo,
        shareExpenses = shareExpenses
    )
}

package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.LinkedAccount
import com.application.motium.domain.model.LinkedAccountStatus
import com.application.motium.domain.model.SharingPreferences
import com.application.motium.domain.model.Trip
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Repository for managing linked accounts in Supabase
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
     * Get all linked accounts for a Pro account
     */
    suspend fun getLinkedAccounts(proAccountId: String): Result<List<LinkedAccount>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("linked_accounts")
                .select()
                .decodeList<LinkedAccountDto>()

            val accounts = response
                .filter { it.proAccountId == proAccountId }
                .map { it.toDomain() }

            Result.success(accounts)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked accounts: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get a linked account by ID
     */
    suspend fun getLinkedAccountById(accountId: String): Result<LinkedAccount> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("linked_accounts")
                .select() {
                    filter {
                        eq("id", accountId)
                    }
                }
                .decodeSingle<LinkedAccountDto>()

            Result.success(response.toDomain())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting linked account: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Invite a new account by email
     */
    suspend fun inviteAccount(proAccountId: String, email: String): Result<LinkedAccount> = withContext(Dispatchers.IO) {
        try {
            val invitationToken = UUID.randomUUID().toString()
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val expiresAt = now.plus(7.days) // 7 days to accept

            val dto = LinkedAccountDto(
                id = UUID.randomUUID().toString(),
                proAccountId = proAccountId,
                userId = null,
                userEmail = email,
                userName = null,
                status = "pending",
                sharingPreferences = SharingPreferencesDto(),
                invitationToken = invitationToken,
                invitationExpiresAt = expiresAt.toString(),
                invitedEmail = email,
                createdAt = now.toString(),
                updatedAt = now.toString()
            )

            supabaseClient.from("linked_accounts")
                .insert(dto)

            MotiumApplication.logger.i("Invitation sent to $email", "LinkedAccountRepo")
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inviting account: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Revoke access for a linked account
     */
    suspend fun revokeAccount(accountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("linked_accounts")
                .update({
                    set("status", "revoked")
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", accountId)
                    }
                }

            MotiumApplication.logger.i("Account $accountId revoked", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error revoking account: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get shared trips from a linked account
     */
    suspend fun getSharedTrips(linkedAccountId: String): Result<List<Trip>> = withContext(Dispatchers.IO) {
        try {
            // Get the linked account to check sharing preferences
            val accountResult = getLinkedAccountById(linkedAccountId)
            val account = accountResult.getOrNull() ?: return@withContext Result.failure(
                Exception("Linked account not found")
            )

            if (account.userId == null) {
                return@withContext Result.success(emptyList())
            }

            // Get trips based on sharing preferences
            val prefs = account.sharingPreferences
            val tripTypes = mutableListOf<String>()
            if (prefs.shareProfessionalTrips) tripTypes.add("PROFESSIONAL")
            if (prefs.sharePersonalTrips) tripTypes.add("PERSONAL")

            if (tripTypes.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            // For now, return empty list - actual implementation would query trips table
            // This would require a proper trips query with user filtering
            Result.success(emptyList())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting shared trips: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Accept an invitation (called by the invited user)
     */
    suspend fun acceptInvitation(invitationToken: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("linked_accounts")
                .update({
                    set("user_id", userId)
                    set("status", "active")
                    set("invitation_token", null as String?)
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("invitation_token", invitationToken)
                    }
                }

            MotiumApplication.logger.i("Invitation accepted by user $userId", "LinkedAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error accepting invitation: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Update sharing preferences (called by the linked user)
     */
    suspend fun updateSharingPreferences(
        accountId: String,
        preferences: SharingPreferences
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val prefsDto = SharingPreferencesDto(
                shareProfessionalTrips = preferences.shareProfessionalTrips,
                sharePersonalTrips = preferences.sharePersonalTrips,
                shareVehicleInfo = preferences.shareVehicleInfo,
                shareExpenses = preferences.shareExpenses
            )

            supabaseClient.from("linked_accounts")
                .update({
                    set("sharing_preferences", prefsDto)
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", accountId)
                    }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating sharing preferences: ${e.message}", "LinkedAccountRepo", e)
            Result.failure(e)
        }
    }
}

/**
 * DTO for linked_accounts table
 */
@Serializable
data class LinkedAccountDto(
    val id: String,
    @SerialName("pro_account_id")
    val proAccountId: String,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("user_email")
    val userEmail: String,
    @SerialName("user_name")
    val userName: String? = null,
    val status: String = "pending",
    @SerialName("sharing_preferences")
    val sharingPreferences: SharingPreferencesDto = SharingPreferencesDto(),
    @SerialName("invitation_token")
    val invitationToken: String? = null,
    @SerialName("invitation_expires_at")
    val invitationExpiresAt: String? = null,
    @SerialName("invited_email")
    val invitedEmail: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
) {
    fun toDomain(): LinkedAccount {
        return LinkedAccount(
            id = id,
            proAccountId = proAccountId,
            userId = userId,
            userEmail = userEmail,
            userName = userName,
            status = when (status) {
                "active" -> LinkedAccountStatus.ACTIVE
                "revoked" -> LinkedAccountStatus.REVOKED
                else -> LinkedAccountStatus.PENDING
            },
            sharingPreferences = sharingPreferences.toDomain(),
            invitationToken = invitationToken,
            invitationExpiresAt = invitationExpiresAt?.let { parseInstant(it) },
            invitedEmail = invitedEmail,
            createdAt = parseInstant(createdAt),
            updatedAt = parseInstant(updatedAt)
        )
    }
}

@Serializable
data class SharingPreferencesDto(
    @SerialName("share_professional_trips")
    val shareProfessionalTrips: Boolean = true,
    @SerialName("share_personal_trips")
    val sharePersonalTrips: Boolean = false,
    @SerialName("share_vehicle_info")
    val shareVehicleInfo: Boolean = true,
    @SerialName("share_expenses")
    val shareExpenses: Boolean = false
) {
    fun toDomain(): SharingPreferences {
        return SharingPreferences(
            shareProfessionalTrips = shareProfessionalTrips,
            sharePersonalTrips = sharePersonalTrips,
            shareVehicleInfo = shareVehicleInfo,
            shareExpenses = shareExpenses
        )
    }
}

private fun parseInstant(value: String): Instant {
    return try {
        Instant.parse(value)
    } catch (e: Exception) {
        Instant.fromEpochMilliseconds(System.currentTimeMillis())
    }
}

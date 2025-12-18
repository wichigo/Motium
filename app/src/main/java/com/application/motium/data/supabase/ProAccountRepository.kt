package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.LegalForm
import com.application.motium.domain.model.ProAccount
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository for managing Pro accounts (ENTERPRISE users)
 */
class ProAccountRepository private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        @Volatile
        private var instance: ProAccountRepository? = null

        fun getInstance(context: Context): ProAccountRepository {
            return instance ?: synchronized(this) {
                instance ?: ProAccountRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get Pro account for the current user
     */
    suspend fun getProAccount(userId: String): Result<ProAccountDto?> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("pro_accounts")
                .select() {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeSingleOrNull<ProAccountDto>()

            MotiumApplication.logger.i("Pro account loaded for user $userId", "ProAccountRepo")
            Result.success(response)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "ProAccountRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("pro_accounts")
                            .select() {
                                filter {
                                    eq("user_id", userId)
                                }
                            }
                            .decodeSingleOrNull<ProAccountDto>()
                        MotiumApplication.logger.i("Pro account loaded after token refresh", "ProAccountRepo")
                        Result.success(response)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "ProAccountRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new Pro account
     */
    suspend fun createProAccount(
        userId: String,
        companyName: String,
        siret: String? = null,
        vatNumber: String? = null,
        legalForm: String? = null,
        billingAddress: String? = null,
        billingEmail: String? = null
    ): Result<ProAccountDto> = withContext(Dispatchers.IO) {
        try {
            val newAccount = ProAccountInsertDto(
                userId = userId,
                companyName = companyName,
                siret = siret,
                vatNumber = vatNumber,
                legalForm = legalForm,
                billingAddress = billingAddress,
                billingEmail = billingEmail
            )

            val response = supabaseClient.from("pro_accounts")
                .insert(newAccount) {
                    select()
                }
                .decodeSingle<ProAccountDto>()

            MotiumApplication.logger.i("Pro account created for user $userId", "ProAccountRepo")
            Result.success(response)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error creating pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new Pro account with trial period
     */
    suspend fun createProAccountWithTrial(
        userId: String,
        companyName: String,
        trialStartedAt: String,
        trialEndsAt: String,
        siret: String? = null,
        vatNumber: String? = null,
        legalForm: String? = null,
        billingAddress: String? = null,
        billingEmail: String? = null
    ): Result<ProAccountDto> = withContext(Dispatchers.IO) {
        try {
            val newAccount = ProAccountInsertDto(
                userId = userId,
                companyName = companyName,
                siret = siret,
                vatNumber = vatNumber,
                legalForm = legalForm,
                billingAddress = billingAddress,
                billingEmail = billingEmail,
                trialStartedAt = trialStartedAt,
                trialEndsAt = trialEndsAt
            )

            val response = supabaseClient.from("pro_accounts")
                .insert(newAccount) {
                    select()
                }
                .decodeSingle<ProAccountDto>()

            MotiumApplication.logger.i("Pro account with trial created for user $userId (trial ends: $trialEndsAt)", "ProAccountRepo")
            Result.success(response)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error creating pro account with trial: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Update Pro account
     */
    suspend fun updateProAccount(
        proAccountId: String,
        companyName: String? = null,
        siret: String? = null,
        vatNumber: String? = null,
        legalForm: String? = null,
        billingAddress: String? = null,
        billingEmail: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("pro_accounts")
                .update({
                    companyName?.let { set("company_name", it) }
                    siret?.let { set("siret", it) }
                    vatNumber?.let { set("vat_number", it) }
                    legalForm?.let { set("legal_form", it) }
                    billingAddress?.let { set("billing_address", it) }
                    billingEmail?.let { set("billing_email", it) }
                }) {
                    filter {
                        eq("id", proAccountId)
                    }
                }

            MotiumApplication.logger.i("Pro account $proAccountId updated", "ProAccountRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Update or create Pro account (upsert-like behavior)
     */
    suspend fun saveProAccount(
        userId: String,
        companyName: String,
        siret: String? = null,
        vatNumber: String? = null,
        legalForm: String? = null,
        billingAddress: String? = null,
        billingEmail: String? = null
    ): Result<ProAccountDto> = withContext(Dispatchers.IO) {
        try {
            // Check if account exists
            val existing = getProAccount(userId).getOrNull()

            if (existing != null) {
                // Update existing
                updateProAccount(
                    proAccountId = existing.id,
                    companyName = companyName,
                    siret = siret,
                    vatNumber = vatNumber,
                    legalForm = legalForm,
                    billingAddress = billingAddress,
                    billingEmail = billingEmail
                )
                // Fetch updated
                val updated = getProAccount(userId).getOrNull()
                    ?: return@withContext Result.failure(Exception("Failed to fetch updated account"))
                Result.success(updated)
            } else {
                // Create new
                createProAccount(
                    userId = userId,
                    companyName = companyName,
                    siret = siret,
                    vatNumber = vatNumber,
                    legalForm = legalForm,
                    billingAddress = billingAddress,
                    billingEmail = billingEmail
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        }
    }
}

/**
 * DTO for Pro account data
 */
@Serializable
data class ProAccountDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("company_name")
    val companyName: String,
    val siret: String? = null,
    @SerialName("vat_number")
    val vatNumber: String? = null,
    @SerialName("legal_form")
    val legalForm: String? = null,
    @SerialName("billing_address")
    val billingAddress: String? = null,
    @SerialName("billing_email")
    val billingEmail: String? = null,
    @SerialName("stripe_customer_id")
    val stripeCustomerId: String? = null,
    @SerialName("trial_started_at")
    val trialStartedAt: String? = null,
    @SerialName("trial_ends_at")
    val trialEndsAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    fun toDomainModel(): ProAccount = ProAccount(
        id = id,
        userId = userId,
        companyName = companyName,
        siret = siret,
        vatNumber = vatNumber,
        legalForm = legalForm?.let {
            try { LegalForm.valueOf(it) } catch (e: Exception) { LegalForm.OTHER }
        },
        billingAddress = billingAddress,
        billingEmail = billingEmail,
        stripeCustomerId = stripeCustomerId
    )
}

/**
 * DTO for inserting a new Pro account
 */
@Serializable
data class ProAccountInsertDto(
    @SerialName("user_id")
    val userId: String,
    @SerialName("company_name")
    val companyName: String,
    val siret: String? = null,
    @SerialName("vat_number")
    val vatNumber: String? = null,
    @SerialName("legal_form")
    val legalForm: String? = null,
    @SerialName("billing_address")
    val billingAddress: String? = null,
    @SerialName("billing_email")
    val billingEmail: String? = null,
    @SerialName("trial_started_at")
    val trialStartedAt: String? = null,
    @SerialName("trial_ends_at")
    val trialEndsAt: String? = null
)

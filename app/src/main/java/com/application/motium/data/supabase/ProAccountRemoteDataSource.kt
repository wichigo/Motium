package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.LegalForm
import com.application.motium.domain.model.ProAccount
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * REMOTE DATA SOURCE - Direct API access to Supabase.
 * For offline-first reads, use OfflineFirstProAccountRepository instead.
 * This class is kept for write operations requiring server validation.
 */
class ProAccountRemoteDataSource private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var instance: ProAccountRemoteDataSource? = null

        fun getInstance(context: Context): ProAccountRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: ProAccountRemoteDataSource(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get Pro account for the current user
     *
     * IMPORTANT: Checks authentication before making the request to avoid 401 errors.
     * RLS policy requires user_id = get_user_id_from_auth(), which needs a valid JWT.
     */
    suspend fun getProAccount(userId: String): Result<ProAccountDto?> = withContext(Dispatchers.IO) {
        try {
            // Check if user is authenticated before making the request
            var currentUser = supabaseClient.auth.currentUserOrNull()
            if (currentUser == null) {
                // Try to recover session before failing (common during cold start)
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    currentUser = supabaseClient.auth.currentUserOrNull()
                }
            }
            if (currentUser == null) {
                MotiumApplication.logger.w("No authenticated user, skipping pro_accounts fetch", "ProAccountRepo")
                return@withContext Result.failure(Exception("User not authenticated"))
            }

            val response = supabaseClient.from("pro_accounts")
                .select() {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeSingleOrNull<ProAccountDto>()

            if (response != null) {
                MotiumApplication.logger.i("Pro account found for user $userId", "ProAccountRepo")
            } else {
                MotiumApplication.logger.i("No pro account for user $userId", "ProAccountRepo")
            }
            Result.success(response)
        } catch (e: PostgrestRestException) {
            // JWT expired or 401 - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("401") == true) {
                MotiumApplication.logger.w("JWT expired or 401, refreshing token and retrying...", "ProAccountRepo")
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
                        if (response != null) {
                            MotiumApplication.logger.i("Pro account found after token refresh", "ProAccountRepo")
                        } else {
                            MotiumApplication.logger.i("No pro account after token refresh", "ProAccountRepo")
                        }
                        Result.success(response)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "ProAccountRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            // Don't log cancellation as error - it's expected during navigation/scope cleanup
            MotiumApplication.logger.d("Pro account fetch cancelled (navigation)", "ProAccountRepo")
            throw e // Re-throw to properly propagate cancellation
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting pro account: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get Pro account by its ID (works for linked users too)
     *
     * IMPORTANT: Checks authentication before making the request to avoid 401 errors.
     * RLS policy requires user_id = get_user_id_from_auth(), which needs a valid JWT.
     */
    suspend fun getProAccountById(proAccountId: String): Result<ProAccountDto?> = withContext(Dispatchers.IO) {
        try {
            // Check if user is authenticated before making the request
            var currentUser = supabaseClient.auth.currentUserOrNull()
            if (currentUser == null) {
                // Try to recover session before failing (common during cold start)
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    currentUser = supabaseClient.auth.currentUserOrNull()
                }
            }
            if (currentUser == null) {
                MotiumApplication.logger.w("No authenticated user, skipping pro_accounts fetch by ID", "ProAccountRepo")
                return@withContext Result.failure(Exception("User not authenticated"))
            }

            val response = supabaseClient.from("pro_accounts")
                .select() {
                    filter {
                        eq("id", proAccountId)
                    }
                }
                .decodeSingleOrNull<ProAccountDto>()

            if (response != null) {
                MotiumApplication.logger.i("Pro account found by ID $proAccountId", "ProAccountRepo")
            } else {
                MotiumApplication.logger.i("No pro account for ID $proAccountId", "ProAccountRepo")
            }
            Result.success(response)
        } catch (e: PostgrestRestException) {
            // JWT expired or 401 - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("401") == true) {
                MotiumApplication.logger.w("JWT expired or 401, refreshing token and retrying...", "ProAccountRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("pro_accounts")
                            .select() {
                                filter {
                                    eq("id", proAccountId)
                                }
                            }
                            .decodeSingleOrNull<ProAccountDto>()
                        if (response != null) {
                            MotiumApplication.logger.i("Pro account found by ID after token refresh", "ProAccountRepo")
                        } else {
                            MotiumApplication.logger.i("No pro account for ID after token refresh", "ProAccountRepo")
                        }
                        Result.success(response)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "ProAccountRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting pro account by ID: ${e.message}", "ProAccountRepo", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            // Don't log cancellation as error - it's expected during navigation/scope cleanup
            MotiumApplication.logger.d("Pro account fetch by ID cancelled (navigation)", "ProAccountRepo")
            throw e // Re-throw to properly propagate cancellation
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting pro account by ID: ${e.message}", "ProAccountRepo", e)
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
     * Create a new Pro account
     * Note: Trial period is managed in users table, not pro_accounts
     */
    suspend fun createProAccountWithDefaults(
        userId: String,
        companyName: String,
        siret: String? = null,
        vatNumber: String? = null,
        legalForm: String? = null,
        billingAddress: String? = null,
        billingEmail: String? = null,
        billingDay: Int = 5,
        departments: List<String> = emptyList()
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
                billingDay = billingDay,
                departments = Json.encodeToJsonElement(departments)
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
     * Update billing anchor day for a Pro account.
     * This day (1-15) is used as the renewal date for all monthly licenses.
     *
     * @param proAccountId The Pro account ID
     * @param anchorDay The day of the month (1-15) for license renewals
     * @return Result indicating success or failure
     */
    suspend fun updateBillingAnchorDay(
        proAccountId: String,
        anchorDay: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (anchorDay !in 1..15) {
            return@withContext Result.failure(
                IllegalArgumentException("Le jour de renouvellement doit etre entre 1 et 15")
            )
        }

        suspend fun callEdge(token: String): Result<Unit> {
            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/update-billing-anchor"

            val requestBody = UpdateBillingAnchorRequest(
                proAccountId = proAccountId,
                billingAnchorDay = anchorDay
            )
            val jsonBody = json.encodeToString(UpdateBillingAnchorRequest.serializer(), requestBody)

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMessage = try {
                    val parsed = json.parseToJsonElement(responseBody).jsonObject
                    parsed["error"]?.jsonPrimitive?.content ?: "Erreur serveur: ${response.code}"
                } catch (e: Exception) {
                    responseBody.ifBlank { "Erreur serveur: ${response.code}" }
                }
                return Result.failure(Exception(errorMessage))
            }

            val parsed = json.decodeFromString(UpdateBillingAnchorResponse.serializer(), responseBody)
            return if (parsed.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(parsed.error ?: "Erreur lors de la mise a jour"))
            }
        }

        try {
            val accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val initialResult = callEdge(accessToken)
            if (initialResult.isSuccess) {
                MotiumApplication.logger.i("Billing anchor day updated to $anchorDay for pro account $proAccountId", "ProAccountRepo")
                return@withContext initialResult
            }

            val errorMessage = initialResult.exceptionOrNull()?.message ?: ""
            val shouldRefresh = errorMessage.contains("JWT expired", ignoreCase = true) ||
                errorMessage.contains("Invalid token", ignoreCase = true) ||
                errorMessage.contains("Unauthorized", ignoreCase = true)

            if (shouldRefresh) {
                MotiumApplication.logger.w("JWT expired or invalid, refreshing token and retrying...", "ProAccountRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    val refreshedToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
                        ?: return@withContext Result.failure(Exception("Not authenticated"))
                    val retryResult = callEdge(refreshedToken)
                    if (retryResult.isSuccess) {
                        MotiumApplication.logger.i("Billing anchor day updated after token refresh", "ProAccountRepo")
                    }
                    return@withContext retryResult
                }
            }

            Result.failure(initialResult.exceptionOrNull() ?: Exception("Erreur lors de la mise a jour"))
        } catch (e: CancellationException) {
            MotiumApplication.logger.d("Billing anchor day update cancelled (navigation)", "ProAccountRepo")
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating billing anchor day: ${e.message}", "ProAccountRepo", e)
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
private data class UpdateBillingAnchorRequest(
    @SerialName("pro_account_id")
    val proAccountId: String,
    @SerialName("billing_anchor_day")
    val billingAnchorDay: Int
)

@Serializable
private data class UpdateBillingAnchorResponse(
    val success: Boolean,
    val error: String? = null
)

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
    @SerialName("billing_day")
    val billingDay: Int = 5,
    @SerialName("billing_anchor_day")
    val billingAnchorDay: Int? = null,  // Jour de renouvellement unifié pour licences (1-28)
    val departments: JsonElement? = null,  // JSONB from Supabase can be string or array
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    /**
     * Parse departments from JsonElement (can be array or stringified array from Supabase)
     */
    private fun parseDepartments(): List<String> {
        if (departments == null) return emptyList()
        return try {
            // Try to parse as JSON array directly
            departments.jsonArray.map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            try {
                // If it's a string, parse the string as JSON array
                val jsonString = departments.jsonPrimitive.content
                Json.decodeFromString<List<String>>(jsonString)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

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
        billingDay = billingDay,
        billingAnchorDay = billingAnchorDay,
        departments = parseDepartments(),
        createdAt = createdAt?.let { kotlinx.datetime.Instant.parse(it) },
        updatedAt = updatedAt?.let { kotlinx.datetime.Instant.parse(it) }
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
    @SerialName("billing_day")
    val billingDay: Int = 5,
    val departments: JsonElement? = null  // JSONB - use Json.encodeToJsonElement(listOf(...))
)

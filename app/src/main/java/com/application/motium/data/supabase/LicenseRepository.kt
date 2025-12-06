package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Repository for managing licenses in Supabase
 */
class LicenseRepository private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context
) {
    private val supabaseClient = SupabaseClient.client

    companion object {
        @Volatile
        private var instance: LicenseRepository? = null

        fun getInstance(context: Context): LicenseRepository {
            return instance ?: synchronized(this) {
                instance ?: LicenseRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get all licenses for a Pro account
     */
    suspend fun getLicenses(proAccountId: String): Result<List<License>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("licenses")
                .select()
                .decodeList<LicenseDto>()

            val licenses = response
                .filter { it.proAccountId == proAccountId }
                .map { it.toDomain() }

            Result.success(licenses)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting licenses: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Create new licenses
     */
    suspend fun createLicenses(proAccountId: String, quantity: Int): Result<List<License>> = withContext(Dispatchers.IO) {
        try {
            val licenses = (1..quantity).map {
                LicenseDto(
                    id = UUID.randomUUID().toString(),
                    proAccountId = proAccountId,
                    linkedAccountId = null, // Will be assigned when linked
                    priceMonthlyHt = License.LICENSE_PRICE_HT,
                    vatRate = License.VAT_RATE,
                    status = "pending",
                    startDate = null,
                    endDate = null,
                    stripeSubscriptionId = null,
                    stripeSubscriptionItemId = null,
                    stripePriceId = "price_PLACEHOLDER", // TODO: Replace with real Stripe price ID
                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString(),
                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
                )
            }

            supabaseClient.from("licenses")
                .insert(licenses)

            MotiumApplication.logger.i("Created $quantity licenses for Pro account $proAccountId", "LicenseRepo")
            Result.success(licenses.map { it.toDomain() })
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error creating licenses: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Cancel a license
     */
    suspend fun cancelLicense(licenseId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("licenses")
                .update({
                    set("status", "cancelled")
                    set("end_date", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", licenseId)
                    }
                }

            MotiumApplication.logger.i("License $licenseId cancelled", "LicenseRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error cancelling license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Activate a license (after Stripe payment confirmation)
     */
    suspend fun activateLicense(
        licenseId: String,
        stripeSubscriptionId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("licenses")
                .update({
                    set("status", "active")
                    set("start_date", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                    set("stripe_subscription_id", stripeSubscriptionId)
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", licenseId)
                    }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error activating license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Assign a license to a linked account
     */
    suspend fun assignLicense(
        licenseId: String,
        linkedAccountId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", linkedAccountId)
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", licenseId)
                    }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error assigning license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }
}

/**
 * DTO for licenses table
 */
@Serializable
data class LicenseDto(
    val id: String,
    @SerialName("pro_account_id")
    val proAccountId: String,
    @SerialName("linked_account_id")
    val linkedAccountId: String? = null,
    @SerialName("price_monthly_ht")
    val priceMonthlyHt: Double = License.LICENSE_PRICE_HT,
    @SerialName("vat_rate")
    val vatRate: Double = License.VAT_RATE,
    val status: String = "pending",
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("end_date")
    val endDate: String? = null,
    @SerialName("stripe_subscription_id")
    val stripeSubscriptionId: String? = null,
    @SerialName("stripe_subscription_item_id")
    val stripeSubscriptionItemId: String? = null,
    @SerialName("stripe_price_id")
    val stripePriceId: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
) {
    fun toDomain(): License {
        return License(
            id = id,
            proAccountId = proAccountId,
            linkedAccountId = linkedAccountId ?: "",
            priceMonthlyHT = priceMonthlyHt,
            vatRate = vatRate,
            status = when (status) {
                "active" -> LicenseStatus.ACTIVE
                "expired" -> LicenseStatus.EXPIRED
                "cancelled" -> LicenseStatus.CANCELLED
                else -> LicenseStatus.PENDING
            },
            startDate = startDate?.let { parseInstant(it) },
            endDate = endDate?.let { parseInstant(it) },
            stripeSubscriptionId = stripeSubscriptionId,
            stripeSubscriptionItemId = stripeSubscriptionItemId,
            stripePriceId = stripePriceId,
            createdAt = parseInstant(createdAt),
            updatedAt = parseInstant(updatedAt)
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

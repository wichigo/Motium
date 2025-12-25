package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * REMOTE DATA SOURCE - Direct API access to Supabase.
 * For offline-first reads, use OfflineFirstLicenseRepository instead.
 * This class is kept for write operations requiring server validation.
 */
class LicenseRemoteDataSource private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        @Volatile
        private var instance: LicenseRemoteDataSource? = null

        fun getInstance(context: Context): LicenseRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: LicenseRemoteDataSource(context.applicationContext).also { instance = it }
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
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("licenses")
                            .select()
                            .decodeList<LicenseDto>()
                        val licenses = response
                            .filter { it.proAccountId == proAccountId }
                            .map { it.toDomain() }
                        MotiumApplication.logger.i("Licenses loaded after token refresh", "LicenseRepo")
                        Result.success(licenses)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting licenses: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
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
     * Assign a license to a linked account (sets linkedAt for cooldown tracking)
     */
    suspend fun assignLicense(
        licenseId: String,
        linkedAccountId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", linkedAccountId)
                    set("linked_at", now.toString())
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                    }
                }

            // Update user's subscription_type to LICENSED for offline-first checks
            updateUserSubscriptionType(linkedAccountId, "LICENSED")

            MotiumApplication.logger.i("License $licenseId assigned to account $linkedAccountId", "LicenseRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error assigning license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get a single license by ID
     */
    suspend fun getLicenseById(licenseId: String): Result<License?> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("licenses")
                .select {
                    filter {
                        eq("id", licenseId)
                    }
                }
                .decodeList<LicenseDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Request to unlink a license (starts 30-day notice period)
     * The license remains assigned until the notice period expires.
     */
    suspend fun requestUnlink(licenseId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Fetch the license to validate
            val license = getLicenseById(licenseId).getOrNull()
                ?: return@withContext Result.failure(Exception("Licence introuvable"))

            if (!license.canRequestUnlink()) {
                return@withContext Result.failure(
                    Exception("Impossible de délier cette licence: elle n'est pas assignée ou est déjà en cours de déliaison")
                )
            }

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val effectiveDate = Instant.fromEpochMilliseconds(
                now.toEpochMilliseconds() + (License.UNLINK_NOTICE_DAYS * 24L * 60 * 60 * 1000)
            )

            supabaseClient.from("licenses")
                .update({
                    set("unlink_requested_at", now.toString())
                    set("unlink_effective_at", effectiveDate.toString())
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            MotiumApplication.logger.i(
                "Unlink requested for license $licenseId, effective at $effectiveDate",
                "LicenseRepo"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error requesting unlink: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Cancel an unlink request before it takes effect
     */
    suspend fun cancelUnlinkRequest(licenseId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val license = getLicenseById(licenseId).getOrNull()
                ?: return@withContext Result.failure(Exception("Licence introuvable"))

            if (!license.canCancelUnlinkRequest()) {
                return@withContext Result.failure(
                    Exception("Aucune demande de déliaison en cours à annuler")
                )
            }

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            supabaseClient.from("licenses")
                .update({
                    set("unlink_requested_at", null as String?)
                    set("unlink_effective_at", null as String?)
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            MotiumApplication.logger.i("Unlink request cancelled for license $licenseId", "LicenseRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error cancelling unlink request: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get available licenses in the pool (not assigned to any account)
     */
    suspend fun getAvailableLicenses(proAccountId: String): Result<List<License>> = withContext(Dispatchers.IO) {
        try {
            // Fetch all active licenses for this pro account, then filter in Kotlin
            val response = supabaseClient.from("licenses")
                .select {
                    filter {
                        eq("pro_account_id", proAccountId)
                        eq("status", "active")
                    }
                }
                .decodeList<LicenseDto>()

            // Filter to only include licenses with null linked_account_id
            val availableLicenses = response
                .filter { it.linkedAccountId == null }
                .map { it.toDomain() }

            Result.success(availableLicenses)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting available licenses: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Assign a license from the pool to a linked account
     */
    suspend fun assignLicenseToAccount(
        licenseId: String,
        proAccountId: String,
        linkedAccountId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

            // Verify license is available
            val license = getLicenseById(licenseId).getOrNull()
                ?: return@withContext Result.failure(Exception("Licence introuvable"))

            if (license.isAssigned) {
                return@withContext Result.failure(Exception("Cette licence est déjà assignée"))
            }

            if (license.status != LicenseStatus.ACTIVE) {
                return@withContext Result.failure(Exception("Cette licence n'est pas active"))
            }

            // Note: We already verified the license is available above (license.isAssigned check)
            // If there's a race condition, the worst case is a double assignment which
            // can be handled by database constraints
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", linkedAccountId)
                    set("linked_at", now.toString())
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // Update user's subscription_type to LICENSED for offline-first checks
            updateUserSubscriptionType(linkedAccountId, "LICENSED")

            MotiumApplication.logger.i(
                "License $licenseId assigned to account $linkedAccountId",
                "LicenseRepo"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error assigning license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get license for a specific linked account
     */
    suspend fun getLicenseForAccount(
        proAccountId: String,
        linkedAccountId: String
    ): Result<License?> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("licenses")
                .select {
                    filter {
                        eq("pro_account_id", proAccountId)
                        eq("linked_account_id", linkedAccountId)
                    }
                }
                .decodeList<LicenseDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting license for account: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Process all expired unlink requests (called by background worker)
     * Returns the number of licenses that were unlinked
     */
    suspend fun processExpiredUnlinks(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

            // Fetch all licenses and filter in Kotlin for expired unlinks
            val allLicenses = supabaseClient.from("licenses")
                .select()
                .decodeList<LicenseDto>()

            // Find licenses with expired unlink dates:
            // - unlink_effective_at is not null
            // - unlink_effective_at <= now
            // - linked_account_id is not null
            val expiredLicenses = allLicenses.filter { license ->
                license.unlinkEffectiveAt != null &&
                license.linkedAccountId != null &&
                try {
                    val effectiveAt = Instant.parse(license.unlinkEffectiveAt)
                    effectiveAt <= now
                } catch (e: Exception) {
                    false
                }
            }

            if (expiredLicenses.isEmpty()) {
                return@withContext Result.success(0)
            }

            // Unlink each expired license
            var count = 0
            for (license in expiredLicenses) {
                try {
                    val unlinkedUserId = license.linkedAccountId

                    supabaseClient.from("licenses")
                        .update({
                            set("linked_account_id", null as String?)
                            set("linked_at", null as String?)
                            set("unlink_requested_at", null as String?)
                            set("unlink_effective_at", null as String?)
                            set("updated_at", now.toString())
                        }) {
                            filter {
                                eq("id", license.id)
                            }
                        }

                    // Update user's subscription_type back to FREE
                    if (unlinkedUserId != null) {
                        updateUserSubscriptionType(unlinkedUserId, "FREE")
                    }

                    count++
                } catch (e: Exception) {
                    MotiumApplication.logger.e(
                        "Failed to process expired unlink for license ${license.id}: ${e.message}",
                        "LicenseRepo",
                        e
                    )
                }
            }

            MotiumApplication.logger.i("Processed $count expired unlinks", "LicenseRepo")
            Result.success(count)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error processing expired unlinks: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Get count of available licenses in the pool
     */
    suspend fun getAvailableLicensesCount(proAccountId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val licenses = getAvailableLicenses(proAccountId).getOrThrow()
            Result.success(licenses.size)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting available licenses count: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Assign a license from the pool to the Pro account owner themselves.
     * This allows Pro owners to use one of their own licenses for their personal subscription.
     *
     * @param proAccountId The Pro account ID
     * @param ownerUserId The user ID of the Pro account owner
     * @return Result with the assigned license
     */
    suspend fun assignLicenseToOwner(
        proAccountId: String,
        ownerUserId: String
    ): Result<License> = withContext(Dispatchers.IO) {
        try {
            // Check if owner already has a license assigned
            val existingLicense = getLicenseForAccount(proAccountId, ownerUserId).getOrNull()
            if (existingLicense != null) {
                return@withContext Result.failure(Exception("Vous avez déjà une licence assignée"))
            }

            // Get available licenses
            val availableLicenses = getAvailableLicenses(proAccountId).getOrThrow()
            if (availableLicenses.isEmpty()) {
                return@withContext Result.failure(Exception("Aucune licence disponible dans votre pool"))
            }

            // Pick the first available license
            val licenseToAssign = availableLicenses.first()
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", ownerUserId)
                    set("linked_at", now.toString())
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseToAssign.id)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // Update user's subscription_type to LICENSED for offline-first checks
            updateUserSubscriptionType(ownerUserId, "LICENSED")

            MotiumApplication.logger.i(
                "License ${licenseToAssign.id} assigned to owner $ownerUserId",
                "LicenseRepo"
            )

            // Return the updated license
            val updatedLicense = licenseToAssign.copy(
                linkedAccountId = ownerUserId,
                linkedAt = now
            )
            Result.success(updatedLicense)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error assigning license to owner: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Assign a specific license (by ID) to the Pro account owner.
     * Used when owner wants to choose between lifetime vs monthly license.
     *
     * @param proAccountId The Pro account ID
     * @param ownerUserId The user ID of the Pro account owner
     * @param licenseId The specific license ID to assign
     * @return Result with the assigned license
     */
    suspend fun assignSpecificLicenseToOwner(
        proAccountId: String,
        ownerUserId: String,
        licenseId: String
    ): Result<License> = withContext(Dispatchers.IO) {
        try {
            // Check if owner already has a license assigned
            val existingLicense = getLicenseForAccount(proAccountId, ownerUserId).getOrNull()
            if (existingLicense != null) {
                return@withContext Result.failure(Exception("Vous avez deja une licence assignee"))
            }

            // Get the specific license and verify it's available
            val availableLicenses = getAvailableLicenses(proAccountId).getOrThrow()
            val licenseToAssign = availableLicenses.find { it.id == licenseId }
                ?: return@withContext Result.failure(Exception("Cette licence n'est pas disponible"))

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", ownerUserId)
                    set("linked_at", now.toString())
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // Update user's subscription_type to LICENSED for offline-first checks
            updateUserSubscriptionType(ownerUserId, "LICENSED")

            MotiumApplication.logger.i(
                "License $licenseId (${if (licenseToAssign.isLifetime) "lifetime" else "monthly"}) assigned to owner $ownerUserId",
                "LicenseRepo"
            )

            // Return the updated license
            val updatedLicense = licenseToAssign.copy(
                linkedAccountId = ownerUserId,
                linkedAt = now
            )
            Result.success(updatedLicense)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error assigning specific license to owner: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Check if the Pro account owner has a license assigned to themselves
     */
    suspend fun isOwnerLicensed(
        proAccountId: String,
        ownerUserId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val license = getLicenseForAccount(proAccountId, ownerUserId).getOrNull()
            Result.success(license != null && license.status == LicenseStatus.ACTIVE)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking owner license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Helper function to update user's subscription_type in the users table.
     * This is critical for offline-first license checking.
     */
    private suspend fun updateUserSubscriptionType(userId: String, subscriptionType: String) {
        try {
            supabaseClient.from("users")
                .update({
                    set("subscription_type", subscriptionType)
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", userId)
                    }
                }
            MotiumApplication.logger.i(
                "Updated user $userId subscription_type to $subscriptionType",
                "LicenseRepo"
            )
        } catch (e: Exception) {
            // Log but don't fail the license operation - trigger will handle as backup
            MotiumApplication.logger.w(
                "Failed to update subscription_type for user $userId: ${e.message}",
                "LicenseRepo"
            )
        }
    }

    /**
     * Create lifetime licenses (one-time payment, no expiration)
     */
    suspend fun createLifetimeLicenses(proAccountId: String, quantity: Int): Result<List<License>> = withContext(Dispatchers.IO) {
        try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val licenses = (1..quantity).map {
                LicenseDto(
                    id = UUID.randomUUID().toString(),
                    proAccountId = proAccountId,
                    linkedAccountId = null,
                    linkedAt = null,
                    isLifetime = true,
                    priceMonthlyHt = 0.0, // Pas de coût mensuel
                    vatRate = License.VAT_RATE,
                    status = "pending",
                    startDate = null,
                    endDate = null, // Lifetime = pas de date de fin
                    stripeSubscriptionId = null,
                    stripeSubscriptionItemId = null,
                    stripePriceId = "price_pro_license_lifetime_placeholder", // TODO: Replace with real Stripe price ID
                    createdAt = now.toString(),
                    updatedAt = now.toString()
                )
            }

            supabaseClient.from("licenses")
                .insert(licenses)

            MotiumApplication.logger.i("Created $quantity lifetime licenses for Pro account $proAccountId", "LicenseRepo")
            Result.success(licenses.map { it.toDomain() })
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error creating lifetime licenses: ${e.message}", "LicenseRepo", e)
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
    @SerialName("linked_at")
    val linkedAt: String? = null,
    @SerialName("is_lifetime")
    val isLifetime: Boolean = false,
    @SerialName("price_monthly_ht")
    val priceMonthlyHt: Double = License.LICENSE_PRICE_HT,
    @SerialName("vat_rate")
    val vatRate: Double = License.VAT_RATE,
    val status: String = "pending",
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("end_date")
    val endDate: String? = null,
    // Unlink notice period fields
    @SerialName("unlink_requested_at")
    val unlinkRequestedAt: String? = null,
    @SerialName("unlink_effective_at")
    val unlinkEffectiveAt: String? = null,
    // Billing start date
    @SerialName("billing_starts_at")
    val billingStartsAt: String? = null,
    // Stripe
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
            linkedAccountId = linkedAccountId,
            linkedAt = linkedAt?.let { parseInstant(it) },
            isLifetime = isLifetime,
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
            unlinkRequestedAt = unlinkRequestedAt?.let { parseInstant(it) },
            unlinkEffectiveAt = unlinkEffectiveAt?.let { parseInstant(it) },
            billingStartsAt = billingStartsAt?.let { parseInstant(it) },
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

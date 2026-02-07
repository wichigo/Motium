package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Résultat d'une tentative d'attribution de licence.
 * Conformément aux spécifications:
 * - LIFETIME: bloqué (déjà abonnement à vie)
 * - LICENSED: bloqué (déjà une licence)
 * - PREMIUM: nécessite annulation de l'abonnement existant
 * - TRIAL/EXPIRED: attribution directe OK
 */
sealed class LicenseAssignmentResult {
    data class Success(val license: License) : LicenseAssignmentResult()
    data class AlreadyLifetime(val message: String = "Ce collaborateur a déjà un abonnement à vie") : LicenseAssignmentResult()
    data class AlreadyLicensed(val message: String = "Ce collaborateur a déjà une licence active") : LicenseAssignmentResult()
    data class NeedsCancelExisting(
        val message: String = "Ce collaborateur a un abonnement Premium actif qui doit être résilié",
        val collaboratorId: String,
        val licenseId: String
    ) : LicenseAssignmentResult()
    data class LicenseNotAvailable(val message: String = "La licence n'est pas disponible") : LicenseAssignmentResult()
    data class CollaboratorNotFound(val message: String = "Collaborateur introuvable") : LicenseAssignmentResult()
    data class Error(val message: String) : LicenseAssignmentResult()
}

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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonParser = Json { ignoreUnknownKeys = true }

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
     *
     * IMPORTANT: Uses server-side filter (pro_account_id) to comply with RLS policies.
     * Without this filter, the query would return 401 Unauthorized because RLS requires
     * the pro_account_id to belong to the authenticated user.
     */
    suspend fun getLicenses(proAccountId: String): Result<List<License>> = withContext(Dispatchers.IO) {
        try {
            // Check if user is authenticated before making the request
            var currentUser = supabaseClient.auth.currentUserOrNull()
            if (currentUser == null) {
                // Session may be expired locally - try to refresh before failing
                MotiumApplication.logger.w("No local session found, attempting token refresh...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    currentUser = supabaseClient.auth.currentUserOrNull()
                }
                if (currentUser == null) {
                    // Refresh failed or no refresh token available - user needs to re-login
                    MotiumApplication.logger.e("Session recovery failed - user must re-authenticate", "LicenseRepo")
                    return@withContext Result.failure(Exception("Session expired - please re-login"))
                }
                MotiumApplication.logger.i("Session restored via token refresh", "LicenseRepo")
            }

            // Use server-side filter to comply with RLS policies
            val response = supabaseClient.from("licenses")
                .select() {
                    filter {
                        eq("pro_account_id", proAccountId)
                    }
                }
                .decodeList<LicenseDto>()

            val licenses = response.map { it.toDomain() }
            MotiumApplication.logger.i("Loaded ${licenses.size} licenses for pro account $proAccountId", "LicenseRepo")
            Result.success(licenses)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("401") == true) {
                MotiumApplication.logger.w("JWT expired or 401, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("licenses")
                            .select() {
                                filter {
                                    eq("pro_account_id", proAccountId)
                                }
                            }
                            .decodeList<LicenseDto>()
                        val licenses = response.map { it.toDomain() }
                        MotiumApplication.logger.i("Licenses loaded after token refresh: ${licenses.size}", "LicenseRepo")
                        Result.success(licenses)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting licenses: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            // Re-throw CancellationException to respect coroutine cancellation
            // This is expected behavior during navigation - not an error
            throw e
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
                    status = "available",  // Conformément aux specs: licences dans le pool = available
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
     * Cancel a license (résiliation).
     *
     * IMPORTANT: This function:
     * 1. Sets status to "canceled" (no renewal)
     * 2. KEEPS linked_account_id - user retains access until end_date
     * 3. KEEPS end_date - license remains valid until paid period ends
     *
     * The actual unlinking (linked_account_id = NULL) and user subscription_type update
     * happens automatically when end_date is reached (via background job/trigger).
     */
    suspend fun cancelLicense(licenseId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val license = getLicenseById(licenseId).getOrNull()
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            supabaseClient.from("licenses")
                .update({
                    set("status", "canceled")  // Conformément aux specs: "canceled" (American English)
                    // DO NOT change linked_account_id - user keeps access until end_date
                    // DO NOT change end_date - license remains valid until paid period ends
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                    }
                }

            MotiumApplication.logger.i(
                "License $licenseId canceled (user keeps access until end_date)",
                "LicenseRepo"
            )
            license?.proAccountId?.let { proId ->
                syncProLicenseQuantityBestEffort(proId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error canceling license: ${e.message}", "LicenseRepo", e)
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
            // Get license to retrieve end_date and is_lifetime info
            val license = getLicenseById(licenseId).getOrNull()
                ?: return@withContext Result.failure(Exception("Licence introuvable"))

            // If collaborator was previously unlinked, reactivate company_link before assignment.
            ensureActiveCompanyLinkForAssignment(license.proAccountId, linkedAccountId).getOrElse { error ->
                return@withContext Result.failure(error)
            }

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", linkedAccountId)
                    set("linked_at", now.toString())
                    set("status", "active")  // FIX: Change status from 'available' to 'active'
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                    }
                }

            // Update user's subscription_type and subscription_expires_at for offline-first checks
            // For lifetime licenses, subscription_expires_at is null
            val expiresAt = if (license?.isLifetime == true) null else license?.endDate?.toString()
            updateUserSubscriptionType(linkedAccountId, "LICENSED", expiresAt)

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
            // Check if user is authenticated before making the request
            var currentUser = supabaseClient.auth.currentUserOrNull()
            if (currentUser == null) {
                // Session may be expired locally - try to refresh before failing
                MotiumApplication.logger.w("No local session found, attempting token refresh...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    currentUser = supabaseClient.auth.currentUserOrNull()
                }
                if (currentUser == null) {
                    // Refresh failed or no refresh token available - user needs to re-login
                    MotiumApplication.logger.e("Session recovery failed - user must re-authenticate", "LicenseRepo")
                    return@withContext Result.failure(Exception("Session expired - please re-login"))
                }
                MotiumApplication.logger.i("Session restored via token refresh", "LicenseRepo")
            }

            val response = supabaseClient.from("licenses")
                .select {
                    filter {
                        eq("id", licenseId)
                    }
                }
                .decodeList<LicenseDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w("JWT expired in getLicenseById, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("licenses")
                            .select {
                                filter {
                                    eq("id", licenseId)
                                }
                            }
                            .decodeList<LicenseDto>()
                        MotiumApplication.logger.i("License by ID loaded after token refresh", "LicenseRepo")
                        Result.success(response.firstOrNull()?.toDomain())
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Unassign a license from an account (immediate removal).
     * This is different from requestUnlink which respects the renewal date for monthly licenses.
     * Used when the Pro account owner wants to immediately free up a license.
     *
     * @param licenseId The license ID to unassign
     * @param proAccountId The Pro account ID (for RLS verification)
     * @return Result with success or failure
     */
    suspend fun unassignLicense(licenseId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Fetch the license to get the previous linked user
            val license = getLicenseById(licenseId).getOrNull()
            val previousLinkedUserId = license?.linkedAccountId

            if (license == null) {
                return@withContext Result.failure(Exception("Licence introuvable"))
            }

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", null as String?)
                    set("linked_at", null as String?)
                    set("status", "available")  // Return to pool
                    set("unlink_requested_at", null as String?)
                    set("unlink_effective_at", null as String?)
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // ARBRE 5 FIX: Update the previous user's subscription_type - BUT check for other active licenses first!
            if (previousLinkedUserId != null) {
                val otherActiveLicenses = checkOtherActiveLicenses(previousLinkedUserId, licenseId)
                if (otherActiveLicenses == 0) {
                    updateUserSubscriptionType(previousLinkedUserId, "EXPIRED")
                    MotiumApplication.logger.i(
                        "User $previousLinkedUserId set to EXPIRED (no other active licenses)",
                        "LicenseRepo"
                    )
                } else {
                    MotiumApplication.logger.i(
                        "User $previousLinkedUserId keeps LICENSED ($otherActiveLicenses other active licenses)",
                        "LicenseRepo"
                    )
                }
            }

            MotiumApplication.logger.i("License $licenseId unassigned from account", "LicenseRepo")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error unassigning license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Request to unlink/cancel a license.
     * - Lifetime: déliaison à la prochaine date de renouvellement
     * - Mensuelle: effective à la date de renouvellement (endDate)
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

            // Effective date:
            // - Lifetime: prochaine date de renouvellement (anti-abus)
            // - Mensuelle: date de renouvellement (endDate), ou immédiat si endDate est null/passé
            val effectiveDate = when {
                license.isLifetime -> resolveLifetimeUnlinkEffectiveDate(
                    license = license,
                    proAccountId = proAccountId,
                    now = now
                )
                license.endDate != null && license.endDate.toEpochMilliseconds() > now.toEpochMilliseconds() -> license.endDate  // Mensuelle = endDate
                else -> now  // Pas de endDate ou dans le passé = immédiat
            }

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

            val effectiveType = if (license.isLifetime) "prochaine date de renouvellement (lifetime)" else "date de renouvellement"
            MotiumApplication.logger.i(
                "Unlink requested for license $licenseId ($effectiveType), effective at $effectiveDate",
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

    private suspend fun resolveLifetimeUnlinkEffectiveDate(
        license: License,
        proAccountId: String,
        now: Instant
    ): Instant {
        val defaultAnchorDay = 5
        try {
            val proRows = supabaseClient.from("pro_accounts")
                .select(columns = Columns.list("billing_anchor_day")) {
                    filter {
                        eq("id", proAccountId)
                    }
                }
                .decodeList<ProAccountRenewalConfigDto>()

            val billingAnchorDay = proRows.firstOrNull()?.billingAnchorDay ?: defaultAnchorDay
            return computeNextMonthBillingAnchorDate(now, billingAnchorDay)
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "Failed to resolve renewal anchor for lifetime unlink ${license.id}: ${e.message}",
                "LicenseRepo"
            )
        }

        return computeNextMonthBillingAnchorDate(now, defaultAnchorDay)
    }

    private fun computeNextMonthBillingAnchorDate(now: Instant, billingAnchorDay: Int): Instant {
        val safeAnchorDay = billingAnchorDay.coerceIn(1, 28)
        val nowInstant = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
        val nowUtc = java.time.ZonedDateTime.ofInstant(nowInstant, java.time.ZoneOffset.UTC)
        val currentMonth = java.time.YearMonth.from(nowUtc)

        val candidate = currentMonth
            .plusMonths(1)
            .atDay(safeAnchorDay)
            .atStartOfDay(java.time.ZoneOffset.UTC)

        return Instant.fromEpochMilliseconds(candidate.toInstant().toEpochMilli())
    }

    /**
     * Get available licenses in the pool (not assigned to any account)
     *
     * Fetches licenses with status 'available' (in pool, unassigned) or 'active' without linked_account_id.
     * Per specs:
     * - status='available' means the license is in the pool waiting to be assigned
     * - status='active' with linked_account_id=null is a transitional state (also available)
     */
    suspend fun getAvailableLicenses(proAccountId: String): Result<List<License>> = withContext(Dispatchers.IO) {
        try {
            // Check if user is authenticated before making the request
            var currentUser = supabaseClient.auth.currentUserOrNull()
            if (currentUser == null) {
                // Session may be expired locally - try to refresh before failing
                MotiumApplication.logger.w("No local session found, attempting token refresh...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    currentUser = supabaseClient.auth.currentUserOrNull()
                }
                if (currentUser == null) {
                    // Refresh failed or no refresh token available - user needs to re-login
                    MotiumApplication.logger.e("Session recovery failed - user must re-authenticate", "LicenseRepo")
                    return@withContext Result.failure(Exception("Session expired - please re-login"))
                }
                MotiumApplication.logger.i("Session restored via token refresh", "LicenseRepo")
            }

            // Fetch all licenses for this pro account, then filter in Kotlin
            // We need both 'available' and 'active' statuses since:
            // - 'available' = in pool, unassigned (primary state for available licenses)
            // - 'active' without linked_account_id = transitional/legacy state
            val response = supabaseClient.from("licenses")
                .select {
                    filter {
                        eq("pro_account_id", proAccountId)
                    }
                }
                .decodeList<LicenseDto>()

            // Filter to include:
            // 1. Licenses with status='available' (always available in pool)
            // 2. Licenses with status='active' AND no linked_account_id (transitional state)
            val availableLicenses = response
                .filter { license ->
                    license.linkedAccountId == null &&
                    (license.status == "available" || license.status == "active")
                }
                .map { it.toDomain() }

            MotiumApplication.logger.d(
                "getAvailableLicenses: Found ${availableLicenses.size} available licenses for proAccountId=$proAccountId",
                "LicenseRepo"
            )

            Result.success(availableLicenses)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w("JWT expired in getAvailableLicenses, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("licenses")
                            .select {
                                filter {
                                    eq("pro_account_id", proAccountId)
                                }
                            }
                            .decodeList<LicenseDto>()
                        val availableLicenses = response
                            .filter { license ->
                                license.linkedAccountId == null &&
                                (license.status == "available" || license.status == "active")
                            }
                            .map { it.toDomain() }
                        MotiumApplication.logger.i("Available licenses loaded after token refresh: ${availableLicenses.size}", "LicenseRepo")
                        Result.success(availableLicenses)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting available licenses: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            // Re-throw CancellationException to respect coroutine cancellation
            throw e
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

            // Accept licenses with status 'available' (in pool) or 'active' (transitional state)
            if (license.status != LicenseStatus.ACTIVE && license.status != LicenseStatus.AVAILABLE) {
                return@withContext Result.failure(Exception("Cette licence n'est pas disponible"))
            }

            ensureActiveCompanyLinkForAssignment(proAccountId, linkedAccountId).getOrElse { error ->
                return@withContext Result.failure(error)
            }

            // Note: We already verified the license is available above (license.isAssigned check)
            // If there's a race condition, the worst case is a double assignment which
            // can be handled by database constraints
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", linkedAccountId)
                    set("linked_at", now.toString())
                    set("status", "active")  // FIX: Change status from 'available' to 'active'
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // Update user's subscription_type and subscription_expires_at for offline-first checks
            // For lifetime licenses, subscription_expires_at is null
            val expiresAt = if (license.isLifetime) null else license.endDate?.toString()
            updateUserSubscriptionType(linkedAccountId, "LICENSED", expiresAt)

            MotiumApplication.logger.i(
                "License $licenseId assigned to account $linkedAccountId",
                "LicenseRepo"
            )
            Result.success(Unit)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w("JWT expired in assignLicenseToAccount, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        // Re-verify license state after refresh
                        val license = getLicenseById(licenseId).getOrNull()
                            ?: return@withContext Result.failure(Exception("Licence introuvable"))
                        if (license.isAssigned) {
                            return@withContext Result.failure(Exception("Cette licence est déjà assignée"))
                        }
                        if (license.status != LicenseStatus.ACTIVE && license.status != LicenseStatus.AVAILABLE) {
                            return@withContext Result.failure(Exception("Cette licence n'est pas disponible"))
                        }

                        ensureActiveCompanyLinkForAssignment(proAccountId, linkedAccountId).getOrElse { error ->
                            return@withContext Result.failure(error)
                        }

                        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                        supabaseClient.from("licenses")
                            .update({
                                set("linked_account_id", linkedAccountId)
                                set("linked_at", now.toString())
                                set("status", "active")
                                set("updated_at", now.toString())
                            }) {
                                filter {
                                    eq("id", licenseId)
                                    eq("pro_account_id", proAccountId)
                                }
                            }

                        val expiresAt = if (license.isLifetime) null else license.endDate?.toString()
                        updateUserSubscriptionType(linkedAccountId, "LICENSED", expiresAt)
                        MotiumApplication.logger.i("License $licenseId assigned after token refresh", "LicenseRepo")
                        Result.success(Unit)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error assigning license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
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
            // Check if user is authenticated before making the request
            var currentUser = supabaseClient.auth.currentUserOrNull()
            if (currentUser == null) {
                // Session may be expired locally - try to refresh before failing
                MotiumApplication.logger.w("No local session found, attempting token refresh...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    currentUser = supabaseClient.auth.currentUserOrNull()
                }
                if (currentUser == null) {
                    // Refresh failed or no refresh token available - user needs to re-login
                    MotiumApplication.logger.e("Session recovery failed - user must re-authenticate", "LicenseRepo")
                    return@withContext Result.failure(Exception("Session expired - please re-login"))
                }
                MotiumApplication.logger.i("Session restored via token refresh", "LicenseRepo")
            }

            val response = supabaseClient.from("licenses")
                .select {
                    filter {
                        eq("pro_account_id", proAccountId)
                        eq("linked_account_id", linkedAccountId)
                    }
                }
                .decodeList<LicenseDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w("JWT expired in getLicenseForAccount, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val response = supabaseClient.from("licenses")
                            .select {
                                filter {
                                    eq("pro_account_id", proAccountId)
                                    eq("linked_account_id", linkedAccountId)
                                }
                            }
                            .decodeList<LicenseDto>()
                        MotiumApplication.logger.i("License for account loaded after token refresh", "LicenseRepo")
                        Result.success(response.firstOrNull()?.toDomain())
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error getting license for account: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting license for account: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Process all expired license unlinks (called by background worker).
     * This delegates to the Edge Function so business logic is shared with web.
     */
    suspend fun processExpiredUnlinks(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
            if (accessToken == null) {
                tokenRefreshCoordinator.refreshIfNeeded(force = true)
                accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
            }

            if (accessToken == null) {
                return@withContext Result.failure(Exception("Session expired - please re-login"))
            }

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/process-expired-license-unlinks")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("process-expired-license-unlinks failed (HTTP ${response.code}): $responseBody")
                )
            }

            val parsed = runCatching {
                jsonParser.decodeFromString(ProcessExpiredLicenseUnlinksResponse.serializer(), responseBody)
            }.getOrElse {
                return@withContext Result.failure(
                    Exception("Invalid process-expired-license-unlinks response: $responseBody")
                )
            }

            if (!parsed.success) {
                return@withContext Result.failure(
                    Exception(parsed.error ?: "process-expired-license-unlinks returned failure")
                )
            }

            MotiumApplication.logger.i(
                "Processed ${parsed.processedCount} expired unlinks via edge function " +
                    "(deleted=${parsed.deletedCount}, returned=${parsed.returnedToPoolCount})",
                "LicenseRepo"
            )
            Result.success(parsed.processedCount)
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
                    set("status", "active")  // FIX: Change status from 'available' to 'active'
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseToAssign.id)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // Update user's subscription_type and subscription_expires_at for offline-first checks
            // For lifetime licenses, subscription_expires_at is null
            val expiresAt = if (licenseToAssign.isLifetime) null else licenseToAssign.endDate?.toString()
            updateUserSubscriptionType(ownerUserId, "LICENSED", expiresAt)

            MotiumApplication.logger.i(
                "License ${licenseToAssign.id} assigned to owner $ownerUserId",
                "LicenseRepo"
            )

            // Return the updated license
            val updatedLicense = licenseToAssign.copy(
                linkedAccountId = ownerUserId,
                linkedAt = now,
                status = LicenseStatus.ACTIVE  // FIX: Also update the returned object
            )
            Result.success(updatedLicense)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w("JWT expired in assignLicenseToOwner, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        // Re-fetch to verify state after refresh
                        val existingLicense = getLicenseForAccount(proAccountId, ownerUserId).getOrNull()
                        if (existingLicense != null) {
                            return@withContext Result.failure(Exception("Vous avez déjà une licence assignée"))
                        }
                        val availableLicenses = getAvailableLicenses(proAccountId).getOrThrow()
                        if (availableLicenses.isEmpty()) {
                            return@withContext Result.failure(Exception("Aucune licence disponible dans votre pool"))
                        }
                        val licenseToAssign = availableLicenses.first()
                        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

                        supabaseClient.from("licenses")
                            .update({
                                set("linked_account_id", ownerUserId)
                                set("linked_at", now.toString())
                                set("status", "active")
                                set("updated_at", now.toString())
                            }) {
                                filter {
                                    eq("id", licenseToAssign.id)
                                    eq("pro_account_id", proAccountId)
                                }
                            }

                        val expiresAt = if (licenseToAssign.isLifetime) null else licenseToAssign.endDate?.toString()
                        updateUserSubscriptionType(ownerUserId, "LICENSED", expiresAt)
                        MotiumApplication.logger.i("License assigned after token refresh: ${licenseToAssign.id} to owner $ownerUserId", "LicenseRepo")
                        val updatedLicense = licenseToAssign.copy(
                            linkedAccountId = ownerUserId,
                            linkedAt = now,
                            status = LicenseStatus.ACTIVE
                        )
                        Result.success(updatedLicense)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error assigning license to owner: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
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
                    set("status", "active")  // FIX: Change status from 'available' to 'active'
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // Update user's subscription_type and subscription_expires_at for offline-first checks
            // For lifetime licenses, subscription_expires_at is null
            val expiresAt = if (licenseToAssign.isLifetime) null else licenseToAssign.endDate?.toString()
            updateUserSubscriptionType(ownerUserId, "LICENSED", expiresAt)

            MotiumApplication.logger.i(
                "License $licenseId (${if (licenseToAssign.isLifetime) "lifetime" else "monthly"}) assigned to owner $ownerUserId",
                "LicenseRepo"
            )

            // Return the updated license
            val updatedLicense = licenseToAssign.copy(
                linkedAccountId = ownerUserId,
                linkedAt = now,
                status = LicenseStatus.ACTIVE  // FIX: Also update the returned object
            )
            Result.success(updatedLicense)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w("JWT expired in assignSpecificLicenseToOwner, refreshing token and retrying...", "LicenseRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        // Re-fetch to verify state after refresh
                        val existingLicense = getLicenseForAccount(proAccountId, ownerUserId).getOrNull()
                        if (existingLicense != null) {
                            return@withContext Result.failure(Exception("Vous avez deja une licence assignee"))
                        }
                        val availableLicenses = getAvailableLicenses(proAccountId).getOrThrow()
                        val licenseToAssign = availableLicenses.find { it.id == licenseId }
                            ?: return@withContext Result.failure(Exception("Cette licence n'est pas disponible"))

                        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                        supabaseClient.from("licenses")
                            .update({
                                set("linked_account_id", ownerUserId)
                                set("linked_at", now.toString())
                                set("status", "active")
                                set("updated_at", now.toString())
                            }) {
                                filter {
                                    eq("id", licenseId)
                                    eq("pro_account_id", proAccountId)
                                }
                            }

                        val expiresAt = if (licenseToAssign.isLifetime) null else licenseToAssign.endDate?.toString()
                        updateUserSubscriptionType(ownerUserId, "LICENSED", expiresAt)
                        MotiumApplication.logger.i("License assigned after token refresh: $licenseId to owner $ownerUserId", "LicenseRepo")
                        val updatedLicense = licenseToAssign.copy(
                            linkedAccountId = ownerUserId,
                            linkedAt = now,
                            status = LicenseStatus.ACTIVE
                        )
                        Result.success(updatedLicense)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "LicenseRepo", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error assigning specific license to owner: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
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
     * ARBRE 5: Check if user has other active licenses (excluding the one being unlinked)
     * @param userId The user ID to check
     * @param excludeLicenseId The license ID being unlinked (to exclude from count)
     * @return Number of other active licenses
     */
    private suspend fun checkOtherActiveLicenses(userId: String, excludeLicenseId: String): Int {
        return try {
            val licenses = supabaseClient.from("licenses")
                .select() {
                    filter {
                        eq("linked_account_id", userId)
                        eq("status", "active")
                        neq("id", excludeLicenseId)
                    }
                }
                .decodeList<LicenseDto>()
            licenses.size
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "Failed to check other licenses for user $userId: ${e.message}",
                "LicenseRepo"
            )
            // Fail-safe: assume no other licenses (will set to EXPIRED)
            0
        }
    }

    /**
     * Helper function to update user's subscription_type and subscription_expires_at in the users table.
     * This is critical for offline-first license checking.
     *
     * @param userId The user ID to update
     * @param subscriptionType The new subscription type (LICENSED, EXPIRED, etc.)
     * @param subscriptionExpiresAt The expiration date (null for lifetime licenses or EXPIRED)
     */
    private suspend fun updateUserSubscriptionType(
        userId: String,
        subscriptionType: String,
        subscriptionExpiresAt: String? = null
    ) {
        try {
            supabaseClient.from("users")
                .update({
                    set("subscription_type", subscriptionType)
                    set("subscription_expires_at", subscriptionExpiresAt)
                    set("updated_at", Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString())
                }) {
                    filter {
                        eq("id", userId)
                    }
                }
            MotiumApplication.logger.i(
                "Updated user $userId subscription_type=$subscriptionType, expires_at=$subscriptionExpiresAt",
                "LicenseRepo"
            )
        } catch (e: Exception) {
            // Log but don't fail the license operation - trigger will handle as backup
            MotiumApplication.logger.w(
                "Failed to update subscription for user $userId: ${e.message}",
                "LicenseRepo"
            )
        }
    }

    /**
     * Get the subscription type of a user.
     * Used to validate before assigning a license.
     */
    private suspend fun getUserSubscriptionType(userId: String): String? {
        return try {
            val response = supabaseClient.from("users")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeList<UserSubscriptionDto>()
            response.firstOrNull()?.subscriptionType
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting user subscription type: ${e.message}", "LicenseRepo", e)
            null
        }
    }

    /**
     * Assign a license to a collaborator with full validation.
     * Conformément aux spécifications:
     * - LIFETIME: bloqué (déjà abonnement à vie)
     * - LICENSED: bloqué (déjà une licence)
     * - PREMIUM: retourne NeedsCancelExisting (l'abonnement doit être résilié)
     * - TRIAL/EXPIRED: attribution directe OK
     *
     * @param licenseId The license ID to assign
     * @param proAccountId The Pro account ID
     * @param collaboratorId The collaborator's user ID
     * @return LicenseAssignmentResult indicating success or the specific error
     */
    suspend fun assignLicenseWithValidation(
        licenseId: String,
        proAccountId: String,
        collaboratorId: String
    ): LicenseAssignmentResult = withContext(Dispatchers.IO) {
        try {
            // 1. Verify license exists and is available
            val license = getLicenseById(licenseId).getOrNull()
                ?: return@withContext LicenseAssignmentResult.LicenseNotAvailable()

            if (license.proAccountId != proAccountId) {
                return@withContext LicenseAssignmentResult.LicenseNotAvailable("Cette licence n'appartient pas à ce compte Pro")
            }

            if (license.isAssigned) {
                return@withContext LicenseAssignmentResult.LicenseNotAvailable("Cette licence est déjà assignée")
            }

            if (license.status != LicenseStatus.ACTIVE && license.status != LicenseStatus.AVAILABLE) {
                return@withContext LicenseAssignmentResult.LicenseNotAvailable("Cette licence n'est pas active")
            }

            // 2. Get collaborator's current subscription type
            val subscriptionType = getUserSubscriptionType(collaboratorId)
                ?: return@withContext LicenseAssignmentResult.CollaboratorNotFound()

            // 3. Validate based on subscription type
            when (subscriptionType.uppercase()) {
                "LIFETIME" -> {
                    MotiumApplication.logger.w(
                        "Cannot assign license to user $collaboratorId: already LIFETIME",
                        "LicenseRepo"
                    )
                    return@withContext LicenseAssignmentResult.AlreadyLifetime()
                }
                "LICENSED" -> {
                    MotiumApplication.logger.w(
                        "Cannot assign license to user $collaboratorId: already LICENSED",
                        "LicenseRepo"
                    )
                    return@withContext LicenseAssignmentResult.AlreadyLicensed()
                }
                "PREMIUM" -> {
                    MotiumApplication.logger.i(
                        "User $collaboratorId has PREMIUM subscription, needs cancellation first",
                        "LicenseRepo"
                    )
                    return@withContext LicenseAssignmentResult.NeedsCancelExisting(
                        collaboratorId = collaboratorId,
                        licenseId = licenseId
                    )
                }
                // TRIAL, EXPIRED -> OK to assign directly
            }

            ensureActiveCompanyLinkForAssignment(proAccountId, collaboratorId).getOrElse { error ->
                return@withContext LicenseAssignmentResult.Error(error.message ?: "Collaborateur non lié au compte Pro")
            }

            // 4. Assign the license
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            supabaseClient.from("licenses")
                .update({
                    set("linked_account_id", collaboratorId)
                    set("linked_at", now.toString())
                    set("status", "active")  // FIX: Change status from 'available' to 'active'
                    set("updated_at", now.toString())
                }) {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            // 5. Update user's subscription_type to LICENSED
            val expiresAt = if (license.isLifetime) null else license.endDate?.toString()
            updateUserSubscriptionType(collaboratorId, "LICENSED", expiresAt)

            MotiumApplication.logger.i(
                "License $licenseId assigned to collaborator $collaboratorId (was $subscriptionType)",
                "LicenseRepo"
            )

            val updatedLicense = license.copy(
                linkedAccountId = collaboratorId,
                linkedAt = now,
                status = LicenseStatus.ACTIVE  // FIX: Also update the returned object
            )
            LicenseAssignmentResult.Success(updatedLicense)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error assigning license with validation: ${e.message}", "LicenseRepo", e)
            LicenseAssignmentResult.Error(e.message ?: "Erreur inconnue")
        }
    }

    /**
     * Ensure collaborator is an ACTIVE member of the pro account before license assignment.
     * If a matching INACTIVE link exists, reactivate it automatically.
     */
    private suspend fun ensureActiveCompanyLinkForAssignment(
        proAccountId: String,
        linkedAccountId: String
    ): Result<Unit> {
        return try {
            // Owner assignment is always allowed by DB trigger, no company_link needed.
            val ownerRows = supabaseClient.from("pro_accounts")
                .select(columns = Columns.list("id")) {
                    filter {
                        eq("id", proAccountId)
                        eq("user_id", linkedAccountId)
                    }
                }
                .decodeList<SimpleIdDto>()

            if (ownerRows.isNotEmpty()) {
                return Result.success(Unit)
            }

            val links = supabaseClient.from("company_links")
                .select(columns = Columns.list("id", "status")) {
                    filter {
                        eq("linked_pro_account_id", proAccountId)
                        eq("user_id", linkedAccountId)
                    }
                }
                .decodeList<CompanyLinkStatusDto>()

            if (links.any { it.status.equals("ACTIVE", ignoreCase = true) }) {
                return Result.success(Unit)
            }

            val inactiveLink = links.firstOrNull { it.status.equals("INACTIVE", ignoreCase = true) }
            if (inactiveLink == null) {
                return Result.failure(Exception("Utilisateur non lié activement à ce compte Pro"))
            }

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
            supabaseClient.from("company_links")
                .update({
                    set("status", "ACTIVE")
                    set("unlinked_at", null as String?)
                    set("linked_activated_at", now)
                    set("updated_at", now)
                }) {
                    filter {
                        eq("id", inactiveLink.id)
                    }
                }

            MotiumApplication.logger.i(
                "Reactivated company_link ${inactiveLink.id} for user $linkedAccountId before license assignment",
                "LicenseRepo"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Failed to ensure ACTIVE company_link for user $linkedAccountId on pro $proAccountId: ${e.message}",
                "LicenseRepo",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Complete license assignment after PREMIUM subscription was canceled.
     * Called when NeedsCancelExisting was returned and user has since canceled their subscription.
     */
    suspend fun finalizeLicenseAssignment(
        licenseId: String,
        proAccountId: String,
        collaboratorId: String
    ): LicenseAssignmentResult = withContext(Dispatchers.IO) {
        try {
            // Verify collaborator is no longer PREMIUM
            val subscriptionType = getUserSubscriptionType(collaboratorId)
                ?: return@withContext LicenseAssignmentResult.CollaboratorNotFound()

            if (subscriptionType.uppercase() == "PREMIUM") {
                return@withContext LicenseAssignmentResult.NeedsCancelExisting(
                    message = "L'abonnement du collaborateur n'a pas encore été résilié",
                    collaboratorId = collaboratorId,
                    licenseId = licenseId
                )
            }

            // Now try to assign
            assignLicenseWithValidation(licenseId, proAccountId, collaboratorId)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error finalizing license assignment: ${e.message}", "LicenseRepo", e)
            LicenseAssignmentResult.Error(e.message ?: "Erreur inconnue")
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
                    status = "available",  // Conformément aux specs: licences dans le pool = available
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

    /**
     * Delete a license permanently.
     * Only unassigned monthly licenses can be deleted.
     *
     * @param licenseId The license ID to delete
     * @param proAccountId The Pro account ID (for verification)
     * @return Result with success or failure
     */
    suspend fun deleteLicense(licenseId: String, proAccountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Fetch the license to validate
            val license = getLicenseById(licenseId).getOrNull()
                ?: return@withContext Result.failure(Exception("Licence introuvable"))

            if (!license.canDelete()) {
                val reason = when {
                    license.isAssigned -> "La licence est assignée à un utilisateur"
                    license.isPendingUnlink -> "La licence est en cours de déliaison"
                    license.isLifetime -> "Les licences à vie ne peuvent pas être supprimées"
                    else -> "Cette licence ne peut pas être supprimée"
                }
                return@withContext Result.failure(Exception(reason))
            }

            supabaseClient.from("licenses")
                .delete {
                    filter {
                        eq("id", licenseId)
                        eq("pro_account_id", proAccountId)
                    }
                }

            MotiumApplication.logger.i("License $licenseId deleted permanently", "LicenseRepo")
            syncProLicenseQuantityBestEffort(proAccountId)
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting license: ${e.message}", "LicenseRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Best-effort sync to align Stripe subscription quantity with billable monthly licenses.
     * This reduces drift windows while webhook reconciliation remains the source of truth.
     */
    private suspend fun syncProLicenseQuantityBestEffort(proAccountId: String) {
        try {
            var accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
            if (accessToken == null) {
                tokenRefreshCoordinator.refreshIfNeeded(force = true)
                accessToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
            }

            if (accessToken == null) {
                MotiumApplication.logger.w(
                    "Quantity sync skipped (no auth session) for pro account $proAccountId",
                    "LicenseRepo"
                )
                return
            }

            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/sync-pro-license-quantity"
            val payload = """{"pro_account_id":"$proAccountId"}"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(payload)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                MotiumApplication.logger.w(
                    "Quantity sync failed for $proAccountId: HTTP ${response.code} - $body",
                    "LicenseRepo"
                )
            } else {
                MotiumApplication.logger.i(
                    "Quantity sync requested for pro account $proAccountId",
                    "LicenseRepo"
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "Quantity sync error for $proAccountId: ${e.message}",
                "LicenseRepo"
            )
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
    val status: String = "available",  // Conformément aux specs: available par défaut
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
            status = LicenseStatus.fromDbValue(status),  // Utilise le mapping avec gestion legacy
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

@Serializable
private data class ProcessExpiredLicenseUnlinksResponse(
    val success: Boolean,
    val error: String? = null,
    @SerialName("processed_count")
    val processedCount: Int = 0,
    @SerialName("deleted_count")
    val deletedCount: Int = 0,
    @SerialName("returned_to_pool_count")
    val returnedToPoolCount: Int = 0
)

@Serializable
private data class ProAccountRenewalConfigDto(
    @SerialName("billing_anchor_day")
    val billingAnchorDay: Int? = null
)

@Serializable
private data class SimpleIdDto(
    val id: String
)

@Serializable
private data class CompanyLinkStatusDto(
    val id: String,
    val status: String
)

private fun parseInstant(value: String): Instant {
    return try {
        Instant.parse(value)
    } catch (e: Exception) {
        Instant.fromEpochMilliseconds(System.currentTimeMillis())
    }
}

/**
 * Minimal DTO for fetching user subscription type.
 * Used by getUserSubscriptionType() to check before license assignment.
 */
@Serializable
private data class UserSubscriptionDto(
    val id: String,
    @SerialName("subscription_type")
    val subscriptionType: String? = null
)

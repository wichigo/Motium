package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.datetime.Clock
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Repository for phone number verification via SMS OTP.
 * Uses Supabase Auth phone verification with Twilio integration.
 *
 * The verification flow:
 * 1. User enters phone number
 * 2. sendOtp() sends SMS via Supabase/Twilio
 * 3. User enters the 6-digit code
 * 4. verifyOtp() validates the code
 * 5. On success, phone is registered in our database
 */
class PhoneVerificationRepository private constructor(private val context: Context) {

    private val client = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        private const val TABLE_NAME = "verified_phone_numbers"

        @Volatile
        private var instance: PhoneVerificationRepository? = null

        fun getInstance(context: Context): PhoneVerificationRepository {
            return instance ?: synchronized(this) {
                instance ?: PhoneVerificationRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Hash a phone number for storage/lookup.
         * We store hashes to prevent exposing phone numbers in database queries.
         */
        fun hashPhoneNumber(phoneNumber: String): String {
            val normalized = normalizePhoneNumber(phoneNumber)
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        /**
         * Normalize phone number to E.164 format.
         * Removes spaces, dashes, parentheses, etc.
         */
        fun normalizePhoneNumber(phoneNumber: String): String {
            // Remove all non-digit characters except the leading +
            val cleaned = phoneNumber.replace(Regex("[^+\\d]"), "")

            // Ensure it starts with +
            return if (cleaned.startsWith("+")) cleaned else "+$cleaned"
        }
    }

    /**
     * Check if a phone number has already been used for registration.
     *
     * @param phoneNumber Phone number to check (any format)
     * @return PhoneEligibility result
     */
    suspend fun checkPhoneEligibility(phoneNumber: String): PhoneEligibility = withContext(Dispatchers.IO) {
        try {
            val phoneHash = hashPhoneNumber(phoneNumber)

            val result = client.postgrest[TABLE_NAME]
                .select(Columns.list("id", "user_id", "blocked")) {
                    filter {
                        eq("phone_hash", phoneHash)
                    }
                }
                .decodeSingleOrNull<VerifiedPhoneDto>()

            when {
                result == null -> PhoneEligibility.Eligible
                result.blocked -> PhoneEligibility.Blocked
                result.userId != null -> PhoneEligibility.AlreadyRegistered(result.userId)
                else -> PhoneEligibility.Eligible
            }
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "PhoneVerificationRepository")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val phoneHash = hashPhoneNumber(phoneNumber)
                        val result = client.postgrest[TABLE_NAME]
                            .select(Columns.list("id", "user_id", "blocked")) {
                                filter {
                                    eq("phone_hash", phoneHash)
                                }
                            }
                            .decodeSingleOrNull<VerifiedPhoneDto>()
                        MotiumApplication.logger.i("Phone eligibility checked after token refresh", "PhoneVerificationRepository")
                        when {
                            result == null -> PhoneEligibility.Eligible
                            result.blocked -> PhoneEligibility.Blocked
                            result.userId != null -> PhoneEligibility.AlreadyRegistered(result.userId)
                            else -> PhoneEligibility.Eligible
                        }
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "PhoneVerificationRepository", retryError)
                        PhoneEligibility.Eligible
                    }
                }
            }
            MotiumApplication.logger.e(
                "Error checking phone eligibility: ${e.message}",
                "PhoneVerificationRepository",
                e
            )
            // On error, allow but log it
            PhoneEligibility.Eligible
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error checking phone eligibility: ${e.message}",
                "PhoneVerificationRepository",
                e
            )
            // On error, allow but log it
            PhoneEligibility.Eligible
        }
    }

    /**
     * Send OTP code to phone number.
     * Uses Supabase Auth phone verification (Twilio).
     *
     * @param phoneNumber Phone number in E.164 format (e.g., +33612345678)
     * @return Result indicating success or failure
     */
    suspend fun sendOtp(phoneNumber: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizePhoneNumber(phoneNumber)

            MotiumApplication.logger.i(
                "Sending OTP to $normalized",
                "PhoneVerificationRepository"
            )

            // Use Supabase Auth to send OTP
            client.auth.signInWith(OTP) {
                phone = normalized
            }

            MotiumApplication.logger.i(
                "OTP sent successfully to $normalized",
                "PhoneVerificationRepository"
            )

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error sending OTP: ${e.message}",
                "PhoneVerificationRepository",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Verify OTP code entered by user.
     *
     * @param phoneNumber Phone number that received the OTP
     * @param code 6-digit OTP code
     * @return Result<Boolean> - true if verification successful
     */
    suspend fun verifyOtp(phoneNumber: String, code: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizePhoneNumber(phoneNumber)

            MotiumApplication.logger.i(
                "Verifying OTP for $normalized",
                "PhoneVerificationRepository"
            )

            // Verify the OTP with Supabase Auth
            client.auth.verifyPhoneOtp(
                phone = normalized,
                token = code,
                type = io.github.jan.supabase.auth.OtpType.Phone.SMS
            )

            MotiumApplication.logger.i(
                "OTP verified successfully for $normalized",
                "PhoneVerificationRepository"
            )

            Result.success(true)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error verifying OTP: ${e.message}",
                "PhoneVerificationRepository",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Register a verified phone number for a user.
     * Called after successful OTP verification.
     *
     * @param phoneNumber The verified phone number
     * @param userId The user ID to associate with this phone
     * @return Result with success or failure
     */
    suspend fun registerVerifiedPhone(
        phoneNumber: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizePhoneNumber(phoneNumber)
            val phoneHash = hashPhoneNumber(phoneNumber)

            MotiumApplication.logger.i(
                "Registering verified phone for user $userId",
                "PhoneVerificationRepository"
            )

            // Upsert to handle race conditions
            val now = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
            client.postgrest[TABLE_NAME]
                .upsert(
                    VerifiedPhoneInsertDto(
                        phoneNumber = normalized,
                        phoneHash = phoneHash,
                        userId = userId,
                        verifiedAt = now
                    )
                ) {
                    onConflict = "phone_number"
                }

            MotiumApplication.logger.i(
                "Phone registered successfully for user $userId",
                "PhoneVerificationRepository"
            )

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error registering verified phone: ${e.message}",
                "PhoneVerificationRepository",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Resend OTP code (same as sendOtp but semantically different for UI).
     */
    suspend fun resendOtp(phoneNumber: String): Result<Unit> = sendOtp(phoneNumber)
}

/**
 * Eligibility status for phone registration
 */
sealed class PhoneEligibility {
    /** Phone number has not been used for registration before */
    data object Eligible : PhoneEligibility()

    /** Phone number has already been used for registration */
    data class AlreadyRegistered(val existingUserId: String) : PhoneEligibility()

    /** Phone number has been manually blocked */
    data object Blocked : PhoneEligibility()
}

/**
 * DTO for verified phone data from Supabase
 */
@Serializable
private data class VerifiedPhoneDto(
    val id: String,
    @SerialName("phone_number")
    val phoneNumber: String? = null,
    @SerialName("phone_hash")
    val phoneHash: String? = null,
    @SerialName("user_id")
    val userId: String? = null,
    val blocked: Boolean = false,
    @SerialName("verified_at")
    val verifiedAt: String? = null
)

/**
 * DTO for inserting a new verified phone
 */
@Serializable
private data class VerifiedPhoneInsertDto(
    @SerialName("phone_number")
    val phoneNumber: String,
    @SerialName("phone_hash")
    val phoneHash: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("verified_at")
    val verifiedAt: String
)

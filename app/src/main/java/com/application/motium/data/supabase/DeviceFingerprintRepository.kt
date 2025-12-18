package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.security.DeviceFingerprintManager
import com.application.motium.data.sync.TokenRefreshCoordinator
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository for managing device fingerprints in Supabase.
 * Used to prevent trial abuse by tracking which devices have already registered.
 */
class DeviceFingerprintRepository private constructor(private val context: Context) {

    private val client = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }
    private val fingerprintManager = DeviceFingerprintManager.getInstance(context)

    companion object {
        private const val TABLE_NAME = "device_fingerprints"

        @Volatile
        private var instance: DeviceFingerprintRepository? = null

        fun getInstance(context: Context): DeviceFingerprintRepository {
            return instance ?: synchronized(this) {
                instance ?: DeviceFingerprintRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Check if the current device is eligible for registration (hasn't been used before).
     *
     * @return DeviceEligibility result indicating if device can register
     */
    suspend fun checkDeviceEligibility(): DeviceEligibility = withContext(Dispatchers.IO) {
        try {
            val deviceId = fingerprintManager.getDeviceId()
            if (deviceId == null) {
                MotiumApplication.logger.w(
                    "Could not get device ID - allowing registration",
                    "DeviceFingerprintRepository"
                )
                return@withContext DeviceEligibility.Eligible
            }

            val result = client.postgrest[TABLE_NAME]
                .select(Columns.list("id", "user_id", "blocked", "registered_at")) {
                    filter {
                        eq("device_drm_id", deviceId)
                    }
                }
                .decodeSingleOrNull<DeviceFingerprintDto>()

            when {
                result == null -> DeviceEligibility.Eligible
                result.blocked -> DeviceEligibility.Blocked
                result.userId != null -> DeviceEligibility.AlreadyRegistered(result.userId)
                else -> DeviceEligibility.Eligible
            }
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "DeviceFingerprintRepository")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val deviceId = fingerprintManager.getDeviceId() ?: return@withContext DeviceEligibility.Eligible
                        val result = client.postgrest[TABLE_NAME]
                            .select(Columns.list("id", "user_id", "blocked", "registered_at")) {
                                filter {
                                    eq("device_drm_id", deviceId)
                                }
                            }
                            .decodeSingleOrNull<DeviceFingerprintDto>()
                        MotiumApplication.logger.i("Device eligibility checked after token refresh", "DeviceFingerprintRepository")
                        when {
                            result == null -> DeviceEligibility.Eligible
                            result.blocked -> DeviceEligibility.Blocked
                            result.userId != null -> DeviceEligibility.AlreadyRegistered(result.userId)
                            else -> DeviceEligibility.Eligible
                        }
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "DeviceFingerprintRepository", retryError)
                        DeviceEligibility.Eligible
                    }
                }
            }
            MotiumApplication.logger.e(
                "Error checking device eligibility: ${e.message}",
                "DeviceFingerprintRepository",
                e
            )
            // On error, allow registration but log it
            DeviceEligibility.Eligible
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error checking device eligibility: ${e.message}",
                "DeviceFingerprintRepository",
                e
            )
            // On error, allow registration but log it
            DeviceEligibility.Eligible
        }
    }

    /**
     * Register the current device for a user.
     * Called after successful registration to link device to user.
     *
     * @param userId The user ID to link the device to
     * @return Result with the device fingerprint ID if successful
     */
    suspend fun registerDevice(userId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val deviceId = fingerprintManager.getDeviceId()
            if (deviceId == null) {
                MotiumApplication.logger.w(
                    "Could not get device ID for registration",
                    "DeviceFingerprintRepository"
                )
                return@withContext Result.failure(Exception("Device ID not available"))
            }

            // Use upsert to handle race conditions
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
            val result = client.postgrest[TABLE_NAME]
                .upsert(
                    DeviceFingerprintInsertDto(
                        deviceDrmId = deviceId,
                        userId = userId,
                        registeredAt = now
                    )
                ) {
                    onConflict = "device_drm_id"
                }
                .decodeSingle<DeviceFingerprintDto>()

            MotiumApplication.logger.i(
                "Device registered successfully: ${result.id}",
                "DeviceFingerprintRepository"
            )

            Result.success(result.id)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error registering device: ${e.message}",
                "DeviceFingerprintRepository",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Get the current device's fingerprint ID if registered.
     *
     * @return The fingerprint ID or null if not registered
     */
    suspend fun getDeviceFingerprintId(): String? = withContext(Dispatchers.IO) {
        try {
            val deviceId = fingerprintManager.getDeviceId() ?: return@withContext null

            val result = client.postgrest[TABLE_NAME]
                .select(Columns.list("id")) {
                    filter {
                        eq("device_drm_id", deviceId)
                    }
                }
                .decodeSingleOrNull<DeviceFingerprintIdDto>()

            result?.id
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error getting device fingerprint ID: ${e.message}",
                "DeviceFingerprintRepository",
                e
            )
            null
        }
    }
}

/**
 * Eligibility status for device registration
 */
sealed class DeviceEligibility {
    /** Device has not been used for registration before */
    data object Eligible : DeviceEligibility()

    /** Device has already been used for registration */
    data class AlreadyRegistered(val existingUserId: String) : DeviceEligibility()

    /** Device has been manually blocked */
    data object Blocked : DeviceEligibility()
}

/**
 * DTO for device fingerprint data from Supabase
 */
@Serializable
private data class DeviceFingerprintDto(
    val id: String,
    @SerialName("device_drm_id")
    val deviceDrmId: String,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("registered_at")
    val registeredAt: String? = null,
    val blocked: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * DTO for inserting a new device fingerprint
 */
@Serializable
private data class DeviceFingerprintInsertDto(
    @SerialName("device_drm_id")
    val deviceDrmId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("registered_at")
    val registeredAt: String
)

/**
 * Minimal DTO for ID-only queries
 */
@Serializable
private data class DeviceFingerprintIdDto(
    val id: String
)

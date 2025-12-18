package com.application.motium.data.security

import android.content.Context
import android.media.MediaDrm
import android.provider.Settings
import android.util.Base64
import com.application.motium.MotiumApplication
import java.security.MessageDigest
import java.util.UUID

/**
 * Manager for device fingerprinting using MediaDrm Widevine DRM ID.
 * This ID is persistent across app reinstalls and factory resets on most devices.
 *
 * Used to prevent trial abuse by detecting when the same device attempts
 * to register a new account after already having used the trial.
 */
class DeviceFingerprintManager private constructor(private val context: Context) {

    companion object {
        // Widevine DRM UUID - standard across Android devices
        private const val WIDEVINE_UUID_STRING = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
        private val WIDEVINE_UUID: UUID = UUID.fromString(WIDEVINE_UUID_STRING)

        @Volatile
        private var instance: DeviceFingerprintManager? = null

        fun getInstance(context: Context): DeviceFingerprintManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceFingerprintManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Get a persistent device ID using MediaDrm Widevine.
     * This ID persists across app reinstalls and even factory resets on most devices.
     *
     * @return Base64-encoded device ID, or null if unavailable
     */
    fun getDeviceId(): String? {
        return try {
            getWidevineDeviceId() ?: getAndroidIdFallback()
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Failed to get device ID: ${e.message}",
                "DeviceFingerprintManager",
                e
            )
            getAndroidIdFallback()
        }
    }

    /**
     * Get device ID using MediaDrm Widevine DRM.
     * Most reliable and persistent method.
     */
    private fun getWidevineDeviceId(): String? {
        return try {
            val mediaDrm = MediaDrm(WIDEVINE_UUID)
            val deviceId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.close()

            if (deviceId != null && deviceId.isNotEmpty()) {
                Base64.encodeToString(deviceId, Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "Widevine DRM not available: ${e.message}",
                "DeviceFingerprintManager"
            )
            null
        }
    }

    /**
     * Fallback to ANDROID_ID if MediaDrm is not available.
     * Less reliable but still useful - resets on factory reset.
     */
    private fun getAndroidIdFallback(): String? {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            if (!androidId.isNullOrEmpty()) {
                // Hash the Android ID for consistency with Widevine format
                hashString(androidId)
            } else {
                null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Failed to get Android ID: ${e.message}",
                "DeviceFingerprintManager",
                e
            )
            null
        }
    }

    /**
     * Hash a string using SHA-256 and return as Base64.
     * Used to standardize the format of fallback IDs.
     */
    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Get a hash of the device ID for storage.
     * The hash is used for database lookups to avoid storing raw device IDs.
     *
     * @return SHA-256 hash of the device ID as hex string, or null if unavailable
     */
    fun getDeviceIdHash(): String? {
        val deviceId = getDeviceId() ?: return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(deviceId.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Failed to hash device ID: ${e.message}",
                "DeviceFingerprintManager",
                e
            )
            null
        }
    }
}

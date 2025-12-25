package com.application.motium.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.application.motium.MotiumApplication

/**
 * Secure cache for Pro license validation state.
 * Uses EncryptedSharedPreferences to store license validation results
 * for offline-first license checking.
 *
 * Cache validity: 7 days (licenses are typically monthly or lifetime).
 */
class ProLicenseCache private constructor(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        MotiumApplication.logger.e(
            "Cannot create encrypted license cache - using fallback behavior",
            TAG,
            e
        )
        // Return a non-functional SharedPreferences that always returns defaults
        context.getSharedPreferences("pro_license_cache_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "ProLicenseCache"
        private const val PREFS_NAME = "pro_license_cache_secure"

        // Keys
        private const val KEY_USER_ID = "cached_user_id"
        private const val KEY_IS_LICENSED = "is_licensed"
        private const val KEY_PRO_ACCOUNT_ID = "pro_account_id"
        private const val KEY_LICENSE_ID = "license_id"
        private const val KEY_LICENSE_STATUS = "license_status"
        private const val KEY_LAST_VALIDATED_AT = "last_validated_at"

        // Cache validity: 7 days in milliseconds
        private const val CACHE_VALIDITY_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: ProLicenseCache? = null

        fun getInstance(context: Context): ProLicenseCache {
            return instance ?: synchronized(this) {
                instance ?: ProLicenseCache(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Data class representing cached license state.
     */
    data class CachedLicenseState(
        val userId: String,
        val isLicensed: Boolean,
        val proAccountId: String?,
        val licenseId: String?,
        val licenseStatus: String?,
        val lastValidatedAt: Long
    ) {
        /**
         * Check if this cached state is still valid (within 7 days).
         */
        fun isValid(): Boolean {
            val age = System.currentTimeMillis() - lastValidatedAt
            return age < CACHE_VALIDITY_MS
        }

        /**
         * Get the age of this cache entry in hours (for logging).
         */
        fun getAgeHours(): Long {
            return (System.currentTimeMillis() - lastValidatedAt) / (1000 * 60 * 60)
        }
    }

    /**
     * Save license validation result to cache.
     * Call this after a successful network validation.
     */
    fun saveLicenseState(
        userId: String,
        isLicensed: Boolean,
        proAccountId: String? = null,
        licenseId: String? = null,
        licenseStatus: String? = null
    ) {
        try {
            val now = System.currentTimeMillis()
            encryptedPrefs.edit()
                .putString(KEY_USER_ID, userId)
                .putBoolean(KEY_IS_LICENSED, isLicensed)
                .putString(KEY_PRO_ACCOUNT_ID, proAccountId)
                .putString(KEY_LICENSE_ID, licenseId)
                .putString(KEY_LICENSE_STATUS, licenseStatus)
                .putLong(KEY_LAST_VALIDATED_AT, now)
                .apply()

            MotiumApplication.logger.i(
                "License cache saved: isLicensed=$isLicensed, proAccountId=$proAccountId",
                TAG
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving license cache: ${e.message}", TAG, e)
        }
    }

    /**
     * Get cached license state for a specific user.
     * Returns null if no cache exists or if cached for a different user.
     */
    fun getCachedState(userId: String): CachedLicenseState? {
        return try {
            val cachedUserId = encryptedPrefs.getString(KEY_USER_ID, null)

            // Cache is only valid for the same user
            if (cachedUserId != userId) {
                MotiumApplication.logger.d(
                    "License cache user mismatch: cached=$cachedUserId, current=$userId",
                    TAG
                )
                return null
            }

            val lastValidatedAt = encryptedPrefs.getLong(KEY_LAST_VALIDATED_AT, 0)
            if (lastValidatedAt == 0L) {
                MotiumApplication.logger.d("No license cache found", TAG)
                return null
            }

            CachedLicenseState(
                userId = cachedUserId,
                isLicensed = encryptedPrefs.getBoolean(KEY_IS_LICENSED, false),
                proAccountId = encryptedPrefs.getString(KEY_PRO_ACCOUNT_ID, null),
                licenseId = encryptedPrefs.getString(KEY_LICENSE_ID, null),
                licenseStatus = encryptedPrefs.getString(KEY_LICENSE_STATUS, null),
                lastValidatedAt = lastValidatedAt
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error reading license cache: ${e.message}", TAG, e)
            null
        }
    }

    /**
     * Check if cache is valid for the given user.
     * Returns true if we have a valid, non-expired cache entry.
     */
    fun isCacheValid(userId: String): Boolean {
        val cached = getCachedState(userId) ?: return false
        val isValid = cached.isValid()

        if (!isValid) {
            MotiumApplication.logger.d(
                "License cache expired (${cached.getAgeHours()} hours old)",
                TAG
            )
        }

        return isValid
    }

    /**
     * Get cached license status if valid, or null if cache is invalid/expired.
     * This is the main method to use for offline-first license checking.
     */
    fun getValidCachedLicenseStatus(userId: String): Boolean? {
        val cached = getCachedState(userId) ?: return null

        if (!cached.isValid()) {
            MotiumApplication.logger.d(
                "License cache expired - returning null (${cached.getAgeHours()} hours old)",
                TAG
            )
            return null
        }

        MotiumApplication.logger.i(
            "Using cached license status: isLicensed=${cached.isLicensed} (${cached.getAgeHours()}h old)",
            TAG
        )
        return cached.isLicensed
    }

    /**
     * Clear the license cache.
     * Call this on logout or when license state needs to be re-verified.
     */
    fun clearCache() {
        try {
            encryptedPrefs.edit().clear().apply()
            MotiumApplication.logger.i("License cache cleared", TAG)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error clearing license cache: ${e.message}", TAG, e)
        }
    }

    /**
     * Clear cache only for a specific user (useful when switching accounts).
     */
    fun clearCacheForUser(userId: String) {
        val cachedUserId = encryptedPrefs.getString(KEY_USER_ID, null)
        if (cachedUserId == userId) {
            clearCache()
        }
    }
}

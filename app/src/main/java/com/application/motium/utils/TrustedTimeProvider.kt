package com.application.motium.utils

import android.content.Context
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.application.motium.MotiumApplication
import kotlinx.datetime.Instant

/**
 * Provides trusted time validation to prevent clock manipulation attacks.
 *
 * Attack vector: User sets device clock to past date to extend expired licenses offline.
 *
 * Defense strategy:
 * 1. Store last known server time with device's elapsedRealtime (boot-monotonic clock)
 * 2. On each time check, calculate expected time from elapsedRealtime delta
 * 3. Compare expected time with System.currentTimeMillis()
 * 4. If difference exceeds threshold, user may have manipulated clock
 *
 * elapsedRealtime() is monotonic and cannot be changed by user - it counts milliseconds
 * since device boot, including sleep time.
 *
 * SECURITY: Uses EncryptedSharedPreferences to prevent root users from directly
 * modifying the time anchor values.
 */
class TrustedTimeProvider private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TrustedTimeProvider"
        private const val PREFS_NAME = "trusted_time_prefs_secure"
        private const val KEY_LAST_SERVER_TIME = "last_server_time"
        private const val KEY_ELAPSED_AT_SERVER_TIME = "elapsed_at_server_time"
        private const val KEY_LAST_BOOT_COUNT = "last_boot_count"

        // Maximum allowed drift between expected and actual time (5 minutes)
        // This accounts for network latency and minor clock drift
        const val MAX_ALLOWED_DRIFT_MS = 5 * 60 * 1000L

        // Threshold for suspicious backward time jump (1 hour)
        // If clock jumps backward more than this, definitely manipulation
        const val SUSPICIOUS_BACKWARD_JUMP_MS = 60 * 60 * 1000L

        @Volatile
        private var instance: TrustedTimeProvider? = null

        fun getInstance(context: Context): TrustedTimeProvider {
            return instance ?: synchronized(this) {
                instance ?: TrustedTimeProvider(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * SECURITY: Use EncryptedSharedPreferences to protect the time anchor from
     * modification by rooted users.
     *
     * SECURITY FIX: NO FALLBACK to regular SharedPreferences.
     * With minSdk 31 (Android 12), all devices support EncryptedSharedPreferences.
     * If encryption fails (KeyStore corruption), we attempt recovery by deleting
     * the corrupted file. If that fails, prefs is null and all time checks fail-secure.
     */
    private val prefs: android.content.SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "EncryptedSharedPreferences failed, attempting recovery by deleting corrupted file. " +
                "Error: ${e.message}",
                TAG
            )

            // Attempt recovery: delete corrupted file and try again
            try {
                context.deleteSharedPreferences(PREFS_NAME)
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e2: Exception) {
                // FAIL-SECURE: Do NOT fall back to unencrypted storage
                // Return null - all time checks will fail-secure (return null/false)
                MotiumApplication.logger.e(
                    "CRITICAL SECURITY: Cannot create EncryptedSharedPreferences even after recovery. " +
                    "Device KeyStore may be compromised. All time validation will fail-secure. " +
                    "Error: ${e2.message}",
                    TAG, e2
                )
                null
            }
        }
    }

    /**
     * Update the trusted time anchor when receiving a response from the server.
     * Call this after any successful API call that includes server timestamp.
     *
     * @param serverTimeMs Server time in milliseconds (from updated_at, created_at, etc.)
     */
    fun updateServerTime(serverTimeMs: Long) {
        val p = prefs
        if (p == null) {
            MotiumApplication.logger.w(
                "Cannot update trusted time anchor: EncryptedSharedPreferences unavailable",
                TAG
            )
            return
        }

        val elapsedNow = SystemClock.elapsedRealtime()
        p.edit()
            .putLong(KEY_LAST_SERVER_TIME, serverTimeMs)
            .putLong(KEY_ELAPSED_AT_SERVER_TIME, elapsedNow)
            .apply()

        MotiumApplication.logger.d(
            "Trusted time anchor updated: serverTime=$serverTimeMs, elapsed=$elapsedNow",
            TAG
        )
    }

    /**
     * Update server time from Instant
     */
    fun updateServerTime(serverTime: Instant) {
        updateServerTime(serverTime.toEpochMilliseconds())
    }

    /**
     * Get the current time, validated against the trusted anchor.
     *
     * @return Validated time in milliseconds, or null if clock manipulation is suspected
     *         or if secure storage is unavailable (fail-secure)
     */
    fun getTrustedTimeMs(): Long? {
        val p = prefs
        if (p == null) {
            MotiumApplication.logger.w(
                "getTrustedTimeMs() returning null: EncryptedSharedPreferences unavailable (fail-secure)",
                TAG
            )
            return null
        }

        val lastServerTime = p.getLong(KEY_LAST_SERVER_TIME, 0L)
        val elapsedAtServerTime = p.getLong(KEY_ELAPSED_AT_SERVER_TIME, 0L)

        // SECURITY FIX: No anchor yet - we MUST sync with server first
        // Returning System.currentTimeMillis() would allow clock manipulation before first sync
        // Instead, return null to force callers to sync with server or use fail-secure behavior
        if (lastServerTime == 0L || elapsedAtServerTime == 0L) {
            MotiumApplication.logger.w(
                "No trusted time anchor - require server sync before trusting time. " +
                "Returning null (fail-secure)",
                TAG
            )
            return null
        }

        val elapsedNow = SystemClock.elapsedRealtime()
        val elapsedDelta = elapsedNow - elapsedAtServerTime

        // If device was rebooted, elapsed time resets - anchor is invalid
        // SECURITY FIX: Do NOT fall back to System.currentTimeMillis() as it may be manipulated
        // Instead, return null to force a network sync before trusting time again
        if (elapsedDelta < 0) {
            MotiumApplication.logger.w(
                "Device rebooted detected (elapsedDelta=$elapsedDelta) - anchor invalid, require network sync",
                TAG
            )
            // Clear the anchor to indicate it's no longer trustworthy
            clearAnchor()
            return null  // Caller must handle null by forcing network sync
        }

        // Expected current time based on monotonic clock
        val expectedTime = lastServerTime + elapsedDelta
        val actualTime = System.currentTimeMillis()

        // Calculate drift
        val drift = actualTime - expectedTime

        // Check for suspicious manipulation
        when {
            drift < -SUSPICIOUS_BACKWARD_JUMP_MS -> {
                // Clock jumped backward significantly - likely manipulation
                MotiumApplication.logger.w(
                    "CLOCK MANIPULATION SUSPECTED: Clock jumped backward ${-drift / 1000}s",
                    TAG
                )
                return null
            }

            drift < -MAX_ALLOWED_DRIFT_MS -> {
                // Minor backward drift - suspicious but tolerable
                MotiumApplication.logger.w(
                    "Suspicious clock drift: ${drift / 1000}s backward from expected",
                    TAG
                )
                // Return expected time instead of actual
                return expectedTime
            }

            drift > MAX_ALLOWED_DRIFT_MS -> {
                // Clock is ahead - could be legitimate (e.g., NTP sync)
                // or could be trying to accelerate time (less useful for attack)
                MotiumApplication.logger.d(
                    "Clock ahead by ${drift / 1000}s - using actual time",
                    TAG
                )
                return actualTime
            }

            else -> {
                // Within acceptable drift
                return actualTime
            }
        }
    }

    /**
     * Get trusted time as Instant
     */
    fun getTrustedTime(): Instant? {
        return getTrustedTimeMs()?.let { Instant.fromEpochMilliseconds(it) }
    }

    /**
     * Check if the current system time appears to be trustworthy.
     *
     * @return true if time is trusted, false if manipulation suspected
     */
    fun isTimeTrusted(): Boolean {
        return getTrustedTimeMs() != null
    }

    /**
     * Get the best available current time - trusted if possible, system time as fallback.
     *
     * ⚠️ SECURITY WARNING: This method falls back to System.currentTimeMillis() which
     * can be manipulated by the user. DO NOT use this for security-sensitive operations
     * like expiration checks. Use [isExpiredFailSecure] or [getTrustedTimeMs] instead.
     *
     * Only use this for non-security-critical operations like:
     * - Displaying timestamps to the user
     * - Logging
     * - Analytics
     *
     * @return Current time in milliseconds (trusted or system)
     */
    @Deprecated(
        message = "Unsafe for security checks - falls back to manipulable system time. " +
                "Use getTrustedTimeMs() with null check or isExpiredFailSecure() instead.",
        replaceWith = ReplaceWith("getTrustedTimeMs()"),
        level = DeprecationLevel.WARNING
    )
    fun getBestAvailableTimeMs(): Long {
        return getTrustedTimeMs() ?: System.currentTimeMillis()
    }

    /**
     * Check if an expiration date has passed, using trusted time.
     *
     * @param expirationMs Expiration time in milliseconds
     * @return true if expired AND time is trusted, false if not expired, null if time untrusted
     */
    fun isExpired(expirationMs: Long): Boolean? {
        val trustedTime = getTrustedTimeMs() ?: return null
        return trustedTime > expirationMs
    }

    /**
     * Check if an expiration date has passed, using trusted time.
     * Falls back to assuming expired if clock manipulation suspected (fail-secure).
     *
     * @param expirationMs Expiration time in milliseconds
     * @return true if expired or clock manipulation suspected (fail-secure)
     */
    fun isExpiredFailSecure(expirationMs: Long): Boolean {
        val trustedTime = getTrustedTimeMs()
        if (trustedTime == null) {
            // Clock manipulation suspected - fail secure = treat as expired
            MotiumApplication.logger.w(
                "Clock manipulation suspected - treating as expired (fail-secure)",
                TAG
            )
            return true
        }
        return trustedTime > expirationMs
    }

    /**
     * Clear the trusted time anchor.
     * Call this on logout or when server sync fails repeatedly.
     */
    fun clearAnchor() {
        val p = prefs ?: return
        p.edit()
            .remove(KEY_LAST_SERVER_TIME)
            .remove(KEY_ELAPSED_AT_SERVER_TIME)
            .apply()
        MotiumApplication.logger.d("Trusted time anchor cleared", TAG)
    }
}

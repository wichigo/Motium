package com.application.motium.data.supabase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.application.motium.MotiumApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages caching of RPC results with TTL (time-to-live).
 * Provides offline-first capability for critical RPC calls.
 *
 * Usage:
 * ```
 * val result = rpcCacheManager.withCache(
 *     key = "is_in_work_hours_$userId",
 *     ttlMinutes = 15,
 *     rpcCall = { workScheduleRepository.isInWorkHours(userId) },
 *     fallback = { calculateLocallyIsInWorkHours(userId) }
 * )
 * ```
 */
class RpcCacheManager private constructor(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "rpc_cache_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        @Volatile
        private var instance: RpcCacheManager? = null

        fun getInstance(context: Context): RpcCacheManager {
            return instance ?: synchronized(this) {
                instance ?: RpcCacheManager(context.applicationContext).also { instance = it }
            }
        }

        private const val TAG = "RpcCacheManager"
        private const val TIMESTAMP_SUFFIX = "_ts"
    }

    /**
     * Execute an RPC call with caching and fallback.
     *
     * @param key Unique cache key for this RPC call
     * @param ttlMinutes Time-to-live in minutes for cached value
     * @param rpcCall Suspend function that executes the RPC
     * @param fallback Suspend function that provides offline fallback value
     * @return Result from RPC, cache, or fallback (in that order)
     */
    suspend fun <T> withCache(
        key: String,
        ttlMinutes: Int = 60,
        rpcCall: suspend () -> T,
        fallback: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        try {
            // Try RPC first
            val result = rpcCall()
            // Cache the result
            cacheValue(key, result, ttlMinutes)
            MotiumApplication.logger.d("RPC succeeded and cached: $key", TAG)
            result
        } catch (e: Exception) {
            MotiumApplication.logger.w("RPC failed: $key - ${e.message}, checking cache...", TAG)

            // Try cache
            val cached = getCachedValue<T>(key, ttlMinutes)
            if (cached != null) {
                MotiumApplication.logger.i("Using cached value for: $key", TAG)
                return@withContext cached
            }

            // Fall back to local calculation
            MotiumApplication.logger.i("Cache miss, using fallback for: $key", TAG)
            fallback()
        }
    }

    /**
     * Cache a boolean value.
     */
    suspend fun cacheBooleanValue(key: String, value: Boolean, ttlMinutes: Int = 60) = withContext(Dispatchers.IO) {
        cacheValue(key, value, ttlMinutes)
    }

    /**
     * Get cached boolean value if valid (within TTL).
     */
    suspend fun getCachedBoolean(key: String, ttlMinutes: Int = 60): Boolean? = withContext(Dispatchers.IO) {
        getCachedValue<Boolean>(key, ttlMinutes)
    }

    /**
     * Cache a string value.
     */
    suspend fun cacheStringValue(key: String, value: String, ttlMinutes: Int = 60) = withContext(Dispatchers.IO) {
        cacheValue(key, value, ttlMinutes)
    }

    /**
     * Get cached string value if valid.
     */
    suspend fun getCachedString(key: String, ttlMinutes: Int = 60): String? = withContext(Dispatchers.IO) {
        getCachedValue<String>(key, ttlMinutes)
    }

    /**
     * Generic cache storage using simple key-value pairs with timestamp.
     */
    private fun <T> cacheValue(key: String, value: T, ttlMinutes: Int) {
        try {
            val timestamp = System.currentTimeMillis()
            when (value) {
                is Boolean -> {
                    prefs.edit()
                        .putBoolean(key, value)
                        .putLong(key + TIMESTAMP_SUFFIX, timestamp)
                        .apply()
                }
                is String -> {
                    prefs.edit()
                        .putString(key, value)
                        .putLong(key + TIMESTAMP_SUFFIX, timestamp)
                        .apply()
                }
                else -> throw IllegalArgumentException("Unsupported type: ${value!!::class.simpleName}")
            }
            MotiumApplication.logger.d("Cached value for key: $key (TTL: ${ttlMinutes}min)", TAG)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to cache value for key $key: ${e.message}", TAG, e)
        }
    }

    /**
     * Generic cache retrieval with TTL check.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getCachedValue(key: String, ttlMinutes: Int): T? {
        try {
            val timestamp = prefs.getLong(key + TIMESTAMP_SUFFIX, 0L)
            if (timestamp == 0L) return null

            // Check TTL
            val age = System.currentTimeMillis() - timestamp
            val ttlMs = ttlMinutes * 60 * 1000L

            if (age >= ttlMs) {
                MotiumApplication.logger.d("Cache expired for key: $key (age: ${age / 1000}s, TTL: ${ttlMs / 1000}s)", TAG)
                // Clean up expired cache
                prefs.edit()
                    .remove(key)
                    .remove(key + TIMESTAMP_SUFFIX)
                    .apply()
                return null
            }

            // Try to get value - check if it's boolean first, then string
            val result: T? = if (prefs.contains(key)) {
                try {
                    // Try boolean first
                    @Suppress("UNCHECKED_CAST")
                    prefs.getBoolean(key, false) as T
                } catch (e: ClassCastException) {
                    // Must be a string
                    @Suppress("UNCHECKED_CAST")
                    prefs.getString(key, null) as T?
                }
            } else {
                null
            }

            if (result != null) {
                MotiumApplication.logger.d("Cache hit for key: $key (age: ${age / 1000}s)", TAG)
            }
            return result
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to retrieve cached value for key $key: ${e.message}", TAG, e)
            return null
        }
    }

    /**
     * Invalidate (clear) a cached value.
     */
    suspend fun invalidate(key: String) = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(key)
            .remove(key + TIMESTAMP_SUFFIX)
            .apply()
        MotiumApplication.logger.d("Invalidated cache for key: $key", TAG)
    }

    /**
     * Clear all cached RPC values.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        MotiumApplication.logger.i("Cleared all RPC cache", TAG)
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getCacheStats(): Map<String, Any> {
        val allKeys = prefs.all.keys
        return mapOf(
            "totalKeys" to allKeys.size,
            "keys" to allKeys.toList()
        )
    }
}

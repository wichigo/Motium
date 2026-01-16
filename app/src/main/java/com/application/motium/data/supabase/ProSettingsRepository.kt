package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Repository for managing Pro account settings (departments, etc.)
 * Data is stored in the pro_accounts table.
 */
class ProSettingsRepository private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        @Volatile
        private var instance: ProSettingsRepository? = null

        fun getInstance(context: Context): ProSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: ProSettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get departments for a pro account
     */
    suspend fun getDepartments(proAccountId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val result = supabaseClient.from("pro_accounts")
                .select {
                    filter { eq("id", proAccountId) }
                }
                .decodeSingleOrNull<ProAccountDepartmentsDto>()

            result?.getDepartmentsList() ?: emptyList()
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "ProSettingsRepo")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val result = supabaseClient.from("pro_accounts")
                            .select {
                                filter { eq("id", proAccountId) }
                            }
                            .decodeSingleOrNull<ProAccountDepartmentsDto>()
                        MotiumApplication.logger.i("Departments loaded after token refresh", "ProSettingsRepo")
                        result?.getDepartmentsList() ?: emptyList()
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "ProSettingsRepo", retryError)
                        emptyList()
                    }
                }
            }
            MotiumApplication.logger.e("Error getting departments: ${e.message}", "ProSettingsRepo", e)
            emptyList()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting departments: ${e.message}", "ProSettingsRepo", e)
            emptyList()
        }
    }

    /**
     * Update departments for a pro account
     */
    suspend fun updateDepartments(proAccountId: String, departments: List<String>): Result<Unit> {
        return try {
            // Supabase accepts native JSON arrays for JSONB columns
            supabaseClient.from("pro_accounts")
                .update(mapOf("departments" to departments)) {
                    filter { eq("id", proAccountId) }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating departments: ${e.message}", "ProSettingsRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Add a department to a pro account
     */
    suspend fun addDepartment(proAccountId: String, department: String): Result<Unit> {
        return try {
            val currentDepartments = getDepartments(proAccountId).toMutableList()
            if (!currentDepartments.contains(department)) {
                currentDepartments.add(department)
                updateDepartments(proAccountId, currentDepartments)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error adding department: ${e.message}", "ProSettingsRepo", e)
            Result.failure(e)
        }
    }

    /**
     * Remove a department from a pro account
     */
    suspend fun removeDepartment(proAccountId: String, department: String): Result<Unit> {
        return try {
            val currentDepartments = getDepartments(proAccountId).toMutableList()
            currentDepartments.remove(department)
            updateDepartments(proAccountId, currentDepartments)
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error removing department: ${e.message}", "ProSettingsRepo", e)
            Result.failure(e)
        }
    }
}

/**
 * DTO for reading departments from pro_accounts
 * Note: Supabase returns JSONB columns as native JSON arrays, not strings
 */
@Serializable
private data class ProAccountDepartmentsDto(
    val id: String,
    val departments: List<String>? = null // JSONB returned as native JSON array
) {
    fun getDepartmentsList(): List<String> = departments ?: emptyList()
}

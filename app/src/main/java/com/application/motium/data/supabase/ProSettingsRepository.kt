package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Repository for managing Pro account settings (departments, etc.)
 * Data is stored in the pro_accounts table.
 */
class ProSettingsRepository private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val json = Json { ignoreUnknownKeys = true }

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
    suspend fun getDepartments(proAccountId: String): List<String> {
        return try {
            val result = supabaseClient.from("pro_accounts")
                .select {
                    filter { eq("id", proAccountId) }
                }
                .decodeSingleOrNull<ProAccountDepartmentsDto>()

            result?.getDepartmentsList() ?: emptyList()
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
            // Simple JSON array encoding
            val departmentsJson = "[${departments.joinToString(",") { "\"$it\"" }}]"

            supabaseClient.from("pro_accounts")
                .update(mapOf("departments" to departmentsJson)) {
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
 */
@Serializable
private data class ProAccountDepartmentsDto(
    val id: String,
    val departments: String? = null // JSONB as string
) {
    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }

    fun getDepartmentsList(): List<String> {
        return try {
            if (departments != null) {
                jsonParser.decodeFromString<List<String>>(departments)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

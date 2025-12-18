package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

class SupabaseExpenseRepository private constructor(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val secureSessionStorage = SecureSessionStorage(context)

    companion object {
        @Volatile
        private var instance: SupabaseExpenseRepository? = null

        fun getInstance(context: Context): SupabaseExpenseRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseExpenseRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get the current user ID from local user repository or secure storage.
     * FIX RLS: Uses users.id (from localUserRepository) instead of auth.uid()
     * because expenses_trips.user_id must match users.id for RLS policies.
     */
    private suspend fun getCurrentUserId(): String? {
        // Try from local user repository first (returns users.id, not auth.uid())
        localUserRepository.getLoggedInUser()?.id?.let { return it }
        // Fallback to secure session storage
        return secureSessionStorage.restoreSession()?.userId
    }

    @Serializable
    private data class SupabaseExpense(
        val id: String,
        val user_id: String,            // REQUIRED: User ID for RLS
        val date: String,               // Date au format YYYY-MM-DD
        val type: String,
        val amount: Double,
        val amount_ht: Double? = null,
        val note: String = "",
        val photo_uri: String? = null,
        val created_at: String,
        val updated_at: String
    )

    /**
     * Save an expense to Supabase
     */
    suspend fun saveExpense(expense: Expense): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("User not authenticated"))

            MotiumApplication.logger.i("Saving expense to Supabase: ${expense.id} for user $userId", "SupabaseExpenseRepository")

            // Format timestamps
            val createdAtFormatted = formatInstant(expense.createdAt)
            val updatedAtFormatted = formatInstant(expense.updatedAt)

            val supabaseExpense = SupabaseExpense(
                id = expense.id,
                user_id = userId,             // REQUIRED: User ID for RLS
                date = expense.date,          // Date de la dépense
                type = expense.type.name,
                amount = expense.amount,
                amount_ht = expense.amountHT,
                note = expense.note,
                photo_uri = expense.photoUri,
                created_at = createdAtFormatted,
                updated_at = updatedAtFormatted
            )

            // Check if expense already exists
            val existing = try {
                postgres
                    .from("expenses_trips")
                    .select(columns = Columns.list("id")) {
                        filter {
                            eq("id", expense.id)
                        }
                    }
                    .decodeList<Map<String, String>>()
                    .firstOrNull()
            } catch (e: Exception) {
                null
            }

            if (existing != null) {
                // Update existing expense
                MotiumApplication.logger.i("Expense exists, updating: ${expense.id}", "SupabaseExpenseRepository")
                postgres.from("expenses_trips").update(supabaseExpense) {
                    filter {
                        eq("id", expense.id)
                    }
                }
            } else {
                // Insert new expense
                MotiumApplication.logger.i("Expense doesn't exist, inserting: ${expense.id}", "SupabaseExpenseRepository")
                postgres.from("expenses_trips").insert(supabaseExpense)
            }

            MotiumApplication.logger.i("Expense saved successfully: ${expense.id}", "SupabaseExpenseRepository")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving expense to Supabase: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Save multiple expenses
     */
    suspend fun saveExpenses(expenses: List<Expense>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Saving ${expenses.size} expenses to Supabase", "SupabaseExpenseRepository")

            expenses.forEach { expense ->
                val result = saveExpense(expense)
                if (result.isFailure) {
                    MotiumApplication.logger.w("Failed to save expense ${expense.id}", "SupabaseExpenseRepository")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving expenses: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Get all expenses for a user from Supabase
     */
    suspend fun getAllExpenses(userId: String): Result<List<Expense>> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching all expenses for user: $userId", "SupabaseExpenseRepository")

            val supabaseExpenses = postgres
                .from("expenses_trips")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<SupabaseExpense>()

            val expenses = supabaseExpenses.map { it.toExpense() }

            MotiumApplication.logger.i("Fetched ${expenses.size} expenses from Supabase", "SupabaseExpenseRepository")
            Result.success(expenses)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "SupabaseExpenseRepository")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val supabaseExpenses = postgres
                            .from("expenses_trips")
                            .select {
                                filter {
                                    eq("user_id", userId)
                                }
                            }
                            .decodeList<SupabaseExpense>()
                        val expenses = supabaseExpenses.map { it.toExpense() }
                        MotiumApplication.logger.i("Expenses loaded after token refresh", "SupabaseExpenseRepository")
                        Result.success(expenses)
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "SupabaseExpenseRepository", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error fetching all expenses: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching all expenses: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an expense
     */
    suspend fun deleteExpense(expenseId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting expense: $expenseId", "SupabaseExpenseRepository")

            postgres.from("expenses_trips").delete {
                filter {
                    eq("id", expenseId)
                }
            }

            MotiumApplication.logger.i("Expense deleted successfully: $expenseId", "SupabaseExpenseRepository")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting expense: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Format Instant to PostgreSQL TIMESTAMPTZ
     */
    private fun formatInstant(instant: Instant): String {
        val date = Date(instant.toEpochMilliseconds())
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)
    }

    /**
     * Parse PostgreSQL TIMESTAMPTZ to Instant
     */
    private fun parseInstant(timestamptz: String): Instant {
        return try {
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss"
            )

            for (format in formats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = sdf.parse(timestamptz)
                    if (date != null) {
                        return Instant.fromEpochMilliseconds(date.time)
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }

            // Fallback to current time if parsing fails
            MotiumApplication.logger.w("Failed to parse timestamp: $timestamptz, using current time", "SupabaseExpenseRepository")
            Instant.fromEpochMilliseconds(System.currentTimeMillis())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error parsing timestamp: ${e.message}", "SupabaseExpenseRepository", e)
            Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
    }

    /**
     * Get all expenses for a specific day
     * NOUVEAU: Récupère les dépenses par journée au lieu de par trip
     */
    suspend fun getExpensesForDay(date: String): Result<List<Expense>> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching expenses for date: $date", "SupabaseExpenseRepository")

            val supabaseExpenses = postgres
                .from("expenses_trips")
                .select {
                    filter {
                        eq("date", date)
                    }
                }
                .decodeList<SupabaseExpense>()

            val expenses = supabaseExpenses.map { it.toExpense() }

            MotiumApplication.logger.i("Fetched ${expenses.size} expenses for date $date", "SupabaseExpenseRepository")
            Result.success(expenses)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching expenses for day: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Get all expenses for multiple days in a single query (optimized for batch export)
     * NOUVEAU: Récupère les dépenses pour plusieurs journées
     */
    suspend fun getExpensesForDays(dates: List<String>): Result<Map<String, List<Expense>>> = withContext(Dispatchers.IO) {
        try {
            if (dates.isEmpty()) {
                return@withContext Result.success(emptyMap())
            }

            MotiumApplication.logger.i("Fetching expenses for ${dates.size} days in batch", "SupabaseExpenseRepository")

            val supabaseExpenses = postgres
                .from("expenses_trips")
                .select {
                    filter {
                        isIn("date", dates)
                    }
                }
                .decodeList<SupabaseExpense>()

            // Group expenses by date
            val expensesByDay = supabaseExpenses
                .map { it.toExpense() }
                .filter { it.date.isNotEmpty() }
                .groupBy { it.date }

            MotiumApplication.logger.i("Fetched ${supabaseExpenses.size} expenses for ${dates.size} days in batch", "SupabaseExpenseRepository")
            Result.success(expensesByDay)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching expenses for days in batch: ${e.message}", "SupabaseExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Convert SupabaseExpense to Expense
     */
    private fun SupabaseExpense.toExpense(): Expense {
        return Expense(
            id = id,
            date = date,
            type = ExpenseType.valueOf(type),
            amount = amount,
            amountHT = amount_ht,
            note = note,
            photoUri = photo_uri,
            createdAt = parseInstant(created_at),
            updatedAt = parseInstant(updated_at)
        )
    }
}

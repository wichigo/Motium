package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import io.github.jan.supabase.postgrest.from
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
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var instance: SupabaseExpenseRepository? = null

        fun getInstance(context: Context): SupabaseExpenseRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseExpenseRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    @Serializable
    private data class SupabaseExpense(
        val id: String,
        val trip_id: String,
        val type: String,
        val amount: Double,
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
            MotiumApplication.logger.i("Saving expense to Supabase: ${expense.id}", "SupabaseExpenseRepository")

            // Format timestamps
            val createdAtFormatted = formatInstant(expense.createdAt)
            val updatedAtFormatted = formatInstant(expense.updatedAt)

            val supabaseExpense = SupabaseExpense(
                id = expense.id,
                trip_id = expense.tripId,
                type = expense.type.name,
                amount = expense.amount,
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
     * Save multiple expenses for a trip
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
     * Get all expenses for a trip
     */
    suspend fun getExpensesForTrip(tripId: String): Result<List<Expense>> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching expenses for trip: $tripId", "SupabaseExpenseRepository")

            val supabaseExpenses = postgres
                .from("expenses_trips")
                .select {
                    filter {
                        eq("trip_id", tripId)
                    }
                }
                .decodeList<SupabaseExpense>()

            val expenses = supabaseExpenses.map { it.toExpense() }

            MotiumApplication.logger.i("Fetched ${expenses.size} expenses for trip $tripId", "SupabaseExpenseRepository")
            Result.success(expenses)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching expenses: ${e.message}", "SupabaseExpenseRepository", e)
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
     * Convert SupabaseExpense to Expense
     */
    private fun SupabaseExpense.toExpense(): Expense {
        return Expense(
            id = id,
            tripId = trip_id,
            type = ExpenseType.valueOf(type),
            amount = amount,
            note = note,
            photoUri = photo_uri,
            createdAt = parseInstant(created_at),
            updatedAt = parseInstant(updated_at)
        )
    }
}

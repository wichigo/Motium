package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.ExpenseDao
import com.application.motium.data.local.entities.ExpenseEntity
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseExpenseRepository
import com.application.motium.domain.model.Expense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository for managing expenses with offline-first architecture.
 * Data is stored locally in Room and synced with Supabase when possible.
 */
class ExpenseRepository private constructor(private val context: Context) {

    private val database = MotiumDatabase.getInstance(context)
    private val expenseDao: ExpenseDao = database.expenseDao()
    private val authRepository = SupabaseAuthRepository.getInstance(context)
    private val supabaseExpenseRepository = SupabaseExpenseRepository.getInstance(context)
    private val secureSessionStorage = SecureSessionStorage(context)

    /**
     * Get the current user ID from auth or secure storage.
     */
    private suspend fun getCurrentUserId(): String? {
        // Try from Supabase auth first
        authRepository.getCurrentAuthUser()?.id?.let { return it }

        // Fallback to secure session storage
        return secureSessionStorage.restoreSession()?.userId
    }

    companion object {
        @Volatile
        private var instance: ExpenseRepository? = null

        fun getInstance(context: Context): ExpenseRepository {
            return instance ?: synchronized(this) {
                instance ?: ExpenseRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Get all expenses for the current user from local cache.
     */
    suspend fun getExpensesForUser(): List<Expense> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext emptyList()
            val entities = expenseDao.getExpensesForUser(userId)
            entities.map { it.toDomainModel() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting expenses: ${e.message}", "ExpenseRepository", e)
            emptyList()
        }
    }

    /**
     * Get expenses as Flow for reactive updates.
     */
    fun getExpensesForUserFlow(userId: String): Flow<List<Expense>> {
        return expenseDao.getExpensesForUserFlow(userId).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * Get a specific expense by ID from local cache.
     */
    suspend fun getExpenseById(expenseId: String): Expense? = withContext(Dispatchers.IO) {
        try {
            expenseDao.getExpenseById(expenseId)?.toDomainModel()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting expense by ID: ${e.message}", "ExpenseRepository", e)
            null
        }
    }

    /**
     * Get expenses for a specific date from local cache.
     */
    suspend fun getExpensesForDate(date: String): List<Expense> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext emptyList()
            expenseDao.getExpensesForDate(userId, date).map { it.toDomainModel() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting expenses for date: ${e.message}", "ExpenseRepository", e)
            emptyList()
        }
    }

    /**
     * Get expenses between two dates from local cache.
     */
    suspend fun getExpensesBetweenDates(startDate: String, endDate: String): List<Expense> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext emptyList()
            expenseDao.getExpensesBetweenDates(userId, startDate, endDate).map { it.toDomainModel() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting expenses between dates: ${e.message}", "ExpenseRepository", e)
            emptyList()
        }
    }

    /**
     * Save an expense locally and sync with Supabase.
     */
    suspend fun saveExpense(expense: Expense): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("User not authenticated"))

            // Save locally first (offline-first)
            val entity = expense.toEntity(userId, needsSync = true)
            expenseDao.insertExpense(entity)
            MotiumApplication.logger.i("✅ Expense saved locally: ${expense.id}", "ExpenseRepository")

            // Try to sync with Supabase
            try {
                val result = supabaseExpenseRepository.saveExpense(expense)
                if (result.isSuccess) {
                    expenseDao.markExpenseAsSynced(expense.id, System.currentTimeMillis())
                    MotiumApplication.logger.i("✅ Expense synced to Supabase: ${expense.id}", "ExpenseRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.w("⚠️ Expense saved locally, will sync later: ${e.message}", "ExpenseRepository")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving expense: ${e.message}", "ExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an expense locally and from Supabase.
     */
    suspend fun deleteExpense(expenseId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Delete locally first
            expenseDao.deleteExpenseById(expenseId)
            MotiumApplication.logger.i("✅ Expense deleted locally: $expenseId", "ExpenseRepository")

            // Try to delete from Supabase
            try {
                supabaseExpenseRepository.deleteExpense(expenseId)
                MotiumApplication.logger.i("✅ Expense deleted from Supabase: $expenseId", "ExpenseRepository")
            } catch (e: Exception) {
                MotiumApplication.logger.w("⚠️ Could not delete from Supabase: ${e.message}", "ExpenseRepository")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting expense: ${e.message}", "ExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Sync expenses from Supabase to local cache.
     * Call this when app starts or when user pulls to refresh.
     */
    suspend fun syncFromSupabase(userId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = userId ?: getCurrentUserId()
                ?: return@withContext Result.failure(Exception("User not authenticated"))

            MotiumApplication.logger.i("🔄 Fetching expenses from Supabase for user: $effectiveUserId", "ExpenseRepository")

            val result = supabaseExpenseRepository.getAllExpenses(effectiveUserId)
            if (result.isSuccess) {
                val supabaseExpenses = result.getOrNull() ?: emptyList()
                MotiumApplication.logger.i("📥 Fetched ${supabaseExpenses.size} expenses from Supabase", "ExpenseRepository")

                // Charger les expenses locales depuis Room
                val localExpenseEntities = expenseDao.getExpensesForUser(effectiveUserId)
                MotiumApplication.logger.i("📂 Found ${localExpenseEntities.size} local expenses in Room", "ExpenseRepository")

                // Identifier les expenses Supabase par ID
                val supabaseExpenseIds = supabaseExpenses.map { it.id }.toSet()

                // Garder les expenses locales non encore synchronisées (needsSync = true)
                val localOnlyExpensesToKeep = localExpenseEntities
                    .filter { it.id !in supabaseExpenseIds && it.needsSync }

                // Supprimer les expenses qui ont été supprimées sur Supabase
                val expensesToDelete = localExpenseEntities
                    .filter { it.id !in supabaseExpenseIds && !it.needsSync }

                if (expensesToDelete.isNotEmpty()) {
                    expensesToDelete.forEach { entity ->
                        expenseDao.deleteExpenseById(entity.id)
                    }
                    MotiumApplication.logger.i(
                        "🗑️ Deleted ${expensesToDelete.size} expenses that were removed from Supabase",
                        "ExpenseRepository"
                    )
                }

                // Convertir les expenses Supabase en entities Room
                val supabaseEntities = supabaseExpenses.map { expense ->
                    expense.toEntity(effectiveUserId, lastSyncedAt = System.currentTimeMillis(), needsSync = false)
                }

                // Sauvegarder dans Room: expenses Supabase + expenses locales non synchronisées
                val allEntitiesToSave = supabaseEntities + localOnlyExpensesToKeep
                MotiumApplication.logger.i(
                    "💾 Saving ${allEntitiesToSave.size} expenses to Room (${supabaseEntities.size} from Supabase + ${localOnlyExpensesToKeep.size} local pending)",
                    "ExpenseRepository"
                )

                if (allEntitiesToSave.isNotEmpty()) {
                    expenseDao.insertExpenses(allEntitiesToSave)
                    MotiumApplication.logger.i("✅ Successfully inserted ${allEntitiesToSave.size} expenses into Room", "ExpenseRepository")
                }
            } else {
                MotiumApplication.logger.w("⚠️ Failed to fetch expenses from Supabase", "ExpenseRepository")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing expenses from Supabase: ${e.message}", "ExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Sync pending local changes to Supabase.
     */
    suspend fun syncToSupabase(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("User not authenticated"))

            val expensesNeedingSync = expenseDao.getExpensesNeedingSync(userId)

            for (entity in expensesNeedingSync) {
                try {
                    val expense = entity.toDomainModel()
                    val result = supabaseExpenseRepository.saveExpense(expense)
                    if (result.isSuccess) {
                        expenseDao.markExpenseAsSynced(entity.id, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to sync expense ${entity.id}: ${e.message}", "ExpenseRepository")
                }
            }

            MotiumApplication.logger.i("✅ Synced ${expensesNeedingSync.size} pending expenses", "ExpenseRepository")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing expenses to Supabase: ${e.message}", "ExpenseRepository", e)
            Result.failure(e)
        }
    }
}

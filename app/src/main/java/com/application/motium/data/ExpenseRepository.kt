package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.ExpenseDao
import com.application.motium.data.local.entities.ExpenseEntity
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.Expense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Repository for managing expenses with offline-first architecture.
 * Data is stored locally in Room and synced with Supabase when possible.
 */
class ExpenseRepository private constructor(private val context: Context) {

    private val database = MotiumDatabase.getInstance(context)
    private val expenseDao: ExpenseDao = database.expenseDao()
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val secureSessionStorage = SecureSessionStorage(context)
    private val syncManager by lazy { OfflineFirstSyncManager.getInstance(context.applicationContext) }

    /**
     * Get the current user ID from local user repository or secure storage.
     * FIX RLS: Uses users.id (from localUserRepository) instead of auth.uid()
     * because expenses.user_id must match users.id for RLS policies.
     */
    private suspend fun getCurrentUserId(): String? {
        // Try from local user repository first (returns users.id, not auth.uid())
        localUserRepository.getLoggedInUser()?.id?.let { return it }

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
     * Get expenses for a specific user within a date range (for Pro export).
     * @param userId The user ID to fetch expenses for
     * @param startDate Start date as epoch milliseconds
     * @param endDate End date as epoch milliseconds
     * @return List of expenses for the user within the date range
     */
    suspend fun getExpensesForDateRange(
        userId: String,
        startDate: Long,
        endDate: Long
    ): List<Expense> = withContext(Dispatchers.IO) {
        try {
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val startDateStr = dateFormat.format(java.util.Date(startDate))
            val endDateStr = dateFormat.format(java.util.Date(endDate))

            expenseDao.getExpensesBetweenDates(userId, startDateStr, endDateStr).map { it.toDomainModel() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting expenses for date range: ${e.message}", "ExpenseRepository", e)
            emptyList()
        }
    }

    /**
     * Save an expense locally and queue for atomic sync via sync_changes() RPC.
     */
    suspend fun saveExpense(expense: Expense): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("User not authenticated"))

            // Check existing entity BEFORE insert to determine isNew and version
            val existingEntity = expenseDao.getExpenseById(expense.id)
            val isNew = existingEntity == null
            val newVersion = (existingEntity?.version ?: 0) + 1

            // Save locally with PENDING_UPLOAD and incremented version
            val entity = expense.toEntity(
                userId,
                syncStatus = SyncStatus.PENDING_UPLOAD.name,
                version = newVersion
            )
            expenseDao.insertExpense(entity)
            MotiumApplication.logger.i("Expense saved locally: ${expense.id} (version=$newVersion)", "ExpenseRepository")

            // Queue for atomic sync via sync_changes() RPC
            val payload = buildExpensePayload(expense, newVersion)
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_EXPENSE,
                entityId = expense.id,
                action = if (isNew) PendingOperationEntity.ACTION_CREATE else PendingOperationEntity.ACTION_UPDATE,
                payload = payload,
                priority = 1
            )
            MotiumApplication.logger.i("Expense queued for sync: ${expense.id}", "ExpenseRepository")

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving expense: ${e.message}", "ExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an expense locally and queue deletion for atomic sync.
     */
    suspend fun deleteExpense(expenseId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Queue the DELETE operation BEFORE local deletion
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_EXPENSE,
                entityId = expenseId,
                action = PendingOperationEntity.ACTION_DELETE,
                payload = null,
                priority = 1
            )

            // Delete locally
            expenseDao.deleteExpenseById(expenseId)
            MotiumApplication.logger.i("Expense deleted and queued for sync: $expenseId", "ExpenseRepository")

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting expense: ${e.message}", "ExpenseRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Build JSON payload matching push_expense_change() SQL fields.
     */
    private fun buildExpensePayload(expense: Expense, version: Int): String {
        return buildJsonObject {
            put("type", expense.type.name)
            put("amount", expense.amount)
            expense.amountHT?.let { put("amount_ht", it) }
            put("note", expense.note)
            expense.photoUri?.let { put("photo_uri", it) }
            put("date", expense.date)
            put("version", version)
        }.toString()
    }
}

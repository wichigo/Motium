package com.application.motium.data.local.dao

import androidx.room.*
import com.application.motium.data.local.entities.ExpenseEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for expense operations.
 * Provides methods to interact with the expenses table in Room database.
 */
@Dao
interface ExpenseDao {

    /**
     * Insert or replace an expense in the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity)

    /**
     * Insert multiple expenses at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenses(expenses: List<ExpenseEntity>)

    /**
     * Update an existing expense.
     */
    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    /**
     * Delete an expense.
     */
    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    /**
     * Delete an expense by ID.
     */
    @Query("DELETE FROM expenses WHERE id = :expenseId")
    suspend fun deleteExpenseById(expenseId: String)

    /**
     * Get all expenses for a user.
     * Returns Flow for reactive updates.
     */
    @Query("SELECT * FROM expenses WHERE userId = :userId ORDER BY date DESC, createdAt DESC")
    fun getExpensesForUserFlow(userId: String): Flow<List<ExpenseEntity>>

    /**
     * Get all expenses for a user (one-time fetch).
     */
    @Query("SELECT * FROM expenses WHERE userId = :userId ORDER BY date DESC, createdAt DESC")
    suspend fun getExpensesForUser(userId: String): List<ExpenseEntity>

    /**
     * Get a specific expense by ID.
     */
    @Query("SELECT * FROM expenses WHERE id = :expenseId")
    suspend fun getExpenseById(expenseId: String): ExpenseEntity?

    /**
     * Get expenses for a specific date.
     */
    @Query("SELECT * FROM expenses WHERE userId = :userId AND date = :date ORDER BY createdAt DESC")
    suspend fun getExpensesForDate(userId: String, date: String): List<ExpenseEntity>

    /**
     * Get expenses between two dates.
     */
    @Query("SELECT * FROM expenses WHERE userId = :userId AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesBetweenDates(userId: String, startDate: String, endDate: String): List<ExpenseEntity>

    /**
     * Get expenses that need to be synced to Supabase.
     */
    @Query("SELECT * FROM expenses WHERE userId = :userId AND needsSync = 1")
    suspend fun getExpensesNeedingSync(userId: String): List<ExpenseEntity>

    /**
     * Mark an expense as synced.
     */
    @Query("UPDATE expenses SET needsSync = 0, lastSyncedAt = :timestamp WHERE id = :expenseId")
    suspend fun markExpenseAsSynced(expenseId: String, timestamp: Long)

    /**
     * Mark an expense as needing sync.
     */
    @Query("UPDATE expenses SET needsSync = 1 WHERE id = :expenseId")
    suspend fun markExpenseAsNeedingSync(expenseId: String)

    /**
     * Delete all expenses for a user.
     */
    @Query("DELETE FROM expenses WHERE userId = :userId")
    suspend fun deleteAllExpensesForUser(userId: String)

    /**
     * Delete all expenses (used during complete logout).
     */
    @Query("DELETE FROM expenses")
    suspend fun deleteAllExpenses()

    /**
     * Get total expense amount for a user between dates.
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE userId = :userId AND date >= :startDate AND date <= :endDate")
    suspend fun getTotalExpensesBetweenDates(userId: String, startDate: String, endDate: String): Double
}

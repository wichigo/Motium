package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import kotlinx.datetime.Instant

/**
 * Room entity for storing expense data locally.
 * Allows offline expense management and automatic sync when connection is restored.
 */
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val date: String,              // Date de la dépense (format YYYY-MM-DD)
    val type: String,              // ExpenseType enum stored as String
    val amount: Double,            // Montant TTC
    val amountHT: Double?,         // Montant HT
    val note: String,
    val photoUri: String?,
    val createdAt: String,         // Instant stored as ISO-8601 string
    val updatedAt: String,         // Instant stored as ISO-8601 string
    val lastSyncedAt: Long?,       // Timestamp of last sync with Supabase
    val needsSync: Boolean         // Flag indicating if expense needs to be synced
)

/**
 * Extension function to convert ExpenseEntity to domain Expense model
 */
fun ExpenseEntity.toDomainModel(): Expense {
    return Expense(
        id = id,
        date = date,
        type = ExpenseType.valueOf(type),
        amount = amount,
        amountHT = amountHT,
        note = note,
        photoUri = photoUri,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain Expense to ExpenseEntity
 */
fun Expense.toEntity(userId: String, lastSyncedAt: Long? = null, needsSync: Boolean = false): ExpenseEntity {
    return ExpenseEntity(
        id = id,
        userId = userId,
        date = date,
        type = type.name,
        amount = amount,
        amountHT = amountHT,
        note = note,
        photoUri = photoUri,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

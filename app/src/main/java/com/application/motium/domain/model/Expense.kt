package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

enum class ExpenseType {
    FUEL,           // Carburant
    HOTEL,          // Hôtel
    TOLL,           // Péage
    PARKING,        // Parking
    RESTAURANT,     // Restaurant
    MEAL_OUT,       // Repas hors restaurant
    OTHER           // Autres
}

@Serializable
data class Expense(
    val id: String,
    val date: String,             // Date de la dépense (format YYYY-MM-DD)
    val type: ExpenseType,
    val amount: Double,           // Montant TTC (Toutes Taxes Comprises)
    val amountHT: Double? = null, // Montant HT (Hors Taxes)
    val note: String = "",
    val photoUri: String? = null, // URI de la photo de la facture
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun getFormattedAmount(): String {
        return String.format("%.2f € TTC", amount)
    }

    fun getFormattedAmountHT(): String? {
        return amountHT?.let { String.format("%.2f € HT", it) }
    }

    fun getExpenseTypeLabel(): String {
        return when (type) {
            ExpenseType.FUEL -> "Carburant"
            ExpenseType.HOTEL -> "Hôtel"
            ExpenseType.TOLL -> "Péage"
            ExpenseType.PARKING -> "Parking"
            ExpenseType.RESTAURANT -> "Restaurant"
            ExpenseType.MEAL_OUT -> "Repas hors restaurant"
            ExpenseType.OTHER -> "Autres"
        }
    }
}

package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Représente une licence Pro pour un utilisateur lié
 * Tarif: 5€ HT/mois par utilisateur (6€ TTC avec TVA 20%)
 */
@Serializable
data class License(
    val id: String,
    @SerialName("pro_account_id")
    val proAccountId: String,
    @SerialName("linked_account_id")
    val linkedAccountId: String,
    
    // Tarification
    @SerialName("price_monthly_ht")
    val priceMonthlyHT: Double = LICENSE_PRICE_HT,
    @SerialName("vat_rate")
    val vatRate: Double = VAT_RATE,
    
    // Statut
    val status: LicenseStatus = LicenseStatus.PENDING,
    @SerialName("start_date")
    val startDate: Instant? = null,
    @SerialName("end_date")
    val endDate: Instant? = null,
    
    // Stripe
    @SerialName("stripe_subscription_id")
    val stripeSubscriptionId: String? = null,
    @SerialName("stripe_subscription_item_id")
    val stripeSubscriptionItemId: String? = null,
    @SerialName("stripe_price_id")
    val stripePriceId: String? = null,
    
    // Métadonnées
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    val updatedAt: Instant
) {
    companion object {
        const val LICENSE_PRICE_HT = 5.0    // 5€ HT par utilisateur par mois
        const val VAT_RATE = 0.20           // TVA 20%
    }
    
    /**
     * Prix TTC (HT + TVA)
     */
    val priceMonthlyTTC: Double
        get() = priceMonthlyHT * (1 + vatRate)
    
    /**
     * Montant de TVA
     */
    val vatAmount: Double
        get() = priceMonthlyHT * vatRate
    
    /**
     * Vérifie si la licence est active et valide
     */
    val isActive: Boolean
        get() = status == LicenseStatus.ACTIVE && !isExpired
    
    /**
     * Vérifie si la licence a expiré
     */
    val isExpired: Boolean
        get() = endDate?.let { end ->
            Instant.fromEpochMilliseconds(System.currentTimeMillis()) > end
        } ?: false
    
    /**
     * Jours restants avant expiration (null si pas de date de fin)
     */
    val daysRemaining: Int?
        get() = endDate?.let { end ->
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val diff = end.toEpochMilliseconds() - now.toEpochMilliseconds()
            (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        }
}

/**
 * Statut d'une licence
 */
@Serializable
enum class LicenseStatus {
    @SerialName("pending")
    PENDING,     // En attente de paiement ou d'activation
    
    @SerialName("active")
    ACTIVE,      // Licence active et payée
    
    @SerialName("expired")
    EXPIRED,     // Licence expirée (non renouvelée)
    
    @SerialName("cancelled")
    CANCELLED    // Licence annulée par le Pro
}

/**
 * Résumé des licences pour affichage dans l'écran de gestion
 */
data class LicensesSummary(
    val totalLicenses: Int,
    val activeLicenses: Int,
    val pendingLicenses: Int,
    val expiredLicenses: Int,
    val monthlyTotalHT: Double,
    val monthlyTotalTTC: Double,
    val monthlyVAT: Double
) {
    companion object {
        fun fromLicenses(licenses: List<License>): LicensesSummary {
            val active = licenses.count { it.status == LicenseStatus.ACTIVE }
            val pending = licenses.count { it.status == LicenseStatus.PENDING }
            val expired = licenses.count { it.status == LicenseStatus.EXPIRED }
            val totalHT = licenses.filter { it.isActive }.sumOf { it.priceMonthlyHT }
            
            return LicensesSummary(
                totalLicenses = licenses.size,
                activeLicenses = active,
                pendingLicenses = pending,
                expiredLicenses = expired,
                monthlyTotalHT = totalHT,
                monthlyTotalTTC = totalHT * (1 + License.VAT_RATE),
                monthlyVAT = totalHT * License.VAT_RATE
            )
        }
    }
}

package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Représente une licence Pro pour un utilisateur lié
 * Tarif mensuel: 5€ HT/mois par utilisateur (6€ TTC avec TVA 20%)
 * Tarif à vie: 100€ HT par utilisateur (120€ TTC avec TVA 20%)
 *
 * Système de pool: Les licences sont achetées et vont dans un pool,
 * puis sont assignées aux comptes liés individuellement.
 * Déliaison avec préavis de 30 jours.
 */
@Serializable
data class License(
    val id: String,
    @SerialName("pro_account_id")
    val proAccountId: String,

    // Nullable: licence dans le pool si null
    @SerialName("linked_account_id")
    val linkedAccountId: String? = null,

    // Timestamp de liaison (pour le cooldown de déliaison)
    @SerialName("linked_at")
    val linkedAt: Instant? = null,

    // Flag licence à vie
    @SerialName("is_lifetime")
    val isLifetime: Boolean = false,

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

    // Déliaison avec préavis 30 jours
    @SerialName("unlink_requested_at")
    val unlinkRequestedAt: Instant? = null,
    @SerialName("unlink_effective_at")
    val unlinkEffectiveAt: Instant? = null,

    // Date de début de facturation (pour les nouvelles licences)
    @SerialName("billing_starts_at")
    val billingStartsAt: Instant? = null,

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
        const val LICENSE_PRICE_HT = 5.0           // 5€ HT par utilisateur par mois
        const val LICENSE_LIFETIME_PRICE_HT = 100.0 // 100€ HT par utilisateur à vie
        const val VAT_RATE = 0.20                   // TVA 20%
        const val UNLINK_NOTICE_DAYS = 30          // Préavis de déliaison en jours
    }

    /**
     * Vérifie si la licence est assignée à un compte
     */
    val isAssigned: Boolean
        get() = !linkedAccountId.isNullOrEmpty()

    /**
     * Vérifie si une demande de déliaison est en cours (préavis de 30 jours)
     */
    val isPendingUnlink: Boolean
        get() = unlinkRequestedAt != null && unlinkEffectiveAt != null &&
                Instant.fromEpochMilliseconds(System.currentTimeMillis()) < unlinkEffectiveAt

    /**
     * Jours restants avant que la déliaison prenne effet (null si pas de déliaison en cours)
     */
    val daysUntilUnlinkEffective: Int?
        get() {
            if (!isPendingUnlink || unlinkEffectiveAt == null) return null
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val diff = unlinkEffectiveAt.toEpochMilliseconds() - now.toEpochMilliseconds()
            return (diff / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        }

    /**
     * Statut effectif de la licence (combiné pour l'UI)
     */
    val effectiveStatus: LicenseEffectiveStatus
        get() = when {
            status != LicenseStatus.ACTIVE -> when (status) {
                LicenseStatus.PENDING -> LicenseEffectiveStatus.PENDING_PAYMENT
                LicenseStatus.EXPIRED -> LicenseEffectiveStatus.EXPIRED
                LicenseStatus.CANCELLED -> LicenseEffectiveStatus.CANCELLED
                else -> LicenseEffectiveStatus.INACTIVE
            }
            !isAssigned -> LicenseEffectiveStatus.AVAILABLE
            isPendingUnlink -> LicenseEffectiveStatus.PENDING_UNLINK
            else -> LicenseEffectiveStatus.ACTIVE
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

    /**
     * Prix TTC pour licence à vie
     */
    val priceLifetimeTTC: Double
        get() = LICENSE_LIFETIME_PRICE_HT * (1 + vatRate)

    /**
     * Vérifie si une demande de déliaison peut être faite
     * Retourne true si la licence est assignée et pas déjà en cours de déliaison
     */
    fun canRequestUnlink(): Boolean {
        return isAssigned && !isPendingUnlink && status == LicenseStatus.ACTIVE
    }

    /**
     * Vérifie si la demande de déliaison peut être annulée
     */
    fun canCancelUnlinkRequest(): Boolean {
        return isPendingUnlink
    }
}

/**
 * Statut d'une licence (stocké en base)
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
 * Statut effectif d'une licence (calculé pour l'UI)
 * Combine le statut de base avec l'état d'assignation et de déliaison
 */
enum class LicenseEffectiveStatus {
    AVAILABLE,       // Dans le pool, prête à être assignée
    ACTIVE,          // Assignée à un compte et active
    PENDING_UNLINK,  // En préavis de déliaison (30 jours)
    PENDING_PAYMENT, // En attente de paiement
    EXPIRED,         // Licence expirée
    CANCELLED,       // Licence annulée
    INACTIVE         // Autre état inactif
}

/**
 * Résumé des licences pour affichage dans l'écran de gestion
 */
data class LicensesSummary(
    val totalLicenses: Int,
    val availableLicenses: Int,      // Dans le pool, non assignées
    val assignedLicenses: Int,       // Assignées à un compte
    val pendingUnlinkLicenses: Int,  // En préavis de déliaison
    val pendingPaymentLicenses: Int, // En attente de paiement
    val expiredLicenses: Int,
    val lifetimeLicenses: Int,       // Licences à vie
    val monthlyLicenses: Int,        // Licences mensuelles
    val monthlyTotalHT: Double,
    val monthlyTotalTTC: Double,
    val monthlyVAT: Double
) {
    companion object {
        fun fromLicenses(licenses: List<License>): LicensesSummary {
            val available = licenses.count { it.effectiveStatus == LicenseEffectiveStatus.AVAILABLE }
            val assigned = licenses.count { it.isAssigned && it.status == LicenseStatus.ACTIVE }
            val pendingUnlink = licenses.count { it.effectiveStatus == LicenseEffectiveStatus.PENDING_UNLINK }
            val pendingPayment = licenses.count { it.status == LicenseStatus.PENDING }
            val expired = licenses.count { it.status == LicenseStatus.EXPIRED }
            val lifetime = licenses.count { it.isLifetime && it.status == LicenseStatus.ACTIVE }
            val monthly = licenses.count { !it.isLifetime && it.status == LicenseStatus.ACTIVE }

            // Calcul du coût mensuel (seulement les licences mensuelles actives)
            val monthlyActiveLicenses = licenses.filter { !it.isLifetime && it.status == LicenseStatus.ACTIVE }
            val totalHT = monthlyActiveLicenses.sumOf { it.priceMonthlyHT }

            return LicensesSummary(
                totalLicenses = licenses.size,
                availableLicenses = available,
                assignedLicenses = assigned,
                pendingUnlinkLicenses = pendingUnlink,
                pendingPaymentLicenses = pendingPayment,
                expiredLicenses = expired,
                lifetimeLicenses = lifetime,
                monthlyLicenses = monthly,
                monthlyTotalHT = totalHT,
                monthlyTotalTTC = totalHT * (1 + License.VAT_RATE),
                monthlyVAT = totalHT * License.VAT_RATE
            )
        }
    }
}

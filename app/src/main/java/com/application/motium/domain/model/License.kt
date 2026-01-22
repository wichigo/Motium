package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Représente une licence Pro pour un utilisateur lié
 * Tarif mensuel: 5€ HT/mois par utilisateur (6€ TTC avec TVA 20%)
 * Tarif à vie: 120€ HT par utilisateur (144€ TTC avec TVA 20%)
 *
 * Système de pool: Les licences sont achetées et vont dans un pool,
 * puis sont assignées aux comptes liés individuellement.
 *
 * Résiliation/Déliaison:
 * - Licences mensuelles: résiliation effective à la date de renouvellement (endDate)
 * - Licences lifetime: déliaison immédiate
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
    val status: LicenseStatus = LicenseStatus.AVAILABLE,
    @SerialName("start_date")
    val startDate: Instant? = null,
    @SerialName("end_date")
    val endDate: Instant? = null,

    // Déliaison (effective à endDate pour mensuelle, immédiate pour lifetime)
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
    @SerialName("stripe_subscription_ref")
    val stripeSubscriptionRef: String? = null,  // FK vers stripe_subscriptions.id (UUID)
    @SerialName("stripe_payment_intent_id")
    val stripePaymentIntentId: String? = null,  // Pour les paiements lifetime

    // Métadonnées
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    val updatedAt: Instant
) {
    companion object {
        const val LICENSE_PRICE_HT = 5.0           // 5€ HT par utilisateur par mois
        const val LICENSE_LIFETIME_PRICE_HT = 120.0 // 120€ HT par utilisateur à vie
        const val VAT_RATE = 0.20                   // TVA 20%
    }

    /**
     * Vérifie si la licence est assignée à un compte
     */
    val isAssigned: Boolean
        get() = !linkedAccountId.isNullOrEmpty()

    /**
     * Vérifie si une demande de déliaison/résiliation est en cours.
     * La licence reste active jusqu'à unlinkEffectiveAt (= endDate pour mensuelle, now pour lifetime).
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [isPendingUnlinkWithTrustedTime].
     */
    val isPendingUnlink: Boolean
        get() = unlinkRequestedAt != null && unlinkEffectiveAt != null &&
                Instant.fromEpochMilliseconds(System.currentTimeMillis()) < unlinkEffectiveAt

    /**
     * SECURE version: Check if unlink is pending using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if pending, false if not pending, null if time not trusted
     */
    fun isPendingUnlinkWithTrustedTime(trustedTimeMs: Long?): Boolean? {
        if (trustedTimeMs == null) return null
        if (unlinkRequestedAt == null || unlinkEffectiveAt == null) return false
        return Instant.fromEpochMilliseconds(trustedTimeMs) < unlinkEffectiveAt
    }

    /**
     * Jours restants avant que la déliaison prenne effet (null si pas de déliaison en cours)
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [daysUntilUnlinkEffectiveWithTrustedTime].
     */
    val daysUntilUnlinkEffective: Int?
        get() {
            if (!isPendingUnlink || unlinkEffectiveAt == null) return null
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val diff = unlinkEffectiveAt.toEpochMilliseconds() - now.toEpochMilliseconds()
            return (diff / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        }

    /**
     * SECURE version: Days until unlink using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return days remaining, or null if not pending or time not trusted
     */
    fun daysUntilUnlinkEffectiveWithTrustedTime(trustedTimeMs: Long?): Int? {
        if (trustedTimeMs == null) return null
        if (isPendingUnlinkWithTrustedTime(trustedTimeMs) != true || unlinkEffectiveAt == null) return null
        val now = Instant.fromEpochMilliseconds(trustedTimeMs)
        val diff = unlinkEffectiveAt.toEpochMilliseconds() - now.toEpochMilliseconds()
        return (diff / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
    }

    /**
     * Statut effectif de la licence (combiné pour l'UI)
     */
    val effectiveStatus: LicenseEffectiveStatus
        get() = when (status) {
            LicenseStatus.AVAILABLE -> LicenseEffectiveStatus.AVAILABLE
            LicenseStatus.SUSPENDED -> LicenseEffectiveStatus.SUSPENDED
            LicenseStatus.CANCELED -> LicenseEffectiveStatus.CANCELLED
            LicenseStatus.UNLINKED -> LicenseEffectiveStatus.PENDING_UNLINK
            LicenseStatus.ACTIVE -> when {
                !isAssigned -> LicenseEffectiveStatus.AVAILABLE
                isPendingUnlink -> LicenseEffectiveStatus.PENDING_UNLINK
                else -> LicenseEffectiveStatus.ACTIVE
            }
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
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [isActiveWithTrustedTime].
     */
    val isActive: Boolean
        get() = status == LicenseStatus.ACTIVE && !isExpired

    /**
     * SECURE version: Check if license is active using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if active, false if not active, null if time not trusted
     */
    fun isActiveWithTrustedTime(trustedTimeMs: Long?): Boolean? {
        if (trustedTimeMs == null) return null
        return status == LicenseStatus.ACTIVE && (isExpiredWithTrustedTime(trustedTimeMs) == false)
    }

    /**
     * FAIL-SECURE version: Returns false if time is not trusted.
     * Use this for security gates where denying access is safer.
     */
    fun isActiveFailSecure(trustedTimeMs: Long?): Boolean {
        return isActiveWithTrustedTime(trustedTimeMs) ?: false
    }

    /**
     * Vérifie si la licence a expiré
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [isExpiredWithTrustedTime].
     */
    val isExpired: Boolean
        get() = endDate?.let { end ->
            Instant.fromEpochMilliseconds(System.currentTimeMillis()) > end
        } ?: false

    /**
     * SECURE version: Check if license is expired using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if expired, false if not expired, null if time not trusted
     */
    fun isExpiredWithTrustedTime(trustedTimeMs: Long?): Boolean? {
        if (trustedTimeMs == null) return null
        return endDate?.let { end ->
            Instant.fromEpochMilliseconds(trustedTimeMs) > end
        } ?: false
    }

    /**
     * FAIL-SECURE version: Returns true (expired) if time is not trusted.
     * Use this for security gates where denying access is safer.
     */
    fun isExpiredFailSecure(trustedTimeMs: Long?): Boolean {
        return isExpiredWithTrustedTime(trustedTimeMs) ?: true
    }

    /**
     * Jours restants avant expiration (null si pas de date de fin)
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [daysRemainingWithTrustedTime].
     */
    val daysRemaining: Int?
        get() = endDate?.let { end ->
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val diff = end.toEpochMilliseconds() - now.toEpochMilliseconds()
            (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        }

    /**
     * SECURE version: Days remaining using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return days remaining, or null if no end date or time not trusted
     */
    fun daysRemainingWithTrustedTime(trustedTimeMs: Long?): Int? {
        if (trustedTimeMs == null) return null
        return endDate?.let { end ->
            val now = Instant.fromEpochMilliseconds(trustedTimeMs)
            val diff = end.toEpochMilliseconds() - now.toEpochMilliseconds()
            (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        }
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

    /**
     * Vérifie si la licence peut être supprimée définitivement.
     * Seules les licences mensuelles NON assignées peuvent être supprimées.
     * Les licences assignées, lifetime ou en cours de déliaison ne peuvent pas être supprimées.
     */
    fun canDelete(): Boolean {
        return !isAssigned && !isPendingUnlink && !isLifetime &&
            (status == LicenseStatus.ACTIVE || status == LicenseStatus.AVAILABLE)
    }

    /**
     * Vérifie si la licence peut être résiliée.
     * Seules les licences mensuelles actives et assignées peuvent être résiliées.
     */
    fun canCancel(): Boolean {
        return isAssigned && status == LicenseStatus.ACTIVE && !isLifetime
    }

    /**
     * Vérifie si la licence peut être liée à un compte.
     * Seules les licences disponibles (non assignées) peuvent être liées.
     */
    fun canLink(): Boolean {
        return !isAssigned && (status == LicenseStatus.ACTIVE || status == LicenseStatus.AVAILABLE)
    }

    /**
     * Vérifie si la licence lifetime peut être déliée.
     * Seules les licences lifetime assignées peuvent être déliées.
     */
    fun canUnlink(): Boolean {
        return isAssigned && isLifetime && status == LicenseStatus.ACTIVE
    }
}

/**
 * Statut d'une licence (stocké en base)
 *
 * Conformément aux spécifications:
 * - available: Dans le pool, non attribuée
 * - active: Attribuée à un collaborateur
 * - suspended: Impayé du compte pro (mensuelles uniquement)
 * - canceled: Résiliée (date de résiliation = date de renouvellement prévue)
 * - unlinked: Délinkage, bloquée jusqu'à date groupée
 */
@Serializable
enum class LicenseStatus {
    @SerialName("available")
    AVAILABLE,   // Dans le pool, non attribuée, prête à être assignée

    @SerialName("active")
    ACTIVE,      // Licence active et assignée à un collaborateur

    @SerialName("suspended")
    SUSPENDED,   // Impayé du compte pro (mensuelles uniquement)

    @SerialName("canceled")
    CANCELED,    // Résiliée (date de résiliation = date de renouvellement prévue)

    @SerialName("unlinked")
    UNLINKED;    // Délinkage en cours, bloquée jusqu'à date groupée

    companion object {
        /**
         * Parse le statut depuis la base de données avec gestion des legacy values.
         * Logs unknown values to help identify data inconsistencies.
         */
        fun fromDbValue(value: String): LicenseStatus {
            val normalized = value.lowercase().trim()
            return when (normalized) {
                "available" -> AVAILABLE
                "active" -> ACTIVE
                "suspended" -> SUSPENDED
                "canceled", "cancelled" -> CANCELED  // Support both spellings
                "unlinked" -> UNLINKED
                // Legacy mappings - paused devient available (migration)
                "paused" -> {
                    android.util.Log.w("LicenseStatus", "Legacy status 'paused' encountered, mapping to AVAILABLE")
                    AVAILABLE
                }
                "pending" -> {
                    android.util.Log.w("LicenseStatus", "Legacy status 'pending' encountered, mapping to SUSPENDED")
                    SUSPENDED
                }
                "expired" -> {
                    android.util.Log.w("LicenseStatus", "Legacy status 'expired' encountered, mapping to SUSPENDED")
                    SUSPENDED
                }
                else -> {
                    android.util.Log.e(
                        "LicenseStatus",
                        "Unknown license status '$value' encountered - defaulting to AVAILABLE. " +
                        "This may indicate data corruption or a new status not handled by the app."
                    )
                    AVAILABLE
                }
            }
        }
    }
}

/**
 * Statut effectif d'une licence (calculé pour l'UI)
 * Combine le statut de base avec l'état d'assignation et de déliaison
 */
enum class LicenseEffectiveStatus {
    AVAILABLE,       // Dans le pool, prête à être assignée
    ACTIVE,          // Assignée à un compte et active
    PENDING_UNLINK,  // En préavis de déliaison / status UNLINKED
    SUSPENDED,       // Impayé du compte pro
    CANCELLED,       // Licence résiliée (canceled)
    INACTIVE         // Autre état inactif
}

/**
 * Résumé des licences pour affichage dans l'écran de gestion
 */
data class LicensesSummary(
    val totalLicenses: Int,
    val availableLicenses: Int,      // Dans le pool, non assignées (status AVAILABLE ou ACTIVE sans linked_account_id)
    val assignedLicenses: Int,       // Assignées à un compte
    val pendingUnlinkLicenses: Int,  // En préavis de déliaison (status UNLINKED)
    val suspendedLicenses: Int,      // Impayé du compte pro (status SUSPENDED)
    val canceledLicenses: Int,       // Résiliées (status CANCELED)
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
            val pendingUnlink = licenses.count { it.status == LicenseStatus.UNLINKED }
            val suspended = licenses.count { it.status == LicenseStatus.SUSPENDED }
            val canceled = licenses.count { it.status == LicenseStatus.CANCELED }
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
                suspendedLicenses = suspended,
                canceledLicenses = canceled,
                lifetimeLicenses = lifetime,
                monthlyLicenses = monthly,
                monthlyTotalHT = totalHT,
                monthlyTotalTTC = totalHT * (1 + License.VAT_RATE),
                monthlyVAT = totalHT * License.VAT_RATE
            )
        }
    }
}

package com.application.motium.domain.model

import kotlinx.datetime.Instant

data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    val subscription: Subscription,
    val phoneNumber: String = "",
    val address: String = "",

    // Device fingerprint pour anti-abus
    val deviceFingerprintId: String? = null,

    // Paramètre fiscal : prendre en compte toute la distance travail-maison (sans plafond 40km)
    val considerFullDistance: Boolean = false,

    // Couleurs favorites de l'utilisateur (pour personnalisation UI)
    val favoriteColors: List<String> = emptyList(),

    val createdAt: Instant,
    val updatedAt: Instant
)
// Note: Les champs de liaison Pro (linkedProAccountId, linkStatus, préférences de partage, etc.)
// sont maintenant gérés dans la table company_links et le modèle CompanyLink

/** Statut de la liaison avec un compte Pro */
enum class LinkStatus {
    PENDING,             // Invitation envoyée, en attente d'acceptation
    PENDING_ACTIVATION,  // Activation en cours (offline-first, en attente de sync)
    PENDING_UNLINK,      // Demande de déliaison en cours (email de confirmation envoyé)
    ACTIVE,              // Liaison active, compte lié et fonctionnel
    INACTIVE,            // Utilisateur s'est délié (conserve contact mais perd accès aux trajets)
    REVOKED              // Liaison révoquée par le Pro
}

data class Subscription(
    val type: SubscriptionType,
    val expiresAt: Instant?,
    val trialStartedAt: Instant? = null,
    val trialEndsAt: Instant? = null,
    val stripeCustomerId: String? = null,
    val stripeSubscriptionId: String? = null
) {
    /**
     * Check if subscription/trial is active (not expired)
     *
     * IMPORTANT: This uses ONLY these fields for expiration checks:
     * - TRIAL: uses trialEndsAt (from users.trial_ends_at)
     * - PREMIUM/LICENSED: uses expiresAt (from users.subscription_expires_at)
     * - LIFETIME: never expires (expiresAt ignored)
     */
    fun isActive(): Boolean {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return when (type) {
            SubscriptionType.TRIAL -> trialEndsAt?.let { now < it } ?: false
            SubscriptionType.EXPIRED -> false
            SubscriptionType.LIFETIME -> true
            // LICENSED: If expiresAt is null, it's a lifetime license (always active)
            // If expiresAt is set (from licenses.end_date), check if not expired
            SubscriptionType.LICENSED -> expiresAt?.let { now < it } ?: true
            SubscriptionType.PREMIUM -> expiresAt?.let { now < it } ?: false
        }
    }

    /**
     * Check if user is currently in trial period
     */
    fun isInTrial(): Boolean = type == SubscriptionType.TRIAL && isActive()

    /**
     * Get days remaining in trial (null if not in trial)
     */
    fun daysLeftInTrial(): Int? {
        if (type != SubscriptionType.TRIAL || trialEndsAt == null) return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val diffMs = trialEndsAt.toEpochMilliseconds() - now.toEpochMilliseconds()
        return (diffMs / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
    }

    /**
     * Check if user has valid access (active trial or subscription)
     */
    fun hasValidAccess(): Boolean = isActive()

    /**
     * Check if user can export data (all active subscriptions can export)
     */
    fun canExport(): Boolean = isActive()

    /**
     * Check if user has unlimited trips (all active subscriptions have unlimited)
     */
    fun hasUnlimitedTrips(): Boolean = isActive()
}

enum class UserRole(val displayName: String) {
    INDIVIDUAL("Individual"),
    ENTERPRISE("Enterprise")
}

enum class SubscriptionType(val displayName: String) {
    TRIAL("Essai gratuit"),    // 7-day free trial with full access
    EXPIRED("Expiré"),         // Trial or subscription expired - no access
    PREMIUM("Premium"),        // Monthly subscription
    LIFETIME("À vie"),         // One-time lifetime purchase
    LICENSED("Licence Pro");   // Pro user with assigned license (via licenses table)

    companion object {
        /**
         * Parse subscription type from string, with fallback for legacy FREE type
         */
        fun fromString(value: String): SubscriptionType {
            // Normalize: remove whitespace and uppercase
            val normalized = value.trim().uppercase()
            
            return when (normalized) {
                "FREE" -> TRIAL
                "TRIAL" -> TRIAL
                // Stripe identifiers
                "INDIVIDUAL_MONTHLY" -> PREMIUM
                "INDIVIDUAL_LIFETIME" -> LIFETIME
                "PRO_LICENSE_MONTHLY" -> LICENSED
                "PRO_LICENSE_LIFETIME" -> LICENSED
                // French/User variants
                "MENSUEL" -> PREMIUM 
                "LICENSED" -> LICENSED
                "PREMIUM" -> PREMIUM
                "LIFETIME" -> LIFETIME
                else -> try {
                    valueOf(normalized)
                } catch (e: IllegalArgumentException) {
                    EXPIRED
                }
            }
        }
    }
}
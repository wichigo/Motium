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

    // Version for optimistic locking (synced from server to prevent VERSION_CONFLICT)
    val version: Int = 1,

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
     * ⚠️ SECURITY WARNING: This method uses System.currentTimeMillis() which can be
     * manipulated by the user. For security-critical operations, use [isActiveWithTrustedTime]
     * with a timestamp from TrustedTimeProvider instead.
     *
     * IMPORTANT: This uses ONLY these fields for expiration checks:
     * - TRIAL: uses trialEndsAt (from users.trial_ends_at)
     * - PREMIUM/LICENSED: uses expiresAt (from users.subscription_expires_at)
     * - LIFETIME: never expires (expiresAt ignored)
     */
    fun isActive(): Boolean {
        return isActiveAt(Instant.fromEpochMilliseconds(System.currentTimeMillis()))
    }

    /**
     * Check if subscription/trial is active at the given time.
     * Use this with TrustedTimeProvider for security-critical checks.
     *
     * @param now The current time to check against (should come from TrustedTimeProvider)
     * @return true if subscription is active at the given time
     */
    fun isActiveAt(now: Instant): Boolean {
        return when (type) {
            // TRIAL: SECURITY FIX - Si trialEndsAt est null, on considère le trial comme expiré
            // Cela force une vérification serveur pour définir la date de fin
            // Avant: null -> true (vulnérabilité: trial infini)
            // Après: null -> false (fail-secure)
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
     * SECURE version: Check if subscription is active using trusted time.
     * Returns null if time cannot be trusted (requires network sync).
     *
     * @param trustedTimeMs Trusted time in milliseconds from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if active, false if expired, null if time is untrusted
     */
    fun isActiveWithTrustedTime(trustedTimeMs: Long?): Boolean? {
        if (trustedTimeMs == null) {
            // Time is not trusted - caller should force network sync
            return null
        }
        return isActiveAt(Instant.fromEpochMilliseconds(trustedTimeMs))
    }

    /**
     * FAIL-SECURE version: Check if subscription is active.
     * If time cannot be trusted, returns false (denies access).
     *
     * Use this for security-critical gates where denying access is safer than granting it.
     *
     * @param trustedTimeMs Trusted time in milliseconds from TrustedTimeProvider.getTrustedTimeMs()
     * @return true only if active AND time is trusted, false otherwise
     */
    fun isActiveFailSecure(trustedTimeMs: Long?): Boolean {
        return isActiveWithTrustedTime(trustedTimeMs) ?: false
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
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [hasValidAccessSecure].
     */
    fun hasValidAccess(): Boolean = isActive()

    /**
     * SECURE version: Check if user has valid access using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if access is valid, false if not, null if time not trusted
     */
    fun hasValidAccessWithTrustedTime(trustedTimeMs: Long?): Boolean? {
        return isActiveWithTrustedTime(trustedTimeMs)
    }

    /**
     * FAIL-SECURE version: Returns false if time is not trusted.
     * Use this for security gates where denying access is safer.
     */
    fun hasValidAccessSecure(trustedTimeMs: Long?): Boolean {
        return isActiveFailSecure(trustedTimeMs)
    }

    /**
     * Check if user can export data (all active subscriptions can export)
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [canExportSecure].
     */
    fun canExport(): Boolean = isActive()

    /**
     * SECURE version: Check if user can export using trusted time.
     * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
     * @return true if can export, false if not, null if time not trusted
     */
    fun canExportWithTrustedTime(trustedTimeMs: Long?): Boolean? {
        return isActiveWithTrustedTime(trustedTimeMs)
    }

    /**
     * FAIL-SECURE version: Returns false if time is not trusted.
     * Use this for security gates where denying access is safer.
     */
    fun canExportSecure(trustedTimeMs: Long?): Boolean {
        return isActiveFailSecure(trustedTimeMs)
    }

    /**
     * Check if user has unlimited trips (all active subscriptions have unlimited)
     *
     * ⚠️ SECURITY WARNING: Uses System.currentTimeMillis() for UI display purposes.
     * For security-critical checks, use [hasUnlimitedTripsSecure].
     */
    fun hasUnlimitedTrips(): Boolean = isActive()

    /**
     * FAIL-SECURE version: Returns false if time is not trusted.
     * Use this for security gates where denying access is safer.
     */
    fun hasUnlimitedTripsSecure(trustedTimeMs: Long?): Boolean {
        return isActiveFailSecure(trustedTimeMs)
    }
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
         * Parse subscription type from string, with fallback for legacy FREE type.
         * Logs unknown values for monitoring data inconsistencies.
         */
        fun fromString(value: String): SubscriptionType {
            // Normalize: remove whitespace and uppercase
            val normalized = value.trim().uppercase()

            return when (normalized) {
                "FREE" -> {
                    // Log legacy FREE usage - should have been migrated to TRIAL
                    android.util.Log.w("SubscriptionType", "Legacy 'FREE' type encountered, mapping to TRIAL")
                    TRIAL
                }
                "TRIAL" -> TRIAL
                "EXPIRED" -> EXPIRED
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
                    // Log unknown type - helps identify data corruption or new types
                    android.util.Log.e(
                        "SubscriptionType",
                        "Unknown subscription type '$value' encountered - defaulting to EXPIRED. " +
                        "This may indicate data corruption or a new type not handled by the app."
                    )
                    EXPIRED
                }
            }
        }
    }
}
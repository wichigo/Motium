package com.application.motium.domain.model

import kotlinx.datetime.Instant

data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    val subscription: Subscription,
    val monthlyTripCount: Int = 0,
    val phoneNumber: String = "",
    val address: String = "",

    // Liaison avec un compte Pro (pour les utilisateurs Individual)
    val linkedProAccountId: String? = null,  // ID du compte Pro auquel l'utilisateur est lié
    val linkStatus: LinkStatus? = null,      // pending/active/revoked
    val invitationToken: String? = null,     // Token d'invitation (si pending)
    val invitedAt: Instant? = null,          // Date d'invitation
    val linkActivatedAt: Instant? = null,    // Date d'activation du lien

    // Préférences de partage (contrôlées par l'utilisateur Individual)
    val shareProfessionalTrips: Boolean = true,  // Pro voit les trajets pro
    val sharePersonalTrips: Boolean = false,     // Pro ne voit PAS les trajets perso par défaut
    val shareVehicleInfo: Boolean = true,        // Pro voit les véhicules
    val shareExpenses: Boolean = false,          // Pro ne voit PAS les dépenses par défaut

    // Paramètre fiscal : prendre en compte toute la distance travail-maison (sans plafond 40km)
    val considerFullDistance: Boolean = false,

    val createdAt: Instant,
    val updatedAt: Instant
) {
    /** L'utilisateur est-il lié à un compte Pro ? */
    val isLinkedToCompany: Boolean
        get() = linkedProAccountId != null && linkStatus == LinkStatus.ACTIVE
}

/** Statut de la liaison avec un compte Pro */
enum class LinkStatus {
    PENDING,   // Invitation envoyée, en attente d'acceptation
    ACTIVE,    // Liaison active, compte lié et fonctionnel
    UNLINKED,  // Utilisateur s'est délié (conserve contact mais perd accès aux trajets)
    REVOKED    // Liaison révoquée par le Pro
}

data class Subscription(
    val type: SubscriptionType,
    val expiresAt: Instant?,
    val stripeCustomerId: String? = null,
    val stripeSubscriptionId: String? = null
) {
    /**
     * Check if subscription is active (not expired)
     */
    fun isActive(): Boolean {
        return when (type) {
            SubscriptionType.FREE -> true // Free is always "active"
            SubscriptionType.LIFETIME -> true // Lifetime never expires
            SubscriptionType.PREMIUM -> expiresAt?.let { expiry ->
                Instant.fromEpochMilliseconds(System.currentTimeMillis()) < expiry
            } ?: false
        }
    }

    /**
     * Check if user can export data
     */
    fun canExport(): Boolean = type.canExport() && isActive()

    /**
     * Check if user has unlimited trips
     */
    fun hasUnlimitedTrips(): Boolean = type.hasUnlimitedTrips() && isActive()

    /**
     * Get trip limit (null = unlimited)
     */
    fun getTripLimit(): Int? = if (isActive()) type.tripLimit else SubscriptionType.FREE.tripLimit
}

enum class UserRole(val displayName: String) {
    INDIVIDUAL("Individual"),
    ENTERPRISE("Enterprise")
}

enum class SubscriptionType(val displayName: String, val tripLimit: Int?) {
    FREE("Gratuit", 20),
    PREMIUM("Premium", null),
    LIFETIME("À vie", null);

    /**
     * Check if this subscription type allows export functionality
     */
    fun canExport(): Boolean = this != FREE

    /**
     * Check if this subscription type has unlimited trips
     */
    fun hasUnlimitedTrips(): Boolean = tripLimit == null
}
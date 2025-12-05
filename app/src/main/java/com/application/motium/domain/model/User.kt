package com.application.motium.domain.model

import kotlinx.datetime.Instant

data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    val organizationId: String?,
    val organizationName: String?,
    val subscription: Subscription,
    val monthlyTripCount: Int = 0,
    val phoneNumber: String = "",
    val address: String = "",
    val linkedToCompany: Boolean = false,
    val shareProfessionalTrips: Boolean = true,
    val sharePersonalTrips: Boolean = false,
    val sharePersonalInfo: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant
)

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
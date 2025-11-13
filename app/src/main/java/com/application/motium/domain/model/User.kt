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
    val expiresAt: Instant?
)

enum class UserRole(val displayName: String) {
    INDIVIDUAL("Individual"),
    ENTERPRISE("Enterprise")
}

enum class SubscriptionType(val displayName: String, val tripLimit: Int?) {
    FREE("Gratuit", 10),
    PREMIUM("Premium", null),
    LIFETIME("À vie", null)
}
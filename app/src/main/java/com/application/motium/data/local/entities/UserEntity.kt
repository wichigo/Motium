package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.*
import kotlinx.datetime.Instant

/**
 * Room entity for storing user data locally.
 * This allows the app to work offline and maintain user session without Supabase connectivity.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val email: String,
    val role: String, // UserRole enum stored as String
    val organizationId: String?,
    val organizationName: String?,
    val subscriptionType: String, // SubscriptionType enum stored as String
    val subscriptionExpiresAt: String?, // Instant stored as ISO-8601 string
    val stripeCustomerId: String? = null, // Stripe customer ID for payments
    val stripeSubscriptionId: String? = null, // Stripe subscription ID
    val monthlyTripCount: Int,
    val phoneNumber: String,
    val address: String,
    val linkedToCompany: Boolean,
    val shareProfessionalTrips: Boolean,
    val sharePersonalTrips: Boolean,
    val sharePersonalInfo: Boolean,
    val createdAt: String, // Instant stored as ISO-8601 string
    val updatedAt: String, // Instant stored as ISO-8601 string
    val lastSyncedAt: Long? = null, // Timestamp of last sync with Supabase
    val isLocallyConnected: Boolean = true // Flag to indicate user is logged in locally
)

/**
 * Extension function to convert UserEntity to domain User model
 */
fun UserEntity.toDomainModel(): User {
    return User(
        id = id,
        name = name,
        email = email,
        role = UserRole.valueOf(role),
        organizationId = organizationId,
        organizationName = organizationName,
        subscription = Subscription(
            type = SubscriptionType.valueOf(subscriptionType),
            expiresAt = subscriptionExpiresAt?.let { Instant.parse(it) },
            stripeCustomerId = stripeCustomerId,
            stripeSubscriptionId = stripeSubscriptionId
        ),
        monthlyTripCount = monthlyTripCount,
        phoneNumber = phoneNumber,
        address = address,
        linkedToCompany = linkedToCompany,
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = sharePersonalInfo,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain User to UserEntity
 */
fun User.toEntity(lastSyncedAt: Long? = null, isLocallyConnected: Boolean = true): UserEntity {
    return UserEntity(
        id = id,
        name = name,
        email = email,
        role = role.name,
        organizationId = organizationId,
        organizationName = organizationName,
        subscriptionType = subscription.type.name,
        subscriptionExpiresAt = subscription.expiresAt?.toString(),
        stripeCustomerId = subscription.stripeCustomerId,
        stripeSubscriptionId = subscription.stripeSubscriptionId,
        monthlyTripCount = monthlyTripCount,
        phoneNumber = phoneNumber,
        address = address,
        linkedToCompany = linkedToCompany,
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = sharePersonalInfo,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        isLocallyConnected = isLocallyConnected
    )
}

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
    val subscriptionType: String, // SubscriptionType enum stored as String
    val subscriptionExpiresAt: String?, // Instant stored as ISO-8601 string
    val stripeCustomerId: String? = null, // Stripe customer ID for payments
    val stripeSubscriptionId: String? = null, // Stripe subscription ID
    val monthlyTripCount: Int,
    val phoneNumber: String,
    val address: String,
    // Pro link fields
    val linkedProAccountId: String? = null,
    val linkStatus: String? = null, // LinkStatus enum stored as String
    val invitationToken: String? = null,
    val invitedAt: String? = null,
    val linkActivatedAt: String? = null,
    // Sharing preferences
    val shareProfessionalTrips: Boolean,
    val sharePersonalTrips: Boolean,
    val shareVehicleInfo: Boolean,
    val shareExpenses: Boolean,
    // Fiscal settings
    val considerFullDistance: Boolean = false, // Prendre en compte toute la distance travail-maison (sans plafond 40km)
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
        subscription = Subscription(
            type = SubscriptionType.valueOf(subscriptionType),
            expiresAt = subscriptionExpiresAt?.let { Instant.parse(it) },
            stripeCustomerId = stripeCustomerId,
            stripeSubscriptionId = stripeSubscriptionId
        ),
        monthlyTripCount = monthlyTripCount,
        phoneNumber = phoneNumber,
        address = address,
        linkedProAccountId = linkedProAccountId,
        linkStatus = linkStatus?.let { LinkStatus.valueOf(it) },
        invitationToken = invitationToken,
        invitedAt = invitedAt?.let { Instant.parse(it) },
        linkActivatedAt = linkActivatedAt?.let { Instant.parse(it) },
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        shareVehicleInfo = shareVehicleInfo,
        shareExpenses = shareExpenses,
        considerFullDistance = considerFullDistance,
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
        subscriptionType = subscription.type.name,
        subscriptionExpiresAt = subscription.expiresAt?.toString(),
        stripeCustomerId = subscription.stripeCustomerId,
        stripeSubscriptionId = subscription.stripeSubscriptionId,
        monthlyTripCount = monthlyTripCount,
        phoneNumber = phoneNumber,
        address = address,
        linkedProAccountId = linkedProAccountId,
        linkStatus = linkStatus?.name,
        invitationToken = invitationToken,
        invitedAt = invitedAt?.toString(),
        linkActivatedAt = linkActivatedAt?.toString(),
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        shareVehicleInfo = shareVehicleInfo,
        shareExpenses = shareExpenses,
        considerFullDistance = considerFullDistance,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        isLocallyConnected = isLocallyConnected
    )
}

package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.application.motium.domain.model.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Room entity for storing user data locally.
 * This allows the app to work offline and maintain user session without Supabase connectivity.
 *
 * Note: Les champs de liaison Pro (linkedProAccountId, linkStatus, préférences de partage, etc.)
 * sont maintenant gérés dans CompanyLinkEntity et la table company_links.
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
    val trialStartedAt: String? = null, // Trial start timestamp (ISO-8601)
    val trialEndsAt: String? = null, // Trial end timestamp (ISO-8601)
    val stripeCustomerId: String? = null, // Stripe customer ID for payments
    val stripeSubscriptionId: String? = null, // Stripe subscription ID
    val phoneNumber: String,
    val address: String,
    // Device fingerprint for anti-abuse
    val deviceFingerprintId: String? = null,
    // Fiscal settings
    val considerFullDistance: Boolean = false, // Prendre en compte toute la distance travail-maison (sans plafond 40km)
    // UI customization
    val favoriteColors: String = "[]", // List<String> stored as JSON array
    val createdAt: String, // Instant stored as ISO-8601 string
    val updatedAt: String, // Instant stored as ISO-8601 string
    val lastSyncedAt: Long? = null, // Timestamp of last sync with Supabase
    val isLocallyConnected: Boolean = true, // Flag to indicate user is logged in locally
    // ==================== OFFLINE-FIRST SYNC FIELDS ====================
    val syncStatus: String = SyncStatus.SYNCED.name, // SyncStatus enum as String
    val localUpdatedAt: Long = System.currentTimeMillis(), // Local modification timestamp
    val serverUpdatedAt: Long? = null, // Server's updated_at (from Supabase)
    val version: Int = 1 // Optimistic locking version
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
            type = SubscriptionType.fromString(subscriptionType),
            expiresAt = subscriptionExpiresAt?.let { Instant.parse(it) },
            trialStartedAt = trialStartedAt?.let { Instant.parse(it) },
            trialEndsAt = trialEndsAt?.let { Instant.parse(it) },
            stripeCustomerId = stripeCustomerId,
            stripeSubscriptionId = stripeSubscriptionId
        ),
        phoneNumber = phoneNumber,
        address = address,
        deviceFingerprintId = deviceFingerprintId,
        considerFullDistance = considerFullDistance,
        favoriteColors = try {
            Json.decodeFromString<List<String>>(favoriteColors)
        } catch (e: Exception) {
            emptyList()
        },
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
        trialStartedAt = subscription.trialStartedAt?.toString(),
        trialEndsAt = subscription.trialEndsAt?.toString(),
        stripeCustomerId = subscription.stripeCustomerId,
        stripeSubscriptionId = subscription.stripeSubscriptionId,
        phoneNumber = phoneNumber,
        address = address,
        deviceFingerprintId = deviceFingerprintId,
        considerFullDistance = considerFullDistance,
        favoriteColors = Json.encodeToString(favoriteColors),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        isLocallyConnected = isLocallyConnected
    )
}

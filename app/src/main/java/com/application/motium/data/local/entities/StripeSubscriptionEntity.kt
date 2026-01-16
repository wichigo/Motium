package com.application.motium.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.application.motium.domain.model.StripeSubscription
import com.application.motium.domain.model.StripeSubscriptionStatus
import com.application.motium.domain.model.StripeSubscriptionType
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "stripe_subscriptions",
    indices = [
        Index(value = ["oduserId"]),  // Keep DB column name for backward compatibility
        Index(value = ["proAccountId"]),
        Index(value = ["syncStatus"])
    ]
)
data class StripeSubscriptionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "oduserId") val userId: String? = null,  // Map to legacy column name
    val proAccountId: String? = null,
    val stripeSubscriptionId: String,
    val stripeCustomerId: String,
    val stripePriceId: String? = null,
    val stripeProductId: String? = null,
    val subscriptionType: String,
    val status: String,
    val quantity: Int = 1,
    val currency: String = "eur",
    val unitAmountCents: Int? = null,
    val currentPeriodStart: Long? = null,
    val currentPeriodEnd: Long? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val canceledAt: Long? = null,
    val endedAt: Long? = null,
    val metadata: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: String = SyncStatus.SYNCED.name,
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val serverUpdatedAt: Long? = null,
    val version: Int = 1
) {
    fun toDomain(): StripeSubscription = StripeSubscription(
        id = id,
        userId = userId,
        proAccountId = proAccountId,
        stripeSubscriptionId = stripeSubscriptionId,
        stripeCustomerId = stripeCustomerId,
        stripePriceId = stripePriceId,
        stripeProductId = stripeProductId,
        subscriptionType = try { StripeSubscriptionType.fromDbValue(subscriptionType) } catch (e: Exception) { StripeSubscriptionType.INDIVIDUAL_MONTHLY },
        status = try { StripeSubscriptionStatus.fromDbValue(status) } catch (e: Exception) { StripeSubscriptionStatus.INCOMPLETE },
        quantity = quantity,
        currency = currency,
        unitAmountCents = unitAmountCents,
        currentPeriodStart = currentPeriodStart?.let { Instant.fromEpochMilliseconds(it) },
        currentPeriodEnd = currentPeriodEnd?.let { Instant.fromEpochMilliseconds(it) },
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        canceledAt = canceledAt?.let { Instant.fromEpochMilliseconds(it) },
        endedAt = endedAt?.let { Instant.fromEpochMilliseconds(it) },
        metadata = try { Json.decodeFromString<Map<String, String>>(metadata) } catch (e: Exception) { emptyMap() },
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt)
    )
}

fun StripeSubscription.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): StripeSubscriptionEntity = StripeSubscriptionEntity(
    id = id,
    userId = userId,
    proAccountId = proAccountId,
    stripeSubscriptionId = stripeSubscriptionId,
    stripeCustomerId = stripeCustomerId,
    stripePriceId = stripePriceId,
    stripeProductId = stripeProductId,
    subscriptionType = subscriptionType.toDbValue(),
    status = status.toDbValue(),
    quantity = quantity,
    currency = currency,
    unitAmountCents = unitAmountCents,
    currentPeriodStart = currentPeriodStart?.toEpochMilliseconds(),
    currentPeriodEnd = currentPeriodEnd?.toEpochMilliseconds(),
    cancelAtPeriodEnd = cancelAtPeriodEnd,
    canceledAt = canceledAt?.toEpochMilliseconds(),
    endedAt = endedAt?.toEpochMilliseconds(),
    metadata = Json.encodeToString(metadata),
    createdAt = createdAt?.toEpochMilliseconds() ?: System.currentTimeMillis(),
    updatedAt = updatedAt?.toEpochMilliseconds() ?: System.currentTimeMillis(),
    syncStatus = syncStatus,
    localUpdatedAt = localUpdatedAt,
    serverUpdatedAt = serverUpdatedAt,
    version = version
)

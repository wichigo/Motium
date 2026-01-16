package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseStatus
import kotlinx.datetime.Instant

@Entity(
    tableName = "licenses",
    indices = [
        Index(value = ["proAccountId"]),
        Index(value = ["linkedAccountId"]),
        Index(value = ["syncStatus"])
    ]
)
data class LicenseEntity(
    @PrimaryKey val id: String,
    val proAccountId: String,
    val linkedAccountId: String? = null,
    val linkedAt: Long? = null,
    val isLifetime: Boolean = false,
    val priceMonthlyHt: Double = 5.0,
    val vatRate: Double = 0.20,
    val status: String = "pending",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val unlinkRequestedAt: Long? = null,
    val unlinkEffectiveAt: Long? = null,
    val billingStartsAt: Long? = null,
    val stripeSubscriptionId: String? = null,
    val stripeSubscriptionItemId: String? = null,
    val stripePriceId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: String = SyncStatus.SYNCED.name,
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val serverUpdatedAt: Long? = null,
    val version: Int = 1,
    val deletedAt: Long? = null // Soft delete timestamp (null = not deleted)
) {
    fun toDomain(): License = License(
        id = id,
        proAccountId = proAccountId,
        linkedAccountId = linkedAccountId,
        linkedAt = linkedAt?.let { Instant.fromEpochMilliseconds(it) },
        isLifetime = isLifetime,
        priceMonthlyHT = priceMonthlyHt,
        vatRate = vatRate,
        status = try { LicenseStatus.valueOf(status.uppercase()) } catch (e: Exception) { LicenseStatus.PENDING },
        startDate = startDate?.let { Instant.fromEpochMilliseconds(it) },
        endDate = endDate?.let { Instant.fromEpochMilliseconds(it) },
        unlinkRequestedAt = unlinkRequestedAt?.let { Instant.fromEpochMilliseconds(it) },
        unlinkEffectiveAt = unlinkEffectiveAt?.let { Instant.fromEpochMilliseconds(it) },
        billingStartsAt = billingStartsAt?.let { Instant.fromEpochMilliseconds(it) },
        stripeSubscriptionId = stripeSubscriptionId,
        stripeSubscriptionItemId = stripeSubscriptionItemId,
        stripePriceId = stripePriceId,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt)
    )
}

fun License.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): LicenseEntity = LicenseEntity(
    id = id,
    proAccountId = proAccountId,
    linkedAccountId = linkedAccountId,
    linkedAt = linkedAt?.toEpochMilliseconds(),
    isLifetime = isLifetime,
    priceMonthlyHt = priceMonthlyHT,
    vatRate = vatRate,
    status = status.name.lowercase(),
    startDate = startDate?.toEpochMilliseconds(),
    endDate = endDate?.toEpochMilliseconds(),
    unlinkRequestedAt = unlinkRequestedAt?.toEpochMilliseconds(),
    unlinkEffectiveAt = unlinkEffectiveAt?.toEpochMilliseconds(),
    billingStartsAt = billingStartsAt?.toEpochMilliseconds(),
    stripeSubscriptionId = stripeSubscriptionId,
    stripeSubscriptionItemId = stripeSubscriptionItemId,
    stripePriceId = stripePriceId,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    syncStatus = syncStatus,
    localUpdatedAt = localUpdatedAt,
    serverUpdatedAt = serverUpdatedAt,
    version = version
)

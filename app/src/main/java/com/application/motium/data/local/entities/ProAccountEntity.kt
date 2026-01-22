package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.application.motium.domain.model.LegalForm
import com.application.motium.domain.model.ProAccount
import com.application.motium.domain.model.ProAccountStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "pro_accounts",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["syncStatus"])
    ]
)
data class ProAccountEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val companyName: String,
    val siret: String? = null,
    val vatNumber: String? = null,
    val legalForm: String? = null,
    val billingAddress: String? = null,
    val billingEmail: String? = null,
    val billingDay: Int = 5,
    val billingAnchorDay: Int? = null,
    val status: String = "trial",  // trial, active, expired, suspended
    val trialEndsAt: Long? = null,
    val stripeSubscriptionId: String? = null, // ID subscription Stripe principale
    val departments: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: String = SyncStatus.SYNCED.name,
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val serverUpdatedAt: Long? = null,
    val version: Int = 1
) {
    fun toDomain(): ProAccount = ProAccount(
        id = id,
        userId = userId,
        companyName = companyName,
        siret = siret,
        vatNumber = vatNumber,
        legalForm = legalForm?.let {
            try { LegalForm.valueOf(it) } catch (e: Exception) { null }
        },
        billingAddress = billingAddress,
        billingEmail = billingEmail,
        billingDay = billingDay,
        billingAnchorDay = billingAnchorDay,
        status = ProAccountStatus.fromDbValue(status),
        trialEndsAt = trialEndsAt?.let { Instant.fromEpochMilliseconds(it) },
        stripeSubscriptionId = stripeSubscriptionId,
        departments = try {
            Json.decodeFromString<List<String>>(departments)
        } catch (e: Exception) {
            emptyList()
        },
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt)
    )
}

fun ProAccount.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): ProAccountEntity = ProAccountEntity(
    id = id,
    userId = userId,
    companyName = companyName,
    siret = siret,
    vatNumber = vatNumber,
    legalForm = legalForm?.name,
    billingAddress = billingAddress,
    billingEmail = billingEmail,
    billingDay = billingDay,
    billingAnchorDay = billingAnchorDay,
    status = status.name.lowercase(),
    trialEndsAt = trialEndsAt?.toEpochMilliseconds(),
    stripeSubscriptionId = stripeSubscriptionId,
    departments = Json.encodeToString(departments),
    createdAt = createdAt?.toEpochMilliseconds() ?: System.currentTimeMillis(),
    updatedAt = updatedAt?.toEpochMilliseconds() ?: System.currentTimeMillis(),
    syncStatus = syncStatus,
    localUpdatedAt = localUpdatedAt,
    serverUpdatedAt = serverUpdatedAt,
    version = version
)

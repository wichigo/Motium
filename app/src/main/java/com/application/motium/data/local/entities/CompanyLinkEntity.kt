package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.application.motium.domain.model.CompanyLink
import com.application.motium.domain.model.LinkStatus
import kotlinx.datetime.Instant

/**
 * Room entity for storing company link data locally.
 * Allows offline company link management and automatic sync when connection is restored.
 */
@Entity(
    tableName = "company_links",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["companyId"])
    ]
)
data class CompanyLinkEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val companyId: String,
    val companyName: String,
    val status: String,                    // LinkStatus enum stored as String
    val shareProfessionalTrips: Boolean,
    val sharePersonalTrips: Boolean,
    val sharePersonalInfo: Boolean,
    val linkedAt: String?,                 // Instant stored as ISO-8601 string, nullable
    val unlinkedAt: String?,               // Instant stored as ISO-8601 string, nullable
    val createdAt: String,                 // Instant stored as ISO-8601 string
    val updatedAt: String,                 // Instant stored as ISO-8601 string
    val lastSyncedAt: Long?,               // Timestamp of last sync with Supabase
    val needsSync: Boolean                 // Flag indicating if link needs to be synced
)

/**
 * Extension function to convert CompanyLinkEntity to domain CompanyLink model
 */
fun CompanyLinkEntity.toDomainModel(): CompanyLink {
    return CompanyLink(
        id = id,
        userId = userId,
        companyId = companyId,
        companyName = companyName,
        status = LinkStatus.valueOf(status),
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = sharePersonalInfo,
        linkedAt = linkedAt?.let { Instant.parse(it) },
        unlinkedAt = unlinkedAt?.let { Instant.parse(it) },
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain CompanyLink to CompanyLinkEntity
 */
fun CompanyLink.toEntity(lastSyncedAt: Long? = null, needsSync: Boolean = false): CompanyLinkEntity {
    return CompanyLinkEntity(
        id = id,
        userId = userId,
        companyId = companyId,
        companyName = companyName,
        status = status.name,
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = sharePersonalInfo,
        linkedAt = linkedAt?.toString(),
        unlinkedAt = unlinkedAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync
    )
}

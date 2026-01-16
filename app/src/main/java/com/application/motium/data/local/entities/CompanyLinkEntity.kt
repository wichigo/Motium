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
        Index(value = ["linkedProAccountId"]),
        Index(value = ["syncStatus"])
    ]
)
data class CompanyLinkEntity(
    @PrimaryKey
    val id: String,
    val userId: String?,                   // Nullable: null until user accepts invitation
    val linkedProAccountId: String,
    val companyName: String,
    val department: String?,               // Département/service dans l'entreprise
    val status: String,                    // LinkStatus enum stored as String
    val shareProfessionalTrips: Boolean,
    val sharePersonalTrips: Boolean,
    val sharePersonalInfo: Boolean,
    val shareExpenses: Boolean,            // Partage des dépenses
    val invitationToken: String?,          // Token d'invitation pour rejoindre l'entreprise
    val invitationEmail: String?,          // Email de l'invité (avant création de compte)
    val invitationExpiresAt: String?,      // Date d'expiration de l'invitation
    val linkedAt: String?,                 // Instant stored as ISO-8601 string, nullable
    val linkedActivatedAt: String?,        // Quand l'utilisateur a accepté l'invitation
    val unlinkedAt: String?,               // Instant stored as ISO-8601 string, nullable
    val createdAt: String,                 // Instant stored as ISO-8601 string
    val updatedAt: String,                 // Instant stored as ISO-8601 string
    // ==================== OFFLINE-FIRST SYNC FIELDS ====================
    val syncStatus: String = SyncStatus.SYNCED.name, // SyncStatus enum as String
    val localUpdatedAt: Long = System.currentTimeMillis(), // Local modification timestamp
    val serverUpdatedAt: Long? = null,     // Server's updated_at (from Supabase)
    val version: Int = 1,                  // Optimistic locking version
    val deletedAt: Long? = null            // Soft delete timestamp (null = not deleted)
)

/**
 * Extension function to convert CompanyLinkEntity to domain CompanyLink model
 */
fun CompanyLinkEntity.toDomainModel(): CompanyLink {
    return CompanyLink(
        id = id,
        userId = userId,
        linkedProAccountId = linkedProAccountId,
        companyName = companyName,
        department = department,
        status = LinkStatus.valueOf(status),
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = sharePersonalInfo,
        shareExpenses = shareExpenses,
        invitationToken = invitationToken,
        invitationEmail = invitationEmail,
        invitationExpiresAt = invitationExpiresAt?.let { Instant.parse(it) },
        linkedAt = linkedAt?.let { Instant.parse(it) },
        linkedActivatedAt = linkedActivatedAt?.let { Instant.parse(it) },
        unlinkedAt = unlinkedAt?.let { Instant.parse(it) },
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt)
    )
}

/**
 * Extension function to convert domain CompanyLink to CompanyLinkEntity
 */
fun CompanyLink.toEntity(
    syncStatus: String = SyncStatus.SYNCED.name,
    localUpdatedAt: Long = System.currentTimeMillis(),
    serverUpdatedAt: Long? = null,
    version: Int = 1
): CompanyLinkEntity {
    return CompanyLinkEntity(
        id = id,
        userId = userId,
        linkedProAccountId = linkedProAccountId,
        companyName = companyName,
        department = department,
        status = status.name,
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = sharePersonalInfo,
        shareExpenses = shareExpenses,
        invitationToken = invitationToken,
        invitationEmail = invitationEmail,
        invitationExpiresAt = invitationExpiresAt?.toString(),
        linkedAt = linkedAt?.toString(),
        linkedActivatedAt = linkedActivatedAt?.toString(),
        unlinkedAt = unlinkedAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        syncStatus = syncStatus,
        localUpdatedAt = localUpdatedAt,
        serverUpdatedAt = serverUpdatedAt,
        version = version
    )
}

package com.application.motium.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.application.motium.data.supabase.LinkedUserDto
import kotlinx.datetime.Instant

/**
 * Room entity for caching linked users (employees linked to Pro accounts).
 * This enables offline access to the LinkedAccountsScreen.
 *
 * Data comes from company_links table joined with users table.
 */
@Entity(
    tableName = "linked_users",
    indices = [
        Index("proAccountId"),
        Index("userId")
    ]
)
data class LinkedUserEntity(
    @PrimaryKey
    val linkId: String,
    val userId: String?,  // Nullable for pending invitations (not yet accepted)
    val proAccountId: String,
    val userName: String?,
    val userEmail: String,
    val userPhone: String?,
    val department: String?,
    val linkStatus: String,  // PENDING, ACTIVE, INACTIVE, REVOKED
    val shareProfessionalTrips: Boolean = true,
    val sharePersonalTrips: Boolean = false,
    val sharePersonalInfo: Boolean = true,
    val shareExpenses: Boolean = false,
    val invitedAt: Long?,
    val linkActivatedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    // Sync metadata
    val syncStatus: String = SyncStatus.SYNCED.name,
    val version: Int = 1
)

/**
 * Convert from DTO (from Supabase) to Entity (for Room).
 */
fun LinkedUserDto.toEntity(proAccountId: String): LinkedUserEntity {
    return LinkedUserEntity(
        linkId = linkId,
        userId = userId,
        proAccountId = proAccountId,
        userName = userName,
        userEmail = userEmail,
        userPhone = userPhone,
        department = department,
        linkStatus = linkStatus ?: "PENDING",
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        sharePersonalInfo = shareVehicleInfo,
        shareExpenses = shareExpenses,
        invitedAt = invitedAt?.let { parseTimestampToLong(it) },
        linkActivatedAt = linkActivatedAt?.let { parseTimestampToLong(it) },
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED.name,
        version = 1
    )
}

/**
 * Convert from Entity (Room) to DTO (for UI/Supabase).
 */
fun LinkedUserEntity.toDto(): LinkedUserDto {
    return LinkedUserDto(
        linkId = linkId,
        userId = userId,
        userName = userName,
        userEmail = userEmail,
        userPhone = userPhone,
        department = department,
        linkStatus = linkStatus,
        invitedAt = invitedAt?.let { formatLongToTimestamp(it) },
        linkActivatedAt = linkActivatedAt?.let { formatLongToTimestamp(it) },
        shareProfessionalTrips = shareProfessionalTrips,
        sharePersonalTrips = sharePersonalTrips,
        shareVehicleInfo = sharePersonalInfo,
        shareExpenses = shareExpenses
    )
}

/**
 * Parse ISO timestamp string to Long (milliseconds).
 */
private fun parseTimestampToLong(timestamp: String): Long {
    return try {
        Instant.parse(timestamp).toEpochMilliseconds()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}

/**
 * Format Long (milliseconds) to ISO timestamp string.
 */
private fun formatLongToTimestamp(millis: Long): String {
    return Instant.fromEpochMilliseconds(millis).toString()
}

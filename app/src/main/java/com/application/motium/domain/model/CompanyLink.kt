package com.application.motium.domain.model

import kotlinx.datetime.Instant

/**
 * Domain model representing a link between an individual user and a company/enterprise.
 * Users can be linked to multiple companies simultaneously.
 */
data class CompanyLink(
    val id: String,
    val userId: String?,                         // Nullable: null until user accepts invitation
    val linkedProAccountId: String,
    val companyName: String,
    val department: String? = null,
    val status: LinkStatus,
    val shareProfessionalTrips: Boolean = true,
    val sharePersonalTrips: Boolean = false,
    val sharePersonalInfo: Boolean = true,
    val shareExpenses: Boolean = false,
    val invitationToken: String? = null,
    val invitationEmail: String? = null,         // Email de l'invité (avant création de compte)
    val invitationExpiresAt: Instant? = null,    // Date d'expiration de l'invitation
    val linkedAt: Instant?,
    val linkedActivatedAt: Instant? = null,
    val unlinkedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

// LinkStatus is defined in User.kt and imported where needed

/**
 * Data class for updating sharing preferences for a company link.
 * Note: This is separate from the Pro interface SharingPreferences in LinkedAccount.kt
 */
data class CompanyLinkPreferences(
    val shareProfessionalTrips: Boolean,
    val sharePersonalTrips: Boolean,
    val sharePersonalInfo: Boolean,
    val shareExpenses: Boolean = false
)

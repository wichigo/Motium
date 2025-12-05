package com.application.motium.domain.model

import kotlinx.datetime.Instant

/**
 * Domain model representing a link between an individual user and a company/enterprise.
 * Users can be linked to multiple companies simultaneously.
 */
data class CompanyLink(
    val id: String,
    val userId: String,
    val companyId: String,
    val companyName: String,
    val status: LinkStatus,
    val shareProfessionalTrips: Boolean = true,
    val sharePersonalTrips: Boolean = false,
    val sharePersonalInfo: Boolean = true,
    val linkedAt: Instant?,
    val unlinkedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Status of a company link.
 */
enum class LinkStatus {
    /** Invitation received, awaiting user activation */
    PENDING,
    /** Currently linked and sharing data */
    ACTIVE,
    /** Previously linked, company retains contact info but loses access to trips */
    UNLINKED
}

/**
 * Data class for updating sharing preferences for a company link.
 */
data class SharingPreferences(
    val shareProfessionalTrips: Boolean,
    val sharePersonalTrips: Boolean,
    val sharePersonalInfo: Boolean
)

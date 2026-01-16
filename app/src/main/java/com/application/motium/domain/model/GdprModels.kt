package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Types de consentement RGPD
 */
enum class ConsentType {
    LOCATION_TRACKING,      // Suivi GPS des trajets
    DATA_COLLECTION,        // Collecte des données de trajets
    COMPANY_DATA_SHARING,   // Partage avec compte Pro lié
    ANALYTICS,              // Analyses anonymisées
    MARKETING               // Communications marketing
}

/**
 * Consentement utilisateur
 */
@Serializable
data class UserConsent(
    val consentType: ConsentType,
    val granted: Boolean,
    val grantedAt: Instant?,
    val revokedAt: Instant?,
    val consentVersion: String
)

/**
 * Info complète d'un consentement
 */
data class ConsentInfo(
    val type: ConsentType,
    val title: String,
    val description: String,
    val isRequired: Boolean,
    val granted: Boolean,
    val grantedAt: Instant?,
    val revokedAt: Instant?,
    val version: String
) {
    companion object {
        fun getDefaultConsents(): List<ConsentInfo> = listOf(
            ConsentInfo(
                type = ConsentType.LOCATION_TRACKING,
                title = "Suivi de localisation",
                description = "Permet l'enregistrement de vos trajets via GPS",
                isRequired = true,
                granted = true,
                grantedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                revokedAt = null,
                version = "1.0"
            ),
            ConsentInfo(
                type = ConsentType.DATA_COLLECTION,
                title = "Collecte des données",
                description = "Stockage de vos trajets et dépenses",
                isRequired = true,
                granted = true,
                grantedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                revokedAt = null,
                version = "1.0"
            ),
            ConsentInfo(
                type = ConsentType.COMPANY_DATA_SHARING,
                title = "Partage avec entreprise",
                description = "Partage de vos trajets avec un compte Pro lié",
                isRequired = false,
                granted = false,
                grantedAt = null,
                revokedAt = null,
                version = "1.0"
            ),
            ConsentInfo(
                type = ConsentType.ANALYTICS,
                title = "Analyses anonymisées",
                description = "Amélioration de l'application via données anonymes",
                isRequired = false,
                granted = false,
                grantedAt = null,
                revokedAt = null,
                version = "1.0"
            ),
            ConsentInfo(
                type = ConsentType.MARKETING,
                title = "Communications",
                description = "Recevoir des actualités et offres par email",
                isRequired = false,
                granted = false,
                grantedAt = null,
                revokedAt = null,
                version = "1.0"
            )
        )
    }
}

/**
 * Acceptation de politique de confidentialité
 */
@Serializable
data class PrivacyPolicyAcceptance(
    val userId: String,
    val policyVersion: String,
    val policyUrl: String,
    val acceptedAt: Instant
)

/**
 * Type de demande RGPD
 */
enum class GdprRequestType {
    DATA_EXPORT,    // Article 15 - Droit d'accès
    DATA_DELETION   // Article 17 - Droit à l'effacement
}

/**
 * Statut de demande RGPD
 */
enum class GdprRequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

/**
 * Demande RGPD
 */
@Serializable
data class GdprDataRequest(
    val id: String,
    val userId: String,
    val requestType: GdprRequestType,
    val status: GdprRequestStatus,
    val exportFileUrl: String?,
    val expiresAt: Instant?,
    val createdAt: Instant
)

/**
 * Résultat d'export RGPD
 */
data class GdprExportResult(
    val success: Boolean,
    val requestId: String?,
    val downloadUrl: String?,
    val expiresAt: Instant?,
    val errorMessage: String?
)

/**
 * Compteurs de données supprimées
 */
@Serializable
data class DeletionCounts(
    val trips: Int = 0,
    val expenses: Int = 0,
    val vehicles: Int = 0,
    @SerialName("work_schedules") val workSchedules: Int = 0,
    val consents: Int = 0
)

/**
 * Résumé de suppression
 */
@Serializable
data class DeletionSummary(
    @SerialName("deleted_counts") val deletedCounts: DeletionCounts,
    @SerialName("deleted_at") val deletedAt: String,
    val reason: String?
)

/**
 * Résultat de suppression de compte
 */
data class GdprDeletionResult(
    val success: Boolean,
    val message: String?,
    val deletionSummary: DeletionSummary?,
    val errorMessage: String?
)

/**
 * Politique de rétention des données
 */
@Serializable
data class DataRetentionPolicy(
    @SerialName("data_type") val dataType: String,
    @SerialName("retention_days") val retentionDays: Int,
    @SerialName("auto_delete") val autoDelete: Boolean,
    @SerialName("legal_basis") val legalBasis: String
)

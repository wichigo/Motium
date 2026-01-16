package com.application.motium.data.repository

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.ConsentEntity
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.supabase.SupabaseGdprRepository
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.ConsentInfo
import com.application.motium.domain.model.ConsentType
import com.application.motium.domain.model.DataRetentionPolicy
import com.application.motium.domain.model.GdprDeletionResult
import com.application.motium.domain.model.GdprExportResult
import com.application.motium.domain.model.PrivacyPolicyAcceptance
import com.application.motium.domain.repository.GdprRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Offline-first implementation of GdprRepository.
 *
 * Architecture:
 * - Reads from Room database (local-first)
 * - Writes to Room with PENDING_UPLOAD status
 * - Queues operations for background sync via OfflineFirstSyncManager
 * - Remote operations (export, deletion) delegate to SupabaseGdprRepository
 */
class OfflineFirstGdprRepository private constructor(
    private val context: Context
) : GdprRepository {

    companion object {
        private const val TAG = "OfflineFirstGdprRepo"
        private const val CURRENT_POLICY_VERSION = "1.0"

        @Volatile
        private var instance: OfflineFirstGdprRepository? = null

        fun getInstance(context: Context): OfflineFirstGdprRepository {
            return instance ?: synchronized(this) {
                instance ?: OfflineFirstGdprRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val consentDao = database.consentDao()
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val syncManager = OfflineFirstSyncManager.getInstance(context)
    private val supabaseGdprRepository = SupabaseGdprRepository.getInstance(context)

    // ==================== CONSENT MANAGEMENT (OFFLINE-FIRST) ====================

    /**
     * Get all consents for the current user from local database.
     * Returns Flow for reactive updates.
     */
    override suspend fun getConsents(): Result<List<ConsentInfo>> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            // Read from local database
            val consents = consentDao.getConsentsForUserOnce(userId)

            // If no consents exist locally, initialize defaults
            if (consents.isEmpty()) {
                MotiumApplication.logger.i("No consents found locally, initializing defaults", TAG)
                initializeDefaultConsents()
                val newConsents = consentDao.getConsentsForUserOnce(userId)
                return@withContext Result.success(newConsents.map { it.toConsentInfo() })
            }

            Result.success(consents.map { it.toConsentInfo() })
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to get consents: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Update a consent (offline-first).
     * 1. Update local database with PENDING_UPLOAD status
     * 2. Queue operation for background sync
     */
    override suspend fun updateConsent(
        type: ConsentType,
        granted: Boolean,
        version: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            // Validate required consents
            if (isConsentRequired(type) && !granted) {
                return@withContext Result.failure(
                    Exception("Le consentement '${getConsentTitle(type)}' est obligatoire")
                )
            }

            val now = System.currentTimeMillis()
            val consentType = type.name.lowercase()

            // Check if consent exists
            val existing = consentDao.getConsentByTypeOnce(userId, consentType)

            if (existing != null) {
                // Update existing consent
                consentDao.updateConsent(userId, consentType, granted, now)
                MotiumApplication.logger.i("Updated consent $consentType: granted=$granted", TAG)
            } else {
                // Create new consent
                val newConsent = ConsentEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    consentType = consentType,
                    granted = granted,
                    version = version.toIntOrNull() ?: 1,
                    grantedAt = if (granted) now else null,
                    revokedAt = if (!granted) now else null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_UPLOAD.name,
                    localUpdatedAt = now
                )
                consentDao.upsert(newConsent)
                MotiumApplication.logger.i("Created new consent $consentType: granted=$granted", TAG)
            }

            // Queue for sync
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_CONSENT,
                entityId = "$userId:$consentType",
                action = PendingOperationEntity.ACTION_UPDATE,
                payload = null // Not needed, we'll read from database
            )

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to update consent: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Initialize default consents for new user.
     * Creates required consents as granted, optional as not granted.
     */
    override suspend fun initializeDefaultConsents(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            val now = System.currentTimeMillis()

            val defaultConsents = listOf(
                // Required consents - granted by default
                ConsentEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    consentType = ConsentType.LOCATION_TRACKING.name.lowercase(),
                    granted = true,
                    version = 1,
                    grantedAt = now,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_UPLOAD.name,
                    localUpdatedAt = now
                ),
                ConsentEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    consentType = ConsentType.DATA_COLLECTION.name.lowercase(),
                    granted = true,
                    version = 1,
                    grantedAt = now,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_UPLOAD.name,
                    localUpdatedAt = now
                ),
                // Optional consents - not granted by default
                ConsentEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    consentType = ConsentType.COMPANY_DATA_SHARING.name.lowercase(),
                    granted = false,
                    version = 1,
                    grantedAt = null,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_UPLOAD.name,
                    localUpdatedAt = now
                ),
                ConsentEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    consentType = ConsentType.ANALYTICS.name.lowercase(),
                    granted = false,
                    version = 1,
                    grantedAt = null,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_UPLOAD.name,
                    localUpdatedAt = now
                ),
                ConsentEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    consentType = ConsentType.MARKETING.name.lowercase(),
                    granted = false,
                    version = 1,
                    grantedAt = null,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_UPLOAD.name,
                    localUpdatedAt = now
                )
            )

            consentDao.upsertAll(defaultConsents)

            // Queue all for sync
            defaultConsents.forEach { consent ->
                syncManager.queueOperation(
                    entityType = PendingOperationEntity.TYPE_CONSENT,
                    entityId = "${consent.userId}:${consent.consentType}",
                    action = PendingOperationEntity.ACTION_UPDATE
                )
            }

            MotiumApplication.logger.i("Initialized ${defaultConsents.size} default consents", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to initialize consents: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    // ==================== PRIVACY POLICY (DELEGATE TO SUPABASE) ====================

    override suspend fun hasAcceptedPolicy(version: String): Boolean {
        return supabaseGdprRepository.hasAcceptedPolicy(version)
    }

    override suspend fun acceptPrivacyPolicy(version: String, policyUrl: String): Result<Unit> {
        return supabaseGdprRepository.acceptPrivacyPolicy(version, policyUrl)
    }

    override suspend fun getPolicyAcceptances(): Result<List<PrivacyPolicyAcceptance>> {
        return supabaseGdprRepository.getPolicyAcceptances()
    }

    // ==================== DATA EXPORT (DELEGATE TO SUPABASE) ====================

    override suspend fun requestDataExport(): Result<GdprExportResult> {
        return supabaseGdprRepository.requestDataExport()
    }

    override suspend fun checkExportStatus(requestId: String): Result<GdprExportResult> {
        return supabaseGdprRepository.checkExportStatus(requestId)
    }

    // ==================== ACCOUNT DELETION (DELEGATE TO SUPABASE) ====================

    override suspend fun requestAccountDeletion(
        confirmation: String,
        reason: String?
    ): Result<GdprDeletionResult> {
        return supabaseGdprRepository.requestAccountDeletion(confirmation, reason)
    }

    // ==================== RETENTION POLICIES (DELEGATE TO SUPABASE) ====================

    override suspend fun getRetentionPolicies(): Result<List<DataRetentionPolicy>> {
        return supabaseGdprRepository.getRetentionPolicies()
    }

    // ==================== AUDIT LOG (DELEGATE TO SUPABASE) ====================

    override suspend fun logGdprAction(action: String, details: String): Result<Unit> {
        return supabaseGdprRepository.logGdprAction(action, details)
    }

    // ==================== HELPERS ====================

    private suspend fun getCurrentUserId(): String? {
        return localUserRepository.getLoggedInUser()?.id
    }

    private fun getConsentTitle(type: ConsentType): String = when (type) {
        ConsentType.LOCATION_TRACKING -> "Suivi de localisation"
        ConsentType.DATA_COLLECTION -> "Collecte des données"
        ConsentType.COMPANY_DATA_SHARING -> "Partage avec entreprise"
        ConsentType.ANALYTICS -> "Analyses anonymisées"
        ConsentType.MARKETING -> "Communications marketing"
    }

    private fun isConsentRequired(type: ConsentType): Boolean = when (type) {
        ConsentType.LOCATION_TRACKING -> true
        ConsentType.DATA_COLLECTION -> true
        ConsentType.COMPANY_DATA_SHARING -> false
        ConsentType.ANALYTICS -> false
        ConsentType.MARKETING -> false
    }

    private fun getConsentDescription(type: ConsentType): String = when (type) {
        ConsentType.LOCATION_TRACKING -> "Permet l'enregistrement de vos trajets via GPS pour le calcul des distances et indemnités kilométriques."
        ConsentType.DATA_COLLECTION -> "Stockage sécurisé de vos trajets, véhicules et dépenses professionnelles."
        ConsentType.COMPANY_DATA_SHARING -> "Partage automatique de vos trajets avec le compte Pro de votre entreprise si vous êtes lié."
        ConsentType.ANALYTICS -> "Données anonymisées utilisées pour améliorer l'application (aucune donnée personnelle)."
        ConsentType.MARKETING -> "Recevoir des actualités, conseils et offres spéciales par email."
    }
}

// ==================== EXTENSION FUNCTIONS ====================

/**
 * Convert ConsentEntity to ConsentInfo domain model
 */
private fun ConsentEntity.toConsentInfo(): ConsentInfo {
    val type = ConsentType.valueOf(consentType.uppercase())
    return ConsentInfo(
        type = type,
        title = when (type) {
            ConsentType.LOCATION_TRACKING -> "Suivi de localisation"
            ConsentType.DATA_COLLECTION -> "Collecte des données"
            ConsentType.COMPANY_DATA_SHARING -> "Partage avec entreprise"
            ConsentType.ANALYTICS -> "Analyses anonymisées"
            ConsentType.MARKETING -> "Communications marketing"
        },
        description = when (type) {
            ConsentType.LOCATION_TRACKING -> "Permet l'enregistrement de vos trajets via GPS"
            ConsentType.DATA_COLLECTION -> "Stockage de vos trajets et dépenses"
            ConsentType.COMPANY_DATA_SHARING -> "Partage de vos trajets avec un compte Pro lié"
            ConsentType.ANALYTICS -> "Amélioration de l'application via données anonymes"
            ConsentType.MARKETING -> "Recevoir des actualités et offres par email"
        },
        isRequired = when (type) {
            ConsentType.LOCATION_TRACKING -> true
            ConsentType.DATA_COLLECTION -> true
            else -> false
        },
        granted = granted,
        grantedAt = grantedAt?.let { Instant.fromEpochMilliseconds(it) },
        revokedAt = revokedAt?.let { Instant.fromEpochMilliseconds(it) },
        version = version.toString()
    )
}

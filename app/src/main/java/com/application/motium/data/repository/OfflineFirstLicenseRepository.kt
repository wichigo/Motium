package com.application.motium.data.repository

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.LicenseDao
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.License
import com.application.motium.domain.repository.LicenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Offline-first implementation of LicenseRepository.
 * Uses local Room database as source of truth with background sync to Supabase.
 */
class OfflineFirstLicenseRepository private constructor(
    private val context: Context
) : LicenseRepository {

    companion object {
        private const val TAG = "OfflineFirstLicenseRepo"
        // CRITICAL: Must match PendingOperationEntity.TYPE_LICENSE for sync to work
        private const val ENTITY_TYPE = PendingOperationEntity.TYPE_LICENSE

        @Volatile
        private var instance: OfflineFirstLicenseRepository? = null

        fun getInstance(context: Context): OfflineFirstLicenseRepository {
            return instance ?: synchronized(this) {
                instance ?: OfflineFirstLicenseRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val licenseDao: LicenseDao = database.licenseDao()
    private val syncManager = OfflineFirstSyncManager.getInstance(context)

    // ==================== FLOW-BASED QUERIES ====================

    override fun getLicensesByProAccount(proAccountId: String): Flow<List<License>> {
        return licenseDao.getLicensesByProAccount(proAccountId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getLicenseForUser(userId: String): Flow<License?> {
        return licenseDao.getActiveLicenseForUser(userId).map { it?.toDomain() }
    }

    override fun getAvailableLicenses(proAccountId: String): Flow<List<License>> {
        return licenseDao.getAvailableLicenses(proAccountId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun isUserLicensed(userId: String): Flow<Boolean> {
        return licenseDao.hasActiveLicense(userId)
    }

    // ==================== ONE-SHOT QUERIES ====================

    override suspend fun getLicenseById(licenseId: String): License? {
        return licenseDao.getByIdOnce(licenseId)?.toDomain()
    }

    override suspend fun getPendingSync(): List<License> {
        return licenseDao.getPendingSync().map { it.toDomain() }
    }

    // ==================== MUTATIONS WITH SYNC ====================

    override suspend fun assignLicense(licenseId: String, userId: String) {
        val now = System.currentTimeMillis()
        licenseDao.assignLicense(licenseId, userId, now, now)

        // Build payload for server sync
        val payload = buildJsonObject {
            put("linked_account_id", userId)
        }.toString()

        // Queue for sync with payload
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = payload,
            priority = 1 // High priority for license changes
        )

        MotiumApplication.logger.i(
            "License $licenseId assigned to user $userId (offline-first, payload=$payload)",
            TAG
        )
    }

    override suspend fun unassignLicense(licenseId: String) {
        // DEBUG: Log before operation for tracing
        MotiumApplication.logger.w(
            "🟣 DEBUG unassignLicense() START - licenseId: $licenseId - This sets linkedAccountId=NULL (IMMEDIATE unlink)",
            TAG
        )

        val now = System.currentTimeMillis()
        licenseDao.unassignLicense(licenseId, now)

        // Build payload for server sync - set linked_account_id to null
        val payload = buildJsonObject {
            put("linked_account_id", JsonNull)
        }.toString()

        // Queue for sync with payload
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = payload,
            priority = 1
        )

        MotiumApplication.logger.w(
            "🟣 DEBUG unassignLicense() DONE - License $licenseId unassigned, queued for sync (payload=$payload)",
            TAG
        )
    }

    override suspend fun requestUnlink(licenseId: String, proAccountId: String) {
        // DEBUG: Log before operation for tracing
        MotiumApplication.logger.w(
            "🟠 DEBUG requestUnlink() START - licenseId: $licenseId - This sets unlinkRequestedAt (keeps linkedAccountId)",
            TAG
        )

        val now = System.currentTimeMillis()

        // Get license to check end_date and isLifetime
        val license = licenseDao.getByIdOnce(licenseId)
        val licenseEndDate = license?.endDate
        val isLifetime = license?.isLifetime ?: false

        // Effective date:
        // - Lifetime: déliaison immédiate (now)
        // - Mensuelle: date de renouvellement (endDate), ou immédiat si endDate est null/passé
        val effectiveAt = when {
            isLifetime -> now  // Lifetime = immédiat
            licenseEndDate != null && licenseEndDate > now -> licenseEndDate  // Mensuelle = endDate
            else -> now  // Pas de endDate ou dans le passé = immédiat
        }

        licenseDao.setUnlinkRequest(licenseId, now, effectiveAt, now)

        // Build payload for server sync
        // IMPORTANT: Include unlink_effective_at so server knows when unlink takes effect
        // Without this, server would default to immediate unlink
        val effectiveAtIso = java.time.Instant.ofEpochMilli(effectiveAt).toString()
        val payload = buildJsonObject {
            put("unlink_requested", true)
            put("unlink_effective_at", effectiveAtIso)
        }.toString()

        // Queue for sync with payload
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = payload,
            priority = 1
        )

        val daysUntilEffective = ((effectiveAt - now) / (24L * 60 * 60 * 1000)).toInt()
        val effectiveType = if (isLifetime) "immédiat (lifetime)" else "date de renouvellement"
        MotiumApplication.logger.w(
            "🟠 DEBUG requestUnlink() DONE - License $licenseId unlink requested ($effectiveType), effectiveAt: $effectiveAtIso ($daysUntilEffective jours), queued for sync (payload=$payload)",
            TAG
        )
    }

    override suspend fun cancelUnlinkRequest(licenseId: String, proAccountId: String) {
        val now = System.currentTimeMillis()
        licenseDao.clearUnlinkRequest(licenseId, now)

        // Build payload for server sync
        // Note: Server currently only sets unlink_requested_at when true, but include for consistency
        val payload = buildJsonObject {
            put("unlink_requested", false)
        }.toString()

        // Queue for sync with payload
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = payload,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId unlink cancelled (offline-first, payload=$payload)",
            TAG
        )
    }

    override suspend fun cancelLicense(licenseId: String) {
        MotiumApplication.logger.i(
            "cancelLicense() START - licenseId: $licenseId - Setting status='canceled' (user keeps access until end_date)",
            TAG
        )

        val now = System.currentTimeMillis()
        // Only update status - user keeps access (linked_account_id unchanged) until end_date
        licenseDao.updateStatus(licenseId, "canceled", now)

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "cancelLicense() DONE - License $licenseId canceled, user keeps access until end_date, queued for sync",
            TAG
        )
    }

    override suspend fun assignLicenseToAccount(
        licenseId: String,
        proAccountId: String,
        linkedAccountId: String
    ) {
        val now = System.currentTimeMillis()
        licenseDao.assignLicense(licenseId, linkedAccountId, now, now)

        // Build payload for server sync
        val payload = buildJsonObject {
            put("linked_account_id", linkedAccountId)
        }.toString()

        // Queue for sync with payload
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = payload,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId assigned to account $linkedAccountId (offline-first, payload=$payload)",
            TAG
        )
    }

    override suspend fun assignLicenseToOwner(proAccountId: String, ownerUserId: String) {
        // Get first available license
        val availableLicenses = licenseDao.getAvailableLicenses(proAccountId).map { list ->
            list.map { it.toDomain() }
        }.first()

        if (availableLicenses.isEmpty()) {
            throw Exception("Aucune licence disponible dans votre pool")
        }

        val licenseToAssign = availableLicenses.first()
        val now = System.currentTimeMillis()

        licenseDao.assignLicense(licenseToAssign.id, ownerUserId, now, now)

        // Build payload for server sync
        val payload = buildJsonObject {
            put("linked_account_id", ownerUserId)
        }.toString()

        // Queue for sync with payload
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseToAssign.id,
            action = PendingOperationEntity.ACTION_UPDATE,
            payload = payload,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License ${licenseToAssign.id} assigned to owner $ownerUserId (offline-first, payload=$payload)",
            TAG
        )
    }
}

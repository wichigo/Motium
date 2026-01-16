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
        private const val ENTITY_TYPE = "license"

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

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1 // High priority for license changes
        )

        MotiumApplication.logger.i(
            "License $licenseId assigned to user $userId (offline-first)",
            TAG
        )
    }

    override suspend fun unassignLicense(licenseId: String) {
        val now = System.currentTimeMillis()
        licenseDao.unassignLicense(licenseId, now)

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId unassigned (offline-first)",
            TAG
        )
    }

    override suspend fun requestUnlink(licenseId: String, proAccountId: String) {
        val now = System.currentTimeMillis()
        val effectiveAt = now + (com.application.motium.domain.model.License.UNLINK_NOTICE_DAYS * 24L * 60 * 60 * 1000)

        licenseDao.setUnlinkRequest(licenseId, now, effectiveAt, now)

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId unlink requested (offline-first)",
            TAG
        )
    }

    override suspend fun cancelUnlinkRequest(licenseId: String, proAccountId: String) {
        val now = System.currentTimeMillis()
        licenseDao.clearUnlinkRequest(licenseId, now)

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId unlink cancelled (offline-first)",
            TAG
        )
    }

    override suspend fun cancelLicense(licenseId: String) {
        val now = System.currentTimeMillis()
        licenseDao.updateStatus(licenseId, "cancelled", now)

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId cancelled (offline-first)",
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

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseId,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License $licenseId assigned to account $linkedAccountId (offline-first)",
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

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = licenseToAssign.id,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "License ${licenseToAssign.id} assigned to owner $ownerUserId (offline-first)",
            TAG
        )
    }
}

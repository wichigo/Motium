package com.application.motium.data.repository

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.ProAccountDao
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.ProAccount
import com.application.motium.domain.repository.ProAccountRepositoryInterface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline-first implementation of ProAccountRepositoryInterface.
 * Uses local Room database as source of truth with background sync to Supabase.
 */
class OfflineFirstProAccountRepository private constructor(
    private val context: Context
) : ProAccountRepositoryInterface {

    companion object {
        private const val TAG = "OfflineFirstProAccountRepo"
        // CRITICAL: Must match PendingOperationEntity.TYPE_PRO_ACCOUNT for sync to work
        private const val ENTITY_TYPE = PendingOperationEntity.TYPE_PRO_ACCOUNT

        @Volatile
        private var instance: OfflineFirstProAccountRepository? = null

        fun getInstance(context: Context): OfflineFirstProAccountRepository {
            return instance ?: synchronized(this) {
                instance ?: OfflineFirstProAccountRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val proAccountDao: ProAccountDao = database.proAccountDao()
    private val syncManager = OfflineFirstSyncManager.getInstance(context)

    // ==================== FLOW-BASED QUERIES ====================

    override fun getProAccountForUser(userId: String): Flow<ProAccount?> {
        return proAccountDao.getByUserId(userId).map { it?.toDomain() }
    }

    override fun getProAccountById(id: String): Flow<ProAccount?> {
        return proAccountDao.getById(id).map { it?.toDomain() }
    }

    override fun hasProAccount(userId: String): Flow<Boolean> {
        return proAccountDao.hasProAccount(userId)
    }

    // ==================== ONE-SHOT QUERIES ====================

    override suspend fun getProAccountForUserOnce(userId: String): ProAccount? {
        return proAccountDao.getByUserIdOnce(userId)?.toDomain()
    }

    // ==================== MUTATIONS WITH SYNC ====================

    override suspend fun saveProAccount(proAccount: ProAccount) {
        val entity = proAccount.toEntity(
            syncStatus = SyncStatus.PENDING_UPLOAD.name,
            localUpdatedAt = System.currentTimeMillis()
        )
        proAccountDao.upsert(entity)

        // Queue for sync
        syncManager.queueOperation(
            entityType = ENTITY_TYPE,
            entityId = proAccount.id,
            action = PendingOperationEntity.ACTION_UPDATE,
            priority = 1
        )

        MotiumApplication.logger.i(
            "Pro account ${proAccount.id} saved (offline-first)",
            TAG
        )
    }
}

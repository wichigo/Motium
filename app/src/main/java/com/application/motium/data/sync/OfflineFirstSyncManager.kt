package com.application.motium.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncMetadataEntity
import com.application.motium.utils.NetworkConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Central sync manager for offline-first architecture.
 * Coordinates between local Room database and remote Supabase.
 *
 * Responsibilities:
 * - Expose sync state (pending operations count, online status) for UI
 * - Queue operations for background sync
 * - Trigger immediate sync when needed
 * - Schedule periodic sync via WorkManager
 * - Observe network changes and trigger sync on reconnection
 */
class OfflineFirstSyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OfflineFirstSyncManager"
        private const val PERIODIC_SYNC_WORK_NAME = "delta_sync_periodic"
        private const val IMMEDIATE_SYNC_WORK_NAME = "delta_sync_immediate"

        // Sync intervals
        private const val PERIODIC_SYNC_INTERVAL_MINUTES = 15L
        private const val PERIODIC_SYNC_FLEX_MINUTES = 5L
        private const val NETWORK_RESTORE_DELAY_MS = 2000L

        @Volatile
        private var instance: OfflineFirstSyncManager? = null

        fun getInstance(context: Context): OfflineFirstSyncManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineFirstSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val pendingOpDao = database.pendingOperationDao()
    private val syncMetadataDao = database.syncMetadataDao()
    private val tripDao = database.tripDao()
    private val vehicleDao = database.vehicleDao()
    private val networkManager = NetworkConnectionManager.getInstance(context)
    private val workManager = WorkManager.getInstance(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== PUBLIC STATE FLOWS ====================

    /**
     * Flow of pending operations count for UI display.
     */
    val pendingOperationsCount: Flow<Int> = pendingOpDao.getCountFlow()

    /**
     * Flow of failed operations count for UI display.
     * Operations fail when they exceed MAX_RETRY_COUNT.
     */
    val failedOperationsCount: Flow<Int> = pendingOpDao.getFailedCountFlow()

    /**
     * StateFlow of network connectivity status.
     */
    val isOnline: StateFlow<Boolean> = networkManager.isConnected

    // ==================== INITIALIZATION ====================

    init {
        // Observe network changes and trigger sync on reconnection
        scope.launch {
            networkManager.isConnected.collectLatest { isConnected ->
                if (isConnected) {
                    val pendingCount = pendingOpDao.getCount()
                    if (pendingCount > 0) {
                        MotiumApplication.logger.i(
                            "Network restored - $pendingCount operations pending, triggering sync after ${NETWORK_RESTORE_DELAY_MS}ms",
                            TAG
                        )
                        // Wait for network stabilization
                        delay(NETWORK_RESTORE_DELAY_MS)
                        triggerImmediateSync()
                    }
                }
            }
        }
    }

    // ==================== OPERATION QUEUING ====================

    /**
     * Queue an operation for background sync.
     * Atomically replaces any existing operation for the same entity to prevent duplicates.
     * Uses @Transaction in the DAO to prevent race conditions between delete and insert.
     *
     * @param entityType Entity type (TRIP, VEHICLE, EXPENSE, USER)
     * @param entityId ID of the entity
     * @param action Operation type (CREATE, UPDATE, DELETE)
     * @param payload Optional JSON payload for CREATE/UPDATE
     * @param priority Priority (0=normal, higher=more urgent)
     */
    suspend fun queueOperation(
        entityType: String,
        entityId: String,
        action: String,
        payload: String? = null,
        priority: Int = 0
    ) {
        val createdAt = System.currentTimeMillis()
        val idempotencyKey = PendingOperationEntity.generateIdempotencyKey(
            entityType = entityType,
            entityId = entityId,
            action = action,
            createdAt = createdAt
        )

        val operation = PendingOperationEntity(
            id = UUID.randomUUID().toString(),
            idempotencyKey = idempotencyKey,
            entityType = entityType,
            entityId = entityId,
            action = action,
            payload = payload,
            createdAt = createdAt,
            priority = priority
        )

        // Atomically replace any existing operation for same entity
        // Uses @Transaction to prevent race conditions
        pendingOpDao.replaceOperation(entityId, entityType, operation)

        MotiumApplication.logger.i(
            "Queued $action operation for $entityType:$entityId (priority=$priority, idempotencyKey=$idempotencyKey)",
            TAG
        )

        // Trigger immediate sync if online
        if (networkManager.isConnected.value) {
            triggerImmediateSync()
        }
    }

    /**
     * Queue a DELETE operation with higher priority.
     */
    suspend fun queueDeleteOperation(entityType: String, entityId: String) {
        queueOperation(
            entityType = entityType,
            entityId = entityId,
            action = PendingOperationEntity.ACTION_DELETE,
            priority = 1 // Higher priority for deletes
        )
    }

    // ==================== SYNC TRIGGERING ====================

    /**
     * Trigger immediate sync via WorkManager.
     * Uses a one-time work request with network constraint.
     */
    fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DeltaSyncWorker>()
            .setConstraints(constraints)
            .addTag(IMMEDIATE_SYNC_WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE, // Replace any pending immediate sync
            request
        )

        MotiumApplication.logger.i("Triggered immediate sync", TAG)
    }

    /**
     * Start periodic sync via WorkManager.
     * Should be called on app startup and after login.
     */
    fun startPeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<DeltaSyncWorker>(
            PERIODIC_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES,
            PERIODIC_SYNC_FLEX_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(PERIODIC_SYNC_WORK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing schedule
            request
        )

        MotiumApplication.logger.i("Started periodic sync (every ${PERIODIC_SYNC_INTERVAL_MINUTES} minutes)", TAG)
    }

    /**
     * Stop all sync activities.
     * Should be called on logout.
     */
    fun stopSync() {
        workManager.cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        workManager.cancelUniqueWork(IMMEDIATE_SYNC_WORK_NAME)
        MotiumApplication.logger.i("Stopped all sync activities", TAG)
    }

    // ==================== SYNC METADATA ====================

    /**
     * Initialize sync metadata for an entity type if not exists.
     */
    suspend fun initializeSyncMetadata(entityType: String) {
        syncMetadataDao.initializeIfNotExists(entityType)
    }

    /**
     * Force a full re-sync by resetting all sync metadata timestamps to epoch (0).
     * This will cause the next delta sync to fetch ALL data from the server.
     *
     * Use cases:
     * - After a major server migration
     * - When local data is suspected to be corrupted
     * - When the user explicitly requests a full refresh
     *
     * Note: This does NOT delete local data. It just resets the "lastSyncTimestamp"
     * so the delta sync will re-download everything. Local changes marked as
     * PENDING_UPLOAD will still be uploaded normally.
     */
    suspend fun forceFullSync() {
        // Reset all sync metadata timestamps to 0 (epoch)
        syncMetadataDao.resetAllTimestamps()
        MotiumApplication.logger.i("Reset all sync timestamps for full re-sync", TAG)

        // Trigger immediate sync if online
        if (networkManager.isConnected.value) {
            triggerImmediateSync()
        }
    }

    /**
     * Get the last sync timestamp for an entity type.
     */
    suspend fun getLastSyncTimestamp(entityType: String): Long {
        return syncMetadataDao.getLastSyncTimestamp(entityType) ?: 0L
    }

    /**
     * Update the last sync timestamp after successful sync.
     */
    suspend fun updateLastSyncTimestamp(entityType: String, timestamp: Long, syncedCount: Int = 0) {
        syncMetadataDao.updateLastSyncTimestamp(entityType, timestamp, syncedCount)
    }

    // ==================== STATISTICS ====================

    /**
     * Get sync statistics for display.
     */
    suspend fun getSyncStats(): SyncStats {
        val pendingCount = pendingOpDao.getCount()
        val failedCount = pendingOpDao.getFailedCount()
        val metadata = syncMetadataDao.getAll()

        return SyncStats(
            pendingOperations = pendingCount,
            failedOperations = failedCount,
            isOnline = networkManager.isConnected.value,
            entityMetadata = metadata.associate { it.entityType to it }
        )
    }

    /**
     * Get pending operations for debugging/display.
     */
    suspend fun getPendingOperations(): List<PendingOperationEntity> {
        return pendingOpDao.getAll()
    }

    /**
     * Get failed operations for retry/display.
     */
    suspend fun getFailedOperations(): List<PendingOperationEntity> {
        return pendingOpDao.getFailedOperations()
    }

    /**
     * Reset failed operations for retry.
     */
    suspend fun resetFailedOperations() {
        val failed = pendingOpDao.getFailedOperations()
        failed.forEach { operation ->
            pendingOpDao.resetRetry(operation.id)
        }
        MotiumApplication.logger.i("Reset ${failed.size} failed operations for retry", TAG)

        if (failed.isNotEmpty() && networkManager.isConnected.value) {
            triggerImmediateSync()
        }
    }

    /**
     * Clear all pending operations (for testing/reset).
     */
    suspend fun clearAllPendingOperations() {
        pendingOpDao.deleteAll()
        MotiumApplication.logger.i("Cleared all pending operations", TAG)
    }

    // ==================== CONFLICT MANAGEMENT ====================

    /**
     * Get Flow of conflict trips count for a user.
     * Used to show badge/indicator in UI when conflicts need resolution.
     */
    fun getConflictTripsCountFlow(userId: String): Flow<Int> {
        return tripDao.getConflictTripsCountFlow(userId)
    }

    /**
     * Get Flow of conflict vehicles count for a user.
     */
    fun getConflictVehiclesCountFlow(userId: String): Flow<Int> {
        return vehicleDao.getConflictVehiclesCountFlow(userId)
    }

    /**
     * Resolve a trip conflict by keeping local changes.
     * The trip will be marked PENDING_UPLOAD and synced to server.
     */
    suspend fun resolveConflictKeepLocalTrip(tripId: String) {
        tripDao.resolveConflictKeepLocal(tripId)
        MotiumApplication.logger.i("Resolved trip conflict - kept local: $tripId", TAG)
        if (networkManager.isConnected.value) {
            triggerImmediateSync()
        }
    }

    /**
     * Resolve a trip conflict by accepting server version.
     * Local changes will be discarded.
     */
    suspend fun resolveConflictKeepServerTrip(tripId: String) {
        tripDao.resolveConflictKeepServer(tripId)
        MotiumApplication.logger.i("Resolved trip conflict - kept server: $tripId", TAG)
    }

    /**
     * Resolve a vehicle conflict by keeping local changes.
     */
    suspend fun resolveConflictKeepLocalVehicle(vehicleId: String) {
        vehicleDao.resolveConflictKeepLocal(vehicleId)
        MotiumApplication.logger.i("Resolved vehicle conflict - kept local: $vehicleId", TAG)
        if (networkManager.isConnected.value) {
            triggerImmediateSync()
        }
    }

    /**
     * Resolve a vehicle conflict by accepting server version.
     */
    suspend fun resolveConflictKeepServerVehicle(vehicleId: String) {
        vehicleDao.resolveConflictKeepServer(vehicleId)
        MotiumApplication.logger.i("Resolved vehicle conflict - kept server: $vehicleId", TAG)
    }

    // ==================== DATA CLASSES ====================

    data class SyncStats(
        val pendingOperations: Int,
        val failedOperations: Int,
        val isOnline: Boolean,
        val entityMetadata: Map<String, SyncMetadataEntity>
    )
}

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
        // BATTERY OPTIMIZATION (2026-01-22): Increased from 30min to 60min (1 hour)
        // Rationale: Trips/vehicles rarely change after creation. Critical operations
        // (deletes, high-priority updates) still trigger immediate sync via queueOperation.
        // WorkManager handles Doze mode and network constraints automatically.
        // Mobile radio was consuming 15.9 mAh - reducing sync frequency to save battery.
        private const val PERIODIC_SYNC_INTERVAL_MINUTES = 60L
        private const val PERIODIC_SYNC_FLEX_MINUTES = 15L
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

    // BATTERY OPTIMIZATION: Track last sync time to prevent excessive syncs
    private var lastImmediateSyncTriggerTime: Long = 0
    private val MIN_SYNC_INTERVAL_MS = 60_000L // Minimum 1 minute between immediate syncs

    // ==================== INITIALIZATION ====================

    init {
        // BATTERY OPTIMIZATION: Don't observe network in init to avoid excessive syncs
        // The periodic WorkManager sync will handle network restoration naturally
        // Only critical operations (queueOperation with priority > 0) will trigger immediate sync
        MotiumApplication.logger.i("OfflineFirstSyncManager initialized (battery optimized)", TAG)
    }

    // ==================== OPERATION QUEUING ====================

    /**
     * Queue an operation for background sync.
     * Intelligently merges operations to prevent data loss:
     * - CREATE + UPDATE → Merge payloads, keep as CREATE (server needs CREATE first)
     * - CREATE + DELETE → Cancel both (entity never existed on server)
     * - Otherwise → Replace existing operation
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
        // Check for existing pending operation for this entity
        val existingOps = pendingOpDao.getByEntity(entityId, entityType)
        val existingOp = existingOps.firstOrNull()

        // CRITICAL FIX: Handle CREATE→UPDATE and CREATE→DELETE intelligently
        // This prevents the bug where UPDATE replaces CREATE, causing "Trip not found" errors
        if (existingOp != null) {
            when {
                // CREATE + UPDATE = Merge payloads, keep CREATE
                // The server needs the CREATE first, but we want the latest data from UPDATE
                existingOp.action == PendingOperationEntity.ACTION_CREATE &&
                action == PendingOperationEntity.ACTION_UPDATE -> {
                    val mergedPayload = mergePayloads(existingOp.payload, payload)
                    val mergedOp = existingOp.copy(
                        payload = mergedPayload,
                        // Keep original idempotencyKey (stable for retries)
                        // Update priority if new one is higher
                        priority = maxOf(existingOp.priority, priority)
                    )
                    pendingOpDao.deleteByEntity(entityId, entityType)
                    pendingOpDao.insert(mergedOp)
                    MotiumApplication.logger.i(
                        "Merged UPDATE into existing CREATE for $entityType:$entityId",
                        TAG
                    )
                    // Trigger sync if needed
                    if (priority > 0 && networkManager.isConnected.value) {
                        triggerImmediateSync()
                    }
                    return
                }

                // CREATE + DELETE = Cancel both (entity never synced to server)
                // No need to create then delete - just forget about it
                existingOp.action == PendingOperationEntity.ACTION_CREATE &&
                action == PendingOperationEntity.ACTION_DELETE -> {
                    pendingOpDao.deleteByEntity(entityId, entityType)
                    MotiumApplication.logger.i(
                        "Cancelled CREATE+DELETE for $entityType:$entityId (never synced)",
                        TAG
                    )
                    return
                }

                // All other cases: replace existing operation
                else -> {
                    // Fall through to normal replacement logic
                }
            }
        }

        // Normal case: create new operation and replace any existing
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
        // Uses @Transaction in DAO to prevent race conditions
        pendingOpDao.replaceOperation(entityId, entityType, operation)

        MotiumApplication.logger.i(
            "Queued $action operation for $entityType:$entityId (priority=$priority, idempotencyKey=$idempotencyKey)",
            TAG
        )

        // BATTERY OPTIMIZATION: Only trigger immediate sync for high-priority operations
        // Normal operations will be synced by the periodic WorkManager task
        if (priority > 0 && networkManager.isConnected.value) {
            triggerImmediateSync()
        }
    }

    /**
     * Merge two JSON payloads, with newer values taking precedence.
     * Used when merging CREATE + UPDATE operations.
     *
     * @param basePayload Original CREATE payload (may be null)
     * @param updatePayload New UPDATE payload with updated fields (may be null)
     * @return Merged JSON string, or updatePayload if merge fails
     */
    private fun mergePayloads(basePayload: String?, updatePayload: String?): String? {
        if (basePayload == null) return updatePayload
        if (updatePayload == null) return basePayload

        return try {
            val baseJson = Json.parseToJsonElement(basePayload).jsonObject
            val updateJson = Json.parseToJsonElement(updatePayload).jsonObject

            // Merge: start with base, override with update values
            val merged = buildJsonObject {
                // Add all base fields
                baseJson.forEach { (key, value) ->
                    put(key, value)
                }
                // Override with update fields (newer values win)
                updateJson.forEach { (key, value) ->
                    put(key, value)
                }
            }

            merged.toString()
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "Failed to merge payloads, using update payload: ${e.message}",
                TAG
            )
            updatePayload
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
     *
     * BATTERY OPTIMIZATION: Rate-limited to at most once per minute to prevent battery drain.
     */
    fun triggerImmediateSync() {
        val now = System.currentTimeMillis()
        val timeSinceLastSync = now - lastImmediateSyncTriggerTime

        // BATTERY OPTIMIZATION: Rate limit immediate syncs to once per minute
        if (timeSinceLastSync < MIN_SYNC_INTERVAL_MS) {
            MotiumApplication.logger.d(
                "Skipping immediate sync - last trigger was ${timeSinceLastSync / 1000}s ago (min ${MIN_SYNC_INTERVAL_MS / 1000}s)",
                TAG
            )
            return
        }

        lastImmediateSyncTriggerTime = now

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DeltaSyncWorker>()
            .setConstraints(constraints)
            .addTag(IMMEDIATE_SYNC_WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP, // Keep existing if already pending (don't replace)
            request
        )

        MotiumApplication.logger.i("Triggered immediate sync (rate-limited)", TAG)
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

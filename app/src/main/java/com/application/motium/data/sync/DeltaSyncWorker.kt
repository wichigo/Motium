package com.application.motium.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.ConsentEntity
import com.application.motium.data.local.entities.LicenseEntity
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.ProAccountEntity
import com.application.motium.data.local.entities.StripeSubscriptionEntity
import com.application.motium.data.local.entities.SyncMetadataEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.TripEntity
import com.application.motium.data.local.entities.VehicleEntity
import com.application.motium.data.local.entities.toDataModel
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.LicenseDto
import com.application.motium.data.supabase.LicenseRemoteDataSource as SupabaseLicenseRepository
import com.application.motium.data.supabase.ProAccountRemoteDataSource as SupabaseProAccountRepository
import com.application.motium.data.supabase.TripRemoteDataSource
import com.application.motium.data.supabase.VehicleRemoteDataSource
import com.application.motium.data.supabase.ChangesRemoteDataSource
import com.application.motium.data.supabase.SyncChangesRemoteDataSource
import com.application.motium.data.supabase.ChangeEntityMapper
import com.application.motium.data.CompanyLinkRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.domain.model.TripType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * WorkManager CoroutineWorker for delta synchronization.
 * Uses atomic push+pull via sync_changes() RPC to prevent race conditions.
 *
 * Sync Process (v2 - Atomic):
 * 1. File Upload Phase: Upload pending receipt photos to Storage
 * 2. Atomic Sync Phase: Push pending operations + Pull changes in single RPC call
 * 3. Process Phase: Apply pulled changes to local database
 *
 * Key improvements over v1:
 * - No race condition between push and pull (atomic transaction)
 * - Idempotent operations via idempotency_key
 * - Version-based conflict detection for TRIP, VEHICLE, USER
 * - Single HTTP request for push+pull (reduced latency)
 *
 * Note: When Hilt is enabled, convert to @HiltWorker with @AssistedInject
 */
class DeltaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DeltaSyncWorker"
        private const val MAX_BATCH_SIZE = 50
        private const val PAGINATION_LIMIT = 500
        // BATTERY OPTIMIZATION (2026-01): Limit retries to prevent infinite sync loops
        // that drain battery when server is unreachable or auth is broken
        private const val MAX_RETRY_ATTEMPTS = 5

        // Notification for expedited/foreground work (required on API 31+ for setExpedited)
        private const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 1001

        // FIX (2026-01-26): Mutex to prevent concurrent sync execution.
        // The periodic worker (delta_sync_periodic) and immediate worker (delta_sync_immediate)
        // use different unique work names, so WorkManager can run them concurrently.
        // This causes race conditions where one worker's pull overwrites data that another
        // worker's push just committed (e.g., consider_full_distance toggle).
        private val syncMutex = kotlinx.coroutines.sync.Mutex()
    }

    private val database = MotiumDatabase.getInstance(applicationContext)
    private val pendingOpDao = database.pendingOperationDao()
    private val syncMetadataDao = database.syncMetadataDao()
    private val tripDao = database.tripDao()
    private val vehicleDao = database.vehicleDao()
    private val licenseDao = database.licenseDao()
    private val proAccountDao = database.proAccountDao()
    private val stripeSubscriptionDao = database.stripeSubscriptionDao()
    private val pendingFileUploadDao = database.pendingFileUploadDao()
    private val expenseDao = database.expenseDao()
    private val consentDao = database.consentDao()
    private val workScheduleDao = database.workScheduleDao()

    private val localUserRepository = LocalUserRepository.getInstance(applicationContext)
    // BATTERY OPTIMIZATION (2026-01): These RemoteDataSources are NO LONGER USED for sync
    // All sync now goes through syncChangesRemoteDataSource.syncChanges() (atomic RPC)
    // Kept only for deprecated code reference - see uploadPendingOperations()
    @Deprecated("Used only by deprecated uploadPendingOperations()")
    private val tripRemoteDataSource = TripRemoteDataSource.getInstance(applicationContext)
    @Deprecated("Used only by deprecated uploadPendingOperations()")
    private val vehicleRemoteDataSource = VehicleRemoteDataSource.getInstance(applicationContext)
    private val syncChangesRemoteDataSource = SyncChangesRemoteDataSource.getInstance(applicationContext)
    private val supabaseLicenseRepository = SupabaseLicenseRepository.getInstance(applicationContext)
    private val supabaseProAccountRepository = SupabaseProAccountRepository.getInstance(applicationContext)
    private val companyLinkRepository = CompanyLinkRepository.getInstance(applicationContext)
    private val storageService = com.application.motium.service.SupabaseStorageService.getInstance(applicationContext)
    private val supabaseGdprRepository = com.application.motium.data.supabase.SupabaseGdprRepository.getInstance(applicationContext)
    private val authRepository = SupabaseAuthRepository.getInstance(applicationContext)
    private val secureSessionStorage = SecureSessionStorage(applicationContext)

    /**
     * Required for expedited work on API 31+ (Android 12+).
     * When setExpedited() is used and the system needs to run as foreground service,
     * this provides the notification to show.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required on API 26+)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Synchronisation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Synchronisation des données en arrière-plan"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Motium")
            .setContentText("Synchronisation en cours...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // BATTERY OPTIMIZATION (2026-01): Stop retrying after MAX_RETRY_ATTEMPTS to prevent battery drain
        if (runAttemptCount > MAX_RETRY_ATTEMPTS) {
            MotiumApplication.logger.w(
                "⚠️ DeltaSyncWorker exceeded max retry attempts ($runAttemptCount > $MAX_RETRY_ATTEMPTS), giving up",
                TAG
            )
            return@withContext Result.failure()
        }

        try {
            val user = localUserRepository.getLoggedInUser()
            if (user == null) {
                MotiumApplication.logger.w("No user logged in, skipping sync", TAG)
                return@withContext Result.success()
            }

            // FIX: Validate and refresh session BEFORE syncing to avoid RLS errors with anon key
            // Bug: When session refresh fails, Supabase falls back to anon key which causes RLS violations
            if (!ensureValidSession()) {
                MotiumApplication.logger.w("⚠️ Session invalid or refresh failed - skipping sync to prevent data loss", TAG)
                return@withContext Result.retry()
            }

            MotiumApplication.logger.i("Starting delta sync for user: ${user.id}", TAG)

            // Initialize sync metadata for all entity types
            initializeSyncMetadata()

            // Phase 1: Upload pending file uploads (receipts) - must happen before sync
            val fileUploadSuccess = uploadPendingFiles()

            // Phase 2: Atomic push+pull via sync_changes() RPC
            // This replaces the old separate uploadPendingOperations() + downloadServerChanges()
            val syncSuccess = performAtomicSync(user.id)

            if (fileUploadSuccess && syncSuccess) {
                MotiumApplication.logger.i("Delta sync completed successfully", TAG)

                // FIX (2026-01-26): Check if new operations were queued during sync.
                // If so, trigger a follow-up immediate sync so changes made on any screen
                // (not just Home) are pushed to Supabase without waiting for the periodic worker.
                val remainingOps = pendingOpDao.getCount()
                if (remainingOps > 0) {
                    MotiumApplication.logger.i(
                        "$remainingOps pending operations remain after sync, triggering follow-up sync",
                        TAG
                    )
                    OfflineFirstSyncManager.getInstance(applicationContext).triggerImmediateSync()
                }

                Result.success()
            } else {
                MotiumApplication.logger.w("Delta sync completed with errors, will retry", TAG)
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e // Don't catch cancellation
        } catch (e: Exception) {
            MotiumApplication.logger.e("Delta sync failed: ${e.message}", TAG, e)
            Result.retry()
        }
    }

    // ==================== INITIALIZATION ====================

    private suspend fun initializeSyncMetadata() {
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_TRIP)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_VEHICLE)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_EXPENSE)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_LICENSE)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_PRO_ACCOUNT)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_STRIPE_SUBSCRIPTION)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_USER)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_CONSENT)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_WORK_SCHEDULE)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS)
        syncMetadataDao.initializeIfNotExists(SyncMetadataEntity.TYPE_COMPANY_LINK)
    }

    // ==================== SESSION VALIDATION ====================

    /**
     * Ensure we have a valid session with a user JWT before syncing.
     * This prevents the bug where Supabase falls back to anon key when session is expired,
     * causing RLS violations and trips not being uploaded to the server.
     *
     * @return true if session is valid and we can proceed with sync
     */
    private suspend fun ensureValidSession(): Boolean {
        return try {
            // Step 1: Try to refresh/restore the session
            // refreshSessionForSync() handles multiple fallback strategies:
            // - Priority 1: Use SDK session if available
            // - Priority 2: Use stored refresh token
            // - Priority 3: Try silent re-authentication with stored credentials
            val refreshResult = authRepository.refreshSessionForSync()
            if (!refreshResult) {
                MotiumApplication.logger.w("⚠️ Session refresh/restore failed - skipping sync", TAG)
                return false
            }

            // Step 2: Double-check we have a valid session in secure storage
            if (!secureSessionStorage.hasValidSession()) {
                MotiumApplication.logger.w("⚠️ No valid session in storage after refresh - skipping sync", TAG)
                return false
            }

            MotiumApplication.logger.i("✅ Session validated - proceeding with sync", TAG)
            true
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Session validation failed: ${e.message}", TAG, e)
            false
        }
    }

    // ==================== FILE UPLOAD PHASE ====================

    /**
     * Upload pending receipt photos to Supabase Storage.
     * Process queued file uploads and update expense entities with public URLs.
     */
    private suspend fun uploadPendingFiles(): Boolean {
        val now = System.currentTimeMillis()
        val backoffThreshold = now - com.application.motium.data.local.entities.PendingFileUploadEntity.calculateBackoffMs(0)

        val pendingUploads = pendingFileUploadDao.getUploadsReadyForRetry(backoffThreshold, batchSize = 20)

        if (pendingUploads.isEmpty()) {
            MotiumApplication.logger.d("No pending file uploads to process", TAG)
            return true
        }

        MotiumApplication.logger.i("Processing ${pendingUploads.size} pending file uploads in parallel", TAG)

        // BATTERY OPTIMIZATION: Process uploads in parallel for faster completion
        // Limit parallelism to 3 to avoid overwhelming the network/server
        val results = coroutineScope {
            pendingUploads.chunked(3).flatMap { batch ->
                batch.map { upload ->
                    async {
                        processFileUpload(upload, now)
                    }
                }.awaitAll()
            }
        }

        val allSuccess = results.all { it }

        // Clean up completed uploads older than 7 days
        cleanupOldCompletedUploads()

        return allSuccess
    }

    /**
     * Process a single file upload.
     * @return true if upload succeeded, false otherwise
     */
    private suspend fun processFileUpload(
        upload: com.application.motium.data.local.entities.PendingFileUploadEntity,
        now: Long
    ): Boolean {
        return try {
            // Mark as uploading
            pendingFileUploadDao.updateStatus(upload.id, com.application.motium.data.local.entities.PendingFileUploadEntity.STATUS_UPLOADING)

            // Upload file to Supabase Storage
            val result = storageService.uploadQueuedPhoto(upload.localUri)

            if (result.isSuccess) {
                val publicUrl = result.getOrNull()!!

                // Mark upload as completed
                pendingFileUploadDao.markCompleted(upload.id, publicUrl)

                // Update expense entity with public URL
                val expense = expenseDao.getExpenseById(upload.expenseId)
                if (expense != null) {
                    val updatedExpense = expense.copy(
                        photoUri = publicUrl,
                        syncStatus = SyncStatus.PENDING_UPLOAD.name, // Mark expense for sync
                        localUpdatedAt = System.currentTimeMillis()
                    )
                    expenseDao.insertExpense(updatedExpense)
                    MotiumApplication.logger.i(
                        "✅ Uploaded receipt and updated expense ${upload.expenseId}: $publicUrl",
                        TAG
                    )
                } else {
                    MotiumApplication.logger.w(
                        "Expense ${upload.expenseId} not found for file upload ${upload.id}",
                        TAG
                    )
                }
                true
            } else {
                // Upload failed - mark as retried
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                pendingFileUploadDao.markRetried(upload.id, now, error)
                MotiumApplication.logger.e(
                    "Failed to upload file ${upload.id}: $error",
                    TAG
                )
                false
            }
        } catch (e: Exception) {
            pendingFileUploadDao.markRetried(upload.id, now, e.message)
            MotiumApplication.logger.e(
                "Exception during file upload ${upload.id}: ${e.message}",
                TAG,
                e
            )
            false
        }
    }

    /**
     * Delete completed file uploads older than 7 days to prevent database bloat.
     */
    private suspend fun cleanupOldCompletedUploads() {
        try {
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            val allUploads = pendingFileUploadDao.getAll()
            val oldCompleted = allUploads.filter {
                it.status == com.application.motium.data.local.entities.PendingFileUploadEntity.STATUS_COMPLETED
                        && it.createdAt < sevenDaysAgo
            }
            oldCompleted.forEach { upload ->
                pendingFileUploadDao.deleteById(upload.id)
            }
            if (oldCompleted.isNotEmpty()) {
                MotiumApplication.logger.i("Cleaned up ${oldCompleted.size} old completed uploads", TAG)
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to cleanup old uploads: ${e.message}", TAG, e)
        }
    }

    // ==================== ATOMIC SYNC PHASE (v2) ====================

    /**
     * Perform atomic push+pull synchronization via sync_changes() RPC.
     *
     * This method:
     * 1. Collects all pending operations ready for sync
     * 2. Calls sync_changes() RPC which atomically:
     *    - Processes all push operations in a transaction
     *    - Fetches all changes since lastSync in the same transaction
     * 3. Processes push results (mark synced or increment retry)
     * 4. Processes pulled changes (apply to local DB)
     *
     * @param userId Current user's ID
     * @return true if sync completed successfully
     */
    private suspend fun performAtomicSync(userId: String): Boolean {
        // FIX (2026-01-26): Acquire mutex to prevent concurrent sync execution.
        // Without this, periodic and immediate workers can race: one worker's pull
        // overwrites local changes that haven't been pushed yet by the other worker.
        return syncMutex.withLock {
            performAtomicSyncLocked(userId)
        }
    }

    private suspend fun performAtomicSyncLocked(userId: String): Boolean {
        // Calculate backoff threshold (operations ready for retry)
        val now = System.currentTimeMillis()
        val backoffThreshold = now - PendingOperationEntity.calculateBackoffMs(0)

        // FIX (2026-01): Reconcile orphan trips before fetching pending operations
        // This recreates missing operations for trips with PENDING_UPLOAD status but no operation in queue
        // Happens when operations were deleted after max retries but trip entity wasn't marked as error
        val reconciled = reconcileOrphanTrips(userId)
        if (reconciled > 0) {
            MotiumApplication.logger.i("🔧 Reconciled $reconciled orphan trips", TAG)
        }

        // Get pending operations that are ready for sync
        val pendingOps = pendingOpDao.getOperationsReadyForRetry(backoffThreshold, MAX_BATCH_SIZE)

        // Get minimum lastSyncTimestamp across all entity types
        val lastSync = getMinimumLastSyncTimestamp()

        MotiumApplication.logger.i(
            "Atomic sync: ${pendingOps.size} pending operations, lastSync: $lastSync (${java.util.Date(lastSync)})",
            TAG
        )

        // Mark all entity types as sync in progress
        markAllSyncInProgress(true)

        try {
            // SINGLE RPC CALL - atomic push + pull
            val result = syncChangesRemoteDataSource.syncChanges(pendingOps, lastSync)

            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                MotiumApplication.logger.e("Atomic sync failed: $error", TAG)
                setAllSyncErrors(error)
                return false
            }

            val syncResult = result.getOrNull()!!

            MotiumApplication.logger.i(
                "Atomic sync RPC completed: ${syncResult.successfulPushCount}/${syncResult.totalPushOperations} pushes, " +
                        "${syncResult.changes.totalChanges} changes pulled",
                TAG
            )

            // Step 1: Process push results - returns entity types with VERSION_CONFLICT
            val conflictedEntityTypes = processPushResults(syncResult.pushResults, pendingOps, now)

            // Step 2: Process pulled changes
            var success = true
            val changes = syncResult.changes
            success = processTripsChanges(changes.trips.map { it.toOldFormat() }, userId) && success
            success = processLinkedTripsChanges(changes.linkedTrips.map { it.toOldFormat() }, userId) && success
            success = processVehiclesChanges(changes.vehicles.map { it.toOldFormat() }, userId) && success
            success = processExpensesChanges(changes.expenses.map { it.toOldFormat() }, userId) && success
            success = processUserChanges(changes.users.map { it.toOldFormat() }) && success
            success = processProAccountChanges(changes.proAccounts.map { it.toOldFormat() }, userId) && success
            success = processLicenseChanges(changes.licenses.map { it.toOldFormat() }) && success
            success = processProLicenseChanges(changes.proLicenses.map { it.toOldFormat() }) && success
            success = processCompanyLinkChanges(changes.companyLinks.map { it.toOldFormat() }, userId) && success
            success = processConsentChanges(changes.consents.map { it.toOldFormat() }, userId) && success
            success = processWorkScheduleChanges(changes.workSchedules.map { it.toOldFormat() }, userId) && success
            success = processAutoTrackingSettingsChanges(changes.autoTrackingSettings.map { it.toOldFormat() }, userId) && success

            // Step 3: Update sync metadata with max timestamp
            // IMPORTANT: Skip entity types that had VERSION_CONFLICT - their timestamps were reset
            // to 0 in resetEntityForPull() and must stay at 0 to force re-fetch in next sync
            if (success && changes.totalChanges > 0) {
                updateLastSyncTimestampsExcluding(syncResult.maxTimestamp, conflictedEntityTypes)
            } else if (success) {
                // No changes but successful - update timestamps anyway (except conflicted ones)
                updateLastSyncTimestampsExcluding(System.currentTimeMillis(), conflictedEntityTypes)
            }

            return success

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e("Atomic sync failed: ${e.message}", TAG, e)
            setAllSyncErrors(e.message)
            return false
        } finally {
            markAllSyncInProgress(false)
        }
    }

    /**
     * Process push results from sync_changes() RPC.
     * Mark successful operations as synced, failed ones are retried.
     *
     * @return Set of entity types that had VERSION_CONFLICT - these should NOT have their
     *         SyncMetadata timestamps updated, so they get re-fetched in the next sync.
     */
    private suspend fun processPushResults(
        pushResults: List<SyncChangesRemoteDataSource.PushResult>,
        pendingOps: List<PendingOperationEntity>,
        timestamp: Long
    ): Set<String> {
        val conflictedEntityTypes = mutableSetOf<String>()

        // Create a map for quick lookup
        val resultsMap = pushResults.associateBy { "${it.entityType}:${it.entityId}" }

        pendingOps.forEach { op ->
            val key = "${op.entityType}:${op.entityId}"
            val result = resultsMap[key]

            if (result == null) {
                // No result for this operation - might have been deduplicated
                MotiumApplication.logger.w("No push result for operation $key", TAG)
                return@forEach
            }

            if (result.success) {
                // Success - delete pending operation
                pendingOpDao.deleteById(op.id)
                MotiumApplication.logger.d(
                    "Successfully synced ${op.action} for ${op.entityType}:${op.entityId}",
                    TAG
                )

                // Mark entity as synced in local DB
                markEntityAsSynced(op.entityType, op.entityId, timestamp)
            } else {
                // Special handling: rollback local LICENSE assignment on non-retryable server rejection
                if (op.entityType == PendingOperationEntity.TYPE_LICENSE) {
                    val handled = handleLicensePushFailure(op, result, timestamp)
                    if (handled) {
                        return@forEach
                    }
                }

                // Failed - check error code for retry strategy
                when (result.errorCode) {
                    "VERSION_CONFLICT" -> {
                        // Version conflict - server has newer version than what we sent.
                        // FIX (2026-01-26): For entities with user-editable fields,
                        // re-queue the operation with the correct server version instead of
                        // discarding the user's local changes. This ensures local changes persist.
                        val serverVer = result.serverVersion
                        val reQueueableTypes = setOf(
                            PendingOperationEntity.TYPE_USER,
                            PendingOperationEntity.TYPE_VEHICLE,
                            PendingOperationEntity.TYPE_TRIP
                        )
                        if (op.entityType in reQueueableTypes && serverVer != null && op.payload != null) {
                            // Re-queue with corrected version: serverVersion + 1
                            val correctedVersion = serverVer + 1
                            MotiumApplication.logger.w(
                                "Version conflict for ${op.entityType}:${op.entityId} (server v$serverVer), " +
                                        "re-queuing with corrected version v$correctedVersion to preserve local changes",
                                TAG
                            )
                            // Update local entity version to corrected version
                            when (op.entityType) {
                                PendingOperationEntity.TYPE_USER ->
                                    database.userDao().resetForPull(op.entityId, SyncStatus.PENDING_UPLOAD.name, 0L, correctedVersion)
                                PendingOperationEntity.TYPE_VEHICLE ->
                                    vehicleDao.resetForPull(op.entityId, SyncStatus.PENDING_UPLOAD.name, 0L, correctedVersion)
                                PendingOperationEntity.TYPE_TRIP ->
                                    tripDao.resetForPull(op.entityId, SyncStatus.PENDING_UPLOAD.name, 0L, correctedVersion)
                            }
                            // Reset SyncMetadata to force full re-fetch after the re-queued push succeeds
                            val syncMetadataType = when (op.entityType) {
                                PendingOperationEntity.TYPE_USER -> SyncMetadataEntity.TYPE_USER
                                PendingOperationEntity.TYPE_VEHICLE -> SyncMetadataEntity.TYPE_VEHICLE
                                PendingOperationEntity.TYPE_TRIP -> SyncMetadataEntity.TYPE_TRIP
                                else -> null
                            }
                            syncMetadataType?.let {
                                syncMetadataDao.updateLastSyncTimestamp(it, 0L, 0)
                            }

                            // Rewrite the payload with the corrected version
                            try {
                                val payloadJson = kotlinx.serialization.json.Json.parseToJsonElement(op.payload)
                                if (payloadJson is kotlinx.serialization.json.JsonObject) {
                                    val correctedPayload = kotlinx.serialization.json.buildJsonObject {
                                        payloadJson.forEach { (k, v) ->
                                            if (k == "version") {
                                                put("version", correctedVersion)
                                            } else {
                                                put(k, v)
                                            }
                                        }
                                    }.toString()
                                    // Replace the pending operation with corrected payload
                                    pendingOpDao.deleteById(op.id)
                                    val correctedOp = op.copy(
                                        id = java.util.UUID.randomUUID().toString(),
                                        payload = correctedPayload,
                                        idempotencyKey = "${op.entityType}:${op.entityId}:${op.action}:${System.currentTimeMillis()}",
                                        retryCount = 0,
                                        lastAttemptAt = null,
                                        lastError = null
                                    )
                                    pendingOpDao.insert(correctedOp)
                                    MotiumApplication.logger.i(
                                        "Re-queued ${op.entityType}:${op.entityId} with version=$correctedVersion",
                                        TAG
                                    )
                                } else {
                                    // Payload not a JSON object - fall through to default handling
                                    pendingOpDao.deleteById(op.id)
                                    resetEntityForPull(op.entityType, op.entityId, serverVer)
                                }
                            } catch (e: Exception) {
                                MotiumApplication.logger.w(
                                    "Failed to rewrite payload for ${op.entityType}:${op.entityId}, falling back to reset: ${e.message}",
                                    TAG
                                )
                                pendingOpDao.deleteById(op.id)
                                resetEntityForPull(op.entityType, op.entityId, serverVer)
                            }
                        } else {
                            // Non re-queueable entity or no server version: original behavior
                            MotiumApplication.logger.w(
                                "Version conflict for ${op.entityType}:${op.entityId} (server v$serverVer), " +
                                        "deleting stale operation and resetting entity with server version",
                                TAG
                            )
                            pendingOpDao.deleteById(op.id)
                            resetEntityForPull(op.entityType, op.entityId, serverVer)
                        }
                        // Track this entity type so we don't overwrite its reset timestamp
                        conflictedEntityTypes.add(op.entityType)
                    }
                    "ALREADY_PROCESSED" -> {
                        // Idempotent - already processed, safe to delete
                        pendingOpDao.deleteById(op.id)
                        MotiumApplication.logger.i(
                            "Operation already processed (idempotent): ${op.idempotencyKey}",
                            TAG
                        )
                    }
                    else -> {
                        // Other error - increment retry count
                        pendingOpDao.markRetried(op.id, timestamp, result.errorMessage)
                        MotiumApplication.logger.e(
                            "Push failed for ${op.entityType}:${op.entityId}: ${result.errorCode} - ${result.errorMessage}",
                            TAG
                        )
                    }
                }
            }
        }

        return conflictedEntityTypes
    }

    /**
     * Roll back local license assignment when server rejects it (non-retryable).
     * This prevents "visual" assignments that never succeeded on the server.
     */
    private suspend fun handleLicensePushFailure(
        op: PendingOperationEntity,
        result: SyncChangesRemoteDataSource.PushResult,
        timestamp: Long
    ): Boolean {
        if (op.action != PendingOperationEntity.ACTION_UPDATE) return false

        val payloadObj = parsePayloadObject(op.payload) ?: return false
        if (!isLicenseAssignmentPayload(payloadObj)) return false
        if (!isNonRetryableLicenseError(result)) return false

        MotiumApplication.logger.w(
            "Rolling back local license assignment after server rejection: " +
                "licenseId=${op.entityId}, error=${result.errorMessage}",
            TAG
        )

        // Revert local assignment to available pool
        licenseDao.rollbackAssignment(op.entityId, timestamp)

        // Remove pending operation to avoid infinite retries
        pendingOpDao.deleteById(op.id)

        // Force a full license re-fetch on next sync to reconcile with server
        syncMetadataDao.updateLastSyncTimestamp(SyncMetadataEntity.TYPE_LICENSE, 0L, 0)

        return true
    }

    private fun parsePayloadObject(payload: String?): JsonObject? {
        if (payload.isNullOrBlank()) return null
        return try {
            val element = Json.parseToJsonElement(payload)
            element as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun isLicenseAssignmentPayload(payload: JsonObject): Boolean {
        if (!payload.containsKey("linked_account_id")) return false
        val value = payload["linked_account_id"] ?: return false
        if (value is JsonNull) return false
        val raw = value.toString().trim('"')
        return raw.isNotBlank() && raw.lowercase(Locale.ROOT) != "null"
    }

    private fun isNonRetryableLicenseError(result: SyncChangesRemoteDataSource.PushResult): Boolean {
        val msg = result.errorMessage?.lowercase(Locale.ROOT) ?: return false
        return msg.contains("cannot assign license") ||
            msg.contains("not an active member") ||
            msg.contains("not authorized") ||
            msg.contains("license not found") ||
            msg.contains("license not available") ||
            msg.contains("already licensed") ||
            msg.contains("lifetime") ||
            msg.contains("only pro account owners")
    }

    /**
     * Mark an entity as synced in local database after successful push.
     */
    private suspend fun markEntityAsSynced(entityType: String, entityId: String, timestamp: Long) {
        try {
            when (entityType) {
                PendingOperationEntity.TYPE_TRIP -> tripDao.markTripAsSynced(entityId, timestamp)
                PendingOperationEntity.TYPE_VEHICLE -> vehicleDao.markVehicleAsSynced(entityId, timestamp)
                PendingOperationEntity.TYPE_LICENSE -> licenseDao.updateSyncStatus(entityId, SyncStatus.SYNCED.name)
                PendingOperationEntity.TYPE_PRO_ACCOUNT -> proAccountDao.updateSyncStatus(entityId, SyncStatus.SYNCED.name)
                PendingOperationEntity.TYPE_USER -> database.userDao().updateSyncStatus(entityId, SyncStatus.SYNCED.name, timestamp)
                PendingOperationEntity.TYPE_CONSENT -> {
                    // Consent entityId format: "userId:consentType"
                    val parts = entityId.split(":")
                    if (parts.size == 2) {
                        val consent = consentDao.getConsentByTypeOnce(parts[0], parts[1])
                        consent?.let { consentDao.markAsSynced(it.id, timestamp) }
                    }
                }
                PendingOperationEntity.TYPE_AUTO_TRACKING_SETTINGS -> {
                    // entityId is the userId for auto tracking settings
                    workScheduleDao.markAutoTrackingSettingsAsSynced(entityId, timestamp)
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w("Failed to mark $entityType:$entityId as synced: ${e.message}", TAG)
        }
    }

    /**
     * Reset an entity's syncStatus to SYNCED after VERSION_CONFLICT.
     * This allows the pull phase to update the entity with server's current version.
     * The stale local changes are discarded in favor of server's authoritative data.
     *
     * IMPORTANT: This function does TWO things to force re-fetch of the entity:
     * 1. Resets the local entity's serverUpdatedAt to 0 (epoch)
     * 2. Resets the SyncMetadataEntity timestamp to 0 for this entity type
     *
     * The second step is CRITICAL because the delta sync SQL uses:
     *   WHERE updated_at > since
     * Where 'since' comes from SyncMetadataEntity.lastSyncTimestamp (NOT from entity's serverUpdatedAt).
     * Without resetting SyncMetadataEntity, if server's updated_at is older than 'since',
     * the entity would never be re-fetched and the version conflict persists indefinitely.
     */
    private suspend fun resetEntityForPull(entityType: String, entityId: String, serverVersion: Int? = null) {
        try {
            val epochForForcePull = 0L
            val now = System.currentTimeMillis()

            // Step 1: Reset local entity's syncStatus, serverUpdatedAt, and version (if provided)
            // FIX (2026-01-24): For USER entity, update version to match server to prevent
            // infinite VERSION_CONFLICT loop where local version stays stale
            when (entityType) {
                PendingOperationEntity.TYPE_TRIP -> {
                    if (serverVersion != null) {
                        tripDao.resetForPull(entityId, SyncStatus.SYNCED.name, epochForForcePull, serverVersion)
                        MotiumApplication.logger.i(
                            "Reset TRIP:$entityId version to $serverVersion (from server)",
                            TAG
                        )
                    } else {
                        tripDao.markTripAsSynced(entityId, epochForForcePull)
                    }
                }
                PendingOperationEntity.TYPE_VEHICLE -> {
                    if (serverVersion != null) {
                        vehicleDao.resetForPull(entityId, SyncStatus.SYNCED.name, epochForForcePull, serverVersion)
                        MotiumApplication.logger.i(
                            "Reset VEHICLE:$entityId version to $serverVersion (from server)",
                            TAG
                        )
                    } else {
                        vehicleDao.markVehicleAsSynced(entityId, epochForForcePull)
                    }
                }
                PendingOperationEntity.TYPE_LICENSE -> licenseDao.updateSyncStatus(entityId, SyncStatus.SYNCED.name)
                PendingOperationEntity.TYPE_PRO_ACCOUNT -> proAccountDao.updateSyncStatus(entityId, SyncStatus.SYNCED.name)
                PendingOperationEntity.TYPE_USER -> {
                    if (serverVersion != null) {
                        // Use new resetForPull that updates version to prevent conflict loop
                        database.userDao().resetForPull(entityId, SyncStatus.SYNCED.name, epochForForcePull, serverVersion)
                        MotiumApplication.logger.i(
                            "Reset USER:$entityId version to $serverVersion (from server)",
                            TAG
                        )
                    } else {
                        database.userDao().updateSyncStatus(entityId, SyncStatus.SYNCED.name, epochForForcePull)
                    }
                }
                PendingOperationEntity.TYPE_CONSENT -> {
                    val parts = entityId.split(":")
                    if (parts.size == 2) {
                        val consent = consentDao.getConsentByTypeOnce(parts[0], parts[1])
                        consent?.let { consentDao.markAsSynced(it.id, now) }
                    }
                }
                PendingOperationEntity.TYPE_AUTO_TRACKING_SETTINGS -> {
                    workScheduleDao.markAutoTrackingSettingsAsSynced(entityId, now)
                }
            }

            // Step 2: CRITICAL - Reset the SyncMetadataEntity timestamp to force delta sync to re-fetch
            // Map PendingOperationEntity types to SyncMetadataEntity types
            val syncMetadataType = when (entityType) {
                PendingOperationEntity.TYPE_TRIP -> SyncMetadataEntity.TYPE_TRIP
                PendingOperationEntity.TYPE_VEHICLE -> SyncMetadataEntity.TYPE_VEHICLE
                PendingOperationEntity.TYPE_USER -> SyncMetadataEntity.TYPE_USER
                PendingOperationEntity.TYPE_LICENSE -> SyncMetadataEntity.TYPE_LICENSE
                PendingOperationEntity.TYPE_PRO_ACCOUNT -> SyncMetadataEntity.TYPE_PRO_ACCOUNT
                PendingOperationEntity.TYPE_CONSENT -> SyncMetadataEntity.TYPE_CONSENT
                PendingOperationEntity.TYPE_AUTO_TRACKING_SETTINGS -> SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS
                else -> null
            }

            syncMetadataType?.let {
                syncMetadataDao.updateLastSyncTimestamp(it, epochForForcePull, 0)
                MotiumApplication.logger.i(
                    "Reset SyncMetadata.$it lastSyncTimestamp to 0 to force delta re-fetch",
                    TAG
                )
            }

            MotiumApplication.logger.i("Reset $entityType:$entityId for forced pull (syncStatus=SYNCED, timestamps=0)", TAG)
        } catch (e: Exception) {
            MotiumApplication.logger.w("Failed to reset $entityType:$entityId for pull: ${e.message}", TAG)
        }
    }

    /**
     * Extension function to convert new ChangeRecord format to old format for backward compatibility.
     * This allows reusing existing processXxxChanges methods.
     */
    private fun SyncChangesRemoteDataSource.ChangeRecord.toOldFormat(): ChangesRemoteDataSource.ChangeRecord {
        return ChangesRemoteDataSource.ChangeRecord(
            entityType = this.entityType,
            entityId = this.entityId,
            action = this.action,
            data = this.data,
            updatedAt = this.updatedAt
        )
    }

    /*
     * ==================== LEGACY CODE REMOVED (2026-01) ====================
     *
     * The following methods have been REMOVED as they used direct Supabase calls
     * instead of the atomic sync_changes() RPC. All synchronization now goes
     * through performAtomicSync() → syncChangesRemoteDataSource.syncChanges().
     *
     * Removed methods:
     * - uploadPendingOperations() - replaced by performAtomicSync()
     * - processOperation() - no longer needed
     * - processTripOperation() - was using tripRemoteDataSource.saveTrip/deleteTrip
     * - processVehicleOperation() - was using vehicleRemoteDataSource.updateVehicle/deleteVehicle
     * - processLicenseOperation() - replaced by sync_changes RPC
     * - processProAccountOperation() - replaced by sync_changes RPC
     * - processCompanyLinkOperation() - replaced by sync_changes RPC
     * - processUserOperation() - replaced by sync_changes RPC
     * - processConsentOperation() - replaced by sync_changes RPC
     *
     * The tripRemoteDataSource and vehicleRemoteDataSource are no longer used
     * in this Worker. All sync operations are now atomic via sync_changes().
     *
     * See git history if you need to reference the old implementation.
     */

    // Legacy code block start - to be removed in future cleanup
    @Suppress("UNUSED", "DEPRECATION")
    @Deprecated("Use performAtomicSync() instead - kept for reference")
    private suspend fun uploadPendingOperations(userId: String): Boolean {
        // Calculate backoff threshold (operations ready for retry)
        val now = System.currentTimeMillis()
        // Get operations that haven't been attempted or whose backoff period has passed
        val backoffThreshold = now - PendingOperationEntity.calculateBackoffMs(0)

        val operations = pendingOpDao.getOperationsReadyForRetry(backoffThreshold, MAX_BATCH_SIZE)

        if (operations.isEmpty()) {
            MotiumApplication.logger.d("No pending operations to upload", TAG)
            return true
        }

        MotiumApplication.logger.i("Uploading ${operations.size} pending operations", TAG)

        var allSuccess = true

        // Group by entity type for batch processing
        operations.groupBy { it.entityType }.forEach { (entityType, ops) ->
            ops.forEach { operation ->
                try {
                    val success = processOperation(operation, userId)
                    if (success) {
                        pendingOpDao.deleteById(operation.id)
                        MotiumApplication.logger.d(
                            "Successfully processed ${operation.action} for ${operation.entityType}:${operation.entityId}",
                            TAG
                        )
                    } else {
                        pendingOpDao.markRetried(operation.id, now, "Operation returned false")
                        allSuccess = false
                    }
                } catch (e: Exception) {
                    pendingOpDao.markRetried(operation.id, now, e.message)
                    MotiumApplication.logger.e(
                        "Failed to process operation ${operation.id}: ${e.message}",
                        TAG,
                        e
                    )
                    allSuccess = false
                }
            }
        }

        return allSuccess
    }

    private suspend fun processOperation(operation: PendingOperationEntity, userId: String): Boolean {
        return when (operation.entityType) {
            PendingOperationEntity.TYPE_TRIP -> processTripOperation(operation, userId)
            PendingOperationEntity.TYPE_VEHICLE -> processVehicleOperation(operation, userId)
            PendingOperationEntity.TYPE_LICENSE -> processLicenseOperation(operation)
            PendingOperationEntity.TYPE_PRO_ACCOUNT -> processProAccountOperation(operation)
            PendingOperationEntity.TYPE_COMPANY_LINK -> processCompanyLinkOperation(operation, userId)
            PendingOperationEntity.TYPE_USER -> processUserOperation(operation, userId)
            PendingOperationEntity.TYPE_CONSENT -> processConsentOperation(operation, userId)
            else -> {
                MotiumApplication.logger.w("Unknown entity type: ${operation.entityType}", TAG)
                true // Remove unknown operations
            }
        }
    }

    private suspend fun processTripOperation(operation: PendingOperationEntity, userId: String): Boolean {
        return when (operation.action) {
            PendingOperationEntity.ACTION_CREATE,
            PendingOperationEntity.ACTION_UPDATE -> {
                val tripEntity = tripDao.getTripById(operation.entityId)
                if (tripEntity != null) {
                    // Convert to domain trip for Supabase
                    val domainTrip = tripEntity.toDataModel().toDomainTrip(userId)
                    val result = tripRemoteDataSource.saveTrip(domainTrip, userId)

                    if (result.isSuccess) {
                        // Mark as synced in local DB
                        tripDao.markTripAsSynced(operation.entityId, System.currentTimeMillis())
                        true
                    } else {
                        MotiumApplication.logger.e(
                            "Failed to save trip to Supabase: ${result.exceptionOrNull()?.message}",
                            TAG
                        )
                        false
                    }
                } else {
                    // Entity was deleted locally, remove operation
                    MotiumApplication.logger.d("Trip ${operation.entityId} not found locally, removing operation", TAG)
                    true
                }
            }
            PendingOperationEntity.ACTION_DELETE -> {
                val result = tripRemoteDataSource.deleteTrip(operation.entityId, userId)
                if (result.isSuccess) {
                    true
                } else {
                    MotiumApplication.logger.e(
                        "Failed to delete trip from Supabase: ${result.exceptionOrNull()?.message}",
                        TAG
                    )
                    // If trip doesn't exist on server, consider it success
                    result.exceptionOrNull()?.message?.contains("not found") == true
                }
            }
            else -> {
                MotiumApplication.logger.w("Unknown action: ${operation.action}", TAG)
                true
            }
        }
    }

    private suspend fun processVehicleOperation(operation: PendingOperationEntity, userId: String): Boolean {
        return when (operation.action) {
            PendingOperationEntity.ACTION_CREATE,
            PendingOperationEntity.ACTION_UPDATE -> {
                val vehicleEntity = vehicleDao.getVehicleById(operation.entityId)
                if (vehicleEntity != null) {
                    try {
                        val domainVehicle = vehicleEntity.toDomainModel()
                        vehicleRemoteDataSource.updateVehicle(domainVehicle)
                        vehicleDao.markVehicleAsSynced(operation.entityId, System.currentTimeMillis())
                        true
                    } catch (e: Exception) {
                        MotiumApplication.logger.e(
                            "Failed to save vehicle to Supabase: ${e.message}",
                            TAG,
                            e
                        )
                        false
                    }
                } else {
                    MotiumApplication.logger.d("Vehicle ${operation.entityId} not found locally, removing operation", TAG)
                    true
                }
            }
            PendingOperationEntity.ACTION_DELETE -> {
                val vehicleEntity = vehicleDao.getVehicleById(operation.entityId)
                if (vehicleEntity != null) {
                    try {
                        val domainVehicle = vehicleEntity.toDomainModel()
                        vehicleRemoteDataSource.deleteVehicle(domainVehicle)
                        true
                    } catch (e: Exception) {
                        MotiumApplication.logger.e(
                            "Failed to delete vehicle from Supabase: ${e.message}",
                            TAG,
                            e
                        )
                        // If vehicle doesn't exist on server, consider it success
                        e.message?.contains("not found") == true
                    }
                } else {
                    // Vehicle already deleted locally - nothing to do on server
                    true
                }
            }
            else -> true
        }
    }

    /**
     * Process license operations with full support for all license state changes.
     * Handles: assignLicense, unassignLicense, requestUnlink, cancelUnlinkRequest, cancelLicense
     */
    private suspend fun processLicenseOperation(operation: PendingOperationEntity): Boolean {
        if (operation.action != PendingOperationEntity.ACTION_UPDATE) {
            return true
        }

        val licenseEntity = licenseDao.getByIdOnce(operation.entityId)
        if (licenseEntity == null) {
            MotiumApplication.logger.d("License ${operation.entityId} not found locally, removing operation", TAG)
            return true
        }

        // DEBUG: Log full license state BEFORE decision logic
        MotiumApplication.logger.w(
            "🔍 DEBUG processLicenseOperation() - License state BEFORE sync decision:\n" +
            "   licenseId: ${licenseEntity.id}\n" +
            "   status: ${licenseEntity.status}\n" +
            "   linkedAccountId: ${licenseEntity.linkedAccountId}\n" +
            "   unlinkRequestedAt: ${licenseEntity.unlinkRequestedAt}\n" +
            "   unlinkEffectiveAt: ${licenseEntity.unlinkEffectiveAt}\n" +
            "   proAccountId: ${licenseEntity.proAccountId}",
            TAG
        )

        return try {
            // Determine the operation based on local state and execute
            val success: Boolean = when {
                // 1. License canceled - sync cancellation to Supabase
                licenseEntity.status == "canceled" -> {
                    MotiumApplication.logger.w(
                        "🔴 DEBUG BRANCH 1 SELECTED: status='canceled' → calling cancelLicense()",
                        TAG
                    )
                    supabaseLicenseRepository.cancelLicense(licenseEntity.id).isSuccess
                }

                // 2. Unlink request in progress (has unlinkRequestedAt AND still assigned)
                licenseEntity.unlinkRequestedAt != null && licenseEntity.linkedAccountId != null -> {
                    MotiumApplication.logger.w(
                        "🟠 DEBUG BRANCH 2 SELECTED: unlinkRequestedAt!=null && linkedAccountId!=null → calling requestUnlink()",
                        TAG
                    )
                    supabaseLicenseRepository.requestUnlink(
                        licenseEntity.id,
                        licenseEntity.proAccountId
                    ).isSuccess
                }

                // 3. Unlink request was cancelled (no unlinkRequestedAt, still assigned)
                // This case handles when cancelUnlinkRequest was called locally
                licenseEntity.unlinkRequestedAt == null
                    && licenseEntity.unlinkEffectiveAt == null
                    && licenseEntity.linkedAccountId != null -> {
                    // Could be either a fresh assignment OR a cancelled unlink request
                    // In both cases, we need to ensure the license is properly assigned on server
                    MotiumApplication.logger.w(
                        "🟢 DEBUG BRANCH 3 SELECTED: unlinkRequestedAt=null && unlinkEffectiveAt=null && linkedAccountId!=null → trying cancelUnlinkRequest/assignLicense",
                        TAG
                    )
                    // First try to cancel any pending unlink request on server
                    val cancelResult = supabaseLicenseRepository.cancelUnlinkRequest(
                        licenseEntity.id,
                        licenseEntity.proAccountId
                    )
                    // If cancel failed because there was no unlink request, just assign
                    if (cancelResult.isFailure && cancelResult.exceptionOrNull()?.message?.contains("Aucune demande") == true) {
                        supabaseLicenseRepository.assignLicense(
                            licenseEntity.id,
                            licenseEntity.linkedAccountId
                        ).isSuccess
                    } else {
                        cancelResult.isSuccess
                    }
                }

                // 4. License unassigned (linkedAccountId is null, was previously assigned)
                licenseEntity.linkedAccountId == null && licenseEntity.status != "canceled" -> {
                    MotiumApplication.logger.w(
                        "🟣 DEBUG BRANCH 4 SELECTED: linkedAccountId=null && status!='canceled' → calling unassignLicense() (IMMEDIATE UNLINK)",
                        TAG
                    )
                    supabaseLicenseRepository.unassignLicense(
                        licenseEntity.id,
                        licenseEntity.proAccountId
                    ).isSuccess
                }

                // 5. Default: license assigned (linkedAccountId is set)
                licenseEntity.linkedAccountId != null -> {
                    MotiumApplication.logger.w(
                        "🔵 DEBUG BRANCH 5 SELECTED: linkedAccountId!=null (fallback) → calling assignLicense()",
                        TAG
                    )
                    supabaseLicenseRepository.assignLicense(
                        licenseEntity.id,
                        licenseEntity.linkedAccountId
                    ).isSuccess
                }

                else -> {
                    MotiumApplication.logger.w(
                        "⚪ DEBUG BRANCH ELSE: No matching condition → no sync action",
                        TAG
                    )
                    true
                }
            }

            if (success) {
                licenseDao.updateSyncStatus(operation.entityId, SyncStatus.SYNCED.name)
                MotiumApplication.logger.i(
                    "Successfully synced license ${licenseEntity.id} to Supabase",
                    TAG
                )
                true
            } else {
                MotiumApplication.logger.e(
                    "Failed to sync license to Supabase",
                    TAG
                )
                false
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Failed to sync license: ${e.message}",
                TAG,
                e
            )
            false
        }
    }

    private suspend fun processProAccountOperation(operation: PendingOperationEntity): Boolean {
        return when (operation.action) {
            PendingOperationEntity.ACTION_UPDATE -> {
                val proAccountEntity = proAccountDao.getByIdOnce(operation.entityId)
                if (proAccountEntity != null) {
                    try {
                        val result = supabaseProAccountRepository.updateProAccount(
                            proAccountId = proAccountEntity.id,
                            companyName = proAccountEntity.companyName,
                            siret = proAccountEntity.siret,
                            vatNumber = proAccountEntity.vatNumber,
                            legalForm = proAccountEntity.legalForm,
                            billingAddress = proAccountEntity.billingAddress,
                            billingEmail = proAccountEntity.billingEmail
                        )

                        if (result.isSuccess) {
                            proAccountDao.updateSyncStatus(operation.entityId, SyncStatus.SYNCED.name)
                            true
                        } else {
                            MotiumApplication.logger.e(
                                "Failed to sync pro account to Supabase: ${result.exceptionOrNull()?.message}",
                                TAG
                            )
                            false
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.e(
                            "Failed to sync pro account: ${e.message}",
                            TAG,
                            e
                        )
                        false
                    }
                } else {
                    MotiumApplication.logger.d("ProAccount ${operation.entityId} not found locally, removing operation", TAG)
                    true
                }
            }
            else -> true
        }
    }

    /**
     * Process company link operations (mainly ACTIVATE action).
     */
    private suspend fun processCompanyLinkOperation(operation: PendingOperationEntity, userId: String): Boolean {
        return when (operation.action) {
            "ACTIVATE" -> {
                // Retry company link activation
                if (operation.payload == null) {
                    MotiumApplication.logger.w("Company link activation operation missing payload", TAG)
                    return true // Remove invalid operation
                }

                try {
                    // Parse payload to get token and userId
                    val payload = Json.decodeFromString<CompanyLinkRepository.ActivationPayload>(operation.payload)

                    MotiumApplication.logger.i(
                        "Retrying company link activation for token: ${payload.token}",
                        TAG
                    )

                    // Retry activation using the repository method
                    val result = companyLinkRepository.activateLinkByToken(payload.token, payload.userId)

                    if (result.isSuccess) {
                        // Activation succeeded - remove the pending operation
                        val companyLink = result.getOrNull()
                        MotiumApplication.logger.i(
                            "Successfully activated company link: ${companyLink?.companyName}",
                            TAG
                        )
                        true
                    } else {
                        // Activation failed - will retry later
                        val error = result.exceptionOrNull()?.message ?: "Unknown error"
                        MotiumApplication.logger.e(
                            "Failed to activate company link: $error",
                            TAG
                        )
                        false
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e(
                        "Error processing company link activation: ${e.message}",
                        TAG,
                        e
                    )
                    false
                }
            }
            else -> {
                MotiumApplication.logger.w("Unknown company link action: ${operation.action}", TAG)
                true // Remove unknown actions
            }
        }
    }

    /**
     * Process user profile operations (UPDATE only - user is created during registration).
     * Syncs user preferences (favoriteColors, considerFullDistance, phoneNumber, address) to Supabase.
     */
    private suspend fun processUserOperation(operation: PendingOperationEntity, userId: String): Boolean {
        // SECURITY: Validate operation belongs to current authenticated user
        // This prevents RLS violations when stale operations exist after account switch
        if (operation.entityId != userId) {
            MotiumApplication.logger.w(
                "Skipping stale user operation: entityId=${operation.entityId} != userId=$userId",
                TAG
            )
            return true // Delete stale operation
        }

        return when (operation.action) {
            PendingOperationEntity.ACTION_UPDATE -> {
                val userEntity = database.userDao().getUserById(operation.entityId)
                if (userEntity == null) {
                    MotiumApplication.logger.d("User ${operation.entityId} not found locally, removing operation", TAG)
                    return true
                }

                try {
                    val domainUser = userEntity.toDomainModel()

                    // Upload user profile to Supabase using SupabaseAuthRepository
                    val authRepo = com.application.motium.data.supabase.SupabaseAuthRepository.getInstance(applicationContext)
                    val result = authRepo.updateUserProfile(domainUser)

                    if (result is com.application.motium.domain.model.AuthResult.Success) {
                        // Mark as synced in local DB
                        database.userDao().updateSyncStatus(
                            userId = operation.entityId,
                            syncStatus = SyncStatus.SYNCED.name,
                            serverUpdatedAt = System.currentTimeMillis()
                        )
                        MotiumApplication.logger.i(
                            "✅ Synced user preferences to Supabase for user: ${domainUser.email}",
                            TAG
                        )
                        true
                    } else {
                        val error = (result as? com.application.motium.domain.model.AuthResult.Error)?.message
                        MotiumApplication.logger.e(
                            "Failed to sync user profile to Supabase: $error",
                            TAG
                        )
                        false
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e(
                        "Failed to sync user profile: ${e.message}",
                        TAG,
                        e
                    )
                    false
                }
            }
            else -> {
                MotiumApplication.logger.w("Unknown user operation: ${operation.action}", TAG)
                true // Remove unknown operations
            }
        }
    }

    /**
     * Process consent operations (UPDATE only).
     * Syncs user consent to Supabase user_consents table.
     */
    private suspend fun processConsentOperation(operation: PendingOperationEntity, userId: String): Boolean {
        return when (operation.action) {
            PendingOperationEntity.ACTION_UPDATE -> {
                // Parse entityId: "userId:consentType"
                val parts = operation.entityId.split(":")
                if (parts.size != 2) {
                    MotiumApplication.logger.w("Invalid consent entityId: ${operation.entityId}", TAG)
                    return true // Remove invalid operation
                }

                val consentUserId = parts[0]
                val consentType = parts[1]

                val consent = consentDao.getConsentByTypeOnce(consentUserId, consentType)
                if (consent == null) {
                    MotiumApplication.logger.d("Consent $consentType not found locally, removing operation", TAG)
                    return true
                }

                try {
                    // Convert to domain ConsentType
                    val type = com.application.motium.domain.model.ConsentType.valueOf(consentType.uppercase())
                    val version = consent.version.toString()

                    // Update via Supabase repository
                    val result = supabaseGdprRepository.updateConsent(type, consent.granted, version)

                    if (result.isSuccess) {
                        // Mark as synced
                        consentDao.markAsSynced(consent.id, System.currentTimeMillis())
                        MotiumApplication.logger.i(
                            "✅ Synced consent $consentType: granted=${consent.granted}",
                            TAG
                        )
                        true
                    } else {
                        MotiumApplication.logger.e(
                            "Failed to sync consent to Supabase: ${result.exceptionOrNull()?.message}",
                            TAG
                        )
                        false
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e(
                        "Failed to sync consent: ${e.message}",
                        TAG,
                        e
                    )
                    false
                }
            }
            else -> {
                MotiumApplication.logger.w("Unknown consent operation: ${operation.action}", TAG)
                true // Remove unknown operations
            }
        }
    }

    private suspend fun getMinimumLastSyncTimestamp(): Long {
        val types = listOf(
            SyncMetadataEntity.TYPE_TRIP,
            SyncMetadataEntity.TYPE_VEHICLE,
            SyncMetadataEntity.TYPE_EXPENSE,
            SyncMetadataEntity.TYPE_USER,
            SyncMetadataEntity.TYPE_LICENSE,
            SyncMetadataEntity.TYPE_PRO_ACCOUNT,
            SyncMetadataEntity.TYPE_CONSENT,
            SyncMetadataEntity.TYPE_WORK_SCHEDULE,
            SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS,
            SyncMetadataEntity.TYPE_COMPANY_LINK
        )

        return types.mapNotNull { syncMetadataDao.getLastSyncTimestamp(it) }
            .minOrNull() ?: 0L
    }

    private suspend fun markAllSyncInProgress(inProgress: Boolean) {
        listOf(
            SyncMetadataEntity.TYPE_TRIP,
            SyncMetadataEntity.TYPE_VEHICLE,
            SyncMetadataEntity.TYPE_EXPENSE,
            SyncMetadataEntity.TYPE_USER,
            SyncMetadataEntity.TYPE_LICENSE,
            SyncMetadataEntity.TYPE_PRO_ACCOUNT,
            SyncMetadataEntity.TYPE_CONSENT,
            SyncMetadataEntity.TYPE_WORK_SCHEDULE,
            SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS,
            SyncMetadataEntity.TYPE_COMPANY_LINK
        ).forEach { type ->
            syncMetadataDao.setSyncInProgress(type, inProgress)
        }
    }

    private suspend fun setAllSyncErrors(error: String?) {
        listOf(
            SyncMetadataEntity.TYPE_TRIP,
            SyncMetadataEntity.TYPE_VEHICLE,
            SyncMetadataEntity.TYPE_EXPENSE,
            SyncMetadataEntity.TYPE_USER,
            SyncMetadataEntity.TYPE_LICENSE,
            SyncMetadataEntity.TYPE_PRO_ACCOUNT,
            SyncMetadataEntity.TYPE_CONSENT,
            SyncMetadataEntity.TYPE_WORK_SCHEDULE,
            SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS,
            SyncMetadataEntity.TYPE_COMPANY_LINK
        ).forEach { type ->
            syncMetadataDao.setSyncError(type, error)
        }
    }

    private suspend fun updateAllLastSyncTimestamps(timestamp: Long) {
        listOf(
            SyncMetadataEntity.TYPE_TRIP,
            SyncMetadataEntity.TYPE_VEHICLE,
            SyncMetadataEntity.TYPE_EXPENSE,
            SyncMetadataEntity.TYPE_USER,
            SyncMetadataEntity.TYPE_LICENSE,
            SyncMetadataEntity.TYPE_PRO_ACCOUNT,
            SyncMetadataEntity.TYPE_CONSENT,
            SyncMetadataEntity.TYPE_WORK_SCHEDULE,
            SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS,
            SyncMetadataEntity.TYPE_COMPANY_LINK
        ).forEach { type ->
            syncMetadataDao.updateLastSyncTimestamp(type, timestamp, 0)
        }
    }

    /**
     * Update lastSyncTimestamp for all entity types EXCEPT those in the excludeTypes set.
     *
     * This is used after VERSION_CONFLICT: the conflicted entity type had its timestamp
     * reset to 0 by resetEntityForPull(), and we must NOT overwrite that with the new
     * timestamp. This ensures the next sync will re-fetch data for that entity type from epoch.
     *
     * @param timestamp The new lastSyncTimestamp to set
     * @param excludeTypes Set of PendingOperationEntity types to exclude (e.g., "USER", "TRIP")
     */
    private suspend fun updateLastSyncTimestampsExcluding(timestamp: Long, excludeTypes: Set<String>) {
        // Map PendingOperationEntity types to SyncMetadataEntity types
        val excludeSyncTypes = excludeTypes.mapNotNull { pendingType ->
            when (pendingType) {
                PendingOperationEntity.TYPE_TRIP -> SyncMetadataEntity.TYPE_TRIP
                PendingOperationEntity.TYPE_VEHICLE -> SyncMetadataEntity.TYPE_VEHICLE
                PendingOperationEntity.TYPE_USER -> SyncMetadataEntity.TYPE_USER
                PendingOperationEntity.TYPE_LICENSE -> SyncMetadataEntity.TYPE_LICENSE
                PendingOperationEntity.TYPE_PRO_ACCOUNT -> SyncMetadataEntity.TYPE_PRO_ACCOUNT
                PendingOperationEntity.TYPE_CONSENT -> SyncMetadataEntity.TYPE_CONSENT
                PendingOperationEntity.TYPE_AUTO_TRACKING_SETTINGS -> SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS
                else -> null
            }
        }.toSet()

        if (excludeSyncTypes.isNotEmpty()) {
            MotiumApplication.logger.i(
                "Updating timestamps excluding conflicted types: $excludeSyncTypes",
                TAG
            )
        }

        listOf(
            SyncMetadataEntity.TYPE_TRIP,
            SyncMetadataEntity.TYPE_VEHICLE,
            SyncMetadataEntity.TYPE_EXPENSE,
            SyncMetadataEntity.TYPE_USER,
            SyncMetadataEntity.TYPE_LICENSE,
            SyncMetadataEntity.TYPE_PRO_ACCOUNT,
            SyncMetadataEntity.TYPE_CONSENT,
            SyncMetadataEntity.TYPE_WORK_SCHEDULE,
            SyncMetadataEntity.TYPE_AUTO_TRACKING_SETTINGS,
            SyncMetadataEntity.TYPE_COMPANY_LINK
        ).forEach { type ->
            if (type !in excludeSyncTypes) {
                syncMetadataDao.updateLastSyncTimestamp(type, timestamp, 0)
            }
        }
    }

    // ==================== PROCESS CHANGES BY ENTITY TYPE ====================

    private suspend fun processTripsChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        tripDao.deleteTripById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToTripEntity(change.data, userId)
                        if (entity != null) {
                            val localTrip = tripDao.getTripById(entity.id)

                            if (localTrip == null) {
                                // New trip from server
                                tripDao.insertTrip(entity)
                                syncedCount++
                            } else if (localTrip.syncStatus == SyncStatus.SYNCED.name) {
                                // No local changes - update from server
                                tripDao.insertTrip(entity.copy(
                                    matchedRouteCoordinates = localTrip.matchedRouteCoordinates
                                ))
                                syncedCount++
                            } else {
                                // CONFLICT RESOLUTION: version-based with semantic merge
                                val serverVersion = entity.version
                                val localVersion = localTrip.version
                                val serverTimestamp = entity.serverUpdatedAt ?: 0L

                                when {
                                    // Case 1: Server has same or older version - local wins
                                    serverVersion <= localVersion -> {
                                        // Local wins - keep local changes, will be uploaded
                                        MotiumApplication.logger.d(
                                            "Conflict: Local wins (localVersion=$localVersion >= serverVersion=$serverVersion)",
                                            TAG
                                        )
                                    }
                                    // Case 2: Server has newer version - merge field-by-field
                                    serverTimestamp > localTrip.localUpdatedAt -> {
                                        // Server has genuinely newer data - merge
                                        val merged = mergeTrips(localTrip, entity)
                                        tripDao.insertTrip(merged)
                                        syncedCount++
                                        MotiumApplication.logger.i(
                                            "Conflict resolved: Merged local+server for trip ${entity.id}",
                                            TAG
                                        )
                                    }
                                    // Case 3: Local is newer timestamp-wise - mark as conflict for review
                                    else -> {
                                        tripDao.markTripAsConflict(localTrip.id)
                                        MotiumApplication.logger.w(
                                            "Conflict detected: Trip ${entity.id} marked for manual review",
                                            TAG
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing trip change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount trip changes", TAG)
        return true
    }

    private suspend fun processLinkedTripsChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        // LINKED_TRIP are read-only trips from collaborators (Pro feature)
        // They are stored in the same trips table but belong to other users
        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        tripDao.deleteTripById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        // For linked trips, use the user_id from the data, not current userId
                        val tripUserId = change.data["user_id"]?.toString()?.trim('"') ?: return@forEach
                        val entity = ChangeEntityMapper.mapToTripEntity(change.data, tripUserId)
                        if (entity != null) {
                            // Always overwrite - these are read-only trips from collaborators
                            tripDao.insertTrip(entity)
                            syncedCount++
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing linked trip change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount linked trip changes", TAG)
        return true
    }

    private suspend fun processVehiclesChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        vehicleDao.deleteVehicleById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToVehicleEntity(change.data, userId)
                        if (entity != null) {
                            val localVehicle = vehicleDao.getVehicleById(entity.id)

                            if (localVehicle == null) {
                                // New vehicle from server
                                vehicleDao.insertVehicle(entity)
                                syncedCount++
                            } else if (localVehicle.syncStatus == SyncStatus.SYNCED.name) {
                                // No local changes - update from server
                                vehicleDao.insertVehicle(entity)
                                syncedCount++
                            } else {
                                // CONFLICT RESOLUTION: version-based with semantic merge
                                val serverVersion = entity.version
                                val localVersion = localVehicle.version
                                val serverTimestamp = entity.serverUpdatedAt ?: 0L

                                when {
                                    // Case 1: Server has same or older version - local wins
                                    serverVersion <= localVersion -> {
                                        // Local wins - keep local changes, will be uploaded
                                        MotiumApplication.logger.d(
                                            "Vehicle conflict: Local wins (localVersion=$localVersion >= serverVersion=$serverVersion)",
                                            TAG
                                        )
                                    }
                                    // Case 2: Server has newer version - merge field-by-field
                                    serverTimestamp > localVehicle.localUpdatedAt -> {
                                        // Server has genuinely newer data - merge
                                        val merged = mergeVehicles(localVehicle, entity)
                                        vehicleDao.insertVehicle(merged)
                                        syncedCount++
                                        MotiumApplication.logger.i(
                                            "Vehicle conflict resolved: Merged local+server for vehicle ${entity.id}",
                                            TAG
                                        )
                                    }
                                    // Case 3: Local is newer timestamp-wise - keep local
                                    else -> {
                                        MotiumApplication.logger.d(
                                            "Vehicle conflict: Local wins on timestamp (local=${localVehicle.localUpdatedAt} > server=$serverTimestamp)",
                                            TAG
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing vehicle change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount vehicle changes", TAG)
        return true
    }

    private suspend fun processExpensesChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        expenseDao.deleteExpenseById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToExpenseEntity(change.data, userId)
                        if (entity != null) {
                            val localExpense = expenseDao.getExpenseById(entity.id)

                            if (localExpense == null || localExpense.syncStatus == SyncStatus.SYNCED.name) {
                                expenseDao.insertExpense(entity)
                                syncedCount++
                            } else {
                                // Check for pending operations before overwriting
                                val hasPendingOp = pendingOpDao.getByEntity(entity.id, PendingOperationEntity.TYPE_EXPENSE).isNotEmpty()
                                if (hasPendingOp) {
                                    MotiumApplication.logger.i(
                                        "Skipping pull overwrite for EXPENSE:${entity.id} - has pending local changes",
                                        TAG
                                    )
                                } else {
                                    expenseDao.insertExpense(entity)
                                    syncedCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing expense change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount expense changes", TAG)
        return true
    }

    private suspend fun processUserChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                if (change.action == "UPSERT") {
                    val entity = ChangeEntityMapper.mapToUserEntity(change.data)
                    if (entity != null) {
                        val localUser = database.userDao().getUserById(entity.id)

                        if (localUser == null) {
                            database.userDao().insertUser(entity)
                            syncedCount++
                        } else if (localUser.syncStatus == SyncStatus.SYNCED.name) {
                            // Preserve local connection status
                            database.userDao().updateUser(entity.copy(
                                isLocallyConnected = localUser.isLocallyConnected
                            ))
                            syncedCount++
                        } else {
                            // FIX (2026-01-26): Don't overwrite local changes that have pending operations.
                            // When syncStatus is PENDING_UPLOAD, there's a pending operation in the queue
                            // that will push the local changes to the server on the next sync.
                            // Overwriting here would discard the user's local change (e.g., consider_full_distance toggle)
                            // and the pending op would push stale data or be lost in race conditions.
                            val hasPendingOp = pendingOpDao.getByEntity(entity.id, PendingOperationEntity.TYPE_USER).isNotEmpty()
                            if (hasPendingOp) {
                                MotiumApplication.logger.i(
                                    "Skipping pull overwrite for USER:${entity.id} - has pending local changes (syncStatus=${localUser.syncStatus})",
                                    TAG
                                )
                            } else {
                                // No pending operation but entity is not SYNCED - apply server data
                                // This handles cases where syncStatus was left stale (e.g., after conflict resolution)
                                database.userDao().updateUser(entity.copy(
                                    isLocallyConnected = localUser.isLocallyConnected
                                ))
                                syncedCount++
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing user change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount user changes", TAG)
        return true
    }

    private suspend fun processProAccountChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                if (change.action == "UPSERT") {
                    val entity = ChangeEntityMapper.mapToProAccountEntity(change.data)
                    if (entity != null) {
                        val localProAccount = proAccountDao.getByIdOnce(entity.id)

                        if (localProAccount == null || localProAccount.syncStatus == SyncStatus.SYNCED.name) {
                            proAccountDao.upsert(entity)
                            syncedCount++
                        } else {
                            val serverTimestamp = entity.serverUpdatedAt ?: 0L
                            if (serverTimestamp > localProAccount.localUpdatedAt) {
                                proAccountDao.upsert(entity)
                                syncedCount++
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing pro account change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount pro account changes", TAG)
        return true
    }

    private suspend fun processLicenseChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        licenseDao.deleteById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToLicenseEntity(change.data)
                        if (entity != null) {
                            val localLicense = licenseDao.getByIdOnce(entity.id)

                            if (localLicense == null || localLicense.syncStatus == SyncStatus.SYNCED.name) {
                                licenseDao.upsert(entity)
                                syncedCount++
                            } else {
                                val serverTimestamp = entity.serverUpdatedAt ?: 0L
                                if (serverTimestamp > localLicense.localUpdatedAt) {
                                    licenseDao.upsert(entity)
                                    syncedCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing license change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount license changes", TAG)
        return true
    }

    private suspend fun processProLicenseChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>
    ): Boolean {
        // PRO_LICENSE are licenses owned by the user's pro account
        // Same processing as LICENSE
        return processLicenseChanges(changes)
    }

    private suspend fun processCompanyLinkChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0
        val companyLinkDao = database.companyLinkDao()

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        companyLinkDao.deleteCompanyLinkById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToCompanyLinkEntity(change.data, userId)
                        if (entity != null) {
                            val localLink = companyLinkDao.getCompanyLinkById(entity.id)

                            if (localLink == null || localLink.syncStatus == SyncStatus.SYNCED.name) {
                                companyLinkDao.insertCompanyLink(entity)
                                syncedCount++
                            } else {
                                // Conflict - last-write-wins
                                val serverTimestamp = entity.serverUpdatedAt ?: 0L
                                if (serverTimestamp > localLink.localUpdatedAt) {
                                    companyLinkDao.insertCompanyLink(entity)
                                    syncedCount++
                                }
                            }
                            // If local has pending changes and is newer, don't overwrite
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing company link change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount company link changes", TAG)
        return true
    }

    private suspend fun processConsentChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        // Consent revoked - update local
                        val consentType = change.data["consent_type"]?.toString()?.trim('"') ?: return@forEach
                        val localConsent = consentDao.getConsentByTypeOnce(userId, consentType)
                        if (localConsent != null) {
                            consentDao.upsert(localConsent.copy(
                                granted = false,
                                revokedAt = System.currentTimeMillis(),
                                syncStatus = SyncStatus.SYNCED.name
                            ))
                            syncedCount++
                        }
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToConsentEntity(change.data, userId)
                        if (entity != null) {
                            val localConsent = consentDao.getConsentByTypeOnce(userId, entity.consentType)

                            if (localConsent == null) {
                                consentDao.upsert(entity)
                                syncedCount++
                            } else if (localConsent.syncStatus == SyncStatus.SYNCED.name) {
                                // Update from server if different
                                if (localConsent.granted != entity.granted) {
                                    consentDao.upsert(entity.copy(id = localConsent.id))
                                    syncedCount++
                                }
                            }
                            // If local has pending changes, don't overwrite
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing consent change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount consent changes", TAG)
        return true
    }

    private suspend fun processWorkScheduleChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        workScheduleDao.deleteWorkScheduleById(change.entityId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToWorkScheduleEntity(change.data, userId)
                        if (entity != null) {
                            val localSchedule = workScheduleDao.getWorkScheduleById(entity.id)

                            if (localSchedule == null || localSchedule.syncStatus == SyncStatus.SYNCED.name) {
                                workScheduleDao.insertWorkSchedule(entity)
                                syncedCount++
                            } else {
                                // Check for pending operations before overwriting
                                val hasPendingOp = pendingOpDao.getByEntity(entity.id, PendingOperationEntity.TYPE_WORK_SCHEDULE).isNotEmpty()
                                if (hasPendingOp) {
                                    MotiumApplication.logger.i(
                                        "Skipping pull overwrite for WORK_SCHEDULE:${entity.id} - has pending local changes",
                                        TAG
                                    )
                                } else {
                                    workScheduleDao.insertWorkSchedule(entity)
                                    syncedCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing work schedule change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount work schedule changes", TAG)
        return true
    }

    private suspend fun processAutoTrackingSettingsChanges(
        changes: List<ChangesRemoteDataSource.ChangeRecord>,
        userId: String
    ): Boolean {
        if (changes.isEmpty()) return true

        var syncedCount = 0

        changes.forEach { change ->
            try {
                when (change.action) {
                    "DELETE" -> {
                        workScheduleDao.deleteAutoTrackingSettingsForUser(userId)
                        syncedCount++
                    }
                    "UPSERT" -> {
                        val entity = ChangeEntityMapper.mapToAutoTrackingSettingsEntity(change.data, userId)
                        if (entity != null) {
                            val localSettings = workScheduleDao.getAutoTrackingSettings(userId)

                            if (localSettings == null || localSettings.syncStatus == SyncStatus.SYNCED.name) {
                                workScheduleDao.insertAutoTrackingSettings(entity)
                                syncedCount++
                            } else {
                                // Check for pending operations before overwriting
                                // entityId for auto tracking settings is the userId
                                val hasPendingOp = pendingOpDao.getByEntity(userId, PendingOperationEntity.TYPE_AUTO_TRACKING_SETTINGS).isNotEmpty()
                                if (hasPendingOp) {
                                    MotiumApplication.logger.i(
                                        "Skipping pull overwrite for AUTO_TRACKING_SETTINGS:$userId - has pending local changes",
                                        TAG
                                    )
                                } else {
                                    workScheduleDao.insertAutoTrackingSettings(entity)
                                    syncedCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Always re-throw cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error processing auto tracking settings change: ${e.message}", TAG, e)
            }
        }

        MotiumApplication.logger.i("Processed $syncedCount auto tracking settings changes", TAG)
        return true
    }

    // ==================== FIELD-LEVEL MERGE HELPERS ====================

    /**
     * Merge local and server trip data field-by-field.
     *
     * Strategy:
     * - User-editable fields (notes, tripType, vehicleId, isWorkHomeTrip): prefer LOCAL if modified
     * - Authoritative/system fields (locations, distance, timestamps, addresses): prefer SERVER
     * - Computed fields (reimbursementAmount): recalculate after merge
     * - Cache fields (matchedRouteCoordinates): preserve local cache
     * - Sync metadata: mark as SYNCED with server's version
     *
     * @param local The local entity with pending changes
     * @param server The server entity with newer data
     * @return Merged entity ready to be saved
     */
    private fun mergeTrips(local: TripEntity, server: TripEntity): TripEntity {
        // Determine if local has user-editable changes by checking if fields differ
        val localHasNotesChange = local.notes != null && local.notes != server.notes
        val localHasTripTypeChange = local.tripType != server.tripType
        val localHasVehicleChange = local.vehicleId != server.vehicleId
        val localHasWorkHomeFlagChange = local.isWorkHomeTrip != server.isWorkHomeTrip
        val localHasValidationChange = local.isValidated != server.isValidated

        // CRITICAL: If we preserved any local changes, mark as PENDING_UPLOAD
        // so the upload phase will push these merged changes to the server.
        // Otherwise, local changes would be silently lost.
        val hasPreservedLocalChanges = localHasNotesChange || localHasTripTypeChange ||
                localHasVehicleChange || localHasWorkHomeFlagChange || localHasValidationChange

        return server.copy(
            // ===== USER-EDITABLE FIELDS: Prefer local if changed =====
            notes = if (localHasNotesChange) local.notes else server.notes,
            tripType = if (localHasTripTypeChange) local.tripType else server.tripType,
            vehicleId = if (localHasVehicleChange) local.vehicleId else server.vehicleId,
            isWorkHomeTrip = if (localHasWorkHomeFlagChange) local.isWorkHomeTrip else server.isWorkHomeTrip,
            isValidated = if (localHasValidationChange) local.isValidated else server.isValidated,

            // ===== AUTHORITATIVE FIELDS: Always from server =====
            // (locations, distance, times, addresses are system-generated)

            // ===== COMPUTED FIELDS: Recalculate based on merged data =====
            // Keep server's reimbursementAmount as it's calculated server-side based on authoritative distance
            reimbursementAmount = server.reimbursementAmount,

            // ===== CACHE FIELDS: Preserve local cache (avoid re-fetching map-matched route) =====
            matchedRouteCoordinates = local.matchedRouteCoordinates ?: server.matchedRouteCoordinates,

            // ===== SYNC METADATA =====
            // If local changes preserved → PENDING_UPLOAD so upload phase syncs them
            // Otherwise → SYNCED as server and local are now identical
            syncStatus = if (hasPreservedLocalChanges) SyncStatus.PENDING_UPLOAD.name else SyncStatus.SYNCED.name,
            serverUpdatedAt = server.serverUpdatedAt,
            // CRITICAL: Increment version if we have local changes to upload.
            // This prevents the server from rejecting our upload due to version conflict.
            // The server expects version > current_server_version for updates.
            version = if (hasPreservedLocalChanges) server.version + 1 else server.version,
            localUpdatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Merge local and server vehicle data field-by-field.
     *
     * Strategy:
     * - User-editable fields (name, licensePlate, power, fuelType, mileageRate, isDefault): prefer LOCAL
     * - Mileage counters: prefer SERVER (authoritative cumulative values)
     * - Sync metadata: mark as SYNCED
     *
     * @param local The local entity with pending changes
     * @param server The server entity with newer data
     * @return Merged entity ready to be saved
     */
    private fun mergeVehicles(local: VehicleEntity, server: VehicleEntity): VehicleEntity {
        val localHasNameChange = local.name != server.name
        val localHasPlateChange = local.licensePlate != server.licensePlate
        val localHasPowerChange = local.power != server.power
        val localHasFuelChange = local.fuelType != server.fuelType
        val localHasRateChange = local.mileageRate != server.mileageRate
        val localHasDefaultChange = local.isDefault != server.isDefault

        // CRITICAL: If we preserved any local changes, mark as PENDING_UPLOAD
        // so the upload phase will push these merged changes to the server.
        val hasPreservedLocalChanges = localHasNameChange || localHasPlateChange ||
                localHasPowerChange || localHasFuelChange ||
                localHasRateChange || localHasDefaultChange

        return server.copy(
            // ===== USER-EDITABLE FIELDS: Prefer local if changed =====
            name = if (localHasNameChange) local.name else server.name,
            licensePlate = if (localHasPlateChange) local.licensePlate else server.licensePlate,
            power = if (localHasPowerChange) local.power else server.power,
            fuelType = if (localHasFuelChange) local.fuelType else server.fuelType,
            mileageRate = if (localHasRateChange) local.mileageRate else server.mileageRate,
            isDefault = if (localHasDefaultChange) local.isDefault else server.isDefault,

            // ===== MILEAGE COUNTERS: Server is authoritative =====
            // (cumulative values calculated from all validated trips)

            // ===== SYNC METADATA =====
            // If local changes preserved → PENDING_UPLOAD so upload phase syncs them
            syncStatus = if (hasPreservedLocalChanges) SyncStatus.PENDING_UPLOAD.name else SyncStatus.SYNCED.name,
            serverUpdatedAt = server.serverUpdatedAt,
            // CRITICAL: Increment version if we have local changes to upload.
            // This prevents the server from rejecting our upload due to version conflict.
            version = if (hasPreservedLocalChanges) server.version + 1 else server.version,
            localUpdatedAt = System.currentTimeMillis()
        )
    }

    // ==================== LEGACY METHODS (kept for fallback) ====================

    private suspend fun downloadProAccount(userId: String): Boolean {
        val entityType = SyncMetadataEntity.TYPE_PRO_ACCOUNT

        try {
            val result = supabaseProAccountRepository.getProAccount(userId)
            val serverProAccount = result.getOrNull()

            if (serverProAccount != null) {
                val localProAccount = proAccountDao.getByUserIdOnce(userId)

                if (localProAccount == null || localProAccount.syncStatus == SyncStatus.SYNCED.name) {
                    // Insert or update from server
                    val entity = ProAccountEntity(
                        id = serverProAccount.id,
                        userId = serverProAccount.userId,
                        companyName = serverProAccount.companyName,
                        siret = serverProAccount.siret,
                        vatNumber = serverProAccount.vatNumber,
                        legalForm = serverProAccount.legalForm,
                        billingAddress = serverProAccount.billingAddress,
                        billingEmail = serverProAccount.billingEmail,
                        billingDay = serverProAccount.billingDay,
                        departments = serverProAccount.departments?.toString() ?: "[]",
                        createdAt = serverProAccount.createdAt?.let { Instant.parse(it).toEpochMilliseconds() }
                            ?: System.currentTimeMillis(),
                        updatedAt = serverProAccount.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() }
                            ?: System.currentTimeMillis(),
                        syncStatus = SyncStatus.SYNCED.name,
                        serverUpdatedAt = serverProAccount.updatedAt?.let { Instant.parse(it).toEpochMilliseconds() }
                    )
                    proAccountDao.upsert(entity)
                    MotiumApplication.logger.i("Downloaded pro account for user $userId", TAG)
                }
            }
            return true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download pro account: ${e.message}", TAG, e)
            return false
        }
    }

    private suspend fun downloadLicenses(userId: String): Boolean {
        val entityType = SyncMetadataEntity.TYPE_LICENSE

        try {
            // First check if user has a pro account
            val proAccount = proAccountDao.getByUserIdOnce(userId) ?: return true

            val result = supabaseLicenseRepository.getLicenses(proAccount.id)
            val serverLicenses = result.getOrNull() ?: return false

            MotiumApplication.logger.i("Fetched ${serverLicenses.size} licenses from server", TAG)

            var syncedCount = 0
            serverLicenses.forEach { serverLicense ->
                val localLicense = licenseDao.getByIdOnce(serverLicense.id)

                if (localLicense == null || localLicense.syncStatus == SyncStatus.SYNCED.name) {
                    // Insert or update from server
                    val entity = LicenseEntity(
                        id = serverLicense.id,
                        proAccountId = serverLicense.proAccountId,
                        linkedAccountId = serverLicense.linkedAccountId,
                        linkedAt = serverLicense.linkedAt?.toEpochMilliseconds(),
                        isLifetime = serverLicense.isLifetime,
                        priceMonthlyHt = serverLicense.priceMonthlyHT,
                        vatRate = serverLicense.vatRate,
                        status = serverLicense.status.name.lowercase(),
                        startDate = serverLicense.startDate?.toEpochMilliseconds(),
                        endDate = serverLicense.endDate?.toEpochMilliseconds(),
                        unlinkRequestedAt = serverLicense.unlinkRequestedAt?.toEpochMilliseconds(),
                        unlinkEffectiveAt = serverLicense.unlinkEffectiveAt?.toEpochMilliseconds(),
                        billingStartsAt = serverLicense.billingStartsAt?.toEpochMilliseconds(),
                        stripeSubscriptionId = serverLicense.stripeSubscriptionId,
                        stripeSubscriptionItemId = serverLicense.stripeSubscriptionItemId,
                        stripePriceId = serverLicense.stripePriceId,
                        createdAt = serverLicense.createdAt.toEpochMilliseconds(),
                        updatedAt = serverLicense.updatedAt.toEpochMilliseconds(),
                        syncStatus = SyncStatus.SYNCED.name,
                        serverUpdatedAt = serverLicense.updatedAt.toEpochMilliseconds()
                    )
                    licenseDao.upsert(entity)
                    syncedCount++
                }
            }

            syncMetadataDao.updateLastSyncTimestamp(entityType, System.currentTimeMillis(), syncedCount)
            MotiumApplication.logger.i("Synced $syncedCount licenses from server", TAG)
            return true
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download licenses: ${e.message}", TAG, e)
            syncMetadataDao.setSyncError(entityType, e.message)
            return false
        }
    }

    /**
     * Download user profile from Supabase and update local preferences.
     * Implements conflict resolution: server wins if no pending local changes.
     */
    private suspend fun downloadUserProfile(userId: String): Boolean {
        try {
            val authRepo = com.application.motium.data.supabase.SupabaseAuthRepository.getInstance(applicationContext)
            val result = authRepo.getUserProfile(userId)

            if (result is com.application.motium.domain.model.AuthResult.Success) {
                val serverUser = result.data
                val localUser = database.userDao().getUserById(userId)

                if (localUser == null) {
                    // Should not happen - user should exist locally
                    MotiumApplication.logger.w("User $userId not found locally during profile sync", TAG)
                    return false
                }

                // Only update if local user has no pending changes (SYNCED status)
                if (localUser.syncStatus == SyncStatus.SYNCED.name) {
                    // Check if server has newer data
                    val serverUpdatedAt = serverUser.updatedAt.toEpochMilliseconds()
                    val localUpdatedAt = localUser.serverUpdatedAt ?: localUser.localUpdatedAt

                    if (serverUpdatedAt > localUpdatedAt) {
                        // Server has newer data - update local
                        val updatedEntity = serverUser.toEntity(
                            lastSyncedAt = System.currentTimeMillis(),
                            isLocallyConnected = localUser.isLocallyConnected
                        ).copy(
                            syncStatus = SyncStatus.SYNCED.name,
                            serverUpdatedAt = serverUpdatedAt
                        )
                        database.userDao().updateUser(updatedEntity)

                        MotiumApplication.logger.i(
                            "✅ Updated local user preferences from server (favoriteColors, considerFullDistance, etc.)",
                            TAG
                        )
                    } else {
                        MotiumApplication.logger.d(
                            "Local user preferences are up-to-date (localUpdatedAt: $localUpdatedAt >= serverUpdatedAt: $serverUpdatedAt)",
                            TAG
                        )
                    }
                } else {
                    // Local has pending changes - conflict resolution: local wins, will be uploaded later
                    MotiumApplication.logger.i(
                        "Skipping user profile download - local has pending changes (syncStatus: ${localUser.syncStatus})",
                        TAG
                    )
                }

                return true
            } else {
                val error = (result as? com.application.motium.domain.model.AuthResult.Error)?.message
                MotiumApplication.logger.e("Failed to fetch user profile from server: $error", TAG)
                return false
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download user profile: ${e.message}", TAG, e)
            return false
        }
    }

    /**
     * Download user consents from Supabase and update local database.
     * Implements conflict resolution: server wins if no pending local changes.
     */
    private suspend fun downloadConsents(userId: String): Boolean {
        val entityType = SyncMetadataEntity.TYPE_CONSENT

        try {
            // Fetch consents from Supabase
            val result = supabaseGdprRepository.getConsents()
            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                syncMetadataDao.setSyncError(entityType, error)
                MotiumApplication.logger.e("Failed to fetch consents from server: $error", TAG)
                return false
            }

            val serverConsents = result.getOrNull() ?: emptyList()
            MotiumApplication.logger.i("Fetched ${serverConsents.size} consents from server", TAG)

            var syncedCount = 0

            serverConsents.forEach { serverConsent ->
                val consentType = serverConsent.type.name.lowercase()
                val localConsent = consentDao.getConsentByTypeOnce(userId, consentType)

                if (localConsent == null) {
                    // New consent from server - insert
                    val entity = ConsentEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        userId = userId,
                        consentType = consentType,
                        granted = serverConsent.granted,
                        version = serverConsent.version.toIntOrNull() ?: 1,
                        grantedAt = serverConsent.grantedAt?.toEpochMilliseconds(),
                        revokedAt = serverConsent.revokedAt?.toEpochMilliseconds(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        syncStatus = SyncStatus.SYNCED.name,
                        localUpdatedAt = System.currentTimeMillis(),
                        serverUpdatedAt = System.currentTimeMillis()
                    )
                    consentDao.upsert(entity)
                    syncedCount++
                } else if (localConsent.syncStatus == SyncStatus.SYNCED.name) {
                    // No local pending changes - update from server if needed
                    val serverUpdatedAt = serverConsent.grantedAt?.toEpochMilliseconds()
                        ?: serverConsent.revokedAt?.toEpochMilliseconds()
                        ?: System.currentTimeMillis()

                    // Only update if server has different value
                    if (localConsent.granted != serverConsent.granted) {
                        val entity = localConsent.copy(
                            granted = serverConsent.granted,
                            version = serverConsent.version.toIntOrNull() ?: localConsent.version,
                            grantedAt = serverConsent.grantedAt?.toEpochMilliseconds(),
                            revokedAt = serverConsent.revokedAt?.toEpochMilliseconds(),
                            updatedAt = System.currentTimeMillis(),
                            syncStatus = SyncStatus.SYNCED.name,
                            serverUpdatedAt = serverUpdatedAt
                        )
                        consentDao.upsert(entity)
                        syncedCount++
                        MotiumApplication.logger.i(
                            "Updated consent $consentType from server: granted=${serverConsent.granted}",
                            TAG
                        )
                    }
                } else {
                    // Local has pending changes - skip update, will be uploaded later
                    MotiumApplication.logger.d(
                        "Skipping consent $consentType - local has pending changes (syncStatus: ${localConsent.syncStatus})",
                        TAG
                    )
                }
            }

            // Update sync metadata
            syncMetadataDao.updateLastSyncTimestamp(entityType, System.currentTimeMillis(), syncedCount)
            MotiumApplication.logger.i("Synced $syncedCount consents from server", TAG)
            return true
        } catch (e: Exception) {
            syncMetadataDao.setSyncError(entityType, e.message)
            MotiumApplication.logger.e("Failed to download consents: ${e.message}", TAG, e)
            return false
        }
    }

    // ==================== ORPHAN TRIP RECONCILIATION ====================

    /**
     * Reconcile orphan trips - trips with PENDING_UPLOAD status but no pending operation.
     *
     * This can happen when:
     * - Operations were deleted after exceeding MAX_RETRY_COUNT (5 attempts)
     * - But the trip entity's syncStatus was never reset to ERROR or SYNCED
     * - Result: Trip is stuck forever in PENDING_UPLOAD without any operation to sync it
     *
     * Fix: Recreate CREATE operations for orphan trips so they get another chance to sync.
     *
     * @param userId Current user's ID
     * @return Number of orphan trips that were reconciled
     */
    private suspend fun reconcileOrphanTrips(userId: String): Int {
        try {
            // Get all trips that need sync (syncStatus != SYNCED)
            val tripsNeedingSync = tripDao.getTripsNeedingSync(userId)

            if (tripsNeedingSync.isEmpty()) {
                return 0
            }

            var reconciledCount = 0
            val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            for (trip in tripsNeedingSync) {
                // Only reconcile PENDING_UPLOAD trips (not CONFLICT, ERROR, etc.)
                if (trip.syncStatus != SyncStatus.PENDING_UPLOAD.name) {
                    continue
                }

                // Check if trip already has a pending operation
                val existingOps = pendingOpDao.getByEntity(trip.id, PendingOperationEntity.TYPE_TRIP)
                if (existingOps.isNotEmpty()) {
                    // Operation exists - no need to reconcile
                    continue
                }

                // ORPHAN DETECTED: Trip has PENDING_UPLOAD but no operation
                MotiumApplication.logger.w(
                    "🔧 Orphan trip detected: ${trip.id} - recreating CREATE operation",
                    TAG
                )

                // Build payload in the same format as TripRepository
                val startLoc = trip.locations.firstOrNull()
                val endLoc = trip.locations.lastOrNull()

                val tripPayload = buildJsonObject {
                    put("start_time", isoDateFormat.format(java.util.Date(trip.startTime)))
                    trip.endTime?.let { put("end_time", isoDateFormat.format(java.util.Date(it))) }

                    // Coordinates
                    startLoc?.let {
                        put("start_latitude", it.latitude)
                        put("start_longitude", it.longitude)
                    }
                    endLoc?.let {
                        put("end_latitude", it.latitude)
                        put("end_longitude", it.longitude)
                    }

                    // Addresses
                    trip.startAddress?.let { put("start_address", it) }
                    trip.endAddress?.let { put("end_address", it) }

                    // Distance and duration
                    put("distance_km", trip.totalDistance / 1000.0)
                    val durationMs = (trip.endTime ?: System.currentTimeMillis()) - trip.startTime
                    put("duration_ms", durationMs)

                    // Trip metadata
                    put("type", trip.tripType ?: "PERSONAL")
                    put("is_validated", trip.isValidated)
                    put("is_work_home_trip", trip.isWorkHomeTrip)
                    trip.vehicleId?.let { put("vehicle_id", it) }
                    trip.reimbursementAmount?.let { put("reimbursement_amount", it) }
                    trip.notes?.let { put("notes", it) }
                    trip.matchedRouteCoordinates?.let { put("matched_route_coordinates", it) }

                    // Include version for conflict detection
                    put("version", trip.version)
                }

                // Create operation - use CREATE since trip doesn't exist on server yet
                val now = System.currentTimeMillis()
                val idempotencyKey = PendingOperationEntity.generateIdempotencyKey(
                    PendingOperationEntity.TYPE_TRIP,
                    trip.id,
                    PendingOperationEntity.ACTION_CREATE,
                    now
                )

                val operation = PendingOperationEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    idempotencyKey = idempotencyKey,
                    entityType = PendingOperationEntity.TYPE_TRIP,
                    entityId = trip.id,
                    action = PendingOperationEntity.ACTION_CREATE,
                    payload = tripPayload.toString(),
                    createdAt = now,
                    retryCount = 0,
                    priority = 0
                )

                pendingOpDao.insert(operation)
                reconciledCount++

                MotiumApplication.logger.i(
                    "✅ Recreated CREATE operation for orphan trip: ${trip.id}",
                    TAG
                )
            }

            return reconciledCount
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to reconcile orphan trips: ${e.message}", TAG, e)
            return 0
        }
    }
}

// ==================== EXTENSION FUNCTIONS ====================

/**
 * Convert data Trip to domain Trip for Supabase sync.
 */
private fun com.application.motium.data.Trip.toDomainTrip(userId: String): com.application.motium.domain.model.Trip {
    val locationPoints = locations.map { tripLocation ->
        com.application.motium.domain.model.LocationPoint(
            latitude = tripLocation.latitude,
            longitude = tripLocation.longitude,
            timestamp = Instant.fromEpochMilliseconds(tripLocation.timestamp),
            accuracy = tripLocation.accuracy
        )
    }

    val startLocation = locations.firstOrNull()
    val endLocation = locations.lastOrNull()

    return com.application.motium.domain.model.Trip(
        id = id,
        userId = userId,
        vehicleId = vehicleId?.takeIf { it.isNotBlank() },
        startTime = Instant.fromEpochMilliseconds(startTime),
        endTime = endTime?.let { Instant.fromEpochMilliseconds(it) },
        startLatitude = startLocation?.latitude ?: 0.0,
        startLongitude = startLocation?.longitude ?: 0.0,
        endLatitude = endLocation?.latitude,
        endLongitude = endLocation?.longitude,
        startAddress = startAddress,
        endAddress = endAddress,
        distanceKm = totalDistance / 1000.0,
        durationMs = (endTime ?: System.currentTimeMillis()) - startTime,
        type = when (tripType) {
            "PROFESSIONAL" -> TripType.PROFESSIONAL
            else -> TripType.PERSONAL
        },
        isValidated = isValidated,
        cost = 0.0,
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
        tracePoints = locationPoints,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt)
    )
}

/**
 * Convert domain Trip to data Trip for local storage.
 */
private fun com.application.motium.domain.model.Trip.toDataTrip(): com.application.motium.data.Trip {
    val tripLocations = tracePoints?.map { locationPoint ->
        com.application.motium.data.TripLocation(
            latitude = locationPoint.latitude,
            longitude = locationPoint.longitude,
            accuracy = locationPoint.accuracy ?: 10.0f,
            timestamp = locationPoint.timestamp.toEpochMilliseconds()
        )
    }?.takeIf { it.isNotEmpty() } ?: run {
        val locations = mutableListOf<com.application.motium.data.TripLocation>()
        if (startLatitude != 0.0 || startLongitude != 0.0) {
            locations.add(
                com.application.motium.data.TripLocation(
                    latitude = startLatitude,
                    longitude = startLongitude,
                    accuracy = 10.0f,
                    timestamp = startTime.toEpochMilliseconds()
                )
            )
        }
        if (endLatitude != null && endLongitude != null) {
            locations.add(
                com.application.motium.data.TripLocation(
                    latitude = endLatitude!!,
                    longitude = endLongitude!!,
                    accuracy = 10.0f,
                    timestamp = endTime?.toEpochMilliseconds() ?: System.currentTimeMillis()
                )
            )
        }
        locations
    }

    return com.application.motium.data.Trip(
        id = id,
        startTime = startTime.toEpochMilliseconds(),
        endTime = endTime?.toEpochMilliseconds(),
        locations = tripLocations,
        totalDistance = distanceKm * 1000,
        isValidated = isValidated,
        vehicleId = vehicleId,
        startAddress = startAddress,
        endAddress = endAddress,
        tripType = when (type) {
            TripType.PROFESSIONAL -> "PROFESSIONAL"
            TripType.PERSONAL -> "PERSONAL"
        },
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        userId = userId,
        matchedRouteCoordinates = null
    )
}

/**
 * Convert data Trip to TripEntity for Room.
 */
private fun com.application.motium.data.Trip.toEntity(userId: String): com.application.motium.data.local.entities.TripEntity {
    return com.application.motium.data.local.entities.TripEntity(
        id = id,
        userId = userId,
        startTime = startTime,
        endTime = endTime,
        locations = locations,
        totalDistance = totalDistance,
        isValidated = isValidated,
        vehicleId = vehicleId,
        startAddress = startAddress,
        endAddress = endAddress,
        notes = notes,
        tripType = tripType,
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
        createdAt = createdAt,
        updatedAt = updatedAt,
        matchedRouteCoordinates = matchedRouteCoordinates,
        syncStatus = SyncStatus.SYNCED.name,
        localUpdatedAt = System.currentTimeMillis(),
        serverUpdatedAt = updatedAt,
        version = 1
    )
}

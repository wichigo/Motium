package com.application.motium.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.LicenseEntity
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.ProAccountEntity
import com.application.motium.data.local.entities.StripeSubscriptionEntity
import com.application.motium.data.local.entities.SyncMetadataEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toDataModel
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity as licenseToEntity
import com.application.motium.data.local.entities.toEntity as proAccountToEntity
import com.application.motium.data.local.entities.toEntity as stripeSubToEntity
import com.application.motium.data.local.entities.toEntity as vehicleToEntity
import com.application.motium.data.supabase.LicenseDto
import com.application.motium.data.supabase.LicenseRemoteDataSource as SupabaseLicenseRepository
import com.application.motium.data.supabase.ProAccountRemoteDataSource as SupabaseProAccountRepository
import com.application.motium.data.supabase.TripRemoteDataSource
import com.application.motium.data.supabase.VehicleRemoteDataSource
import com.application.motium.data.CompanyLinkRepository
import com.application.motium.domain.model.TripType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/**
 * WorkManager CoroutineWorker for delta synchronization.
 * Handles both upload (local changes) and download (server changes).
 *
 * Sync Process:
 * 1. Upload Phase: Process pending operations (CREATE, UPDATE, DELETE)
 * 2. Download Phase: Fetch changes from server since lastSyncTimestamp
 * 3. Conflict Resolution: Last-write-wins based on timestamps
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

    private val localUserRepository = LocalUserRepository.getInstance(applicationContext)
    private val tripRemoteDataSource = TripRemoteDataSource.getInstance(applicationContext)
    private val vehicleRemoteDataSource = VehicleRemoteDataSource.getInstance(applicationContext)
    private val supabaseLicenseRepository = SupabaseLicenseRepository.getInstance(applicationContext)
    private val supabaseProAccountRepository = SupabaseProAccountRepository.getInstance(applicationContext)
    private val companyLinkRepository = CompanyLinkRepository.getInstance(applicationContext)
    private val storageService = com.application.motium.service.SupabaseStorageService.getInstance(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
            if (user == null) {
                MotiumApplication.logger.w("No user logged in, skipping sync", TAG)
                return@withContext Result.success()
            }

            MotiumApplication.logger.i("Starting delta sync for user: ${user.id}", TAG)

            // Initialize sync metadata for all entity types
            initializeSyncMetadata()

            // Phase 1: Upload pending file uploads (receipts)
            val fileUploadSuccess = uploadPendingFiles()

            // Phase 2: Upload pending operations
            val uploadSuccess = uploadPendingOperations(user.id)

            // Phase 3: Download changes from server (delta sync)
            val downloadSuccess = downloadServerChanges(user.id)

            if (fileUploadSuccess && uploadSuccess && downloadSuccess) {
                MotiumApplication.logger.i("Delta sync completed successfully", TAG)
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

        MotiumApplication.logger.i("Processing ${pendingUploads.size} pending file uploads", TAG)

        var allSuccess = true

        pendingUploads.forEach { upload ->
            try {
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
                } else {
                    // Upload failed - mark as retried
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    pendingFileUploadDao.markRetried(upload.id, now, error)
                    MotiumApplication.logger.e(
                        "Failed to upload file ${upload.id}: $error",
                        TAG
                    )
                    allSuccess = false
                }
            } catch (e: Exception) {
                pendingFileUploadDao.markRetried(upload.id, now, e.message)
                MotiumApplication.logger.e(
                    "Exception during file upload ${upload.id}: ${e.message}",
                    TAG,
                    e
                )
                allSuccess = false
            }
        }

        // Clean up completed uploads older than 7 days
        cleanupOldCompletedUploads()

        return allSuccess
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

    // ==================== UPLOAD PHASE ====================

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

    private suspend fun processLicenseOperation(operation: PendingOperationEntity): Boolean {
        if (operation.action != PendingOperationEntity.ACTION_UPDATE) {
            return true
        }

        val licenseEntity = licenseDao.getByIdOnce(operation.entityId)
        if (licenseEntity == null) {
            MotiumApplication.logger.d("License ${operation.entityId} not found locally, removing operation", TAG)
            return true
        }

        return try {
            // Sync to Supabase using existing repository
            if (licenseEntity.linkedAccountId != null) {
                val result = supabaseLicenseRepository.assignLicense(
                    licenseEntity.id,
                    licenseEntity.linkedAccountId
                )
                if (result.isFailure) {
                    MotiumApplication.logger.e(
                        "Failed to sync license to Supabase: ${result.exceptionOrNull()?.message}",
                        TAG
                    )
                    return false
                }
            }
            // If unassigned or success, mark as synced
            licenseDao.updateSyncStatus(operation.entityId, SyncStatus.SYNCED.name)
            true
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

    // ==================== DOWNLOAD PHASE ====================

    private suspend fun downloadServerChanges(userId: String): Boolean {
        var success = true

        // Download trips delta
        try {
            success = downloadTripsDelta(userId) && success
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download trips: ${e.message}", TAG, e)
            success = false
        }

        // Download vehicles delta
        try {
            success = downloadVehiclesDelta(userId) && success
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download vehicles: ${e.message}", TAG, e)
            success = false
        }

        // Download Pro account (for Pro users)
        try {
            success = downloadProAccount(userId) && success
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download pro account: ${e.message}", TAG, e)
            // Don't fail sync for pro account - user might not be Pro
        }

        // Download licenses (for Pro users with pro_account)
        try {
            success = downloadLicenses(userId) && success
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to download licenses: ${e.message}", TAG, e)
            // Don't fail sync for licenses - user might not be Pro
        }

        return success
    }

    private suspend fun downloadTripsDelta(userId: String): Boolean {
        val entityType = SyncMetadataEntity.TYPE_TRIP
        val lastSync = syncMetadataDao.getLastSyncTimestamp(entityType) ?: 0L

        MotiumApplication.logger.i("Downloading trips delta since: $lastSync", TAG)

        // Mark sync in progress
        syncMetadataDao.setSyncInProgress(entityType, true)

        try {
            // Fetch all trips from server (future: add delta query with updated_at filter)
            val result = tripRemoteDataSource.getAllTrips(userId)

            if (result.isSuccess) {
                val serverTrips = result.getOrNull() ?: emptyList()
                MotiumApplication.logger.i("Fetched ${serverTrips.size} trips from server", TAG)

                var syncedCount = 0

                serverTrips.forEach { serverTrip ->
                    val localTrip = tripDao.getTripById(serverTrip.id)

                    if (localTrip == null) {
                        // New trip from server - insert
                        val entity = serverTrip.toDataTrip().toEntity(userId).copy(
                            syncStatus = SyncStatus.SYNCED.name,
                            serverUpdatedAt = serverTrip.updatedAt.toEpochMilliseconds(),
                            needsSync = false
                        )
                        tripDao.insertTrip(entity)
                        syncedCount++
                    } else if (localTrip.syncStatus == SyncStatus.SYNCED.name) {
                        // No local changes - update from server if newer
                        val serverUpdatedAt = serverTrip.updatedAt.toEpochMilliseconds()
                        val localUpdatedAt = localTrip.serverUpdatedAt ?: localTrip.localUpdatedAt

                        if (serverUpdatedAt > localUpdatedAt) {
                            val entity = serverTrip.toDataTrip().toEntity(userId).copy(
                                syncStatus = SyncStatus.SYNCED.name,
                                serverUpdatedAt = serverUpdatedAt,
                                needsSync = false,
                                // Preserve local cache
                                matchedRouteCoordinates = localTrip.matchedRouteCoordinates
                            )
                            tripDao.insertTrip(entity)
                            syncedCount++
                        }
                    } else {
                        // Local has pending changes - resolve conflict
                        resolveConflict(localTrip, serverTrip, userId)
                    }
                }

                // Update sync metadata
                syncMetadataDao.updateLastSyncTimestamp(entityType, System.currentTimeMillis(), syncedCount)
                MotiumApplication.logger.i("Synced $syncedCount trips from server", TAG)
                return true
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                syncMetadataDao.setSyncError(entityType, error)
                return false
            }
        } catch (e: Exception) {
            syncMetadataDao.setSyncError(entityType, e.message)
            throw e
        }
    }

    private suspend fun resolveConflict(
        local: com.application.motium.data.local.entities.TripEntity,
        server: com.application.motium.domain.model.Trip,
        userId: String
    ) {
        // Last-write-wins based on timestamps
        val serverTimestamp = server.updatedAt.toEpochMilliseconds()

        if (serverTimestamp > local.localUpdatedAt) {
            // Server wins - use server version
            MotiumApplication.logger.i(
                "Conflict resolved: Server wins for trip ${local.id}",
                TAG
            )
            val entity = server.toDataTrip().toEntity(userId).copy(
                syncStatus = SyncStatus.SYNCED.name,
                serverUpdatedAt = serverTimestamp,
                needsSync = false,
                matchedRouteCoordinates = local.matchedRouteCoordinates
            )
            tripDao.insertTrip(entity)
        } else {
            // Local wins - keep local, re-queue for upload
            MotiumApplication.logger.i(
                "Conflict resolved: Local wins for trip ${local.id}, re-queuing for upload",
                TAG
            )
            pendingOpDao.insert(
                PendingOperationEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    entityType = PendingOperationEntity.TYPE_TRIP,
                    entityId = local.id,
                    action = PendingOperationEntity.ACTION_UPDATE,
                    payload = null,
                    createdAt = System.currentTimeMillis(),
                    priority = 1
                )
            )
        }
    }

    private suspend fun downloadVehiclesDelta(userId: String): Boolean {
        val entityType = SyncMetadataEntity.TYPE_VEHICLE
        val lastSync = syncMetadataDao.getLastSyncTimestamp(entityType) ?: 0L

        MotiumApplication.logger.i("Downloading vehicles delta since: $lastSync", TAG)

        syncMetadataDao.setSyncInProgress(entityType, true)

        try {
            // getAllVehiclesForUser returns List<Vehicle> directly, throws on error
            val serverVehicles = vehicleRemoteDataSource.getAllVehiclesForUser(userId)
            MotiumApplication.logger.i("Fetched ${serverVehicles.size} vehicles from server", TAG)

            var syncedCount = 0

            serverVehicles.forEach { serverVehicle ->
                val localVehicle = vehicleDao.getVehicleById(serverVehicle.id)

                if (localVehicle == null) {
                    // New vehicle from server
                    val entity = serverVehicle.vehicleToEntity(
                        lastSyncedAt = System.currentTimeMillis(),
                        needsSync = false
                    ).copy(
                        syncStatus = SyncStatus.SYNCED.name,
                        serverUpdatedAt = serverVehicle.updatedAt.toEpochMilliseconds()
                    )
                    vehicleDao.insertVehicle(entity)
                    syncedCount++
                } else if (localVehicle.syncStatus == SyncStatus.SYNCED.name) {
                    // Update from server if newer
                    val serverUpdatedAt = serverVehicle.updatedAt.toEpochMilliseconds()
                    val localUpdatedAt = localVehicle.serverUpdatedAt ?: localVehicle.localUpdatedAt

                    if (serverUpdatedAt > localUpdatedAt) {
                        val entity = serverVehicle.vehicleToEntity(
                            lastSyncedAt = System.currentTimeMillis(),
                            needsSync = false
                        ).copy(
                            syncStatus = SyncStatus.SYNCED.name,
                            serverUpdatedAt = serverUpdatedAt
                        )
                        vehicleDao.insertVehicle(entity)
                        syncedCount++
                    }
                }
                // If local has pending changes, don't overwrite
            }

            syncMetadataDao.updateLastSyncTimestamp(entityType, System.currentTimeMillis(), syncedCount)
            MotiumApplication.logger.i("Synced $syncedCount vehicles from server", TAG)
            return true
        } catch (e: Exception) {
            syncMetadataDao.setSyncError(entityType, e.message)
            throw e
        }
    }

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
        lastSyncedAt = System.currentTimeMillis(),
        needsSync = false
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
        lastSyncedAt = lastSyncedAt,
        needsSync = needsSync,
        matchedRouteCoordinates = matchedRouteCoordinates
    )
}

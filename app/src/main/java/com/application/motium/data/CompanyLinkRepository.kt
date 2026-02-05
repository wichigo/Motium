package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.CompanyLinkDao
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.SupabaseClient
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.CompanyLink
import com.application.motium.domain.model.CompanyLinkPreferences
import com.application.motium.domain.model.LinkStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Repository for company links management.
 * Uses offline-first architecture: Room cache first, then sync with Supabase.
 */
class CompanyLinkRepository private constructor(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    // Room database for offline-first caching
    private val database = MotiumDatabase.getInstance(context)
    private val companyLinkDao: CompanyLinkDao = database.companyLinkDao()
    private val pendingOperationDao = database.pendingOperationDao()

    // Remote data sources for Edge Function calls
    private val linkedAccountRemoteDataSource by lazy { LinkedAccountRemoteDataSource.getInstance(context) }
    private val localUserRepository by lazy { LocalUserRepository.getInstance(context) }

    // ==================== DTOs for Supabase ====================

    @Serializable
    data class CompanyLinkDto(
        val id: String? = null,
        val user_id: String? = null,  // Nullable: null until user accepts invitation
        val linked_pro_account_id: String,
        val company_name: String,
        val department: String? = null,
        val status: String = "PENDING",
        val share_professional_trips: Boolean = true,
        val share_personal_trips: Boolean = false,
        val share_personal_info: Boolean = true,
        val share_expenses: Boolean = false,
        val invitation_token: String? = null,
        val linked_at: String? = null,
        val linked_activated_at: String? = null,
        val unlinked_at: String? = null,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    @Serializable
    data class ActivateLinkRequest(
        val p_token: String,
        val p_user_id: String
    )

    @Serializable
    data class ActivateLinkResponse(
        val success: Boolean,
        val link_id: String? = null,
        val linked_pro_account_id: String? = null,
        val company_name: String? = null,
        val error: String? = null
    )

    @Serializable
    data class UpdatePreferencesRequest(
        val p_link_id: String,
        val p_share_professional_trips: Boolean,
        val p_share_personal_trips: Boolean,
        val p_share_personal_info: Boolean,
        val p_share_expenses: Boolean = false
    )

    @Serializable
    data class UnlinkRequest(
        val p_link_id: String
    )

    @Serializable
    data class ActivationPayload(
        val token: String,
        val userId: String
    )

    @Serializable
    data class UnlinkConfirmationPayload(
        val linkId: String,
        val initiatedBy: String,
        val initiatorEmail: String
    )

    // ==================== Singleton ====================

    companion object {
        @Volatile
        private var instance: CompanyLinkRepository? = null

        fun getInstance(context: Context): CompanyLinkRepository {
            return instance ?: synchronized(this) {
                instance ?: CompanyLinkRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==================== Read Operations ====================

    /**
     * Get all company links for a user from local cache.
     * Uses Room first (offline-first).
     */
    suspend fun getCompanyLinks(userId: String): List<CompanyLink> = withContext(Dispatchers.IO) {
        try {
            val cachedLinks = companyLinkDao.getCompanyLinksForUser(userId)
            if (cachedLinks.isNotEmpty()) {
                MotiumApplication.logger.d("Loaded ${cachedLinks.size} company links from Room cache", "CompanyLinkRepository")
                return@withContext cachedLinks.map { it.toDomainModel() }
            }

            // If cache empty, try to load from Supabase
            MotiumApplication.logger.i("No cached company links, fetching from Supabase for user: $userId", "CompanyLinkRepository")
            val links = fetchCompanyLinksFromSupabase(userId)

            // Cache locally
            if (links.isNotEmpty()) {
                val entities = links.map { it.toEntity(syncStatus = SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis()) }
                companyLinkDao.insertCompanyLinks(entities)
                MotiumApplication.logger.i("Cached ${links.size} company links in Room", "CompanyLinkRepository")
            }

            links
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading company links: ${e.message}", "CompanyLinkRepository", e)
            // Fallback to local cache even on error
            try {
                companyLinkDao.getCompanyLinksForUser(userId).map { it.toDomainModel() }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Get company links as a Flow for reactive UI updates.
     */
    fun getCompanyLinksFlow(userId: String): Flow<List<CompanyLink>> {
        return companyLinkDao.getCompanyLinksForUserFlow(userId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    /**
     * Get only active company links.
     */
    suspend fun getActiveCompanyLinks(userId: String): List<CompanyLink> = withContext(Dispatchers.IO) {
        companyLinkDao.getActiveCompanyLinks(userId).map { it.toDomainModel() }
    }

    /**
     * Get active company links as Flow.
     */
    fun getActiveCompanyLinksFlow(userId: String): Flow<List<CompanyLink>> {
        return companyLinkDao.getActiveCompanyLinksFlow(userId)
            .map { entities -> entities.map { it.toDomainModel() } }
    }

    /**
     * Check if user has any active company links.
     */
    suspend fun hasActiveCompanyLinks(userId: String): Boolean = withContext(Dispatchers.IO) {
        companyLinkDao.getActiveLinksCount(userId) > 0
    }

    // ==================== Write Operations ====================

    /**
     * Activate a company link using an invitation token.
     * Calls Supabase RPC to validate and activate the link.
     */
    suspend fun activateLinkByToken(token: String, userId: String): Result<CompanyLink> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Activating company link with token for user: $userId", "CompanyLinkRepository")

            // Call Supabase RPC to activate the link
            val response = postgres.rpc(
                "activate_company_link",
                ActivateLinkRequest(p_token = token, p_user_id = userId)
            ).decodeSingleOrNull<ActivateLinkResponse>()

            if (response == null) {
                return@withContext Result.failure(Exception("Invalid response from server"))
            }

            if (!response.success) {
                return@withContext Result.failure(Exception(response.error ?: "Failed to activate link"))
            }

            val linkId = response.link_id ?: return@withContext Result.failure(Exception("No link ID returned"))
            val linkedProAccountId = response.linked_pro_account_id ?: return@withContext Result.failure(Exception("No pro account ID returned"))
            val companyName = response.company_name ?: "Unknown Company"

            // Create domain model
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val companyLink = CompanyLink(
                id = linkId,
                userId = userId,
                linkedProAccountId = linkedProAccountId,
                companyName = companyName,
                department = null,
                status = LinkStatus.ACTIVE,
                shareProfessionalTrips = true,
                sharePersonalTrips = false,
                sharePersonalInfo = true,
                shareExpenses = false,
                linkedAt = now,
                linkedActivatedAt = now,
                unlinkedAt = null,
                createdAt = now,
                updatedAt = now
            )

            // Save to local cache
            val entity = companyLink.toEntity(syncStatus = SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis())
            companyLinkDao.insertCompanyLink(entity)

            MotiumApplication.logger.i("Successfully activated link with company: $companyName", "CompanyLinkRepository")
            Result.success(companyLink)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error activating company link: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Offline-first activation of company link.
     * Saves pending state locally first, then attempts RPC.
     * If RPC fails, queues activation for retry in background sync.
     *
     * @param token Invitation token
     * @param userId User ID
     * @return Result with CompanyLink (either active or pending_activation status)
     */
    suspend fun activateLinkOfflineFirst(token: String, userId: String): Result<CompanyLink> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Offline-first activation for invitation token (redacted), user: $userId", "CompanyLinkRepository")

            // 1. Create a pending operation for this activation
            val operationId = UUID.randomUUID().toString()
            val payload = Json.encodeToString(ActivationPayload(token, userId))
            val createdAt = System.currentTimeMillis()
            val idempotencyKey = "${PendingOperationEntity.TYPE_COMPANY_LINK}:$token:ACTIVATE:$createdAt"

            val pendingOp = PendingOperationEntity(
                id = operationId,
                idempotencyKey = idempotencyKey,
                entityType = PendingOperationEntity.TYPE_COMPANY_LINK,
                entityId = token, // Use token as entity ID for activation operations
                action = "ACTIVATE", // Custom action for company link activation
                payload = payload,
                createdAt = createdAt,
                priority = 1 // High priority for activation
            )

            // Save pending operation first
            pendingOperationDao.insert(pendingOp)
            MotiumApplication.logger.i("Created pending activation operation: $operationId", "CompanyLinkRepository")

            // 2. Try RPC immediately
            try {
                val response = postgres.rpc(
                    "activate_company_link",
                    ActivateLinkRequest(p_token = token, p_user_id = userId)
                ).decodeSingleOrNull<ActivateLinkResponse>()

                if (response != null && response.success) {
                    // RPC succeeded - create active link
                    val linkId = response.link_id!!
                    val linkedProAccountId = response.linked_pro_account_id!!
                    val companyName = response.company_name ?: "Unknown Company"

                    val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                    val companyLink = CompanyLink(
                        id = linkId,
                        userId = userId,
                        linkedProAccountId = linkedProAccountId,
                        companyName = companyName,
                        department = null,
                        status = LinkStatus.ACTIVE,
                        shareProfessionalTrips = true,
                        sharePersonalTrips = false,
                        sharePersonalInfo = true,
                        shareExpenses = false,
                        linkedAt = now,
                        linkedActivatedAt = now,
                        unlinkedAt = null,
                        createdAt = now,
                        updatedAt = now
                    )

                    // Save to local cache
                    val entity = companyLink.toEntity(syncStatus = SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis())
                    companyLinkDao.insertCompanyLink(entity)

                    // Remove pending operation since activation succeeded
                    pendingOperationDao.deleteById(operationId)

                    MotiumApplication.logger.i("RPC activation succeeded for company: $companyName", "CompanyLinkRepository")
                    return@withContext Result.success(companyLink)
                } else {
                    throw Exception(response?.error ?: "Activation RPC returned unsuccessful response")
                }
            } catch (e: Exception) {
                // RPC failed - return pending activation state
                MotiumApplication.logger.w(
                    "RPC activation failed (${e.message}), returning PENDING_ACTIVATION state. Will retry in background.",
                    "CompanyLinkRepository"
                )

                // Create a pending company link to show in UI
                val pendingLink = createPendingCompanyLink(userId, token)

                // Save pending link to cache
                val entity = pendingLink.toEntity(syncStatus = SyncStatus.PENDING_UPLOAD.name)
                companyLinkDao.insertCompanyLink(entity)

                return@withContext Result.success(pendingLink)
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error in offline-first activation: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Create a pending company link for offline-first activation.
     */
    private fun createPendingCompanyLink(userId: String, token: String): CompanyLink {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return CompanyLink(
            id = "pending_$token", // Temporary ID
            userId = userId,
            linkedProAccountId = "", // Will be filled on successful activation
            companyName = "Activation en cours...", // Placeholder
            department = null,
            status = LinkStatus.PENDING_ACTIVATION,
            shareProfessionalTrips = true,
            sharePersonalTrips = false,
            sharePersonalInfo = true,
            shareExpenses = false,
            invitationToken = token,
            linkedAt = null,
            linkedActivatedAt = null,
            unlinkedAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Update sharing preferences for a company link.
     */
    suspend fun updateSharingPreferences(
        linkId: String,
        preferences: CompanyLinkPreferences
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()

            // Update locally first (offline-first)
            companyLinkDao.updateSharingPreferences(
                linkId = linkId,
                sharePro = preferences.shareProfessionalTrips,
                sharePerso = preferences.sharePersonalTrips,
                shareInfo = preferences.sharePersonalInfo,
                shareExpenses = preferences.shareExpenses,
                updatedAt = now
            )

            // Try to sync to Supabase
            try {
                postgres.rpc(
                    "update_company_link_preferences",
                    UpdatePreferencesRequest(
                        p_link_id = linkId,
                        p_share_professional_trips = preferences.shareProfessionalTrips,
                        p_share_personal_trips = preferences.sharePersonalTrips,
                        p_share_personal_info = preferences.sharePersonalInfo,
                        p_share_expenses = preferences.shareExpenses
                    )
                )
                // Mark as synced
                companyLinkDao.markCompanyLinkAsSynced(linkId, java.lang.System.currentTimeMillis())
                MotiumApplication.logger.i("Sharing preferences updated and synced for link: $linkId", "CompanyLinkRepository")
            } catch (e: Exception) {
                MotiumApplication.logger.w("Preferences saved locally, will sync later: ${e.message}", "CompanyLinkRepository")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating sharing preferences: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Request to unlink from a company.
     * This initiates the confirmation flow via Edge Function.
     * The actual unlink happens only after email confirmation.
     *
     * Flow:
     * 1. User calls requestUnlink() -> sends confirmation email
     * 2. User clicks link in email -> calls confirm-unlink Edge Function
     * 3. Edge Function updates status to UNLINKED
     *
     * For offline-first support:
     * - If online: calls Edge Function immediately
     * - If offline: queues pending operation for later sync
     *
     * @param linkId The company_link ID to unlink
     * @param initiatorEmail Optional email override (uses current user's email if not provided)
     * @return Result with success if confirmation email was sent (or queued)
     */
    suspend fun requestUnlink(linkId: String, initiatorEmail: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Get current user's email if not provided
            val email = initiatorEmail ?: localUserRepository.getLoggedInUser()?.email
            if (email.isNullOrBlank()) {
                MotiumApplication.logger.e("Cannot request unlink: user email not available", "CompanyLinkRepository")
                return@withContext Result.failure(Exception("User email not available"))
            }

            MotiumApplication.logger.i("Requesting unlink confirmation for link: $linkId", "CompanyLinkRepository")

            // 1. Create pending operation first (offline-first)
            val operationId = UUID.randomUUID().toString()
            val payload = Json.encodeToString(UnlinkConfirmationPayload(
                linkId = linkId,
                initiatedBy = "employee",
                initiatorEmail = email
            ))
            val createdAt = System.currentTimeMillis()
            val idempotencyKey = "${PendingOperationEntity.TYPE_COMPANY_LINK}:$linkId:REQUEST_UNLINK:$createdAt"

            val pendingOp = PendingOperationEntity(
                id = operationId,
                idempotencyKey = idempotencyKey,
                entityType = PendingOperationEntity.TYPE_COMPANY_LINK,
                entityId = linkId,
                action = "REQUEST_UNLINK", // Custom action for unlink confirmation request
                payload = payload,
                createdAt = createdAt,
                priority = 1 // High priority
            )

            pendingOperationDao.insert(pendingOp)
            MotiumApplication.logger.d("Created pending unlink operation: $operationId", "CompanyLinkRepository")

            // 2. Mark link as PENDING_UNLINK locally (optimistic update for UI feedback)
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            companyLinkDao.updateStatus(
                linkId = linkId,
                status = "PENDING_UNLINK",
                unlinkedAt = null, // Not yet unlinked
                updatedAt = now.toString()
            )

            // 3. Try to send confirmation request immediately
            try {
                val result = linkedAccountRemoteDataSource.requestUnlinkConfirmation(
                    linkId = linkId,
                    initiatedBy = "employee",
                    initiatorEmail = email
                )

                if (result.isSuccess) {
                    // Request sent successfully - remove pending operation
                    pendingOperationDao.deleteById(operationId)
                    MotiumApplication.logger.i(
                        "Unlink confirmation email sent for link $linkId",
                        "CompanyLinkRepository"
                    )
                    return@withContext Result.success(Unit)
                } else {
                    // Edge Function returned error - keep pending operation for retry
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    MotiumApplication.logger.w(
                        "Unlink confirmation request failed ($error), will retry in background",
                        "CompanyLinkRepository"
                    )
                    // Still return success - operation is queued
                    return@withContext Result.success(Unit)
                }
            } catch (e: Exception) {
                // Network error - keep pending operation for retry
                MotiumApplication.logger.w(
                    "Unlink confirmation request failed (${e.message}), queued for background sync",
                    "CompanyLinkRepository"
                )
                // Return success - operation is queued and will be retried
                return@withContext Result.success(Unit)
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error requesting unlink: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Process pending unlink confirmation requests.
     * Called by background sync workers.
     */
    suspend fun processPendingUnlinkRequests(): Int = withContext(Dispatchers.IO) {
        var processedCount = 0
        try {
            val pendingOps = pendingOperationDao.getByEntityTypeAndAction(
                entityType = PendingOperationEntity.TYPE_COMPANY_LINK,
                action = "REQUEST_UNLINK"
            )

            for (op in pendingOps) {
                try {
                    val payloadStr = op.payload ?: continue
                    val payload = Json.decodeFromString<UnlinkConfirmationPayload>(payloadStr)

                    val result = linkedAccountRemoteDataSource.requestUnlinkConfirmation(
                        linkId = payload.linkId,
                        initiatedBy = payload.initiatedBy,
                        initiatorEmail = payload.initiatorEmail
                    )

                    if (result.isSuccess) {
                        pendingOperationDao.deleteById(op.id)
                        processedCount++
                        MotiumApplication.logger.i(
                            "Processed pending unlink request for link ${payload.linkId}",
                            "CompanyLinkRepository"
                        )
                    } else {
                        // Update retry count
                        pendingOperationDao.incrementRetryCount(op.id, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.w(
                        "Failed to process pending unlink ${op.id}: ${e.message}",
                        "CompanyLinkRepository"
                    )
                    pendingOperationDao.incrementRetryCount(op.id, System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error processing pending unlink requests: ${e.message}",
                "CompanyLinkRepository",
                e
            )
        }
        return@withContext processedCount
    }

    // ==================== Sync Operations ====================

    /**
     * Fetch company links from Supabase.
     */
    private suspend fun fetchCompanyLinksFromSupabase(userId: String): List<CompanyLink> {
        return try {
            val response = postgres.from("company_links")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeList<CompanyLinkDto>()
            response.map { it.toCompanyLink() }
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired, refreshing token and retrying...", "CompanyLinkRepository")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return try {
                        val response = postgres.from("company_links")
                            .select {
                                filter {
                                    eq("user_id", userId)
                                }
                            }.decodeList<CompanyLinkDto>()
                        response.map { it.toCompanyLink() }
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "CompanyLinkRepository", retryError)
                        emptyList()
                    }
                }
            }
            MotiumApplication.logger.e("Error fetching company links from Supabase: ${e.message}", "CompanyLinkRepository", e)
            emptyList()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching company links from Supabase: ${e.message}", "CompanyLinkRepository", e)
            emptyList()
        }
    }

    /**
     * Sync company links from Supabase to local cache.
     */
    suspend fun syncFromSupabase(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val remoteLinks = fetchCompanyLinksFromSupabase(userId)

            // Get local links that need sync (don't overwrite these)
            val localLinksNeedingSync = companyLinkDao.getCompanyLinksNeedingSync(userId)
            val localNeedsSyncIds = localLinksNeedingSync.map { it.id }.toSet()

            // Filter out remote links that have local changes pending
            val linksToSave = remoteLinks.filter { it.id !in localNeedsSyncIds }

            // Save remote links to local cache
            val entities = linksToSave.map { it.toEntity(syncStatus = SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis()) }
            companyLinkDao.insertCompanyLinks(entities)

            MotiumApplication.logger.i("Synced ${linksToSave.size} company links from Supabase", "CompanyLinkRepository")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing from Supabase: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Sync local changes to Supabase.
     */
    suspend fun syncToSupabase(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val linksNeedingSync = companyLinkDao.getCompanyLinksNeedingSync(userId)

            if (linksNeedingSync.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            for (entity in linksNeedingSync) {
                try {
                    val dto = CompanyLinkDto(
                        id = entity.id,
                        user_id = entity.userId,
                        linked_pro_account_id = entity.linkedProAccountId,
                        company_name = entity.companyName,
                        department = entity.department,
                        status = entity.status,
                        share_professional_trips = entity.shareProfessionalTrips,
                        share_personal_trips = entity.sharePersonalTrips,
                        share_personal_info = entity.sharePersonalInfo,
                        share_expenses = entity.shareExpenses,
                        linked_at = entity.linkedAt,
                        linked_activated_at = entity.linkedActivatedAt,
                        unlinked_at = entity.unlinkedAt,
                        updated_at = entity.updatedAt
                    )

                    postgres.from("company_links").upsert(dto)
                    companyLinkDao.markCompanyLinkAsSynced(entity.id, java.lang.System.currentTimeMillis())
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to sync link ${entity.id}: ${e.message}", "CompanyLinkRepository")
                }
            }

            MotiumApplication.logger.i("Synced ${linksNeedingSync.size} company links to Supabase", "CompanyLinkRepository")
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing to Supabase: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
    }

    // ==================== Helper Extensions ====================

    private fun CompanyLinkDto.toCompanyLink(): CompanyLink {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return CompanyLink(
            id = id ?: UUID.randomUUID().toString(),
            userId = user_id,
            linkedProAccountId = linked_pro_account_id,
            companyName = company_name,
            department = department,
            status = try { LinkStatus.valueOf(status) } catch (e: Exception) { LinkStatus.PENDING },
            shareProfessionalTrips = share_professional_trips,
            sharePersonalTrips = share_personal_trips,
            sharePersonalInfo = share_personal_info,
            shareExpenses = share_expenses,
            linkedAt = linked_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { null } },
            linkedActivatedAt = linked_activated_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { null } },
            unlinkedAt = unlinked_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { null } },
            createdAt = created_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { now } } ?: now,
            updatedAt = updated_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { now } } ?: now
        )
    }
}

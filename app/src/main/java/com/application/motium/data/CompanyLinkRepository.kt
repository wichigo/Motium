package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.CompanyLinkDao
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
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

    // ==================== DTOs for Supabase ====================

    @Serializable
    data class CompanyLinkDto(
        val id: String? = null,
        val user_id: String,
        val company_id: String,
        val company_name: String,
        val status: String = "PENDING",
        val share_professional_trips: Boolean = true,
        val share_personal_trips: Boolean = false,
        val share_personal_info: Boolean = true,
        val invitation_token: String? = null,
        val linked_at: String? = null,
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
        val company_id: String? = null,
        val company_name: String? = null,
        val error: String? = null
    )

    @Serializable
    data class UpdatePreferencesRequest(
        val p_link_id: String,
        val p_share_professional_trips: Boolean,
        val p_share_personal_trips: Boolean,
        val p_share_personal_info: Boolean
    )

    @Serializable
    data class UnlinkRequest(
        val p_link_id: String
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
                val entities = links.map { it.toEntity(lastSyncedAt = java.lang.System.currentTimeMillis(), needsSync = false) }
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
            val companyId = response.company_id ?: return@withContext Result.failure(Exception("No company ID returned"))
            val companyName = response.company_name ?: "Unknown Company"

            // Create domain model
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val companyLink = CompanyLink(
                id = linkId,
                userId = userId,
                companyId = companyId,
                companyName = companyName,
                status = LinkStatus.ACTIVE,
                shareProfessionalTrips = true,
                sharePersonalTrips = false,
                sharePersonalInfo = true,
                linkedAt = now,
                unlinkedAt = null,
                createdAt = now,
                updatedAt = now
            )

            // Save to local cache
            val entity = companyLink.toEntity(lastSyncedAt = java.lang.System.currentTimeMillis(), needsSync = false)
            companyLinkDao.insertCompanyLink(entity)

            MotiumApplication.logger.i("Successfully activated link with company: $companyName", "CompanyLinkRepository")
            Result.success(companyLink)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error activating company link: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
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
                        p_share_personal_info = preferences.sharePersonalInfo
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
     * Sets status to UNLINKED and records unlink timestamp.
     */
    suspend fun requestUnlink(linkId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

            // Update locally first
            companyLinkDao.updateStatus(
                linkId = linkId,
                status = LinkStatus.UNLINKED.name,
                unlinkedAt = now.toString(),
                updatedAt = now.toString()
            )

            // Try to sync to Supabase
            try {
                postgres.rpc(
                    "unlink_company",
                    UnlinkRequest(p_link_id = linkId)
                )
                companyLinkDao.markCompanyLinkAsSynced(linkId, java.lang.System.currentTimeMillis())
                MotiumApplication.logger.i("Company unlinked and synced: $linkId", "CompanyLinkRepository")
            } catch (e: Exception) {
                MotiumApplication.logger.w("Unlink saved locally, will sync later: ${e.message}", "CompanyLinkRepository")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error unlinking company: ${e.message}", "CompanyLinkRepository", e)
            Result.failure(e)
        }
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
            val entities = linksToSave.map { it.toEntity(lastSyncedAt = java.lang.System.currentTimeMillis(), needsSync = false) }
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
                        company_id = entity.companyId,
                        company_name = entity.companyName,
                        status = entity.status,
                        share_professional_trips = entity.shareProfessionalTrips,
                        share_personal_trips = entity.sharePersonalTrips,
                        share_personal_info = entity.sharePersonalInfo,
                        linked_at = entity.linkedAt,
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
            companyId = company_id,
            companyName = company_name,
            status = try { LinkStatus.valueOf(status) } catch (e: Exception) { LinkStatus.PENDING },
            shareProfessionalTrips = share_professional_trips,
            sharePersonalTrips = share_personal_trips,
            sharePersonalInfo = share_personal_info,
            linkedAt = linked_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { null } },
            unlinkedAt = unlinked_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { null } },
            createdAt = created_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { now } } ?: now,
            updatedAt = updated_at?.let { try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) { now } } ?: now
        )
    }
}

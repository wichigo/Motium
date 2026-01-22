package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.entities.PendingOperationEntity
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Remote data source for atomic push+pull synchronization via sync_changes() RPC.
 *
 * This replaces the old separate upload/download phases that caused race conditions.
 * The sync_changes() function:
 * 1. Processes all pending operations (push) within a transaction
 * 2. Fetches all changes since lastSync (pull) in the same transaction
 * 3. Returns both push results and pull changes atomically
 *
 * Key benefits:
 * - Atomic: Push and pull happen in single DB transaction, no race conditions
 * - Idempotent: Uses idempotency keys to prevent duplicate operations
 * - Version-based conflicts: Server rejects stale updates based on version field
 * - Single HTTP request: Reduces latency and bandwidth
 */
class SyncChangesRemoteDataSource(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest

    companion object {
        private const val TAG = "SyncChangesRemoteDS"

        @Volatile
        private var instance: SyncChangesRemoteDataSource? = null

        fun getInstance(context: Context): SyncChangesRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: SyncChangesRemoteDataSource(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==================== REQUEST/RESPONSE DTOs ====================

    /**
     * Operation to push to server.
     * Maps to the sync_changes() p_operations parameter structure.
     */
    @Serializable
    data class SyncOperation(
        @SerialName("entity_type")
        val entityType: String,    // TRIP, VEHICLE, USER, EXPENSE, etc.
        @SerialName("entity_id")
        val entityId: String,      // UUID
        val action: String,         // CREATE, UPDATE, DELETE
        val data: JsonObject?,      // Entity data for CREATE/UPDATE
        @SerialName("idempotency_key")
        val idempotencyKey: String, // Unique key to prevent duplicates
        val version: Int? = null    // For version-based conflict detection
    )

    /**
     * Result of a single push operation.
     *
     * Maps to SQL jsonb_build_object:
     * - idempotency_key, entity_type, entity_id, success
     * - error_message, conflict, server_version, already_processed
     */
    @Serializable
    data class PushResult(
        @SerialName("idempotency_key")
        val idempotencyKey: String? = null,
        @SerialName("entity_type")
        val entityType: String,
        @SerialName("entity_id")
        val entityId: String,
        val success: Boolean,
        @SerialName("error_message")
        val errorMessage: String? = null,
        val conflict: Boolean = false,
        @SerialName("server_version")
        val serverVersion: Int? = null,
        @SerialName("already_processed")
        val alreadyProcessed: Boolean = false
    ) {
        /**
         * Derive error code from error_message for backward compatibility.
         * Used by DeltaSyncWorker.processPushResults()
         */
        val errorCode: String?
            get() = when {
                conflict -> "VERSION_CONFLICT"
                alreadyProcessed -> "ALREADY_PROCESSED"
                errorMessage != null -> "ERROR"
                else -> null
            }
    }

    /**
     * Change record from server (pull).
     * Same structure as get_changes() for backward compatibility.
     */
    @Serializable
    data class ChangeRecord(
        @SerialName("entity_type")
        val entityType: String,
        @SerialName("entity_id")
        val entityId: String,
        val action: String,         // UPSERT or DELETE
        val data: JsonObject,
        @SerialName("updated_at")
        val updatedAt: String
    )

    /**
     * Full response from sync_changes() RPC.
     *
     * Maps to SQL:
     * RETURNS TABLE (
     *     push_results JSONB,     -- Array of PushResult
     *     pull_results JSONB,     -- Array of ChangeRecord
     *     sync_timestamp TIMESTAMPTZ
     * )
     */
    @Serializable
    data class SyncChangesResponse(
        @SerialName("push_results")
        val pushResults: List<PushResult>,
        @SerialName("pull_results")
        val pullResults: List<ChangeRecord>,
        @SerialName("sync_timestamp")
        val syncTimestamp: String
    )

    /**
     * Request DTO for sync_changes() RPC.
     */
    @Serializable
    data class SyncChangesRequest(
        @SerialName("operations")
        val operations: JsonArray,
        @SerialName("since")
        val since: String
    )

    // ==================== PUBLIC RESULT TYPES ====================

    /**
     * Aggregated result of sync operation.
     */
    data class SyncResult(
        val pushResults: List<PushResult>,
        val changes: ChangesResult,
        val maxTimestamp: Long,
        val successfulPushCount: Int,
        val failedPushCount: Int
    ) {
        val totalPushOperations: Int get() = pushResults.size
        val allPushesSuccessful: Boolean get() = failedPushCount == 0
    }

    /**
     * Changes grouped by entity type (same as ChangesRemoteDataSource.ChangesResult).
     */
    data class ChangesResult(
        val trips: List<ChangeRecord> = emptyList(),
        val linkedTrips: List<ChangeRecord> = emptyList(),
        val vehicles: List<ChangeRecord> = emptyList(),
        val expenses: List<ChangeRecord> = emptyList(),
        val users: List<ChangeRecord> = emptyList(),
        val workSchedules: List<ChangeRecord> = emptyList(),
        val autoTrackingSettings: List<ChangeRecord> = emptyList(),
        val proAccounts: List<ChangeRecord> = emptyList(),
        val companyLinks: List<ChangeRecord> = emptyList(),
        val licenses: List<ChangeRecord> = emptyList(),
        val proLicenses: List<ChangeRecord> = emptyList(),
        val consents: List<ChangeRecord> = emptyList(),
        val maxTimestamp: Long
    ) {
        val totalChanges: Int
            get() = trips.size + linkedTrips.size + vehicles.size + expenses.size +
                    users.size + workSchedules.size + autoTrackingSettings.size +
                    proAccounts.size + companyLinks.size + licenses.size +
                    proLicenses.size + consents.size
    }

    // ==================== MAIN SYNC METHOD ====================

    /**
     * Perform atomic push+pull sync via sync_changes() RPC.
     *
     * @param operations List of pending operations to push
     * @param sinceTimestamp Epoch milliseconds for delta pull
     * @return Result containing push results and pull changes
     */
    suspend fun syncChanges(
        operations: List<PendingOperationEntity>,
        sinceTimestamp: Long
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
        try {
            val sinceFormatted = formatTimestamp(sinceTimestamp)

            // Convert pending operations to JSON array
            val operationsJson = buildJsonArray {
                operations.forEach { op ->
                    add(buildJsonObject {
                        put("entity_type", op.entityType)
                        put("entity_id", op.entityId)
                        put("action", op.action)
                        put("idempotency_key", op.idempotencyKey)
                        // Parse payload if present - IMPORTANT: key must be "payload" to match server's op->'payload'
                        if (op.payload != null) {
                            try {
                                val dataObj = kotlinx.serialization.json.Json.parseToJsonElement(op.payload)
                                if (dataObj is JsonObject) {
                                    put("payload", dataObj)
                                    // Extract version from payload and add as client_version at operation level
                                    // The SQL expects op->>'client_version' for versioned entities (TRIP, VEHICLE, USER)
                                    val versionElement = dataObj["version"]
                                    if (versionElement != null) {
                                        val versionValue = versionElement.toString().trim('"').toIntOrNull()
                                        if (versionValue != null) {
                                            put("client_version", versionValue)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                MotiumApplication.logger.w(
                                    "Failed to parse payload for operation ${op.id}: ${e.message}",
                                    TAG
                                )
                            }
                        }
                    })
                }
            }

            MotiumApplication.logger.i(
                "Calling sync_changes() with ${operations.size} operations, since: $sinceFormatted",
                TAG
            )

            // DEBUG: Log LICENSE operations specifically for troubleshooting
            operations.filter { it.entityType == "LICENSE" }.forEach { licenseOp ->
                MotiumApplication.logger.w(
                    "🔵 DEBUG sync LICENSE op: id=${licenseOp.entityId}, action=${licenseOp.action}, payload=${licenseOp.payload}",
                    TAG
                )
            }

            // Call RPC
            val response = postgres.rpc(
                "sync_changes",
                SyncChangesRequest(
                    operations = operationsJson,
                    since = sinceFormatted
                )
            ).decodeSingle<SyncChangesResponse>()

            // Parse results
            val successCount = response.pushResults.count { it.success }
            val failCount = response.pushResults.count { !it.success }

            // Group changes by entity type
            val grouped = response.pullResults.groupBy { it.entityType }
            val maxTs = parseTimestamp(response.syncTimestamp)

            val changesResult = ChangesResult(
                trips = grouped["TRIP"] ?: emptyList(),
                linkedTrips = grouped["LINKED_TRIP"] ?: emptyList(),
                vehicles = grouped["VEHICLE"] ?: emptyList(),
                expenses = grouped["EXPENSE"] ?: emptyList(),
                users = grouped["USER"] ?: emptyList(),
                workSchedules = grouped["WORK_SCHEDULE"] ?: emptyList(),
                autoTrackingSettings = grouped["AUTO_TRACKING_SETTINGS"] ?: emptyList(),
                proAccounts = grouped["PRO_ACCOUNT"] ?: emptyList(),
                companyLinks = grouped["COMPANY_LINK"] ?: emptyList(),
                licenses = grouped["LICENSE"] ?: emptyList(),
                proLicenses = grouped["PRO_LICENSE"] ?: emptyList(),
                consents = grouped["CONSENT"] ?: emptyList(),
                maxTimestamp = maxTs
            )

            MotiumApplication.logger.i(
                "sync_changes() completed: $successCount/${operations.size} pushes succeeded, " +
                        "${response.pullResults.size} changes pulled",
                TAG
            )

            // Log push failures for debugging
            response.pushResults.filter { !it.success }.forEach { failure ->
                MotiumApplication.logger.w(
                    "Push failed: ${failure.entityType}:${failure.entityId} - " +
                            "${failure.errorCode}: ${failure.errorMessage}",
                    TAG
                )
            }

            // DEBUG: Log ALL LICENSE push results (success or failure) for troubleshooting
            response.pushResults.filter { it.entityType == "LICENSE" }.forEach { result ->
                MotiumApplication.logger.w(
                    "🔵 DEBUG LICENSE push result: id=${result.entityId}, success=${result.success}, " +
                            "error=${result.errorMessage ?: "none"}, alreadyProcessed=${result.alreadyProcessed}",
                    TAG
                )
            }

            Result.success(SyncResult(
                pushResults = response.pushResults,
                changes = changesResult,
                maxTimestamp = maxTs,
                successfulPushCount = successCount,
                failedPushCount = failCount
            ))

        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e("sync_changes() failed: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Perform pull-only sync (no pending operations).
     * Useful for initial sync or when there are no local changes.
     */
    suspend fun pullChanges(sinceTimestamp: Long): Result<SyncResult> {
        return syncChanges(emptyList(), sinceTimestamp)
    }

    // ==================== UTILITY METHODS ====================

    private fun formatTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            val normalized = timestamp.trim()
                .replace(" ", "T")
                .substringBefore("+")
                .let { if (it.endsWith("Z")) it else "${it}Z" }
            Instant.parse(normalized).toEpochMilliseconds()
        } catch (e: Exception) {
            MotiumApplication.logger.w("Failed to parse timestamp: $timestamp", TAG)
            System.currentTimeMillis()
        }
    }
}

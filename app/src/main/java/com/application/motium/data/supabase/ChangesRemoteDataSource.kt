package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Remote data source for fetching delta changes via single RPC call.
 * Replaces multiple individual getAllX() calls with a single get_changes() RPC.
 *
 * Performance improvement:
 * - Before: 8+ HTTP requests per sync, downloading ALL entities
 * - After: 1 HTTP request, downloading only changes since lastSync
 */
class ChangesRemoteDataSource(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest

    companion object {
        private const val TAG = "ChangesRemoteDataSource"

        @Volatile
        private var instance: ChangesRemoteDataSource? = null

        fun getInstance(context: Context): ChangesRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: ChangesRemoteDataSource(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * DTO for get_changes RPC response.
     * Maps directly to the SQL function return type.
     */
    @Serializable
    data class ChangeRecord(
        @SerialName("entity_type")
        val entityType: String,   // TRIP, VEHICLE, EXPENSE, USER, etc.
        @SerialName("entity_id")
        val entityId: String,     // UUID as string
        val action: String,        // UPSERT or DELETE
        val data: JsonObject,      // Full entity as JSONB
        @SerialName("updated_at")
        val updatedAt: String      // TIMESTAMPTZ as ISO string
    )

    /**
     * Request DTO for get_changes RPC.
     */
    @Serializable
    data class GetChangesRequest(
        val since: String  // TIMESTAMPTZ formatted string
    )

    /**
     * Result containing all changes grouped by entity type.
     */
    data class ChangesResult(
        val trips: List<ChangeRecord> = emptyList(),
        val linkedTrips: List<ChangeRecord> = emptyList(),  // Trajets des collaborateurs (Pro)
        val vehicles: List<ChangeRecord> = emptyList(),
        val expenses: List<ChangeRecord> = emptyList(),
        val users: List<ChangeRecord> = emptyList(),
        val workSchedules: List<ChangeRecord> = emptyList(),
        val autoTrackingSettings: List<ChangeRecord> = emptyList(),
        val proAccounts: List<ChangeRecord> = emptyList(),
        val companyLinks: List<ChangeRecord> = emptyList(),
        val licenses: List<ChangeRecord> = emptyList(),
        val proLicenses: List<ChangeRecord> = emptyList(),  // Licenses du compte Pro
        val consents: List<ChangeRecord> = emptyList(),
        val maxTimestamp: Long  // Maximum timestamp for updating lastSyncTimestamp
    ) {
        val totalChanges: Int
            get() = trips.size + linkedTrips.size + vehicles.size + expenses.size +
                    users.size + workSchedules.size + autoTrackingSettings.size +
                    proAccounts.size + companyLinks.size + licenses.size +
                    proLicenses.size + consents.size
    }

    /**
     * Fetch all changes since the given timestamp using single RPC call.
     *
     * @param sinceTimestamp Epoch milliseconds - fetch changes after this time.
     *                       Use 0 for first sync (epoch).
     * @return Result containing grouped changes and max timestamp
     */
    suspend fun getChanges(sinceTimestamp: Long): Result<ChangesResult> = withContext(Dispatchers.IO) {
        try {
            // Convert milliseconds to TIMESTAMPTZ format
            val sinceFormatted = formatTimestamp(sinceTimestamp)

            MotiumApplication.logger.i(
                "Fetching changes since: $sinceFormatted (epoch: $sinceTimestamp)",
                TAG
            )

            // Call RPC
            val changes = postgres.rpc(
                "get_changes",
                GetChangesRequest(since = sinceFormatted)
            ).decodeList<ChangeRecord>()

            MotiumApplication.logger.i(
                "Fetched ${changes.size} total changes from server",
                TAG
            )

            // Group by entity type
            val grouped = changes.groupBy { it.entityType }

            // Find max timestamp
            val maxTimestamp = changes.maxOfOrNull { parseTimestamp(it.updatedAt) }
                ?: sinceTimestamp

            val result = ChangesResult(
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
                maxTimestamp = maxTimestamp
            )

            MotiumApplication.logger.i(
                "Changes breakdown: " +
                        "${result.trips.size} trips, " +
                        "${result.linkedTrips.size} linked trips, " +
                        "${result.vehicles.size} vehicles, " +
                        "${result.expenses.size} expenses, " +
                        "${result.users.size} users, " +
                        "${result.workSchedules.size} workSchedules, " +
                        "${result.autoTrackingSettings.size} autoTrackingSettings, " +
                        "${result.proAccounts.size} proAccounts, " +
                        "${result.companyLinks.size} companyLinks, " +
                        "${result.licenses.size} licenses, " +
                        "${result.proLicenses.size} proLicenses, " +
                        "${result.consents.size} consents",
                TAG
            )

            Result.success(result)

        } catch (e: java.util.concurrent.CancellationException) {
            throw e  // Re-throw cancellation
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching changes: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Format epoch milliseconds to TIMESTAMPTZ string for Supabase.
     */
    private fun formatTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
    }

    /**
     * Parse TIMESTAMPTZ string to epoch milliseconds.
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            // Handle various PostgreSQL timestamp formats
            val normalized = timestamp.trim()
                .replace(" ", "T")
                .substringBefore("+")  // Remove timezone offset like +00:00
                .let { if (it.endsWith("Z")) it else "${it}Z" }

            Instant.parse(normalized).toEpochMilliseconds()
        } catch (e: Exception) {
            MotiumApplication.logger.w("Failed to parse timestamp: $timestamp", TAG)
            System.currentTimeMillis()
        }
    }
}

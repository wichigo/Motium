package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.Trip
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.*

/**
 * REMOTE DATA SOURCE - Direct API access to Supabase for trips.
 * For offline-first reads, the TripRepository wrapper uses TripDao.
 * This class is kept for write operations, sync, and Pro access to linked users' trips.
 */
class TripRemoteDataSource(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    @Serializable
    data class GpsPoint(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long, // Epoch milliseconds
        val accuracy: Float?
    )

    @Serializable
    data class SupabaseTrip(
        val id: String,
        val user_id: String,
        val vehicle_id: String? = null,
        val start_time: String, // TIMESTAMPTZ
        val end_time: String? = null, // TIMESTAMPTZ
        val start_latitude: Double,
        val start_longitude: Double,
        val end_latitude: Double? = null,
        val end_longitude: Double? = null,
        val start_address: String? = null,
        val end_address: String? = null,
        val distance_km: Double,
        val duration_ms: Long,
        val type: String,
        val is_validated: Boolean,
        val cost: Double = 0.0,
        val reimbursement_amount: Double? = null, // Stored mileage reimbursement (optional for old trips)
        val is_work_home_trip: Boolean, // Trajet travail-maison - NO DEFAULT to force serialization
        val trace_gps: String? = null, // JSONB as JSON string for manual trips
        val notes: String? = null,
        val matched_route_coordinates: String? = null,
        val created_at: String,
        val updated_at: String
    )

    /**
     * DTO for delta sync response - includes soft-deleted trips.
     * Used by DeltaSyncWorker to fetch only changed trips.
     */
    @Serializable
    data class SupabaseTripDelta(
        val id: String,
        val user_id: String,
        val vehicle_id: String? = null,
        val start_time: String,
        val end_time: String? = null,
        val start_latitude: Double,
        val start_longitude: Double,
        val end_latitude: Double? = null,
        val end_longitude: Double? = null,
        val start_address: String? = null,
        val end_address: String? = null,
        val distance_km: Double,
        val duration_ms: Long,
        val type: String,
        val is_validated: Boolean,
        val cost: Double = 0.0,
        val reimbursement_amount: Double? = null,
        val is_work_home_trip: Boolean, // NO DEFAULT to force serialization
        val trace_gps: String? = null,
        val notes: String? = null,
        val matched_route_coordinates: String? = null,
        val created_at: String,
        val updated_at: String,
        val deleted_at: String? = null, // For soft-delete support
        val version: Int = 1 // For optimistic locking
    )

    /**
     * Result class for delta sync operations
     */
    data class DeltaSyncResult(
        val updatedTrips: List<Trip>,
        val deletedTripIds: List<String>,
        val serverTimestamp: Long
    )

    @Serializable
    data class TripUpdate(
        val start_time: String,
        val end_time: String?,
        val start_latitude: Double,
        val start_longitude: Double,
        val end_latitude: Double?,
        val end_longitude: Double?,
        val start_address: String?,
        val end_address: String?,
        val distance_km: Double,
        val duration_ms: Long,
        val type: String,
        val is_validated: Boolean,
        val vehicle_id: String?,
        val cost: Double,
        val reimbursement_amount: Double?, // Stored mileage reimbursement
        val is_work_home_trip: Boolean, // NO DEFAULT to force serialization
        val trace_gps: String?,
        val notes: String?,
        val matched_route_coordinates: String?,
        val updated_at: String
    )

    suspend fun saveTrip(trip: Trip, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i(
                "📤 Saving trip to Supabase: ${trip.id}\n" +
                "   User ID: $userId\n" +
                "   Distance: ${trip.distanceKm} km\n" +
                "   Start: ${trip.startTime}\n" +
                "   End: ${trip.endTime}\n" +
                "   Trace points: ${trip.tracePoints?.size ?: 0}",
                "SupabaseTripRepository"
            )

            var vehicleId = trip.vehicleId?.takeIf { it.isNotBlank() } ?: try {
                VehicleRemoteDataSource.getInstance(context).getDefaultVehicle(userId)?.id
            } catch (e: Exception) {
                MotiumApplication.logger.w("Could not get default vehicle: ${e.message}", "SupabaseTripRepository")
                null
            }

            // Verify vehicle exists in Supabase to avoid FK constraint violation
            if (vehicleId != null) {
                val vehicleExists = try {
                    VehicleRemoteDataSource.getInstance(context).getVehicleById(vehicleId) != null
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Could not verify vehicle exists: ${e.message}", "SupabaseTripRepository")
                    false
                }
                if (!vehicleExists) {
                    MotiumApplication.logger.w(
                        "⚠️ Vehicle $vehicleId not found in Supabase - saving trip without vehicle to avoid FK constraint violation",
                        "SupabaseTripRepository"
                    )
                    vehicleId = null
                }
            }

            val startTimeFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(trip.startTime.toEpochMilliseconds()))

            val endTimeFormatted = trip.endTime?.let {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date(it.toEpochMilliseconds()))
            }

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            val createdAtFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(trip.createdAt.toEpochMilliseconds()))

            val traceGpsJson = trip.tracePoints?.let {
                if (it.isEmpty()) "{}" else json.encodeToString(it.map { point ->
                    GpsPoint(point.latitude, point.longitude, point.timestamp.toEpochMilliseconds(), point.accuracy)
                })
            } ?: "{}"


            val existingTrip = try {
                postgres.from("trips").select { filter { eq("id", trip.id) } }.decodeList<SupabaseTrip>().firstOrNull()
            } catch (e: Exception) {
                null
            }

            val supabaseTrip = SupabaseTrip(
                id = trip.id,
                user_id = userId,
                vehicle_id = vehicleId,
                start_time = startTimeFormatted,
                end_time = endTimeFormatted,
                start_latitude = trip.startLatitude,
                start_longitude = trip.startLongitude,
                end_latitude = trip.endLatitude,
                end_longitude = trip.endLongitude,
                start_address = trip.startAddress,
                end_address = trip.endAddress,
                distance_km = trip.distanceKm,
                duration_ms = trip.durationMs,
                type = trip.type.name, // FIX: Use actual trip type instead of hardcoded PERSONAL
                is_validated = trip.isValidated,
                cost = 0.0,
                reimbursement_amount = trip.reimbursementAmount,
                is_work_home_trip = trip.isWorkHomeTrip,
                trace_gps = traceGpsJson,
                notes = trip.notes,
                matched_route_coordinates = trip.matchedRouteCoordinates,
                created_at = existingTrip?.created_at ?: createdAtFormatted,
                updated_at = now
            )

            if (existingTrip != null) {
                postgres.from("trips").update(supabaseTrip) { filter { eq("id", trip.id) } }
            } else {
                postgres.from("trips").insert(supabaseTrip)
            }

            MotiumApplication.logger.i("Trip saved successfully to Supabase: ${trip.id}", "SupabaseTripRepository")
            Result.success(Unit)

        } catch (e: java.util.concurrent.CancellationException) {
            // Normal cancellation (e.g., user navigated away) - don't log as error
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving trip to Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    suspend fun updateTrip(trip: Trip, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating trip in Supabase: ${trip.id}", "SupabaseTripRepository")

            // Clean vehicleId: convert empty string to null
            var cleanVehicleId = trip.vehicleId?.takeIf { it.isNotBlank() }

            // Verify vehicle exists in Supabase to avoid FK constraint violation
            if (cleanVehicleId != null) {
                val vehicleExists = try {
                    VehicleRemoteDataSource.getInstance(context).getVehicleById(cleanVehicleId) != null
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Could not verify vehicle exists: ${e.message}", "SupabaseTripRepository")
                    false
                }
                if (!vehicleExists) {
                    MotiumApplication.logger.w(
                        "⚠️ Vehicle $cleanVehicleId not found in Supabase - updating trip without vehicle to avoid FK constraint violation",
                        "SupabaseTripRepository"
                    )
                    cleanVehicleId = null
                }
            }

            // Convertir les timestamps en format PostgreSQL TIMESTAMPTZ
            val startTimeFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(trip.startTime.toEpochMilliseconds()))

            val endTimeFormatted = trip.endTime?.let {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date(it.toEpochMilliseconds()))
            }

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            val traceGpsJson = trip.tracePoints?.let {
                if (it.isEmpty()) "{}" else json.encodeToString(it.map { point ->
                    GpsPoint(point.latitude, point.longitude, point.timestamp.toEpochMilliseconds(), point.accuracy)
                })
            } ?: "{}"

            val updateData = TripUpdate(
                start_time = startTimeFormatted,
                end_time = endTimeFormatted,
                start_latitude = trip.startLatitude,
                start_longitude = trip.startLongitude,
                end_latitude = trip.endLatitude,
                end_longitude = trip.endLongitude,
                start_address = trip.startAddress,
                end_address = trip.endAddress,
                distance_km = trip.distanceKm,
                duration_ms = trip.durationMs,
                type = trip.type.name, // FIX: Use actual trip type instead of hardcoded PERSONAL
                is_validated = trip.isValidated,
                vehicle_id = cleanVehicleId,  // Use cleaned vehicleId
                cost = 0.0,
                reimbursement_amount = trip.reimbursementAmount,
                is_work_home_trip = trip.isWorkHomeTrip,
                trace_gps = traceGpsJson,
                notes = trip.notes,
                matched_route_coordinates = trip.matchedRouteCoordinates,
                updated_at = now
            )

            postgres.from("trips")
                .update(updateData) {
                    filter {
                        eq("id", trip.id)
                        eq("user_id", userId)
                    }
                }

            MotiumApplication.logger.i("Trip updated successfully in Supabase: ${trip.id}", "SupabaseTripRepository")
            Result.success(Unit)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating trip in Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    suspend fun tripExists(tripId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val trip = postgres.from("trips")
                .select {
                    filter {
                        eq("id", tripId)
                        eq("user_id", userId)
                    }
                }
                .decodeList<SupabaseTrip>()
                .firstOrNull()

            trip != null
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking if trip exists: ${e.message}", "SupabaseTripRepository", e)
            false
        }
    }

    suspend fun deleteTrip(tripId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting trip from Supabase: $tripId", "SupabaseTripRepository")

            postgres.from("trips")
                .delete {
                    filter {
                        eq("id", tripId)
                        eq("user_id", userId)
                    }
                }

            MotiumApplication.logger.i("Trip deleted successfully from Supabase: $tripId", "SupabaseTripRepository")
            Result.success(Unit)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting trip from Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    suspend fun syncTripsToSupabase(localTrips: List<Trip>, userId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var syncedCount = 0

            localTrips.forEach { trip ->
                val result = saveTrip(trip, userId)
                if (result.isSuccess) {
                    syncedCount++
                }
            }

            MotiumApplication.logger.i("Synced $syncedCount trips to Supabase", "SupabaseTripRepository")
            Result.success(syncedCount)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing trips to Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch trips modified since a given timestamp (Delta Sync).
     * Returns both updated and soft-deleted trips for the sync worker.
     *
     * @param userId The user ID to fetch trips for
     * @param sinceTimestamp Epoch milliseconds - fetch only trips updated after this time
     * @param limit Maximum number of trips to fetch per page
     * @return DeltaSyncResult with updated trips, deleted trip IDs, and server timestamp
     */
    suspend fun getDeltaChanges(
        userId: String,
        sinceTimestamp: Long,
        limit: Int = 500
    ): Result<DeltaSyncResult> = withContext(Dispatchers.IO) {
        try {
            // Convert timestamp to Supabase format
            val sinceTimeFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(sinceTimestamp))

            MotiumApplication.logger.i(
                "Fetching delta changes since: $sinceTimeFormatted for user: $userId",
                "SupabaseTripRepository"
            )

            // Query trips with updated_at > sinceTimestamp
            // Note: This requires the SQL migration to add updated_at trigger
            val deltaTrips = postgres.from("trips")
                .select {
                    filter {
                        eq("user_id", userId)
                        gt("updated_at", sinceTimeFormatted)
                    }
                    order("updated_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                    limit(limit.toLong())
                }
                .decodeList<SupabaseTripDelta>()

            MotiumApplication.logger.i(
                "Fetched ${deltaTrips.size} delta changes from server",
                "SupabaseTripRepository"
            )

            // Separate active and soft-deleted trips
            val updatedTrips = mutableListOf<Trip>()
            val deletedTripIds = mutableListOf<String>()

            deltaTrips.forEach { tripDelta ->
                if (tripDelta.deleted_at != null) {
                    deletedTripIds.add(tripDelta.id)
                } else {
                    updatedTrips.add(tripDelta.toDomainTrip())
                }
            }

            // Server timestamp is the max updated_at from results, or current time if empty
            val serverTimestamp = deltaTrips.maxOfOrNull { trip ->
                parseTimestampToMillis(trip.updated_at)
            } ?: System.currentTimeMillis()

            Result.success(
                DeltaSyncResult(
                    updatedTrips = updatedTrips,
                    deletedTripIds = deletedTripIds,
                    serverTimestamp = serverTimestamp
                )
            )

        } catch (e: java.util.concurrent.CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error fetching delta changes: ${e.message}",
                "SupabaseTripRepository",
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Parse Supabase timestamp to epoch milliseconds.
     */
    private fun parseTimestampToMillis(timestamp: String): Long {
        return try {
            val normalized = timestamp.trim().replace(" ", "T").substringBefore("+")
            val withTimezone = if (normalized.endsWith("Z")) normalized else "${normalized}Z"
            Instant.parse(withTimezone).toEpochMilliseconds()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * Convert delta DTO to domain Trip.
     */
    private fun SupabaseTripDelta.toDomainTrip(): Trip {
        fun parseInstant(timestamp: String): Instant {
            return try {
                val normalized = timestamp.trim().replace(" ", "T").substringBefore("+")
                val withTimezone = if (normalized.endsWith("Z")) normalized else "${normalized}Z"
                Instant.parse(withTimezone)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to parse timestamp '$timestamp': ${e.message}", "SupabaseTripRepository", e)
                Instant.fromEpochMilliseconds(System.currentTimeMillis())
            }
        }

        val tracePoints = try {
            if (trace_gps.isNullOrBlank() || trace_gps == "{}") emptyList()
            else {
                json.decodeFromString<List<GpsPoint>>(trace_gps).map { point ->
                    com.application.motium.domain.model.LocationPoint(
                        point.latitude,
                        point.longitude,
                        Instant.fromEpochMilliseconds(point.timestamp),
                        point.accuracy
                    )
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w("Failed to deserialize trace_gps for trip $id: ${e.message}", "SupabaseTripRepository")
            emptyList()
        }

        return Trip(
            id = id,
            userId = user_id,
            vehicleId = vehicle_id,
            startTime = parseInstant(start_time),
            endTime = end_time?.let { parseInstant(it) },
            startLatitude = start_latitude,
            startLongitude = start_longitude,
            endLatitude = end_latitude,
            endLongitude = end_longitude,
            startAddress = start_address,
            endAddress = end_address,
            distanceKm = distance_km,
            durationMs = duration_ms,
            type = com.application.motium.domain.model.TripType.valueOf(type),
            isValidated = is_validated,
            cost = cost,
            reimbursementAmount = reimbursement_amount,
            isWorkHomeTrip = is_work_home_trip,
            tracePoints = tracePoints,
            notes = notes,
            matchedRouteCoordinates = matched_route_coordinates,
            createdAt = parseInstant(created_at),
            updatedAt = parseInstant(updated_at)
        )
    }

    suspend fun getAllTrips(userId: String): Result<List<Trip>> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching all trips from Supabase for user: $userId", "SupabaseTripRepository")

            val supabaseTrips = postgres.from("trips")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<SupabaseTrip>()
                .sortedByDescending { it.start_time }

            MotiumApplication.logger.i("Fetched ${supabaseTrips.size} trips from Supabase", "SupabaseTripRepository")

            // Convert to domain Trip objects
            val domainTrips = supabaseTrips.map { it.toDomainTrip() }

            Result.success(domainTrips)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching trips from Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch validated trips for a user with pagination support.
     * @param userId The user ID to fetch trips for
     * @param limit Number of trips to fetch per page
     * @param offset Number of trips to skip (for pagination)
     * @param validatedOnly If true, only fetch validated trips
     * @return Result containing list of trips and whether there are more to load
     */
    suspend fun getTripsWithPagination(
        userId: String,
        limit: Int = 20,
        offset: Int = 0,
        validatedOnly: Boolean = false
    ): Result<PaginatedTripsResult> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i(
                "Fetching trips (limit=$limit, offset=$offset, validatedOnly=$validatedOnly) for user: $userId",
                "SupabaseTripRepository"
            )

            val query = postgres.from("trips")
                .select {
                    filter {
                        eq("user_id", userId)
                        if (validatedOnly) {
                            eq("is_validated", true)
                        }
                    }
                    order("start_time", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    range(offset.toLong(), (offset + limit - 1).toLong())
                }

            val supabaseTrips = query.decodeList<SupabaseTrip>()

            MotiumApplication.logger.i("Fetched ${supabaseTrips.size} trips (page offset=$offset)", "SupabaseTripRepository")

            val domainTrips = supabaseTrips.map { it.toDomainTrip() }
            val hasMore = supabaseTrips.size == limit // If we got exactly limit trips, there might be more

            Result.success(PaginatedTripsResult(trips = domainTrips, hasMore = hasMore))

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching paginated trips: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Result class for paginated trips
     */
    data class PaginatedTripsResult(
        val trips: List<Trip>,
        val hasMore: Boolean
    )

    /**
     * Fetch a single trip by ID from Supabase.
     * Useful to restore GPS trace from cloud when local data is corrupted.
     */
    suspend fun getTripById(tripId: String, userId: String): Result<Trip?> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching trip $tripId from Supabase", "SupabaseTripRepository")

            val supabaseTrips = postgres.from("trips")
                .select {
                    filter {
                        eq("id", tripId)
                        eq("user_id", userId)
                    }
                }
                .decodeList<SupabaseTrip>()

            if (supabaseTrips.isEmpty()) {
                MotiumApplication.logger.w("Trip $tripId not found in Supabase", "SupabaseTripRepository")
                return@withContext Result.success(null)
            }

            val domainTrip = supabaseTrips.first().toDomainTrip()
            val tracePointsCount = domainTrip.tracePoints?.size ?: 0
            MotiumApplication.logger.i("✅ Fetched trip $tripId from Supabase with $tracePointsCount GPS points", "SupabaseTripRepository")

            Result.success(domainTrip)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching trip $tripId from Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch a single trip by ID from Supabase for a linked user.
     * Used by Pro account owners to view trips of their linked users.
     * RLS policy ensures only authorized access is allowed.
     *
     * @param tripId The trip ID to fetch
     * @param linkedUserId The ID of the linked user who owns the trip
     * @return The trip if found and accessible, null otherwise
     */
    suspend fun getTripByIdForLinkedUser(tripId: String, linkedUserId: String): Result<Trip?> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Fetching linked user trip $tripId (user: $linkedUserId) from Supabase", "SupabaseTripRepository")

            val supabaseTrips = postgres.from("trips")
                .select {
                    filter {
                        eq("id", tripId)
                        eq("user_id", linkedUserId)
                    }
                }
                .decodeList<SupabaseTrip>()

            if (supabaseTrips.isEmpty()) {
                MotiumApplication.logger.w("Linked user trip $tripId not found in Supabase", "SupabaseTripRepository")
                return@withContext Result.success(null)
            }

            val domainTrip = supabaseTrips.first().toDomainTrip()
            val tracePointsCount = domainTrip.tracePoints?.size ?: 0
            MotiumApplication.logger.i("✅ Fetched linked user trip $tripId from Supabase with $tracePointsCount GPS points", "SupabaseTripRepository")

            Result.success(domainTrip)

        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired for linked user trip fetch, refreshing token...", "SupabaseTripRepository")
                val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
                if (refreshed) {
                    return@withContext try {
                        val supabaseTrips = postgres.from("trips")
                            .select {
                                filter {
                                    eq("id", tripId)
                                    eq("user_id", linkedUserId)
                                }
                            }
                            .decodeList<SupabaseTrip>()

                        if (supabaseTrips.isEmpty()) {
                            MotiumApplication.logger.w("Linked user trip $tripId not found after token refresh", "SupabaseTripRepository")
                            Result.success(null)
                        } else {
                            val domainTrip = supabaseTrips.first().toDomainTrip()
                            MotiumApplication.logger.i("✅ Fetched linked user trip after token refresh", "SupabaseTripRepository")
                            Result.success(domainTrip)
                        }
                    } catch (retryError: Exception) {
                        MotiumApplication.logger.e("Error after token refresh: ${retryError.message}", "SupabaseTripRepository", retryError)
                        Result.failure(retryError)
                    }
                }
            }
            MotiumApplication.logger.e("Error fetching linked user trip $tripId: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error fetching linked user trip $tripId from Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    private fun SupabaseTrip.toDomainTrip(): Trip {
        fun parseInstant(timestamp: String): Instant {
            return try {
                val normalized = timestamp.trim().replace(" ", "T").substringBefore("+")
                val withTimezone = if (normalized.endsWith("Z")) normalized else "${normalized}Z"
                Instant.parse(withTimezone)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to parse timestamp '$timestamp': ${e.message}", "SupabaseTripRepository", e)
                Instant.fromEpochMilliseconds(System.currentTimeMillis())
            }
        }

        val tracePoints = try {
            if (trace_gps.isNullOrBlank() || trace_gps == "{}") emptyList()
            else {
                json.decodeFromString<List<GpsPoint>>(trace_gps).map { point ->
                    com.application.motium.domain.model.LocationPoint(
                        point.latitude,
                        point.longitude,
                        Instant.fromEpochMilliseconds(point.timestamp),
                        point.accuracy
                    )
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w("Failed to deserialize trace_gps for trip $id: ${e.message}", "SupabaseTripRepository")
            emptyList()
        }

        return Trip(
            id = id,
            userId = user_id,
            vehicleId = vehicle_id,
            startTime = parseInstant(start_time),
            endTime = end_time?.let { parseInstant(it) },
            startLatitude = start_latitude,
            startLongitude = start_longitude,
            endLatitude = end_latitude,
            endLongitude = end_longitude,
            startAddress = start_address,
            endAddress = end_address,
            distanceKm = distance_km,
            durationMs = duration_ms,
            type = com.application.motium.domain.model.TripType.valueOf(type),
            isValidated = is_validated,
            cost = cost,
            reimbursementAmount = reimbursement_amount,
            isWorkHomeTrip = is_work_home_trip,
            tracePoints = tracePoints,
            notes = notes,
            matchedRouteCoordinates = matched_route_coordinates,
            createdAt = parseInstant(created_at),
            updatedAt = parseInstant(updated_at)
        )
    }

    /**
     * Fetch trips for multiple users within a date range (for Pro export).
     * Returns a Map of userId -> List<Trip> for grouping by employee.
     *
     * @param userIds List of user IDs to fetch trips for
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @param tripTypes List of trip types to include (e.g., ["PROFESSIONAL", "PERSONAL"])
     * @return Map of userId to their trips
     */
    suspend fun getTripsForUsers(
        userIds: List<String>,
        startDate: Instant,
        endDate: Instant,
        tripTypes: List<String>
    ): Result<Map<String, List<Trip>>> = withContext(Dispatchers.IO) {
        try {
            if (userIds.isEmpty()) {
                return@withContext Result.success(emptyMap())
            }

            MotiumApplication.logger.i(
                "Fetching trips for ${userIds.size} users from $startDate to $endDate",
                "SupabaseTripRepository"
            )

            // Format dates for Supabase query
            val startTimeFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(startDate.toEpochMilliseconds()))

            val endTimeFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(endDate.toEpochMilliseconds()))

            // Fetch trips for all users in the list
            val allTrips = mutableListOf<SupabaseTrip>()

            // Supabase Kotlin SDK doesn't support IN filter directly, so we batch requests
            userIds.forEach { userId ->
                try {
                    val userTrips = postgres.from("trips")
                        .select {
                            filter {
                                eq("user_id", userId)
                                gte("start_time", startTimeFormatted)
                                lte("start_time", endTimeFormatted)
                                if (tripTypes.isNotEmpty()) {
                                    isIn("type", tripTypes)
                                }
                            }
                        }
                        .decodeList<SupabaseTrip>()
                    allTrips.addAll(userTrips)
                } catch (e: Exception) {
                    MotiumApplication.logger.w(
                        "Error fetching trips for user $userId: ${e.message}",
                        "SupabaseTripRepository"
                    )
                }
            }

            // Group by user ID and convert to domain objects
            val tripsByUser = allTrips
                .map { it.toDomainTrip() }
                .groupBy { it.userId }

            MotiumApplication.logger.i(
                "Fetched ${allTrips.size} trips for ${tripsByUser.size} users",
                "SupabaseTripRepository"
            )

            Result.success(tripsByUser)

        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error fetching trips for multiple users: ${e.message}",
                "SupabaseTripRepository",
                e
            )
            Result.failure(e)
        }
    }

    companion object {
        @Volatile
        private var instance: TripRemoteDataSource? = null

        fun getInstance(context: Context): TripRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: TripRemoteDataSource(context.applicationContext).also { instance = it }
            }
        }
    }
}
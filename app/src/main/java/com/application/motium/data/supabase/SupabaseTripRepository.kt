package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.Trip
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.*

class SupabaseTripRepository(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest
    private val json = Json { ignoreUnknownKeys = true }

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
        val vehicle_id: String?,
        val start_time: String, // TIMESTAMPTZ
        val end_time: String?, // TIMESTAMPTZ
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
        val cost: Double,
        val trace_gps: String?, // JSONB as JSON string for manual trips
        val created_at: String,
        val updated_at: String
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
        val trace_gps: String?,
        val updated_at: String
    )

    suspend fun saveTrip(trip: Trip, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Saving trip to Supabase: ${trip.id}", "SupabaseTripRepository")

            val vehicleId = trip.vehicleId?.takeIf { it.isNotBlank() } ?: try {
                SupabaseVehicleRepository.getInstance(context).getDefaultVehicle(userId)?.id
            } catch (e: Exception) {
                MotiumApplication.logger.w("Could not get default vehicle: ${e.message}", "SupabaseTripRepository")
                null
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
                type = "PERSONAL", // This needs to be dynamic based on trip data
                is_validated = trip.isValidated,
                cost = 0.0,
                trace_gps = traceGpsJson,
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

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving trip to Supabase: ${e.message}", "SupabaseTripRepository", e)
            Result.failure(e)
        }
    }

    suspend fun updateTrip(trip: Trip, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating trip in Supabase: ${trip.id}", "SupabaseTripRepository")

            // Clean vehicleId: convert empty string to null
            val cleanVehicleId = trip.vehicleId?.takeIf { it.isNotBlank() }

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
                type = "PERSONAL",
                is_validated = trip.isValidated,
                vehicle_id = cleanVehicleId,  // Use cleaned vehicleId
                cost = 0.0,
                trace_gps = traceGpsJson,
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
            tracePoints = tracePoints,
            createdAt = parseInstant(created_at),
            updatedAt = parseInstant(updated_at)
        )
    }

    companion object {
        @Volatile
        private var instance: SupabaseTripRepository? = null

        fun getInstance(context: Context): SupabaseTripRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseTripRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
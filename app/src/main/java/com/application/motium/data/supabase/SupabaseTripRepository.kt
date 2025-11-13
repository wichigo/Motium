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

            // Clean vehicleId: convert empty string to null, then try to get default if null
            val cleanVehicleId = trip.vehicleId?.takeIf { it.isNotBlank() }

            val vehicleId = if (cleanVehicleId != null) {
                cleanVehicleId
            } else {
                try {
                    val vehicleRepository = SupabaseVehicleRepository.getInstance(context)
                    vehicleRepository.getDefaultVehicle(userId)?.id
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Could not get default vehicle: ${e.message}", "SupabaseTripRepository")
                    null
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

            // Format the createdAt timestamp from the trip
            val createdAtFormatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date(trip.createdAt.toEpochMilliseconds()))

            // Sérialiser les tracePoints en JSON pour le champ trace_gps
            val traceGpsJson = if (trip.tracePoints.isNullOrEmpty()) {
                MotiumApplication.logger.i("Trip ${trip.id} has no GPS points - manual trip", "SupabaseTripRepository")
                "{}" // Trajet manuel sans GPS
            } else {
                val gpsPoints = trip.tracePoints.map { point ->
                    GpsPoint(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        timestamp = point.timestamp.toEpochMilliseconds(),
                        accuracy = point.accuracy
                    )
                }
                val jsonString = json.encodeToString(gpsPoints)
                MotiumApplication.logger.i("Trip ${trip.id} serialized with ${gpsPoints.size} GPS points (${jsonString.length} chars)", "SupabaseTripRepository")
                jsonString
            }

            // SYNC OPTIMIZATION: Check if trip already exists and compare updated_at
            val existingTrip = try {
                postgres.from("trips")
                    .select {
                        filter {
                            eq("id", trip.id)
                        }
                    }
                    .decodeList<SupabaseTrip>()
                    .firstOrNull()
            } catch (e: Exception) {
                null // If error, we'll proceed with insert/update
            }

            // SYNC OPTIMIZATION: Skip sync if trip already exists and is up-to-date
            if (existingTrip != null) {
                try {
                    // Parse updated_at from Supabase and compare with local
                    val supabaseUpdatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(existingTrip.updated_at.substringBefore(".").substringBefore("+"))?.time ?: 0

                    val localUpdatedAt = trip.updatedAt.toEpochMilliseconds()

                    if (supabaseUpdatedAt >= localUpdatedAt) {
                        MotiumApplication.logger.i(
                            "Trip ${trip.id} already up-to-date on Supabase (remote: $supabaseUpdatedAt >= local: $localUpdatedAt) - SKIPPING",
                            "SupabaseTripRepository"
                        )
                        return@withContext Result.success(Unit)
                    } else {
                        MotiumApplication.logger.i(
                            "Trip ${trip.id} needs update (remote: $supabaseUpdatedAt < local: $localUpdatedAt)",
                            "SupabaseTripRepository"
                        )
                    }
                } catch (e: Exception) {
                    // Si erreur de parsing, continuer avec l'update
                    MotiumApplication.logger.w(
                        "Could not compare updated_at for trip ${trip.id}: ${e.message} - proceeding with update",
                        "SupabaseTripRepository"
                    )
                }
            }

            val existingCreatedAt = existingTrip?.created_at // Preserve original creation time

            val supabaseTrip = SupabaseTrip(
                id = trip.id,
                user_id = userId,
                vehicle_id = vehicleId, // Use default vehicle or null if no vehicles exist
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
                cost = 0.0,
                trace_gps = traceGpsJson,
                created_at = existingCreatedAt ?: createdAtFormatted,  // Preserve original or use trip's createdAt
                updated_at = now
            )

            MotiumApplication.logger.i("Address values: start_address='${supabaseTrip.start_address}', end_address='${supabaseTrip.end_address}'", "SupabaseTripRepository")

            // Use upsert to insert or update (Supabase will handle it based on primary key)
            // This is more efficient than checking existence first
            if (existingCreatedAt != null) {
                MotiumApplication.logger.i("Trip already exists, updating: ${trip.id} (preserved created_at: $existingCreatedAt)", "SupabaseTripRepository")
                postgres.from("trips").update(supabaseTrip) {
                    filter {
                        eq("id", trip.id)
                    }
                }
            } else {
                MotiumApplication.logger.i("Trip doesn't exist, inserting: ${trip.id} (created_at: $createdAtFormatted)", "SupabaseTripRepository")
                postgres.from("trips").insert(supabaseTrip)
            }

            // Update vehicle mileage if we have a vehicle
            vehicleId?.let { vId ->
                try {
                    val vehicleRepository = SupabaseVehicleRepository.getInstance(context)
                    val distanceKm = trip.distanceKm

                    // For now, always increment personal mileage (PERSONAL trip type)
                    // In the future, this could be based on trip type
                    vehicleRepository.updateMileagePersonal(vId, distanceKm)
                    MotiumApplication.logger.i("Updated vehicle $vId personal mileage by $distanceKm km", "SupabaseTripRepository")
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to update vehicle mileage: ${e.message}", "SupabaseTripRepository")
                    // Don't fail the trip save if mileage update fails
                }
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

            // Sérialiser les tracePoints en JSON pour le champ trace_gps
            val traceGpsJson = if (trip.tracePoints.isNullOrEmpty()) {
                "{}" // Trajet manuel sans GPS
            } else {
                val gpsPoints = trip.tracePoints.map { point ->
                    GpsPoint(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        timestamp = point.timestamp.toEpochMilliseconds(),
                        accuracy = point.accuracy
                    )
                }
                json.encodeToString(gpsPoints)
            }

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
        // Helper function to parse timestamps from Supabase
        fun parseInstant(timestamp: String): Instant {
            return try {
                // Supabase peut retourner des timestamps sans timezone, on ajoute 'Z' si nécessaire
                // Format attendu: "2025-10-06T06:08:22.675" ou "2025-10-06T06:08:22.675Z" ou "2025-10-06 06:08:22"
                val normalized = timestamp.trim()
                    .replace(" ", "T") // Remplacer espace par T
                    .substringBefore("+") // Enlever +XX:XX si présent

                val withTimezone = when {
                    normalized.endsWith("Z") -> normalized
                    normalized.contains("T") -> "${normalized}Z" // Format ISO avec T
                    else -> "${normalized}Z" // Fallback
                }

                Instant.parse(withTimezone)
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Failed to parse timestamp '$timestamp': ${e.message}. Using current time as fallback.",
                    "SupabaseTripRepository",
                    e
                )
                // Fallback: utiliser l'heure actuelle
                Instant.fromEpochMilliseconds(System.currentTimeMillis())
            }
        }

        // Désérialiser les points GPS depuis trace_gps (JSON)
        val tracePoints = try {
            if (trace_gps.isNullOrBlank() || trace_gps == "{}") {
                emptyList()
            } else {
                val gpsPoints = json.decodeFromString<List<GpsPoint>>(trace_gps)
                gpsPoints.map { point ->
                    com.application.motium.domain.model.LocationPoint(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        timestamp = Instant.fromEpochMilliseconds(point.timestamp),
                        accuracy = point.accuracy
                    )
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w(
                "Failed to deserialize trace_gps for trip $id: ${e.message}",
                "SupabaseTripRepository"
            )
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
            tracePoints = tracePoints, // GPS trace désérialisé depuis trace_gps
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
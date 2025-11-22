package com.application.motium.data

import android.content.Context
import android.content.SharedPreferences
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseTripRepository
import com.application.motium.domain.model.Trip as DomainTrip
import com.application.motium.domain.model.TripType
import com.application.motium.domain.model.LocationPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class TripLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long
)

@Serializable
data class Trip(
    val id: String,
    val startTime: Long,
    val endTime: Long?,
    val locations: List<TripLocation>,
    val totalDistance: Double,
    val isValidated: Boolean = false,
    val vehicleId: String? = null,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val notes: String? = null,  // Notes générales du trajet
    val tripType: String? = null,  // "PROFESSIONAL" or "PERSONAL"
    val createdAt: Long = System.currentTimeMillis(),  // Timestamp de création
    val updatedAt: Long = System.currentTimeMillis(),   // Timestamp de mise à jour
    val lastSyncedAt: Long? = null,  // SYNC OPTIMIZATION: Timestamp de dernière synchronisation vers Supabase
    val needsSync: Boolean = true,  // SYNC OPTIMIZATION: Flag indiquant si le trip doit être synchronisé
    val userId: String? = null  // SECURITY: User ID pour isolation des données
) {
    fun getFormattedDistance(): String = String.format("%.1f km", totalDistance / 1000)

    fun getFormattedDuration(): String {
        val duration = (endTime ?: System.currentTimeMillis()) - startTime
        val minutes = duration / 1000 / 60
        return if (minutes < 60) "${minutes} min" else "${minutes / 60}h${minutes % 60}min"
    }

    fun getFormattedStartTime(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date(startTime))
    }

    fun getFormattedDate(): String {
        val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return formatter.format(Date(startTime))
    }

    fun getRouteDescription(): String {
        if (locations.size < 2) return "Trajet court"

        // Simuler une description de route basée sur la distance
        return when {
            totalDistance > 50000 -> "Trajet longue distance"
            totalDistance > 10000 -> "Trajet inter-urbain"
            totalDistance > 1000 -> "Trajet urbain"
            else -> "Trajet local"
        }
    }
}

class TripRepository private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "motium_trips"
        private const val KEY_TRIPS = "trips_json"
        private const val KEY_AUTO_TRACKING = "auto_tracking_enabled"
        private const val KEY_LAST_USER_ID = "last_user_id"  // Pour charger les trips avant l'auth complète

        @Volatile
        private var instance: TripRepository? = null

        fun getInstance(context: Context): TripRepository {
            return instance ?: synchronized(this) {
                instance ?: TripRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext // SYNC OPTIMIZATION: Store context for later use
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val supabaseTripRepository = SupabaseTripRepository.getInstance(context)
    private val authRepository = SupabaseAuthRepository.getInstance(context)

    suspend fun saveTrip(trip: Trip) = withContext(Dispatchers.IO) {
        try {
            // SECURITY: S'assurer que le trip a un userId
            val currentUser = authRepository.getCurrentAuthUser()
            val tripWithUserId = if (trip.userId == null && currentUser != null) {
                trip.copy(userId = currentUser.id)
            } else {
                trip
            }

            // Sauvegarder localement
            val trips = getAllTrips().toMutableList()

            // Remplacer si existe déjà, sinon ajouter
            val existingIndex = trips.indexOfFirst { it.id == tripWithUserId.id }
            if (existingIndex >= 0) {
                trips[existingIndex] = tripWithUserId
            } else {
                trips.add(tripWithUserId)
            }

            // Garder seulement les 100 derniers trajets pour éviter de surcharger
            val recentTrips = trips.sortedByDescending { it.startTime }.take(100)

            val tripsJson = json.encodeToString(recentTrips)
            prefs.edit().putString(KEY_TRIPS, tripsJson).apply()

            MotiumApplication.logger.i("Trip saved locally: ${tripWithUserId.id}, ${tripWithUserId.getFormattedDistance()}, userId=${tripWithUserId.userId}", "TripRepository")

            // SYNC OPTIMIZATION: Sync immédiat après création de trip
            // Synchroniser ce trip spécifique immédiatement (pas besoin d'attendre 15 minutes)
            try {
                if (currentUser != null) {
                    val supabaseResult = supabaseTripRepository.saveTrip(tripWithUserId.toDomainTrip(currentUser.id), currentUser.id)
                    if (supabaseResult.isSuccess) {
                        MotiumApplication.logger.i("Trip synced immediately to Supabase: ${tripWithUserId.id}", "TripRepository")

                        // SYNC OPTIMIZATION: Marquer comme synchronisé après succès
                        markTripsAsSynced(listOf(tripWithUserId.id))

                        // SYNC OPTIMIZATION: Trigger sync rapide pour autres trips dirty (si présents)
                        // Notification au SyncManager qu'un trip vient d'être créé
                        com.application.motium.data.sync.SupabaseSyncManager.getInstance(appContext).forceSyncNow()
                    } else {
                        MotiumApplication.logger.w("Failed to sync trip to Supabase: ${tripWithUserId.id}", "TripRepository")
                    }
                } else {
                    MotiumApplication.logger.i("User not authenticated, trip saved locally only: ${tripWithUserId.id}", "TripRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error syncing trip to Supabase: ${e.message}", "TripRepository", e)
                // Ne pas faire échouer la sauvegarde locale si Supabase échoue
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving trip: ${e.message}", "TripRepository", e)
        }
    }

    suspend fun getAllTrips(): List<Trip> = withContext(Dispatchers.IO) {
        try {
            // SECURITY: Obtenir l'utilisateur actuel pour filtrer les trips
            val currentUser = authRepository.getCurrentAuthUser()

            // FIX: Utiliser le dernier userId connu si l'auth n'est pas encore restaurée
            val userId = currentUser?.id ?: prefs.getString(KEY_LAST_USER_ID, null)

            if (userId == null) {
                MotiumApplication.logger.w("getAllTrips called but no user authenticated and no last userId - returning empty list", "TripRepository")
                return@withContext emptyList()
            }

            // Sauvegarder le userId pour les prochains chargements
            if (currentUser != null && prefs.getString(KEY_LAST_USER_ID, null) != userId) {
                prefs.edit().putString(KEY_LAST_USER_ID, userId).apply()
            }

            val tripsJson = prefs.getString(KEY_TRIPS, null) ?: return@withContext emptyList()
            val allTrips = json.decodeFromString<List<Trip>>(tripsJson)

            // SECURITY: Filtrer uniquement les trips de l'utilisateur actuel (ou dernier connu)
            val userTrips = allTrips.filter { trip ->
                trip.userId == userId || trip.userId == null // null pour legacy trips, seront migrés au prochain save
            }

            MotiumApplication.logger.d("Loaded ${userTrips.size} trips for user $userId (${allTrips.size} total in storage)", "TripRepository")
            return@withContext userTrips
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading trips: ${e.message}", "TripRepository", e)
            return@withContext emptyList()
        }
    }

    suspend fun getTripsByDate(date: String): List<Trip> = withContext(Dispatchers.IO) {
        getAllTrips().filter { it.getFormattedDate() == date }
    }

    suspend fun getRecentTrips(limit: Int = 20): List<Trip> = withContext(Dispatchers.IO) {
        getAllTrips().sortedByDescending { it.startTime }.take(limit)
    }

    /**
     * PAGINATION: Récupère les trips avec pagination (limit + offset)
     * Utilisé pour le lazy loading dans HomeScreen
     */
    suspend fun getTripsPaginated(limit: Int = 10, offset: Int = 0): List<Trip> = withContext(Dispatchers.IO) {
        try {
            val allUserTrips = getAllTrips().sortedByDescending { it.startTime }
            val paginatedTrips = allUserTrips.drop(offset).take(limit)

            MotiumApplication.logger.d("Loaded ${paginatedTrips.size} trips (offset=$offset, limit=$limit, total=${allUserTrips.size})", "TripRepository")
            return@withContext paginatedTrips
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading paginated trips: ${e.message}", "TripRepository", e)
            return@withContext emptyList()
        }
    }

    /**
     * PAGINATION: Compte le nombre total de trips de l'utilisateur
     */
    suspend fun getTotalTripsCount(): Int = withContext(Dispatchers.IO) {
        getAllTrips().size
    }

    suspend fun deleteTrip(tripId: String) = withContext(Dispatchers.IO) {
        try {
            // Supprimer localement
            val trips = getAllTrips().toMutableList()
            trips.removeAll { it.id == tripId }

            val tripsJson = json.encodeToString(trips)
            prefs.edit().putString(KEY_TRIPS, tripsJson).apply()

            MotiumApplication.logger.i("Trip deleted locally: $tripId", "TripRepository")

            // Supprimer de Supabase si l'utilisateur est connecté
            try {
                val currentUser = authRepository.getCurrentAuthUser()
                if (currentUser != null) {
                    val supabaseResult = supabaseTripRepository.deleteTrip(tripId, currentUser.id)
                    if (supabaseResult.isSuccess) {
                        MotiumApplication.logger.i("Trip deleted from Supabase: $tripId", "TripRepository")
                    } else {
                        MotiumApplication.logger.w("Failed to delete trip from Supabase: $tripId", "TripRepository")
                    }
                } else {
                    MotiumApplication.logger.i("User not authenticated, trip deleted locally only: $tripId", "TripRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error deleting trip from Supabase: ${e.message}", "TripRepository", e)
                // Ne pas faire échouer la suppression locale si Supabase échoue
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting trip: ${e.message}", "TripRepository", e)
        }
    }

    fun isAutoTrackingEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRACKING, false)
    }

    fun setAutoTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRACKING, enabled).apply()
        MotiumApplication.logger.i("Auto tracking set to: $enabled", "TripRepository")
    }

    suspend fun getTripStats(): TripStats = withContext(Dispatchers.IO) {
        val trips = getAllTrips().filter { it.endTime != null }
        val today = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val todayTrips = trips.filter { it.getFormattedDate() == today }

        TripStats(
            totalTrips = trips.size,
            todayTrips = todayTrips.size,
            totalDistance = trips.sumOf { it.totalDistance },
            todayDistance = todayTrips.sumOf { it.totalDistance },
            validatedTrips = trips.count { it.isValidated },
            todayValidatedTrips = todayTrips.count { it.isValidated }
        )
    }

    suspend fun syncAllTripsToSupabase() = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.getCurrentAuthUser()
            if (currentUser != null) {
                val localTrips = getAllTrips()

                // SYNC OPTIMIZATION: Ne synchroniser que les trips marqués comme "dirty" (needsSync = true)
                val dirtyTrips = localTrips.filter { it.needsSync }

                if (dirtyTrips.isNotEmpty()) {
                    MotiumApplication.logger.i(
                        "Starting sync of ${dirtyTrips.size} dirty trips to Supabase (out of ${localTrips.size} total trips)",
                        "TripRepository"
                    )

                    val result = supabaseTripRepository.syncTripsToSupabase(dirtyTrips.toDomainTripList(currentUser.id), currentUser.id)
                    if (result.isSuccess) {
                        val syncedCount = result.getOrNull() ?: 0
                        MotiumApplication.logger.i("Successfully synced $syncedCount trips to Supabase", "TripRepository")

                        // SYNC OPTIMIZATION: Marquer les trips comme synchronisés
                        markTripsAsSynced(dirtyTrips.map { it.id })
                    } else {
                        MotiumApplication.logger.e("Failed to sync trips to Supabase", "TripRepository")
                    }
                } else {
                    MotiumApplication.logger.i("No dirty trips to sync (${localTrips.size} trips already synchronized)", "TripRepository")
                }
            } else {
                MotiumApplication.logger.w("User not authenticated, cannot sync trips", "TripRepository")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing trips to Supabase: ${e.message}", "TripRepository", e)
        }
    }

    /**
     * SYNC OPTIMIZATION: Marque les trips comme synchronisés
     * Met à jour needsSync = false et lastSyncedAt = now
     */
    private suspend fun markTripsAsSynced(tripIds: List<String>) = withContext(Dispatchers.IO) {
        try {
            val allTrips = getAllTrips().toMutableList()
            val now = System.currentTimeMillis()
            var updatedCount = 0

            for (i in allTrips.indices) {
                if (allTrips[i].id in tripIds) {
                    allTrips[i] = allTrips[i].copy(
                        needsSync = false,
                        lastSyncedAt = now
                    )
                    updatedCount++
                }
            }

            // Sauvegarder les modifications
            val tripsJson = json.encodeToString(allTrips.sortedByDescending { it.startTime }.take(100))
            prefs.edit().putString(KEY_TRIPS, tripsJson).apply()

            MotiumApplication.logger.i("Marked $updatedCount trips as synced (lastSyncedAt updated)", "TripRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error marking trips as synced: ${e.message}", "TripRepository", e)
        }
    }

    suspend fun syncTripsFromSupabase() = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.getCurrentAuthUser()
            if (currentUser != null) {
                MotiumApplication.logger.i("Fetching trips from Supabase for user: ${currentUser.id}", "TripRepository")

                val result = supabaseTripRepository.getAllTrips(currentUser.id)
                if (result.isSuccess) {
                    val supabaseTrips = result.getOrNull() ?: emptyList()
                    MotiumApplication.logger.i("Fetched ${supabaseTrips.size} trips from Supabase", "TripRepository")

                    if (supabaseTrips.isNotEmpty()) {
                        // SECURITY: Convert domain trips to data trips WITH userId
                        val dataTrips = supabaseTrips.map { it.toDataTrip().copy(userId = currentUser.id) }

                        // SECURITY: Lire TOUS les trips (pas filtré par user) pour ne pas perdre les données des autres utilisateurs
                        val tripsJson = prefs.getString(KEY_TRIPS, null)
                        val allStoredTrips = if (tripsJson != null) {
                            try {
                                json.decodeFromString<List<Trip>>(tripsJson).toMutableList()
                            } catch (e: Exception) {
                                MotiumApplication.logger.e("Error parsing stored trips: ${e.message}", "TripRepository", e)
                                mutableListOf()
                            }
                        } else {
                            mutableListOf()
                        }

                        // SECURITY: Séparer les trips de l'utilisateur actuel et des autres utilisateurs
                        val otherUsersTrips = allStoredTrips.filter { it.userId != null && it.userId != currentUser.id }
                        val currentUserLocalTrips = allStoredTrips.filter { it.userId == currentUser.id || it.userId == null }

                        // Merge: trips Supabase + trips locaux uniquement de l'utilisateur actuel non présents dans Supabase
                        val supabaseTripIds = dataTrips.map { it.id }.toSet()
                        val localOnlyTrips = currentUserLocalTrips.filter { it.id !in supabaseTripIds }

                        // SECURITY: Combiner les trips de l'utilisateur actuel + les trips des autres utilisateurs (pour ne pas les perdre)
                        val allTrips = dataTrips + localOnlyTrips + otherUsersTrips

                        // Save merged trips locally
                        val tripsJsonToSave = json.encodeToString(allTrips.sortedByDescending { it.startTime }.take(300)) // Augmenté à 300 pour gérer plusieurs users
                        prefs.edit().putString(KEY_TRIPS, tripsJsonToSave).apply()

                        MotiumApplication.logger.i("Synced ${dataTrips.size} trips from Supabase, kept ${localOnlyTrips.size} local-only trips for user ${currentUser.id}, preserved ${otherUsersTrips.size} trips from other users", "TripRepository")
                    }
                } else {
                    MotiumApplication.logger.e("Failed to fetch trips from Supabase", "TripRepository")
                }
            } else {
                MotiumApplication.logger.i("User not authenticated, skipping Supabase sync", "TripRepository")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error syncing trips from Supabase: ${e.message}", "TripRepository", e)
        }
    }
}

// Conversion functions between data Trip and domain Trip
private fun Trip.toDomainTrip(userId: String = ""): DomainTrip {
    // Convert TripLocation list to LocationPoint list
    val locationPoints = locations.map { tripLocation ->
        LocationPoint(
            latitude = tripLocation.latitude,
            longitude = tripLocation.longitude,
            timestamp = Instant.fromEpochMilliseconds(tripLocation.timestamp),
            accuracy = tripLocation.accuracy
        )
    }

    // Extract start and end locations
    val startLocation = locations.firstOrNull()
    val endLocation = locations.lastOrNull()

    return DomainTrip(
        id = id,
        userId = userId,
        vehicleId = this.vehicleId?.takeIf { it.isNotBlank() },  // Convert empty string to null
        startTime = Instant.fromEpochMilliseconds(startTime),
        endTime = endTime?.let { Instant.fromEpochMilliseconds(it) },
        startLatitude = startLocation?.latitude ?: 0.0,
        startLongitude = startLocation?.longitude ?: 0.0,
        endLatitude = endLocation?.latitude,
        endLongitude = endLocation?.longitude,
        startAddress = this.startAddress, // From data Trip
        endAddress = this.endAddress, // From data Trip
        distanceKm = totalDistance / 1000.0, // Convert meters to km
        durationMs = (endTime ?: System.currentTimeMillis()) - startTime,
        type = when(this.tripType) {
            "PROFESSIONAL" -> TripType.PROFESSIONAL
            "PERSONAL" -> TripType.PERSONAL
            else -> TripType.PERSONAL // Default to personal if null/unknown
        },
        isValidated = isValidated,
        cost = 0.0, // Default cost
        tracePoints = locationPoints,
        createdAt = Instant.fromEpochMilliseconds(createdAt), // Preserve original creation time
        updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()) // Update modification time
    )
}

private fun List<Trip>.toDomainTripList(userId: String = ""): List<DomainTrip> {
    return map { it.toDomainTrip(userId) }
}

private fun DomainTrip.toDataTrip(): Trip {
    // Convert LocationPoint list to TripLocation list
    val tripLocations = tracePoints?.map { locationPoint ->
        TripLocation(
            latitude = locationPoint.latitude,
            longitude = locationPoint.longitude,
            accuracy = locationPoint.accuracy ?: 10.0f,
            timestamp = locationPoint.timestamp.toEpochMilliseconds()
        )
    } ?: emptyList()

    return Trip(
        id = id,
        startTime = startTime.toEpochMilliseconds(),
        endTime = endTime?.toEpochMilliseconds(),
        locations = tripLocations,
        totalDistance = distanceKm * 1000, // Convert km to meters
        isValidated = isValidated,
        vehicleId = vehicleId,
        startAddress = startAddress,
        endAddress = endAddress,
        tripType = when(type) {
            TripType.PROFESSIONAL -> "PROFESSIONAL"
            TripType.PERSONAL -> "PERSONAL"
        },
        createdAt = createdAt.toEpochMilliseconds(),  // Preserve creation time
        updatedAt = updatedAt.toEpochMilliseconds(),  // Preserve update time
        lastSyncedAt = System.currentTimeMillis(),  // 🔧 FIX: Mark as synced since it came from Supabase
        needsSync = false  // 🔧 FIX: Trip from Supabase doesn't need sync
    )
}

data class TripStats(
    val totalTrips: Int,
    val todayTrips: Int,
    val totalDistance: Double,
    val todayDistance: Double,
    val validatedTrips: Int,
    val todayValidatedTrips: Int
) {
    fun getTotalDistanceKm(): String = String.format("%.1f", totalDistance / 1000)
    fun getTodayDistanceKm(): String = String.format("%.1f", todayDistance / 1000)
}
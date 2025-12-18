package com.application.motium.data

import android.content.Context
import android.content.SharedPreferences
import com.application.motium.MotiumApplication
import com.application.motium.data.local.entities.toDataModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseTripRepository
import com.application.motium.domain.model.Trip as DomainTrip
import com.application.motium.domain.model.TripType
import com.application.motium.domain.model.LocationPoint
import com.application.motium.domain.model.TrackingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.utils.TripCalculator

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
    val reimbursementAmount: Double? = null, // Stored mileage reimbursement calculated at save time
    val isWorkHomeTrip: Boolean = false, // Trajet travail-maison (perso uniquement, donne droit aux indemnités)
    val createdAt: Long = System.currentTimeMillis(),  // Timestamp de création
    val updatedAt: Long = System.currentTimeMillis(),   // Timestamp de mise à jour
    val lastSyncedAt: Long? = null,  // SYNC OPTIMIZATION: Timestamp de dernière synchronisation vers Supabase
    val needsSync: Boolean = true,  // SYNC OPTIMIZATION: Flag indiquant si le trip doit être synchronisé
    val userId: String? = null,  // SECURITY: User ID pour isolation des données
    val matchedRouteCoordinates: String? = null  // CACHE: Map-matched route coordinates as JSON [[lon,lat],...]
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
        private const val KEY_TRACKING_MODE = "tracking_mode"  // Cache local du TrackingMode (ALWAYS, WORK_HOURS_ONLY, DISABLED)
        private const val KEY_LAST_USER_ID = "last_user_id"  // Pour charger les trips avant l'auth complète
        private const val KEY_MIGRATION_COMPLETE = "trips_migrated_to_room" // Flag de migration

        @Volatile
        private var instance: TripRepository? = null

        fun getInstance(context: Context): TripRepository {
            return instance ?: synchronized(this) {
                instance ?: TripRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext

    // SÉCURITÉ: Utiliser Room Database au lieu de SharedPreferences non chiffré
    private val tripDao = com.application.motium.data.local.MotiumDatabase.getInstance(context).tripDao()

    // Garder SharedPreferences uniquement pour auto-tracking et migration
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val supabaseTripRepository = SupabaseTripRepository.getInstance(context)
    private val authRepository = SupabaseAuthRepository.getInstance(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val vehicleRepository = VehicleRepository.getInstance(context)

    // Migration automatique au premier lancement
    init {
        CoroutineScope(Dispatchers.IO).launch {
            migrateFromPrefsIfNeeded()
        }
    }

    /**
     * MIGRATION: Transfert des trips depuis SharedPreferences vers Room Database.
     * Exécuté une seule fois au premier lancement après mise à jour.
     */
    private suspend fun migrateFromPrefsIfNeeded() {
        try {
            // Vérifier si la migration a déjà été effectuée
            if (prefs.getBoolean(KEY_MIGRATION_COMPLETE, false)) {
                MotiumApplication.logger.d("Migration already complete, skipping", "TripRepository")
                return
            }

            // Charger les anciens trips depuis SharedPreferences
            val oldTripsJson = prefs.getString(KEY_TRIPS, null)
            if (oldTripsJson != null) {
                val oldTrips = json.decodeFromString<List<Trip>>(oldTripsJson)

                if (oldTrips.isNotEmpty()) {
                    MotiumApplication.logger.i("🔄 Starting migration of ${oldTrips.size} trips to Room Database", "TripRepository")

                    // Obtenir le userId actuel ou le dernier connu
                    // FIX RLS: Utiliser users.id depuis localUserRepository
                    val localUser = localUserRepository.getLoggedInUser()
                    val userId = localUser?.id ?: prefs.getString(KEY_LAST_USER_ID, null)

                    if (userId != null) {
                        // Convertir et insérer dans Room
                        val entities = oldTrips.map { trip ->
                            trip.copy(userId = trip.userId ?: userId).toEntity(userId)
                        }

                        tripDao.insertTrips(entities)

                        MotiumApplication.logger.i("✅ Successfully migrated ${entities.size} trips to Room Database", "TripRepository")
                    } else {
                        MotiumApplication.logger.w("⚠️ No userId available for migration - trips will be migrated on next login", "TripRepository")
                    }
                }

                // Marquer la migration comme terminée et supprimer les anciennes données
                prefs.edit()
                    .remove(KEY_TRIPS)
                    .putBoolean(KEY_MIGRATION_COMPLETE, true)
                    .apply()

                MotiumApplication.logger.i("🗑️ Cleared old SharedPreferences trip data", "TripRepository")
            } else {
                // Pas de données à migrer, marquer comme terminé
                prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).apply()
                MotiumApplication.logger.d("No trips to migrate from SharedPreferences", "TripRepository")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Migration failed: ${e.message}", "TripRepository", e)
            // Ne pas marquer comme terminé en cas d'erreur - retry au prochain lancement
        }
    }

    suspend fun saveTrip(trip: Trip) = withContext(Dispatchers.IO) {
        try {
            // SECURITY: S'assurer que le trip a un userId
            // FIX RLS: Utiliser users.id (depuis localUserRepository) au lieu de auth.uid()
            // car trips.user_id doit correspondre à users.id, pas à auth.uid()
            val localUser = localUserRepository.getLoggedInUser()
            val userId = localUser?.id ?: prefs.getString(KEY_LAST_USER_ID, null)

            var tripWithUserId = if (trip.userId == null && userId != null) {
                trip.copy(userId = userId)
            } else {
                trip
            }

            // REIMBURSEMENT: Calculer le montant du remboursement
            // - PROFESSIONAL: toujours calculé
            // - PERSONAL: uniquement si isWorkHomeTrip = true, sinon 0€
            val vehicleId = tripWithUserId.vehicleId
            if (!vehicleId.isNullOrBlank() && userId != null) {
                val vehicle = vehicleRepository.getVehicleById(vehicleId)
                if (vehicle != null) {
                    val tripType = when (tripWithUserId.tripType) {
                        "PROFESSIONAL" -> TripType.PROFESSIONAL
                        "PERSONAL" -> TripType.PERSONAL
                        else -> TripType.PERSONAL
                    }

                    val reimbursement: Double
                    val distanceKm = tripWithUserId.totalDistance / 1000.0

                    if (tripType == TripType.PROFESSIONAL) {
                        // Trajets PRO: calcul classique
                        reimbursement = TripCalculator.calculateMileageCost(distanceKm, vehicle, tripType)
                    } else {
                        // Trajets PERSO: uniquement si travail-maison
                        if (tripWithUserId.isWorkHomeTrip) {
                            // Récupérer le paramètre considerFullDistance de l'utilisateur
                            val user = localUserRepository.getLoggedInUser()
                            val considerFullDistance = user?.considerFullDistance ?: false

                            // Appliquer le plafond de 40km si nécessaire
                            val effectiveDistance = if (!considerFullDistance && distanceKm > 40.0) {
                                40.0 // Plafond à 40km par trajet
                            } else {
                                distanceKm
                            }

                            // Calculer avec l'odomètre travail-maison pour déterminer la tranche
                            reimbursement = TripCalculator.calculateMileageCost(
                                effectiveDistance,
                                vehicle.copy(totalMileagePerso = vehicle.totalMileageWorkHome), // Utiliser l'odomètre travail-maison
                                tripType
                            )

                            if (!considerFullDistance && distanceKm > 40.0) {
                                MotiumApplication.logger.i(
                                    "📏 Work-home trip capped: ${String.format("%.2f", distanceKm)} km → 40 km (considerFullDistance=false)",
                                    "TripRepository"
                                )
                            }
                        } else {
                            // Trajet perso non travail-maison: pas d'indemnité
                            reimbursement = 0.0
                            MotiumApplication.logger.d(
                                "🚗 Personal trip (not work-home): no reimbursement",
                                "TripRepository"
                            )
                        }
                    }

                    tripWithUserId = tripWithUserId.copy(reimbursementAmount = reimbursement)
                    MotiumApplication.logger.i(
                        "💰 Calculated reimbursement: €${String.format("%.2f", reimbursement)} for ${String.format("%.2f", distanceKm)} km",
                        "TripRepository"
                    )
                }
            }

            // MILEAGE: Récupérer l'ancien trajet pour détecter les changements de véhicule/type
            val oldTrip = tripDao.getTripById(trip.id)?.toDataModel()
            val oldVehicleId = oldTrip?.vehicleId

            // SÉCURITÉ: Sauvegarder dans Room Database au lieu de SharedPreferences non chiffré
            if (userId != null) {
                val tripEntity = tripWithUserId.toEntity(userId)
                tripDao.insertTrip(tripEntity)

                MotiumApplication.logger.i(
                    "✅ Trip saved to Room Database: ${tripWithUserId.id}, ${tripWithUserId.getFormattedDistance()}, userId=$userId",
                    "TripRepository"
                )

                // MILEAGE: Mettre à jour le kilométrage des véhicules concernés
                val newVehicleId = tripWithUserId.vehicleId
                val vehicleIdsToUpdate = mutableListOf<String>()

                // Toujours mettre à jour le nouveau véhicule (s'il existe)
                if (!newVehicleId.isNullOrBlank()) {
                    vehicleIdsToUpdate.add(newVehicleId)
                }

                // Si le véhicule a changé, mettre à jour aussi l'ancien
                if (!oldVehicleId.isNullOrBlank() && oldVehicleId != newVehicleId) {
                    vehicleIdsToUpdate.add(oldVehicleId)
                    MotiumApplication.logger.i(
                        "🚗 Vehicle changed from $oldVehicleId to $newVehicleId - updating both mileages",
                        "TripRepository"
                    )
                }

                // Recalculer le kilométrage pour les véhicules concernés
                if (vehicleIdsToUpdate.isNotEmpty()) {
                    vehicleRepository.recalculateAndUpdateMultipleVehiclesMileage(vehicleIdsToUpdate)
                }
            } else {
                MotiumApplication.logger.w(
                    "⚠️ Cannot save trip - no userId available: ${tripWithUserId.id}",
                    "TripRepository"
                )
                return@withContext // Ne pas continuer sans userId
            }

            // SYNC OPTIMIZATION: Sync immédiat après création de trip
            // Synchroniser ce trip spécifique immédiatement (pas besoin d'attendre 15 minutes)
            try {
                MotiumApplication.logger.i(
                    "🔄 SYNC CHECK: Attempting to sync trip ${tripWithUserId.id} to Supabase\n" +
                    "   Local user available: ${localUser != null}\n" +
                    "   Resolved user ID: $userId\n" +
                    "   Trip user ID: ${tripWithUserId.userId}\n" +
                    "   Distance: ${tripWithUserId.getFormattedDistance()}\n" +
                    "   Points: ${tripWithUserId.locations.size}",
                    "TripRepository"
                )

                if (userId != null && tripWithUserId.userId != null) {
                    val supabaseResult = supabaseTripRepository.saveTrip(tripWithUserId.toDomainTrip(userId), userId)
                    if (supabaseResult.isSuccess) {
                        MotiumApplication.logger.i("✅ Trip synced immediately to Supabase: ${tripWithUserId.id}", "TripRepository")

                        // SYNC OPTIMIZATION: Marquer comme synchronisé après succès
                        markTripsAsSynced(listOf(tripWithUserId.id))

                        // SYNC OPTIMIZATION: Trigger sync rapide pour autres trips dirty (si présents)
                        // Notification au SyncManager qu'un trip vient d'être créé
                        com.application.motium.data.sync.SupabaseSyncManager.getInstance(appContext).forceSyncNow()
                    } else {
                        val error = supabaseResult.exceptionOrNull()
                        MotiumApplication.logger.e(
                            "❌ Failed to sync trip to Supabase: ${tripWithUserId.id}\n" +
                            "   Error: ${error?.message}\n" +
                            "   Stack trace: ${error?.stackTraceToString()?.take(500)}",
                            "TripRepository",
                            error
                        )
                    }
                } else {
                    MotiumApplication.logger.w(
                        "⚠️ Cannot sync trip - No user ID available: ${tripWithUserId.id}\n" +
                        "   Local user: ${localUser != null}\n" +
                        "   Last user ID: ${prefs.getString(KEY_LAST_USER_ID, null)}\n" +
                        "   Trip will sync when user authenticates\n" +
                        "   Needs sync: ${tripWithUserId.needsSync}",
                        "TripRepository"
                    )
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "❌ Exception during trip sync to Supabase: ${e.message}\n" +
                    "   Trip ID: ${tripWithUserId.id}\n" +
                    "   Stack trace: ${e.stackTraceToString().take(500)}",
                    "TripRepository",
                    e
                )
                // Ne pas faire échouer la sauvegarde locale si Supabase échoue
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error saving trip: ${e.message}", "TripRepository", e)
        }
    }

    /**
     * Result of checking trip access
     */
    sealed class TripAccessCheckResult {
        data class Allowed(val isInTrial: Boolean, val trialDaysRemaining: Int?) : TripAccessCheckResult()
        data class AccessDenied(val reason: String) : TripAccessCheckResult()
        data class Error(val message: String) : TripAccessCheckResult()
    }

    /**
     * Check if user can create a new trip based on subscription status.
     * TRIAL: Allowed while trial active
     * PREMIUM/LIFETIME: Always allowed
     * EXPIRED: Not allowed
     */
    suspend fun canCreateTrip(): TripAccessCheckResult = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext TripAccessCheckResult.Error("Utilisateur non connecté")

            val subscription = user.subscription

            if (subscription.hasValidAccess()) {
                TripAccessCheckResult.Allowed(
                    isInTrial = subscription.isInTrial(),
                    trialDaysRemaining = subscription.daysLeftInTrial()
                )
            } else {
                TripAccessCheckResult.AccessDenied(
                    reason = if (subscription.type == SubscriptionType.EXPIRED) {
                        "Votre essai gratuit est terminé"
                    } else {
                        "Abonnement requis"
                    }
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking trip access: ${e.message}", "TripRepository", e)
            TripAccessCheckResult.Error("Erreur: ${e.message}")
        }
    }

    /**
     * Get remaining trial days for the current user.
     * Returns null if subscribed or not in trial.
     */
    suspend fun getTrialDaysRemaining(): Int? = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext null
            user.subscription.daysLeftInTrial()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting trial days: ${e.message}", "TripRepository", e)
            null
        }
    }

    /**
     * Save a trip with access checking.
     * Returns false if access is denied (expired trial or no subscription).
     */
    suspend fun saveTripWithAccessCheck(trip: Trip): Boolean = withContext(Dispatchers.IO) {
        // Check access first
        when (val accessCheck = canCreateTrip()) {
            is TripAccessCheckResult.AccessDenied -> {
                MotiumApplication.logger.w(
                    "Access denied: ${accessCheck.reason}. Trip not saved.",
                    "TripRepository"
                )
                return@withContext false
            }
            is TripAccessCheckResult.Error -> {
                MotiumApplication.logger.e("Error checking trip access: ${accessCheck.message}", "TripRepository")
                // Allow saving in case of error (fail-open)
            }
            is TripAccessCheckResult.Allowed -> {
                // Continue to save
            }
        }

        // Save the trip
        saveTrip(trip)
        true
    }

    suspend fun getAllTrips(): List<Trip> = withContext(Dispatchers.IO) {
        try {
            // SECURITY: Obtenir l'utilisateur actuel pour filtrer les trips
            // FIX RLS: Utiliser users.id depuis localUserRepository
            val localUser = localUserRepository.getLoggedInUser()

            // FIX: Utiliser le dernier userId connu si l'utilisateur local n'est pas disponible
            val userId = localUser?.id ?: prefs.getString(KEY_LAST_USER_ID, null)

            if (userId == null) {
                MotiumApplication.logger.w("getAllTrips called but no user authenticated and no last userId - returning empty list", "TripRepository")
                return@withContext emptyList()
            }

            // Sauvegarder le userId pour les prochains chargements
            if (localUser != null && prefs.getString(KEY_LAST_USER_ID, null) != userId) {
                prefs.edit().putString(KEY_LAST_USER_ID, userId).apply()
            }

            // SÉCURITÉ: Charger depuis Room Database au lieu de SharedPreferences
            val tripEntities = tripDao.getTripsForUser(userId)
            val userTrips = tripEntities.map { it.toDataModel() }

            MotiumApplication.logger.d("Loaded ${userTrips.size} trips from Room Database for user $userId", "TripRepository")
            return@withContext userTrips
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading trips: ${e.message}", "TripRepository", e)
            return@withContext emptyList()
        }
    }

    /**
     * Récupère un trajet par son ID directement depuis Room (rapide, pas de chargement de tous les trajets).
     */
    suspend fun getTripById(tripId: String): Trip? = withContext(Dispatchers.IO) {
        try {
            val entity = tripDao.getTripById(tripId)
            entity?.toDataModel()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting trip by ID: ${e.message}", "TripRepository", e)
            null
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
     * NOTE: Room trie automatiquement par startTime DESC grâce à la requête dans TripDao
     */
    suspend fun getTripsPaginated(limit: Int = 10, offset: Int = 0): List<Trip> = withContext(Dispatchers.IO) {
        try {
            // FIX RLS: Utiliser users.id depuis localUserRepository
            val userId = localUserRepository.getLoggedInUser()?.id
                ?: prefs.getString(KEY_LAST_USER_ID, null)

            if (userId == null) {
                MotiumApplication.logger.w("getTripsPaginated called but no userId - returning empty list", "TripRepository")
                return@withContext emptyList()
            }

            // SÉCURITÉ: Charger depuis Room avec tri automatique
            val allTrips = tripDao.getTripsForUser(userId)
            val paginatedTrips = allTrips.drop(offset).take(limit).map { it.toDataModel() }

            MotiumApplication.logger.d(
                "Loaded ${paginatedTrips.size} trips (offset=$offset, limit=$limit, total=${allTrips.size})",
                "TripRepository"
            )
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
        try {
            // FIX RLS: Utiliser users.id depuis localUserRepository
            val userId = localUserRepository.getLoggedInUser()?.id
                ?: prefs.getString(KEY_LAST_USER_ID, null)

            if (userId == null) return@withContext 0

            // SÉCURITÉ: Compter depuis Room
            tripDao.getTripCount(userId)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error counting trips: ${e.message}", "TripRepository", e)
            0
        }
    }

    suspend fun deleteTrip(tripId: String) = withContext(Dispatchers.IO) {
        try {
            // MILEAGE: Récupérer le trajet avant suppression pour connaître le véhicule
            val tripToDelete = tripDao.getTripById(tripId)?.toDataModel()
            val vehicleId = tripToDelete?.vehicleId

            // SÉCURITÉ: Supprimer depuis Room Database
            tripDao.deleteTripById(tripId)

            MotiumApplication.logger.i("Trip deleted from Room Database: $tripId", "TripRepository")

            // MILEAGE: Recalculer le kilométrage du véhicule concerné
            if (!vehicleId.isNullOrBlank()) {
                MotiumApplication.logger.i(
                    "🚗 Trip deleted - updating mileage for vehicle: $vehicleId",
                    "TripRepository"
                )
                vehicleRepository.recalculateAndUpdateVehicleMileage(vehicleId)
            }

            // Supprimer de Supabase si l'utilisateur est connecté
            // FIX RLS: Utiliser users.id depuis localUserRepository
            try {
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    val supabaseResult = supabaseTripRepository.deleteTrip(tripId, localUser.id)
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

    /**
     * Récupère le TrackingMode depuis le cache local (SharedPreferences).
     * Cette valeur est utilisée pour l'affichage immédiat avant la synchronisation avec Supabase.
     */
    fun getTrackingMode(): TrackingMode {
        val modeName = prefs.getString(KEY_TRACKING_MODE, TrackingMode.DISABLED.name)
        return try {
            TrackingMode.valueOf(modeName ?: TrackingMode.DISABLED.name)
        } catch (e: Exception) {
            MotiumApplication.logger.w("Invalid tracking mode in prefs: $modeName, defaulting to DISABLED", "TripRepository")
            TrackingMode.DISABLED
        }
    }

    /**
     * Sauvegarde le TrackingMode dans le cache local (SharedPreferences).
     * Doit être appelé après chaque changement de mode pour maintenir la cohérence.
     */
    fun setTrackingMode(mode: TrackingMode) {
        prefs.edit().putString(KEY_TRACKING_MODE, mode.name).apply()
        MotiumApplication.logger.i("Tracking mode cached locally: $mode", "TripRepository")
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

    suspend fun syncAllTripsToSupabase(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // FIX RLS: Utiliser users.id depuis localUserRepository
            val localUser = localUserRepository.getLoggedInUser()
            if (localUser != null) {
                val localTrips = getAllTrips()

                // SYNC OPTIMIZATION: Ne synchroniser que les trips marqués comme "dirty" (needsSync = true)
                val dirtyTrips = localTrips.filter { it.needsSync }

                if (dirtyTrips.isNotEmpty()) {
                    MotiumApplication.logger.i(
                        "🔄 Starting sync of ${dirtyTrips.size} dirty trips to Supabase (out of ${localTrips.size} total trips)",
                        "TripRepository"
                    )

                    val result = supabaseTripRepository.syncTripsToSupabase(dirtyTrips.toDomainTripList(localUser.id), localUser.id)
                    if (result.isSuccess) {
                        val syncedCount = result.getOrNull() ?: 0
                        MotiumApplication.logger.i("✅ Successfully synced $syncedCount trips to Supabase", "TripRepository")

                        // SYNC OPTIMIZATION: Marquer les trips comme synchronisés
                        markTripsAsSynced(dirtyTrips.map { it.id })

                        Result.success(syncedCount)
                    } else {
                        MotiumApplication.logger.e("❌ Failed to sync trips to Supabase", "TripRepository")
                        Result.failure(result.exceptionOrNull() ?: Exception("Unknown sync error"))
                    }
                } else {
                    MotiumApplication.logger.i("✓ No dirty trips to sync (${localTrips.size} trips already synchronized)", "TripRepository")
                    Result.success(0)
                }
            } else {
                MotiumApplication.logger.w("⚠️ User not authenticated, cannot sync trips", "TripRepository")
                Result.failure(Exception("User not authenticated"))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error syncing trips to Supabase: ${e.message}", "TripRepository", e)
            Result.failure(e)
        }
    }

    /**
     * Compte le nombre de trips non synchronisés
     */
    suspend fun getUnsyncedTripsCount(): Int = withContext(Dispatchers.IO) {
        try {
            // FIX RLS: Utiliser users.id depuis localUserRepository
            val userId = localUserRepository.getLoggedInUser()?.id
                ?: prefs.getString(KEY_LAST_USER_ID, null)

            if (userId == null) return@withContext 0

            // SÉCURITÉ: Compter depuis Room
            tripDao.getTripsNeedingSync(userId).size
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error counting unsynced trips: ${e.message}", "TripRepository", e)
            0
        }
    }

    /**
     * Récupère tous les trips non synchronisés
     */
    suspend fun getUnsyncedTrips(): List<Trip> = withContext(Dispatchers.IO) {
        try {
            // FIX RLS: Utiliser users.id depuis localUserRepository
            val userId = localUserRepository.getLoggedInUser()?.id
                ?: prefs.getString(KEY_LAST_USER_ID, null)

            if (userId == null) return@withContext emptyList()

            // SÉCURITÉ: Charger depuis Room
            tripDao.getTripsNeedingSync(userId).map { it.toDataModel() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading unsynced trips: ${e.message}", "TripRepository", e)
            emptyList()
        }
    }

    /**
     * SYNC OPTIMIZATION: Marque les trips comme synchronisés
     * Met à jour needsSync = false et lastSyncedAt = now
     */
    private suspend fun markTripsAsSynced(tripIds: List<String>) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()

            // SÉCURITÉ: Mettre à jour dans Room Database
            tripIds.forEach { tripId ->
                tripDao.markTripAsSynced(tripId, now)
            }

            MotiumApplication.logger.i(
                "✅ Marked ${tripIds.size} trips as synced in Room Database (lastSyncedAt updated)",
                "TripRepository"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error marking trips as synced: ${e.message}", "TripRepository", e)
        }
    }

    /**
     * REIMBURSEMENT: Recalcule les montants de remboursement pour tous les trajets d'un véhicule.
     * Utilise le calcul progressif par tranches basé sur l'ordre chronologique des trajets.
     * À appeler quand la puissance fiscale d'un véhicule change.
     *
     * @param vehicleId L'ID du véhicule
     * @param tripType Le type de trajet à recalculer ("PROFESSIONAL" ou "PERSONAL")
     * @return Le nombre de trajets mis à jour
     */
    suspend fun recalculateReimbursementsForVehicle(vehicleId: String, tripType: String): Int = withContext(Dispatchers.IO) {
        try {
            if (vehicleId.isBlank()) {
                MotiumApplication.logger.w("Cannot recalculate reimbursements - no vehicle ID", "TripRepository")
                return@withContext 0
            }

            val vehicle = vehicleRepository.getVehicleById(vehicleId)
            if (vehicle == null) {
                MotiumApplication.logger.w("Cannot recalculate reimbursements - vehicle not found: $vehicleId", "TripRepository")
                return@withContext 0
            }

            // FIX RLS: Utiliser users.id depuis localUserRepository
            val userId = localUserRepository.getLoggedInUser()?.id
                ?: prefs.getString(KEY_LAST_USER_ID, null)
            if (userId == null) {
                MotiumApplication.logger.w("Cannot recalculate reimbursements - no user ID", "TripRepository")
                return@withContext 0
            }

            // Récupérer tous les trajets du véhicule triés par date
            val startOfYear = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val trips = tripDao.getTripsForVehicleAndType(vehicleId, tripType, startOfYear)
                .sortedBy { it.startTime }

            if (trips.isEmpty()) {
                MotiumApplication.logger.i("No trips to recalculate for vehicle $vehicleId", "TripRepository")
                return@withContext 0
            }

            val tripTypeEnum = when (tripType) {
                "PROFESSIONAL" -> TripType.PROFESSIONAL
                "PERSONAL" -> TripType.PERSONAL
                else -> TripType.PERSONAL
            }

            var cumulativeKm = 0.0
            var updatedCount = 0

            trips.forEach { tripEntity ->
                val distanceKm = tripEntity.totalDistance / 1000.0

                // Calculer le remboursement avec le kilométrage cumulatif actuel
                val reimbursement = TripCalculator.calculateMileageCost(
                    distanceKm,
                    vehicle.copy(
                        totalMileagePro = if (tripTypeEnum == TripType.PROFESSIONAL) cumulativeKm else vehicle.totalMileagePro,
                        totalMileagePerso = if (tripTypeEnum == TripType.PERSONAL) cumulativeKm else vehicle.totalMileagePerso
                    ),
                    tripTypeEnum
                )

                // Mettre à jour le trajet si le montant a changé
                if (tripEntity.reimbursementAmount != reimbursement) {
                    val updatedTrip = tripEntity.copy(
                        reimbursementAmount = reimbursement,
                        needsSync = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    tripDao.insertTrip(updatedTrip)
                    updatedCount++
                }

                cumulativeKm += distanceKm
            }

            MotiumApplication.logger.i(
                "✅ Recalculated reimbursements for $updatedCount/${trips.size} $tripType trips (vehicle: $vehicleId)",
                "TripRepository"
            )

            return@withContext updatedCount
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error recalculating reimbursements: ${e.message}", "TripRepository", e)
            return@withContext 0
        }
    }

    suspend fun syncTripsFromSupabase(userId: String? = null) = withContext(Dispatchers.IO) {
        try {
            // Utiliser le userId passé en paramètre ou le récupérer depuis localUserRepository
            // FIX RLS: Utiliser users.id au lieu de auth.uid()
            val effectiveUserId = userId ?: localUserRepository.getLoggedInUser()?.id
            if (effectiveUserId != null) {
                MotiumApplication.logger.i("🔄 Fetching trips from Supabase for user: $effectiveUserId", "TripRepository")

                val result = supabaseTripRepository.getAllTrips(effectiveUserId)
                if (result.isSuccess) {
                    val supabaseTrips = result.getOrNull() ?: emptyList()
                    MotiumApplication.logger.i("📥 Fetched ${supabaseTrips.size} trips from Supabase", "TripRepository")

                    // SECURITY: Convert domain trips to data trips WITH userId
                    val dataTrips = supabaseTrips.mapNotNull { domainTrip ->
                        try {
                            domainTrip.toDataTrip().copy(userId = effectiveUserId)
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("❌ Failed to convert trip ${domainTrip.id}: ${e.message}", "TripRepository", e)
                            null
                        }
                    }
                    MotiumApplication.logger.i("📊 Converted ${dataTrips.size}/${supabaseTrips.size} trips from Supabase", "TripRepository")

                    // Charger les trips locaux depuis Room
                    val localTripEntities = tripDao.getTripsForUser(effectiveUserId)
                    MotiumApplication.logger.i("📂 Found ${localTripEntities.size} local trips in Room", "TripRepository")

                    // Identifier les trips Supabase par ID
                    val supabaseTripIds = dataTrips.map { it.id }.toSet()

                    // UNIQUEMENT garder les trips locaux qui n'ont PAS encore été synchronisés (needsSync = true)
                    // Les trips avec needsSync = false qui ne sont plus dans Supabase ont été supprimés côté serveur
                    val localOnlyTripsToKeep = localTripEntities
                        .filter { it.id !in supabaseTripIds && it.needsSync }
                        .map { it.toDataModel() }

                    // Identifier les trips locaux à supprimer (supprimés côté Supabase)
                    val tripsToDelete = localTripEntities
                        .filter { it.id !in supabaseTripIds && !it.needsSync }

                    // Supprimer les trips qui ont été supprimés sur Supabase
                    if (tripsToDelete.isNotEmpty()) {
                        tripsToDelete.forEach { tripEntity ->
                            tripDao.deleteTripById(tripEntity.id)
                        }
                        MotiumApplication.logger.i(
                            "🗑️ Deleted ${tripsToDelete.size} trips that were removed from Supabase",
                            "TripRepository"
                        )
                    }

                    // Identifier les NOUVEAUX trips (dans Supabase mais pas en local)
                    val localTripIds = localTripEntities.map { it.id }.toSet()
                    val newTripsFromSupabase = dataTrips.filter { it.id !in localTripIds }
                    MotiumApplication.logger.i("🆕 New trips from Supabase: ${newTripsFromSupabase.size}", "TripRepository")
                    if (newTripsFromSupabase.isNotEmpty()) {
                        newTripsFromSupabase.take(3).forEach { trip ->
                            MotiumApplication.logger.i("   → New trip: ${trip.id}, distance=${trip.totalDistance}m, date=${trip.getFormattedDate()}", "TripRepository")
                        }
                    }

                    // Sauvegarder dans Room Database: trips Supabase + trips locaux non encore synchronisés
                    // PRESERVE local matchedRouteCoordinates cache when syncing from Supabase
                    val localCacheMap = localTripEntities.associate { it.id to it.matchedRouteCoordinates }
                    val dataTripsWithCache = dataTrips.map { trip ->
                        val cachedCoords = localCacheMap[trip.id]
                        if (cachedCoords != null) trip.copy(matchedRouteCoordinates = cachedCoords) else trip
                    }

                    val allTripsToSave = dataTripsWithCache + localOnlyTripsToKeep
                    MotiumApplication.logger.i("💾 Saving ${allTripsToSave.size} trips to Room (${dataTripsWithCache.size} from Supabase + ${localOnlyTripsToKeep.size} local pending)", "TripRepository")

                    if (allTripsToSave.isNotEmpty()) {
                        try {
                            val entities = allTripsToSave.map { it.toEntity(effectiveUserId) }
                            tripDao.insertTrips(entities)
                            MotiumApplication.logger.i("✅ Successfully inserted ${entities.size} trips into Room", "TripRepository")
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("❌ Failed to insert trips into Room: ${e.message}", "TripRepository", e)
                        }
                    }

                    // Vérifier le résultat
                    val finalCount = tripDao.getTripCount(effectiveUserId)
                    MotiumApplication.logger.i(
                        "✅ Sync complete: ${dataTrips.size} from Supabase, ${localOnlyTripsToKeep.size} local pending, ${tripsToDelete.size} deleted → Final count: $finalCount",
                        "TripRepository"
                    )

                    // MILEAGE: Recalculer le kilométrage de tous les véhicules concernés
                    // Collecter les vehicleIds de tous les trips traités (supprimés, ajoutés, modifiés)
                    val allVehicleIds = mutableSetOf<String>()

                    // Véhicules des trips supprimés
                    tripsToDelete.mapNotNull { it.vehicleId?.takeIf { id -> id.isNotBlank() } }
                        .forEach { allVehicleIds.add(it) }

                    // Véhicules des trips synchronisés depuis Supabase
                    dataTrips.mapNotNull { it.vehicleId?.takeIf { id -> id.isNotBlank() } }
                        .forEach { allVehicleIds.add(it) }

                    if (allVehicleIds.isNotEmpty()) {
                        MotiumApplication.logger.i(
                            "🚗 Updating mileage for ${allVehicleIds.size} vehicles after sync",
                            "TripRepository"
                        )
                        vehicleRepository.recalculateAndUpdateMultipleVehiclesMileage(allVehicleIds.toList())
                    }
                } else {
                    MotiumApplication.logger.e("❌ Failed to fetch trips from Supabase: ${result.exceptionOrNull()?.message}", "TripRepository")
                }
            } else {
                MotiumApplication.logger.w("⚠️ User not authenticated, skipping Supabase trip sync", "TripRepository")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error syncing trips from Supabase: ${e.message}", "TripRepository", e)
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
        reimbursementAmount = this.reimbursementAmount,
        isWorkHomeTrip = this.isWorkHomeTrip,
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
    }?.takeIf { it.isNotEmpty() } ?: run {
        // Fallback: create locations from start/end coordinates if no trace points
        val locations = mutableListOf<TripLocation>()
        if (startLatitude != 0.0 || startLongitude != 0.0) {
            locations.add(TripLocation(
                latitude = startLatitude,
                longitude = startLongitude,
                accuracy = 10.0f,
                timestamp = startTime.toEpochMilliseconds()
            ))
        }
        if (endLatitude != null && endLongitude != null && (endLatitude != 0.0 || endLongitude != 0.0)) {
            locations.add(TripLocation(
                latitude = endLatitude!!,
                longitude = endLongitude!!,
                accuracy = 10.0f,
                timestamp = endTime?.toEpochMilliseconds() ?: System.currentTimeMillis()
            ))
        }
        locations
    }

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
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
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
package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.VehicleRemoteDataSource
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * OFFLINE-FIRST: Repository pour gérer les véhicules avec stockage local Room Database
 * et synchronisation Supabase.
 *
 * Permet l'accès aux véhicules même en mode avion grâce au cache local.
 */
class VehicleRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: VehicleRepository? = null

        fun getInstance(context: Context): VehicleRepository {
            return instance ?: synchronized(this) {
                instance ?: VehicleRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val database = MotiumDatabase.getInstance(context)
    private val vehicleDao = database.vehicleDao()
    private val tripDao = database.tripDao()
    private val vehicleRemoteDataSource = VehicleRemoteDataSource.getInstance(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)

    /**
     * Get the start of the current year in milliseconds.
     */
    private fun getStartOfYearMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Calculate annual mileage from local trips for a vehicle.
     * Returns the total in kilometers.
     */
    private suspend fun calculateLocalMileage(vehicleId: String, tripType: String): Double {
        return try {
            val startOfYear = getStartOfYearMillis()
            // tripDao returns meters, convert to km
            val meters = tripDao.getAnnualMileageForVehicle(vehicleId, tripType, startOfYear)
            meters / 1000.0
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error calculating local mileage: ${e.message}", "VehicleRepository", e)
            0.0
        }
    }

    /**
     * Calculate work-home mileage with daily cap of 80km (40km each way).
     * Only considers personal trips with isWorkHomeTrip = true.
     *
     * @param vehicleId The vehicle to calculate mileage for
     * @param applyDailyCap If true, apply 80km daily cap. If false (user has considerFullDistance), no cap.
     * @return Total work-home mileage in kilometers
     */
    suspend fun calculateWorkHomeMileage(vehicleId: String, applyDailyCap: Boolean = true): Double {
        return try {
            val startOfYear = getStartOfYearMillis()
            val trips = tripDao.getWorkHomeTripsForVehicle(vehicleId, startOfYear)

            if (!applyDailyCap) {
                // No cap - just sum all distances
                val totalMeters = trips.sumOf { it.totalDistance }
                return totalMeters / 1000.0
            }

            // Apply daily cap of 80km (80000 meters)
            val dailyCap = 80000.0 // 80 km in meters

            // Group trips by day and cap each day's total
            val calendar = Calendar.getInstance()
            val tripsByDay = trips.groupBy { trip ->
                calendar.timeInMillis = trip.startTime
                Triple(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                )
            }

            var totalMileage = 0.0
            tripsByDay.forEach { (_, dayTrips) ->
                val dayTotal = dayTrips.sumOf { it.totalDistance }
                // Cap at 80km per day
                totalMileage += minOf(dayTotal, dailyCap)
            }

            totalMileage / 1000.0 // Convert to km
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error calculating work-home mileage: ${e.message}", "VehicleRepository", e)
            0.0
        }
    }

    /**
     * OFFLINE-FIRST: Get default vehicle for a specific user (for Pro export).
     * Reads from Room database.
     *
     * @param userId The user ID
     * @return The default vehicle for the user, or null if none found
     */
    suspend fun getDefaultVehicleForUser(userId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            getDefaultVehicle(userId, applyWorkHomeDailyCap = true)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting default vehicle for user $userId: ${e.message}", "VehicleRepository", e)
            null
        }
    }

    /**
     * OFFLINE-FIRST: Récupère tous les véhicules de l'utilisateur depuis Room Database.
     * Fonctionne en mode offline.
     *
     * BATTERY OPTIMIZATION (2026-01): Uses cached mileage values from Room Database.
     * Mileage is kept in sync via recalculateAndUpdateVehicleMileage() which is called
     * automatically when trips are created, updated, or deleted (TripRepository).
     * This avoids expensive recalculation on every read.
     *
     * @param userId The user ID
     * @param applyWorkHomeDailyCap Ignored - kept for API compatibility. Daily cap is applied at calculation time.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getAllVehiclesForUser(userId: String, applyWorkHomeDailyCap: Boolean = true): List<Vehicle> = withContext(Dispatchers.IO) {
        try {
            val vehicleEntities = vehicleDao.getVehiclesForUser(userId)

            // BATTERY OPTIMIZATION: Use cached mileage values from Room Database
            // Mileage is automatically updated by recalculateAndUpdateVehicleMileage()
            // whenever trips are modified (see TripRepository)
            val vehicles = vehicleEntities.map { entity ->
                entity.toDomainModel()
            }

            MotiumApplication.logger.i(
                "Loaded ${vehicles.size} vehicles from Room Database for user $userId (using cached mileage)",
                "VehicleRepository"
            )
            return@withContext vehicles
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error loading vehicles from Room: ${e.message}", "VehicleRepository", e)
            return@withContext emptyList()
        }
    }

    /**
     * OFFLINE-FIRST: Récupère un véhicule par son ID depuis Room Database.
     *
     * BATTERY OPTIMIZATION (2026-01): Uses cached mileage values from Room Database.
     * Mileage is kept in sync via recalculateAndUpdateVehicleMileage().
     *
     * @param vehicleId The vehicle ID
     * @param applyWorkHomeDailyCap Ignored - kept for API compatibility
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getVehicleById(vehicleId: String, applyWorkHomeDailyCap: Boolean = true): Vehicle? = withContext(Dispatchers.IO) {
        try {
            val entity = vehicleDao.getVehicleById(vehicleId) ?: return@withContext null
            // BATTERY OPTIMIZATION: Use cached mileage values
            entity.toDomainModel()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting vehicle by ID: ${e.message}", "VehicleRepository", e)
            null
        }
    }

    /**
     * OFFLINE-FIRST: Récupère le véhicule par défaut de l'utilisateur depuis Room Database.
     *
     * BATTERY OPTIMIZATION (2026-01): Uses cached mileage values from Room Database.
     * Mileage is kept in sync via recalculateAndUpdateVehicleMileage().
     *
     * @param userId The user ID
     * @param applyWorkHomeDailyCap Ignored - kept for API compatibility
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getDefaultVehicle(userId: String, applyWorkHomeDailyCap: Boolean = true): Vehicle? = withContext(Dispatchers.IO) {
        try {
            val entity = vehicleDao.getDefaultVehicle(userId) ?: return@withContext null
            // BATTERY OPTIMIZATION: Use cached mileage values
            entity.toDomainModel()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting default vehicle: ${e.message}", "VehicleRepository", e)
            null
        }
    }

    /**
     * OFFLINE-FIRST: Flow réactif pour observer les véhicules de l'utilisateur.
     */
    fun getVehiclesForUserFlow(userId: String): Flow<List<Vehicle>> {
        return vehicleDao.getVehiclesForUserFlow(userId).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * Ajoute un nouveau véhicule.
     * Sauvegarde d'abord localement, puis synchronise avec Supabase si possible.
     */
    suspend fun insertVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            // 0. Si le véhicule est marqué comme défaut, retirer le statut des autres d'abord
            if (vehicle.isDefault) {
                vehicleDao.unsetAllDefaultVehicles(vehicle.userId)
                MotiumApplication.logger.i("Unset default from all vehicles for user: ${vehicle.userId}", "VehicleRepository")
            }

            // 1. Sauvegarder localement dans Room
            val vehicleEntity = vehicle.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.PENDING_UPLOAD.name)
            vehicleDao.insertVehicle(vehicleEntity)

            MotiumApplication.logger.i("✅ Vehicle saved to Room Database: ${vehicle.id}", "VehicleRepository")

            // 2. Synchroniser avec Supabase si l'utilisateur est connecté
            try {
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    // Si le véhicule est défaut, d'abord unset les autres sur Supabase
                    if (vehicle.isDefault) {
                        vehicleRemoteDataSource.setDefaultVehicle(vehicle.userId, vehicle.id)
                    }

                    vehicleRemoteDataSource.insertVehicle(vehicle)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicle.id, System.currentTimeMillis())

                    MotiumApplication.logger.i("✅ Vehicle synced to Supabase: ${vehicle.id}", "VehicleRepository")
                } else {
                    // OFFLINE-FIRST: Queue operation for background sync
                    queueVehicleOperation(vehicle.id, PendingOperationEntity.ACTION_CREATE)
                    MotiumApplication.logger.w("⚠️ Vehicle saved locally only - queued for background sync", "VehicleRepository")
                }
            } catch (e: Exception) {
                // OFFLINE-FIRST: Queue operation for retry when sync fails
                queueVehicleOperation(vehicle.id, PendingOperationEntity.ACTION_CREATE)
                MotiumApplication.logger.e("❌ Failed to sync vehicle to Supabase, queued for retry: ${e.message}", "VehicleRepository", e)
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inserting vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * Met à jour un véhicule existant.
     */
    suspend fun updateVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            // Si le véhicule est marqué comme défaut, retirer le statut des autres d'abord
            if (vehicle.isDefault) {
                vehicleDao.unsetAllDefaultVehicles(vehicle.userId)
                MotiumApplication.logger.i("Unset default from all vehicles for user: ${vehicle.userId}", "VehicleRepository")
            }

            // 1. Mettre à jour localement dans Room
            val vehicleEntity = vehicle.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.PENDING_UPLOAD.name)
            vehicleDao.updateVehicle(vehicleEntity)

            MotiumApplication.logger.i("✅ Vehicle updated in Room Database: ${vehicle.id}, isDefault=${vehicle.isDefault}", "VehicleRepository")

            // 2. Synchroniser avec Supabase si possible
            try {
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    // Si le véhicule est défaut, utiliser setDefaultVehicle pour unset les autres sur Supabase aussi
                    if (vehicle.isDefault) {
                        vehicleRemoteDataSource.setDefaultVehicle(vehicle.userId, vehicle.id)
                    }
                    // Puis mettre à jour toutes les autres propriétés
                    vehicleRemoteDataSource.updateVehicle(vehicle)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicle.id, System.currentTimeMillis())

                    MotiumApplication.logger.i("✅ Vehicle synced to Supabase: ${vehicle.id}", "VehicleRepository")
                } else {
                    // OFFLINE-FIRST: Queue operation for background sync
                    queueVehicleOperation(vehicle.id, PendingOperationEntity.ACTION_UPDATE)
                    MotiumApplication.logger.w("⚠️ Vehicle updated locally only - queued for background sync", "VehicleRepository")
                }
            } catch (e: Exception) {
                // OFFLINE-FIRST: Queue operation for retry when sync fails
                queueVehicleOperation(vehicle.id, PendingOperationEntity.ACTION_UPDATE)
                MotiumApplication.logger.e("❌ Failed to sync vehicle to Supabase, queued for retry: ${e.message}", "VehicleRepository", e)
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * Définit un véhicule comme véhicule par défaut.
     */
    suspend fun setDefaultVehicle(userId: String, vehicleId: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Mettre à jour localement dans Room
            vehicleDao.unsetAllDefaultVehicles(userId)
            vehicleDao.setVehicleAsDefault(vehicleId)
            vehicleDao.markVehicleAsNeedingSync(vehicleId)

            MotiumApplication.logger.i("✅ Default vehicle set in Room Database: $vehicleId", "VehicleRepository")

            // 2. Synchroniser avec Supabase si possible
            try {
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    vehicleRemoteDataSource.setDefaultVehicle(userId, vehicleId)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicleId, System.currentTimeMillis())

                    MotiumApplication.logger.i("✅ Default vehicle synced to Supabase: $vehicleId", "VehicleRepository")
                } else {
                    // OFFLINE-FIRST: Queue operation for background sync
                    queueVehicleOperation(vehicleId, PendingOperationEntity.ACTION_UPDATE)
                    MotiumApplication.logger.w("⚠️ Default vehicle set locally only - queued for background sync", "VehicleRepository")
                }
            } catch (e: Exception) {
                // OFFLINE-FIRST: Queue operation for retry when sync fails
                queueVehicleOperation(vehicleId, PendingOperationEntity.ACTION_UPDATE)
                MotiumApplication.logger.e("❌ Failed to sync default vehicle to Supabase, queued for retry: ${e.message}", "VehicleRepository", e)
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error setting default vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * Supprime un véhicule.
     */
    suspend fun deleteVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            // 1. Tenter la suppression sur Supabase d'abord si possible
            var needsQueueing = false
            try {
                val localUser = localUserRepository.getLoggedInUser()
                if (localUser != null) {
                    vehicleRemoteDataSource.deleteVehicle(vehicle)
                    MotiumApplication.logger.i("✅ Vehicle deleted from Supabase: ${vehicle.id}", "VehicleRepository")
                } else {
                    // OFFLINE-FIRST: Queue operation for background sync BEFORE deleting locally
                    needsQueueing = true
                    MotiumApplication.logger.w("⚠️ User offline - queuing vehicle deletion for background sync", "VehicleRepository")
                }
            } catch (e: Exception) {
                // OFFLINE-FIRST: Queue operation for retry when sync fails
                needsQueueing = true
                MotiumApplication.logger.e("❌ Failed to delete vehicle from Supabase, queued for retry: ${e.message}", "VehicleRepository", e)
            }

            // 2. Queue the delete operation if needed (BEFORE local deletion)
            if (needsQueueing) {
                queueVehicleOperation(vehicle.id, PendingOperationEntity.ACTION_DELETE)
            }

            // 3. Supprimer localement de Room
            vehicleDao.deleteVehicleById(vehicle.id)
            MotiumApplication.logger.i("✅ Vehicle deleted from Room Database: ${vehicle.id}", "VehicleRepository")

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * SYNC: Synchronise tous les véhicules non synchronisés avec Supabase.
     */
    suspend fun syncVehiclesToSupabase(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val localUser = localUserRepository.getLoggedInUser()
            if (localUser == null) {
                MotiumApplication.logger.w("⚠️ User not authenticated, cannot sync vehicles", "VehicleRepository")
                return@withContext Result.failure(Exception("User not authenticated"))
            }

            val vehiclesNeedingSync = vehicleDao.getVehiclesNeedingSync(localUser.id)

            if (vehiclesNeedingSync.isEmpty()) {
                MotiumApplication.logger.i("✓ No vehicles to sync", "VehicleRepository")
                return@withContext Result.success(0)
            }

            MotiumApplication.logger.i("🔄 Syncing ${vehiclesNeedingSync.size} vehicles to Supabase", "VehicleRepository")

            var syncedCount = 0
            vehiclesNeedingSync.forEach { entity ->
                try {
                    val vehicle = entity.toDomainModel()
                    vehicleRemoteDataSource.updateVehicle(vehicle)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicle.id, System.currentTimeMillis())
                    syncedCount++
                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Failed to sync vehicle ${entity.id}: ${e.message}", "VehicleRepository", e)
                }
            }

            MotiumApplication.logger.i("✅ Successfully synced $syncedCount vehicles to Supabase", "VehicleRepository")
            Result.success(syncedCount)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error syncing vehicles to Supabase: ${e.message}", "VehicleRepository", e)
            Result.failure(e)
        }
    }

    /**
     * SYNC: Récupère les véhicules depuis Supabase et les sauvegarde localement.
     */
    suspend fun syncVehiclesFromSupabase() = withContext(Dispatchers.IO) {
        try {
            val localUser = localUserRepository.getLoggedInUser()
            if (localUser == null) {
                MotiumApplication.logger.i("User not authenticated, skipping Supabase vehicle sync", "VehicleRepository")
                return@withContext
            }

            MotiumApplication.logger.i("🔄 Fetching vehicles from Supabase for user: ${localUser.id}", "VehicleRepository")

            val supabaseVehicles = vehicleRemoteDataSource.getAllVehiclesForUser(localUser.id)

            if (supabaseVehicles.isNotEmpty()) {
                // Convertir en entités et sauvegarder dans Room
                val entities = supabaseVehicles.map { it.toEntity(syncStatus = com.application.motium.data.local.entities.SyncStatus.SYNCED.name, serverUpdatedAt = System.currentTimeMillis()) }
                vehicleDao.insertVehicles(entities)

                MotiumApplication.logger.i("✅ Synced ${supabaseVehicles.size} vehicles from Supabase to Room Database", "VehicleRepository")
            } else {
                MotiumApplication.logger.i("No vehicles found on Supabase for user ${localUser.id}", "VehicleRepository")
            }
        } catch (e: java.util.concurrent.CancellationException) {
            // Normal cancellation (e.g., user navigated away) - don't log as error
            MotiumApplication.logger.d("Vehicle sync cancelled (user navigated away)", "VehicleRepository")
            throw e // Rethrow to properly propagate cancellation
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error syncing vehicles from Supabase: ${e.message}", "VehicleRepository", e)
        }
    }

    /**
     * Supprime tous les véhicules de l'utilisateur.
     */
    suspend fun deleteAllVehiclesForUser(userId: String) = withContext(Dispatchers.IO) {
        try {
            vehicleDao.deleteAllVehiclesForUser(userId)
            MotiumApplication.logger.i("Deleted all vehicles for user: $userId", "VehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting all vehicles for user: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * OFFLINE-FIRST: Queue a vehicle operation for background sync.
     * This ensures that vehicle changes are synchronized even when offline.
     *
     * @param vehicleId The ID of the vehicle
     * @param action The action to perform (ACTION_CREATE, ACTION_UPDATE, ACTION_DELETE)
     */
    private suspend fun queueVehicleOperation(vehicleId: String, action: String) {
        try {
            val syncManager = OfflineFirstSyncManager.getInstance(appContext)
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_VEHICLE,
                entityId = vehicleId,
                action = action,
                payload = null,
                priority = 0
            )
            MotiumApplication.logger.i("🔄 Vehicle operation queued: $vehicleId ($action)", "VehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to queue vehicle operation: ${e.message}", "VehicleRepository", e)
        }
    }

    /**
     * Recalcule et met à jour le kilométrage d'un véhicule à partir des trajets.
     * Appelé automatiquement lorsqu'un trajet est créé, modifié ou supprimé.
     *
     * @param vehicleId L'ID du véhicule à mettre à jour
     */
    suspend fun recalculateAndUpdateVehicleMileage(vehicleId: String) = withContext(Dispatchers.IO) {
        try {
            if (vehicleId.isBlank()) {
                MotiumApplication.logger.d("Skipping mileage update - no vehicle ID", "VehicleRepository")
                return@withContext
            }

            // Vérifier que le véhicule existe
            val vehicle = vehicleDao.getVehicleById(vehicleId)
            if (vehicle == null) {
                MotiumApplication.logger.w("Cannot update mileage - vehicle not found: $vehicleId", "VehicleRepository")
                return@withContext
            }

            // Récupérer considerFullDistance pour savoir si on applique le cap de 80km/jour
            val user = localUserRepository.getLoggedInUser()
            val applyDailyCap = !(user?.considerFullDistance ?: false)

            // Recalculer les 3 types de kilométrage depuis les trajets
            val proMileage = calculateLocalMileage(vehicleId, "PROFESSIONAL")
            val persoMileage = calculateLocalMileage(vehicleId, "PERSONAL")
            val workHomeMileage = calculateWorkHomeMileage(vehicleId, applyDailyCap)

            // Mettre à jour les 3 valeurs dans Room Database
            vehicleDao.updateVehicleMileage(vehicleId, persoMileage, proMileage, workHomeMileage)

            MotiumApplication.logger.i(
                "✅ Vehicle mileage updated: $vehicleId (Pro: ${"%.1f".format(proMileage)} km, Perso: ${"%.1f".format(persoMileage)} km, WorkHome: ${"%.1f".format(workHomeMileage)} km)",
                "VehicleRepository"
            )

            // Marquer le véhicule comme nécessitant une sync
            vehicleDao.markVehicleAsNeedingSync(vehicleId)

            // OFFLINE-FIRST: Queue sync operation for immediate upload
            try {
                val syncManager = OfflineFirstSyncManager.getInstance(appContext)
                syncManager.queueOperation(
                    entityType = PendingOperationEntity.TYPE_VEHICLE,
                    entityId = vehicleId,
                    action = PendingOperationEntity.ACTION_UPDATE,
                    payload = null,
                    priority = 0
                )
                MotiumApplication.logger.i(
                    "🔄 Vehicle mileage queued for sync: $vehicleId",
                    "VehicleRepository"
                )
            } catch (e: Exception) {
                MotiumApplication.logger.w(
                    "⚠️ Failed to queue vehicle mileage sync (will retry on next sync): ${e.message}",
                    "VehicleRepository"
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating vehicle mileage: ${e.message}", "VehicleRepository", e)
        }
    }

    /**
     * Met à jour le kilométrage pour plusieurs véhicules.
     * Utilisé lorsqu'un trajet change de véhicule (ancien + nouveau véhicule à mettre à jour).
     *
     * @param vehicleIds Liste des IDs de véhicules à mettre à jour
     */
    suspend fun recalculateAndUpdateMultipleVehiclesMileage(vehicleIds: List<String>) = withContext(Dispatchers.IO) {
        vehicleIds.filter { it.isNotBlank() }.distinct().forEach { vehicleId ->
            recalculateAndUpdateVehicleMileage(vehicleId)
        }
    }
}

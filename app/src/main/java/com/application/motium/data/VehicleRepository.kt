package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.SyncStatus
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.VehicleRemoteDataSource
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.domain.model.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val syncManager by lazy { OfflineFirstSyncManager.getInstance(appContext) }

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
     * PREFETCH: Charge rapidement les vÃ©hicules depuis Supabase si Room est vide.
     * Objectif: afficher les vÃ©hicules dÃ¨s la connexion (avant la fin du delta sync).
     *
     * - Ne touche pas aux donnÃ©es locales si elles existent dÃ©jÃ 
     * - N'Ã©crit que des entitÃ©s SYNCED pour Ã©viter les conflits
     *
     * @return Nombre de vÃ©hicules prÃ©chargÃ©s
     */
    suspend fun prefetchVehiclesIfEmpty(userId: String): Int = withContext(Dispatchers.IO) {
        try {
            val localVehicles = vehicleDao.getVehiclesForUser(userId)
            if (localVehicles.isNotEmpty()) {
                return@withContext 0
            }

            MotiumApplication.logger.i(
                "Prefetching vehicles from Supabase for user: $userId",
                "VehicleRepository"
            )

            val remoteVehicles = VehicleRemoteDataSource.getInstance(appContext)
                .getAllVehiclesForUser(userId)

            if (remoteVehicles.isEmpty()) {
                return@withContext 0
            }

            val now = System.currentTimeMillis()
            val entities = remoteVehicles.map { vehicle ->
                vehicle.toEntity(
                    syncStatus = SyncStatus.SYNCED.name,
                    localUpdatedAt = now,
                    serverUpdatedAt = vehicle.updatedAt.toEpochMilliseconds(),
                    version = 1
                )
            }

            vehicleDao.insertVehicles(entities)

            MotiumApplication.logger.i(
                "Prefetched ${entities.size} vehicles into Room",
                "VehicleRepository"
            )

            return@withContext entities.size
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error prefetching vehicles: ${e.message}",
                "VehicleRepository",
                e
            )
            return@withContext 0
        }
    }

    /**
     * FORCE REFRESH: Charge tous les vÃ©hicules depuis Supabase et met Ã  jour Room.
     * UtilisÃ© pour s'assurer que les vÃ©hicules sont prÃªts avant d'afficher Home.
     *
     * - Ne remplace pas les vÃ©hicules avec modifications locales (syncStatus != SYNCED)
     * - Supprime les vÃ©hicules orphelins cÃ´tÃ© local (si absents serveur et SYNCED)
     *
     * @return Nombre de vÃ©hicules synchronisÃ©s depuis le serveur
     */
    suspend fun refreshVehiclesBlocking(userId: String): Int = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i(
                "Refreshing vehicles from Supabase for user: $userId",
                "VehicleRepository"
            )

            val remoteVehicles = VehicleRemoteDataSource.getInstance(appContext)
                .getAllVehiclesForUser(userId)

            val localVehicles = vehicleDao.getVehiclesForUser(userId)
            val localById = localVehicles.associateBy { it.id }
            val serverIds = remoteVehicles.map { it.id }.toSet()

            var syncedCount = 0
            var skippedCount = 0
            var deletedCount = 0

            // Delete local vehicles that no longer exist on server (only if SYNCED)
            for (local in localVehicles) {
                if (local.id !in serverIds && local.syncStatus == SyncStatus.SYNCED.name) {
                    vehicleDao.deleteVehicleById(local.id)
                    deletedCount++
                }
            }

            // Upsert server vehicles (skip if local has pending changes)
            val now = System.currentTimeMillis()
            for (vehicle in remoteVehicles) {
                val local = localById[vehicle.id]
                if (local == null || local.syncStatus == SyncStatus.SYNCED.name) {
                    vehicleDao.insertVehicle(
                        vehicle.toEntity(
                            syncStatus = SyncStatus.SYNCED.name,
                            localUpdatedAt = now,
                            serverUpdatedAt = vehicle.updatedAt.toEpochMilliseconds(),
                            version = 1
                        )
                    )
                    syncedCount++
                } else {
                    skippedCount++
                }
            }

            MotiumApplication.logger.i(
                "Vehicle refresh: $syncedCount synced, $skippedCount skipped, $deletedCount deleted",
                "VehicleRepository"
            )

            return@withContext syncedCount
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error refreshing vehicles: ${e.message}",
                "VehicleRepository",
                e
            )
            return@withContext 0
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
     * Sauvegarde localement puis queue pour sync atomique via sync_changes() RPC.
     */
    suspend fun insertVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            // 0. Si le véhicule est marqué comme défaut, retirer le statut des autres d'abord
            if (vehicle.isDefault) {
                vehicleDao.unsetAllDefaultVehicles(vehicle.userId)
                MotiumApplication.logger.i("Unset default from all vehicles for user: ${vehicle.userId}", "VehicleRepository")
            }

            // 1. Sauvegarder localement dans Room avec PENDING_UPLOAD
            val version = 1
            val vehicleEntity = vehicle.toEntity(
                syncStatus = SyncStatus.PENDING_UPLOAD.name,
                version = version
            )
            vehicleDao.insertVehicle(vehicleEntity)

            MotiumApplication.logger.i("Vehicle saved to Room Database: ${vehicle.id}", "VehicleRepository")

            // 2. Queue pour sync atomique via sync_changes() RPC
            val payload = buildVehiclePayload(vehicle, version)
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_VEHICLE,
                entityId = vehicle.id,
                action = PendingOperationEntity.ACTION_CREATE,
                payload = payload,
                priority = 1
            )

            MotiumApplication.logger.i("Vehicle queued for sync: ${vehicle.id}", "VehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inserting vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * Met à jour un véhicule existant.
     * Sauvegarde localement puis queue pour sync atomique via sync_changes() RPC.
     */
    suspend fun updateVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            // Si le véhicule est marqué comme défaut, retirer le statut des autres d'abord
            if (vehicle.isDefault) {
                vehicleDao.unsetAllDefaultVehicles(vehicle.userId)
                MotiumApplication.logger.i("Unset default from all vehicles for user: ${vehicle.userId}", "VehicleRepository")
            }

            // 1. Mettre à jour localement avec version incrémentée
            val currentEntity = vehicleDao.getVehicleById(vehicle.id)
            val newVersion = (currentEntity?.version ?: 0) + 1

            val vehicleEntity = vehicle.toEntity(
                syncStatus = SyncStatus.PENDING_UPLOAD.name,
                version = newVersion
            )
            vehicleDao.updateVehicle(vehicleEntity)

            MotiumApplication.logger.i("Vehicle updated in Room Database: ${vehicle.id}, isDefault=${vehicle.isDefault}", "VehicleRepository")

            // 2. Queue pour sync atomique via sync_changes() RPC
            val payload = buildVehiclePayload(vehicle, newVersion)
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_VEHICLE,
                entityId = vehicle.id,
                action = PendingOperationEntity.ACTION_UPDATE,
                payload = payload,
                priority = 1
            )

            MotiumApplication.logger.i("Vehicle update queued for sync: ${vehicle.id}", "VehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * Définit un véhicule comme véhicule par défaut.
     * Sauvegarde localement puis queue pour sync atomique via sync_changes() RPC.
     */
    suspend fun setDefaultVehicle(userId: String, vehicleId: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Get old default vehicle BEFORE unsetting (to queue its sync later)
            val oldDefault = vehicleDao.getDefaultVehicle(userId)

            // 2. Mettre à jour localement dans Room
            vehicleDao.unsetAllDefaultVehicles(userId)
            vehicleDao.setVehicleAsDefault(vehicleId)

            // 3. Queue sync for old default vehicle (isDefault=false)
            if (oldDefault != null && oldDefault.id != vehicleId) {
                vehicleDao.markVehicleAsNeedingSync(oldDefault.id)
                val oldEntity = vehicleDao.getVehicleById(oldDefault.id)
                if (oldEntity != null) {
                    val payload = buildVehiclePayload(oldEntity.toDomainModel(), oldEntity.version ?: 1)
                    syncManager.queueOperation(
                        entityType = PendingOperationEntity.TYPE_VEHICLE,
                        entityId = oldDefault.id,
                        action = PendingOperationEntity.ACTION_UPDATE,
                        payload = payload,
                        priority = 1
                    )
                    MotiumApplication.logger.i("Old default vehicle queued for sync: ${oldDefault.id}", "VehicleRepository")
                }
            }

            // 4. Incrémenter la version pour sync du nouveau default
            val currentEntity = vehicleDao.getVehicleById(vehicleId)
            val newVersion = (currentEntity?.version ?: 0) + 1
            vehicleDao.markVehicleAsNeedingSync(vehicleId)

            MotiumApplication.logger.i("Default vehicle set in Room Database: $vehicleId", "VehicleRepository")

            // 5. Queue pour sync - on a besoin du véhicule complet pour le payload
            if (currentEntity != null) {
                val vehicle = currentEntity.copy(isDefault = true).toDomainModel()
                val payload = buildVehiclePayload(vehicle, newVersion)
                syncManager.queueOperation(
                    entityType = PendingOperationEntity.TYPE_VEHICLE,
                    entityId = vehicleId,
                    action = PendingOperationEntity.ACTION_UPDATE,
                    payload = payload,
                    priority = 1
                )
                MotiumApplication.logger.i("New default vehicle queued for sync: $vehicleId", "VehicleRepository")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error setting default vehicle: ${e.message}", "VehicleRepository", e)
            throw e
        }
    }

    /**
     * Supprime un véhicule.
     * Queue la suppression pour sync atomique via sync_changes() RPC, puis supprime localement.
     */
    suspend fun deleteVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            // 1. Récupérer la version avant suppression
            val currentEntity = vehicleDao.getVehicleById(vehicle.id)
            val version = (currentEntity?.version ?: 0) + 1

            // 2. Queue l'opération DELETE AVANT la suppression locale
            syncManager.queueOperation(
                entityType = PendingOperationEntity.TYPE_VEHICLE,
                entityId = vehicle.id,
                action = PendingOperationEntity.ACTION_DELETE,
                payload = buildJsonObject { put("version", version) }.toString(),
                priority = 1
            )

            // 3. Supprimer localement de Room
            vehicleDao.deleteVehicleById(vehicle.id)
            MotiumApplication.logger.i("Vehicle deleted from Room and queued for sync: ${vehicle.id}", "VehicleRepository")

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting vehicle: ${e.message}", "VehicleRepository", e)
            throw e
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
     * Build JSON payload matching push_vehicle_change() SQL fields.
     */
    private fun buildVehiclePayload(vehicle: Vehicle, version: Int): String {
        return buildJsonObject {
            put("name", vehicle.name)
            put("type", vehicle.type.name)
            put("license_plate", vehicle.licensePlate ?: "")
            put("power", vehicle.power?.name ?: "")
            put("fuel_type", vehicle.fuelType?.name ?: "")
            put("mileage_rate", vehicle.mileageRate)
            put("is_default", vehicle.isDefault)
            put("total_mileage_perso", vehicle.totalMileagePerso)
            put("total_mileage_pro", vehicle.totalMileagePro)
            put("total_mileage_work_home", vehicle.totalMileageWorkHome)
            put("version", version)
        }.toString()
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
                // Re-read the entity after markVehicleAsNeedingSync (which incremented version)
                val updatedEntity = vehicleDao.getVehicleById(vehicleId)
                if (updatedEntity != null) {
                    val updatedVehicle = updatedEntity.toDomainModel()
                    val payload = buildVehiclePayload(updatedVehicle, updatedEntity.version ?: 1)
                    syncManager.queueOperation(
                        entityType = PendingOperationEntity.TYPE_VEHICLE,
                        entityId = vehicleId,
                        action = PendingOperationEntity.ACTION_UPDATE,
                        payload = payload,
                        priority = 1
                    )
                }
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

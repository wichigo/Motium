package com.application.motium.data

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.toDomainModel
import com.application.motium.data.local.entities.toEntity
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseVehicleRepository
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
    private val supabaseVehicleRepository = SupabaseVehicleRepository.getInstance(context)
    private val authRepository = SupabaseAuthRepository.getInstance(context)

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
     * OFFLINE-FIRST: Récupère tous les véhicules de l'utilisateur depuis Room Database.
     * Fonctionne en mode offline.
     * IMPORTANT: Mileage values are recalculated from local trips to ensure accuracy.
     */
    suspend fun getAllVehiclesForUser(userId: String): List<Vehicle> = withContext(Dispatchers.IO) {
        try {
            val vehicleEntities = vehicleDao.getVehiclesForUser(userId)

            // Recalculate mileage from local trips for each vehicle
            // This ensures we always have accurate values, not stale cached data
            val vehicles = vehicleEntities.map { entity ->
                val baseDomain = entity.toDomainModel()
                val proMileage = calculateLocalMileage(entity.id, "PROFESSIONAL")
                val persoMileage = calculateLocalMileage(entity.id, "PERSONAL")

                baseDomain.copy(
                    totalMileagePro = proMileage,
                    totalMileagePerso = persoMileage
                )
            }

            MotiumApplication.logger.i(
                "Loaded ${vehicles.size} vehicles from Room Database for user $userId (mileage recalculated from local trips)",
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
     * Mileage values are recalculated from local trips.
     */
    suspend fun getVehicleById(vehicleId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            val entity = vehicleDao.getVehicleById(vehicleId) ?: return@withContext null
            val baseDomain = entity.toDomainModel()

            // Recalculate mileage from local trips
            val proMileage = calculateLocalMileage(vehicleId, "PROFESSIONAL")
            val persoMileage = calculateLocalMileage(vehicleId, "PERSONAL")

            baseDomain.copy(
                totalMileagePro = proMileage,
                totalMileagePerso = persoMileage
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting vehicle by ID: ${e.message}", "VehicleRepository", e)
            null
        }
    }

    /**
     * OFFLINE-FIRST: Récupère le véhicule par défaut de l'utilisateur depuis Room Database.
     * Mileage values are recalculated from local trips.
     */
    suspend fun getDefaultVehicle(userId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            val entity = vehicleDao.getDefaultVehicle(userId) ?: return@withContext null
            val baseDomain = entity.toDomainModel()

            // Recalculate mileage from local trips
            val proMileage = calculateLocalMileage(entity.id, "PROFESSIONAL")
            val persoMileage = calculateLocalMileage(entity.id, "PERSONAL")

            baseDomain.copy(
                totalMileagePro = proMileage,
                totalMileagePerso = persoMileage
            )
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
            // 1. Sauvegarder localement dans Room
            val vehicleEntity = vehicle.toEntity(needsSync = true)
            vehicleDao.insertVehicle(vehicleEntity)

            MotiumApplication.logger.i("✅ Vehicle saved to Room Database: ${vehicle.id}", "VehicleRepository")

            // 2. Synchroniser avec Supabase si l'utilisateur est connecté
            try {
                val currentUser = authRepository.getCurrentAuthUser()
                if (currentUser != null) {
                    supabaseVehicleRepository.insertVehicle(vehicle)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicle.id, System.currentTimeMillis())

                    MotiumApplication.logger.i("✅ Vehicle synced to Supabase: ${vehicle.id}", "VehicleRepository")
                } else {
                    MotiumApplication.logger.w("⚠️ Vehicle saved locally only - will sync when user authenticates", "VehicleRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to sync vehicle to Supabase: ${e.message}", "VehicleRepository", e)
                // Ne pas faire échouer l'opération si la sync échoue
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
            val vehicleEntity = vehicle.toEntity(needsSync = true)
            vehicleDao.updateVehicle(vehicleEntity)

            MotiumApplication.logger.i("✅ Vehicle updated in Room Database: ${vehicle.id}, isDefault=${vehicle.isDefault}", "VehicleRepository")

            // 2. Synchroniser avec Supabase si possible
            try {
                val currentUser = authRepository.getCurrentAuthUser()
                if (currentUser != null) {
                    // Si le véhicule est défaut, utiliser setDefaultVehicle pour unset les autres sur Supabase aussi
                    if (vehicle.isDefault) {
                        supabaseVehicleRepository.setDefaultVehicle(vehicle.userId, vehicle.id)
                    }
                    // Puis mettre à jour toutes les autres propriétés
                    supabaseVehicleRepository.updateVehicle(vehicle)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicle.id, System.currentTimeMillis())

                    MotiumApplication.logger.i("✅ Vehicle synced to Supabase: ${vehicle.id}", "VehicleRepository")
                } else {
                    MotiumApplication.logger.w("⚠️ Vehicle updated locally only - will sync when user authenticates", "VehicleRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to sync vehicle to Supabase: ${e.message}", "VehicleRepository", e)
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
                val currentUser = authRepository.getCurrentAuthUser()
                if (currentUser != null) {
                    supabaseVehicleRepository.setDefaultVehicle(userId, vehicleId)

                    // Marquer comme synchronisé
                    vehicleDao.markVehicleAsSynced(vehicleId, System.currentTimeMillis())

                    MotiumApplication.logger.i("✅ Default vehicle synced to Supabase: $vehicleId", "VehicleRepository")
                } else {
                    MotiumApplication.logger.w("⚠️ Default vehicle set locally only - will sync when user authenticates", "VehicleRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to sync default vehicle to Supabase: ${e.message}", "VehicleRepository", e)
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
            // 1. Supprimer localement de Room
            vehicleDao.deleteVehicleById(vehicle.id)

            MotiumApplication.logger.i("✅ Vehicle deleted from Room Database: ${vehicle.id}", "VehicleRepository")

            // 2. Supprimer de Supabase si possible
            try {
                val currentUser = authRepository.getCurrentAuthUser()
                if (currentUser != null) {
                    supabaseVehicleRepository.deleteVehicle(vehicle)
                    MotiumApplication.logger.i("✅ Vehicle deleted from Supabase: ${vehicle.id}", "VehicleRepository")
                } else {
                    MotiumApplication.logger.i("Vehicle deleted locally only: ${vehicle.id}", "VehicleRepository")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to delete vehicle from Supabase: ${e.message}", "VehicleRepository", e)
            }
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
            val currentUser = authRepository.getCurrentAuthUser()
            if (currentUser == null) {
                MotiumApplication.logger.w("⚠️ User not authenticated, cannot sync vehicles", "VehicleRepository")
                return@withContext Result.failure(Exception("User not authenticated"))
            }

            val vehiclesNeedingSync = vehicleDao.getVehiclesNeedingSync(currentUser.id)

            if (vehiclesNeedingSync.isEmpty()) {
                MotiumApplication.logger.i("✓ No vehicles to sync", "VehicleRepository")
                return@withContext Result.success(0)
            }

            MotiumApplication.logger.i("🔄 Syncing ${vehiclesNeedingSync.size} vehicles to Supabase", "VehicleRepository")

            var syncedCount = 0
            vehiclesNeedingSync.forEach { entity ->
                try {
                    val vehicle = entity.toDomainModel()
                    supabaseVehicleRepository.updateVehicle(vehicle)

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
            val currentUser = authRepository.getCurrentAuthUser()
            if (currentUser == null) {
                MotiumApplication.logger.i("User not authenticated, skipping Supabase vehicle sync", "VehicleRepository")
                return@withContext
            }

            MotiumApplication.logger.i("🔄 Fetching vehicles from Supabase for user: ${currentUser.id}", "VehicleRepository")

            val supabaseVehicles = supabaseVehicleRepository.getAllVehiclesForUser(currentUser.id)

            if (supabaseVehicles.isNotEmpty()) {
                // Convertir en entités et sauvegarder dans Room
                val entities = supabaseVehicles.map { it.toEntity(lastSyncedAt = System.currentTimeMillis(), needsSync = false) }
                vehicleDao.insertVehicles(entities)

                MotiumApplication.logger.i("✅ Synced ${supabaseVehicles.size} vehicles from Supabase to Room Database", "VehicleRepository")
            } else {
                MotiumApplication.logger.i("No vehicles found on Supabase for user ${currentUser.id}", "VehicleRepository")
            }
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
}

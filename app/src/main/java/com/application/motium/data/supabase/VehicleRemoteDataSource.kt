package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.VehicleRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

/**
 * REMOTE DATA SOURCE - Direct API access to Supabase for vehicles.
 * For offline-first reads, the VehicleRepository wrapper uses VehicleDao.
 * This class is kept for write operations, sync, and Pro access to linked users' vehicles.
 */
class VehicleRemoteDataSource(private val context: Context) : VehicleRepository {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest
    private val tokenRefreshCoordinator = TokenRefreshCoordinator.getInstance(context)

    @Serializable
    data class SupabaseVehicle(
        val id: String,
        val user_id: String,
        val name: String,
        val type: String,
        val license_plate: String?,
        val power: String?,
        val fuel_type: String?,
        val mileage_rate: Double,
        val is_default: Boolean,
        val total_mileage_perso: Double = 0.0,
        val total_mileage_pro: Double = 0.0,
        val total_mileage_work_home: Double = 0.0,
        val created_at: String,
        val updated_at: String
    )

    @Serializable
    data class DefaultVehicleUpdate(
        val is_default: Boolean,
        val updated_at: String
    )

    @Serializable
    data class VehicleUpdate(
        val name: String,
        val type: String,
        val license_plate: String?,
        val power: String?,
        val fuel_type: String?,
        val mileage_rate: Double,
        val is_default: Boolean,
        val total_mileage_perso: Double,
        val total_mileage_pro: Double,
        val total_mileage_work_home: Double,
        val updated_at: String
    )

    // For inserting new vehicles
    @Serializable
    data class VehicleInsert(
        val id: String,
        val user_id: String,
        val name: String,
        val type: String,
        val license_plate: String?,
        val power: String?,
        val fuel_type: String?,
        val mileage_rate: Double,
        val is_default: Boolean,
        val total_mileage_perso: Double = 0.0,
        val total_mileage_pro: Double = 0.0,
        val total_mileage_work_home: Double = 0.0,
        val created_at: String,
        val updated_at: String
    )

    // To query trips for mileage calculation
    @Serializable
    data class TripDistance(
        val distance_km: Double
    )

    /**
     * Exécute une requête Supabase avec gestion automatique du refresh token.
     * Si le JWT est expiré (PGRST303), rafraîchit le token et réessaie.
     */
    private suspend fun <T> executeWithTokenRefresh(
        operationName: String,
        operation: suspend () -> T
    ): T {
        return try {
            operation()
        } catch (e: PostgrestRestException) {
            // Vérifier si c'est une erreur JWT expired (code PGRST303)
            if (e.message?.contains("JWT expired") == true || e.message?.contains("PGRST303") == true) {
                MotiumApplication.logger.w(
                    "🔄 JWT expired during $operationName - refreshing token and retrying",
                    "VehicleRemoteDataSource"
                )

                // Rafraîchir le token via le client Auth directement
                val refreshed = try {
                    val auth = client.auth
                    val currentSession = auth.currentSessionOrNull()
                    val refreshToken = currentSession?.refreshToken

                    if (refreshToken != null) {
                        MotiumApplication.logger.d(
                            "📤 Calling auth.refreshSession() with refresh token",
                            "VehicleRemoteDataSource"
                        )
                        auth.refreshSession(refreshToken)

                        // Vérifier que le nouveau token est valide
                        val newSession = auth.currentSessionOrNull()
                        if (newSession != null && newSession.expiresIn > 0) {
                            MotiumApplication.logger.i(
                                "✅ Token actually refreshed - new token expires in ${newSession.expiresIn}s",
                                "VehicleRemoteDataSource"
                            )
                            true
                        } else {
                            MotiumApplication.logger.e(
                                "❌ Token refresh returned but session is still invalid",
                                "VehicleRemoteDataSource"
                            )
                            false
                        }
                    } else {
                        MotiumApplication.logger.e(
                            "❌ No refresh token available - user needs to re-login",
                            "VehicleRemoteDataSource"
                        )
                        false
                    }
                } catch (refreshError: Exception) {
                    MotiumApplication.logger.e(
                        "❌ Token refresh threw exception: ${refreshError.message}",
                        "VehicleRemoteDataSource",
                        refreshError
                    )
                    false
                }

                if (refreshed) {
                    MotiumApplication.logger.i(
                        "🔁 Retrying $operationName with new token",
                        "VehicleRemoteDataSource"
                    )
                    // Petit délai pour laisser le token se propager
                    kotlinx.coroutines.delay(100)
                    // Réessayer l'opération
                    operation()
                } else {
                    MotiumApplication.logger.e(
                        "❌ Token refresh failed - cannot retry $operationName. User may need to re-login.",
                        "VehicleRemoteDataSource"
                    )
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private suspend fun getAnnualMileageForVehicle(vehicleId: String, tripType: String): Double {
        return try {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.DAY_OF_YEAR, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val startOfYear = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(calendar.time)

            val trips = executeWithTokenRefresh("getAnnualMileageForVehicle") {
                postgres.from("trips").select {
                    filter {
                        eq("vehicle_id", vehicleId)
                        eq("is_validated", true)
                        eq("type", tripType)
                        gte("start_time", startOfYear)
                    }
                }.decodeList<TripDistance>()
            }

            // Retourne le kilométrage total en kilomètres (distance_km est déjà en km)
            trips.sumOf { it.distance_km }
        } catch (e: java.util.concurrent.CancellationException) {
            // Normal cancellation (e.g., user navigated away) - don't log as error
            0.0
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error calculating annual mileage for vehicle $vehicleId: ${e.message}", "VehicleRemoteDataSource", e)
            0.0
        }
    }


    override fun getVehiclesForUser(userId: String): Flow<List<Vehicle>> {
        return flowOf(emptyList())
    }

    override suspend fun getVehicleById(vehicleId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Getting vehicle by ID: $vehicleId", "VehicleRemoteDataSource")

            val response = executeWithTokenRefresh("getVehicleById") {
                postgres.from("vehicles")
                    .select {
                        filter {
                            eq("id", vehicleId)
                        }
                    }.decodeSingleOrNull<SupabaseVehicle>()
            }

            response?.toDomainVehicle()
        } catch (e: java.util.concurrent.CancellationException) {
            // Normal cancellation (e.g., user navigated away)
            null
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting vehicle by ID: ${e.message}", "VehicleRemoteDataSource", e)
            null
        }
    }

    override suspend fun getDefaultVehicle(userId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Getting default vehicle for user: $userId", "VehicleRemoteDataSource")

            val response = executeWithTokenRefresh("getDefaultVehicle") {
                postgres.from("vehicles")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("is_default", true)
                        }
                    }.decodeSingleOrNull<SupabaseVehicle>()
            }

            response?.toDomainVehicle()
        } catch (e: java.util.concurrent.CancellationException) {
            // Normal cancellation (e.g., user navigated away)
            null
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting default vehicle: ${e.message}", "VehicleRemoteDataSource", e)
            null
        }
    }

    suspend fun getAllVehiclesForUser(userId: String): List<Vehicle> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Getting all vehicles for user: $userId", "VehicleRemoteDataSource")

            val response = executeWithTokenRefresh("getAllVehiclesForUser") {
                postgres.from("vehicles")
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }.decodeList<SupabaseVehicle>()
            }

            response.map { it.toDomainVehicle() }
        } catch (e: java.util.concurrent.CancellationException) {
            // Normal cancellation (e.g., user navigated away) - don't log as error
            MotiumApplication.logger.d("Vehicle fetch cancelled (user navigated away)", "VehicleRemoteDataSource")
            throw e // Rethrow to properly propagate cancellation
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting vehicles for user: ${e.message}", "VehicleRemoteDataSource", e)
            emptyList()
        }
    }

    override suspend fun insertVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Inserting vehicle: ${vehicle.id}", "VehicleRemoteDataSource")

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            // Create a serializable object to insert
            val vehicleInsert = VehicleInsert(
                id = vehicle.id,
                user_id = vehicle.userId,
                name = vehicle.name,
                type = vehicle.type.name,
                license_plate = vehicle.licensePlate,
                power = vehicle.power?.cv,
                fuel_type = vehicle.fuelType?.name,
                mileage_rate = vehicle.mileageRate,
                is_default = vehicle.isDefault,
                total_mileage_perso = vehicle.totalMileagePerso,
                total_mileage_pro = vehicle.totalMileagePro,
                total_mileage_work_home = vehicle.totalMileageWorkHome,
                created_at = now,
                updated_at = now
            )

            postgres.from("vehicles").insert(vehicleInsert)

            MotiumApplication.logger.i("Vehicle inserted successfully: ${vehicle.id}", "VehicleRemoteDataSource")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inserting vehicle: ${e.message}", "VehicleRemoteDataSource", e)
            throw e
        }
    }

    override suspend fun updateVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating vehicle: ${vehicle.id} (mileage: perso=${vehicle.totalMileagePerso}, pro=${vehicle.totalMileagePro}, workHome=${vehicle.totalMileageWorkHome})", "VehicleRemoteDataSource")

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            val updateData = VehicleUpdate(
                name = vehicle.name,
                type = vehicle.type.name,
                license_plate = vehicle.licensePlate,
                power = vehicle.power?.cv,
                fuel_type = vehicle.fuelType?.name,
                mileage_rate = vehicle.mileageRate,
                is_default = vehicle.isDefault,
                total_mileage_perso = vehicle.totalMileagePerso,
                total_mileage_pro = vehicle.totalMileagePro,
                total_mileage_work_home = vehicle.totalMileageWorkHome,
                updated_at = now
            )

            postgres.from("vehicles")
                .update(updateData) {
                    filter {
                        eq("id", vehicle.id)
                        eq("user_id", vehicle.userId)
                    }
                }

            MotiumApplication.logger.i("Vehicle updated successfully: ${vehicle.id}", "VehicleRemoteDataSource")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating vehicle: ${e.message}", "VehicleRemoteDataSource", e)
            throw e
        }
    }

    override suspend fun setDefaultVehicle(userId: String, vehicleId: String) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Setting default vehicle: $vehicleId for user: $userId", "VehicleRemoteDataSource")

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            // First, remove default status from all vehicles for this user
            postgres.from("vehicles")
                .update(
                    DefaultVehicleUpdate(
                        is_default = false,
                        updated_at = now
                    )
                ) {
                    filter {
                        eq("user_id", userId)
                    }
                }

            // Then, set the specified vehicle as default
            postgres.from("vehicles")
                .update(
                    DefaultVehicleUpdate(
                        is_default = true,
                        updated_at = now
                    )
                ) {
                    filter {
                        eq("id", vehicleId)
                        eq("user_id", userId)
                    }
                }

            MotiumApplication.logger.i("Default vehicle set successfully: $vehicleId", "VehicleRemoteDataSource")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error setting default vehicle: ${e.message}", "VehicleRemoteDataSource", e)
            throw e
        }
    }

    // This is now obsolete, but kept for interface compatibility. It does nothing.
    override suspend fun updateMileage(vehicleId: String, additionalKm: Double) {
        // Deprecated: Mileage is now calculated on-the-fly.
    }


    override suspend fun deleteVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting vehicle: ${vehicle.id}", "VehicleRemoteDataSource")

            postgres.from("vehicles")
                .delete {
                    filter {
                        eq("id", vehicle.id)
                        eq("user_id", vehicle.userId)
                    }
                }

            MotiumApplication.logger.i("Vehicle deleted successfully: ${vehicle.id}", "VehicleRemoteDataSource")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting vehicle: ${e.message}", "VehicleRemoteDataSource", e)
            throw e
        }
    }

    override suspend fun deleteAllVehiclesForUser(userId: String) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting all vehicles for user: $userId", "VehicleRemoteDataSource")

            postgres.from("vehicles")
                .delete {
                    filter {
                        eq("user_id", userId)
                    }
                }

            MotiumApplication.logger.i("All vehicles deleted successfully for user: $userId", "VehicleRemoteDataSource")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting all vehicles for user: ${e.message}", "VehicleRemoteDataSource", e)
            throw e
        }
    }

    private fun SupabaseVehicle.toDomainVehicle(): Vehicle {
        // Mileage is now stored directly in Supabase, no need to calculate
        return Vehicle(
            id = id,
            userId = user_id,
            name = name,
            type = VehicleType.valueOf(type),
            licensePlate = license_plate,
            power = power?.let { cv -> VehiclePower.values().find { it.cv == cv } },
            fuelType = fuel_type?.let { FuelType.valueOf(it) },
            mileageRate = mileage_rate,
            isDefault = is_default,
            totalMileagePerso = total_mileage_perso,
            totalMileagePro = total_mileage_pro,
            totalMileageWorkHome = total_mileage_work_home,
            createdAt = kotlinx.datetime.Instant.parse(created_at),
            updatedAt = kotlinx.datetime.Instant.parse(updated_at)
        )
    }

    companion object {
        @Volatile
        private var instance: VehicleRemoteDataSource? = null

        fun getInstance(context: Context): VehicleRemoteDataSource {
            return instance ?: synchronized(this) {
                instance ?: VehicleRemoteDataSource(context.applicationContext).also { instance = it }
            }
        }
    }
}
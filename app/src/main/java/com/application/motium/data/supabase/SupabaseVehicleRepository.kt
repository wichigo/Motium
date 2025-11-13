package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.VehicleRepository
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

class SupabaseVehicleRepository(private val context: Context) : VehicleRepository {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest

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
        val total_mileage_perso: Double,
        val total_mileage_pro: Double,
        val created_at: String,
        val updated_at: String
    )

    @Serializable
    data class MileagePersonalUpdate(
        val total_mileage_perso: Double,
        val updated_at: String
    )

    @Serializable
    data class MileageProfessionalUpdate(
        val total_mileage_pro: Double,
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
        val updated_at: String
    )

    override fun getVehiclesForUser(userId: String): Flow<List<Vehicle>> {
        // For now, return empty flow - will implement real-time subscription later
        return flowOf(emptyList())
    }

    override suspend fun getVehicleById(vehicleId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Getting vehicle by ID: $vehicleId", "SupabaseVehicleRepository")

            val response = postgres.from("vehicles")
                .select {
                    filter {
                        eq("id", vehicleId)
                    }
                }.decodeSingleOrNull<SupabaseVehicle>()

            response?.toDomainVehicle()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting vehicle by ID: ${e.message}", "SupabaseVehicleRepository", e)
            null
        }
    }

    override suspend fun getDefaultVehicle(userId: String): Vehicle? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Getting default vehicle for user: $userId", "SupabaseVehicleRepository")

            val response = postgres.from("vehicles")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("is_default", true)
                    }
                }.decodeSingleOrNull<SupabaseVehicle>()

            response?.toDomainVehicle()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting default vehicle: ${e.message}", "SupabaseVehicleRepository", e)
            null
        }
    }

    suspend fun getAllVehiclesForUser(userId: String): List<Vehicle> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Getting all vehicles for user: $userId", "SupabaseVehicleRepository")

            val response = postgres.from("vehicles")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeList<SupabaseVehicle>()

            response.map { it.toDomainVehicle() }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error getting vehicles for user: ${e.message}", "SupabaseVehicleRepository", e)
            emptyList()
        }
    }

    override suspend fun insertVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Inserting vehicle: ${vehicle.id}", "SupabaseVehicleRepository")

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            val supabaseVehicle = SupabaseVehicle(
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
                created_at = now,
                updated_at = now
            )

            postgres.from("vehicles").insert(supabaseVehicle)

            MotiumApplication.logger.i("Vehicle inserted successfully: ${vehicle.id}", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error inserting vehicle: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    override suspend fun updateVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating vehicle: ${vehicle.id}", "SupabaseVehicleRepository")

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
                updated_at = now
            )

            postgres.from("vehicles")
                .update(updateData) {
                    filter {
                        eq("id", vehicle.id)
                        eq("user_id", vehicle.userId)
                    }
                }

            MotiumApplication.logger.i("Vehicle updated successfully: ${vehicle.id}", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating vehicle: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    override suspend fun setDefaultVehicle(userId: String, vehicleId: String) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Setting default vehicle: $vehicleId for user: $userId", "SupabaseVehicleRepository")

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

            MotiumApplication.logger.i("Default vehicle set successfully: $vehicleId", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error setting default vehicle: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    override suspend fun updateMileage(vehicleId: String, additionalKm: Double) = withContext(Dispatchers.IO) {
        // Legacy method - defaults to personal mileage
        updateMileagePersonal(vehicleId, additionalKm)
    }

    suspend fun updateMileagePersonal(vehicleId: String, additionalKm: Double) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating personal mileage for vehicle: $vehicleId, additional: $additionalKm km", "SupabaseVehicleRepository")

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            // Get current vehicle
            val currentVehicle = postgres.from("vehicles")
                .select {
                    filter {
                        eq("id", vehicleId)
                    }
                }.decodeSingle<SupabaseVehicle>()

            val newMileagePerso = currentVehicle.total_mileage_perso + additionalKm

            // Update with serializable class to avoid serialization issues
            postgres.from("vehicles")
                .update(
                    MileagePersonalUpdate(
                        total_mileage_perso = newMileagePerso,
                        updated_at = now
                    )
                ) {
                    filter {
                        eq("id", vehicleId)
                    }
                }

            MotiumApplication.logger.i("Personal mileage updated successfully for vehicle: $vehicleId, new perso total: $newMileagePerso km", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating personal mileage: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    suspend fun updateMileageProfessional(vehicleId: String, additionalKm: Double) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Updating professional mileage for vehicle: $vehicleId, additional: $additionalKm km", "SupabaseVehicleRepository")

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())

            // Get current vehicle
            val currentVehicle = postgres.from("vehicles")
                .select {
                    filter {
                        eq("id", vehicleId)
                    }
                }.decodeSingle<SupabaseVehicle>()

            val newMileagePro = currentVehicle.total_mileage_pro + additionalKm

            // Update with serializable class to avoid serialization issues
            postgres.from("vehicles")
                .update(
                    MileageProfessionalUpdate(
                        total_mileage_pro = newMileagePro,
                        updated_at = now
                    )
                ) {
                    filter {
                        eq("id", vehicleId)
                    }
                }

            MotiumApplication.logger.i("Professional mileage updated successfully for vehicle: $vehicleId, new pro total: $newMileagePro km", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error updating professional mileage: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    override suspend fun deleteVehicle(vehicle: Vehicle) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting vehicle: ${vehicle.id}", "SupabaseVehicleRepository")

            postgres.from("vehicles")
                .delete {
                    filter {
                        eq("id", vehicle.id)
                        eq("user_id", vehicle.userId)
                    }
                }

            MotiumApplication.logger.i("Vehicle deleted successfully: ${vehicle.id}", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting vehicle: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    override suspend fun deleteAllVehiclesForUser(userId: String) = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("Deleting all vehicles for user: $userId", "SupabaseVehicleRepository")

            postgres.from("vehicles")
                .delete {
                    filter {
                        eq("user_id", userId)
                    }
                }

            MotiumApplication.logger.i("All vehicles deleted successfully for user: $userId", "SupabaseVehicleRepository")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error deleting all vehicles for user: ${e.message}", "SupabaseVehicleRepository", e)
            throw e
        }
    }

    private fun SupabaseVehicle.toDomainVehicle(): Vehicle {
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
            createdAt = kotlinx.datetime.Instant.parse(created_at),
            updatedAt = kotlinx.datetime.Instant.parse(updated_at)
        )
    }

    companion object {
        @Volatile
        private var instance: SupabaseVehicleRepository? = null

        fun getInstance(context: Context): SupabaseVehicleRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseVehicleRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
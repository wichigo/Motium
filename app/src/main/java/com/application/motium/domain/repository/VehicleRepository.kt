package com.application.motium.domain.repository

import com.application.motium.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow

interface VehicleRepository {
    fun getVehiclesForUser(userId: String): Flow<List<Vehicle>>
    suspend fun getVehicleById(vehicleId: String): Vehicle?
    suspend fun getDefaultVehicle(userId: String): Vehicle?
    suspend fun insertVehicle(vehicle: Vehicle)
    suspend fun updateVehicle(vehicle: Vehicle)
    suspend fun setDefaultVehicle(userId: String, vehicleId: String)
    suspend fun updateMileage(vehicleId: String, additionalKm: Double)
    suspend fun deleteVehicle(vehicle: Vehicle)
    suspend fun deleteAllVehiclesForUser(userId: String)
}
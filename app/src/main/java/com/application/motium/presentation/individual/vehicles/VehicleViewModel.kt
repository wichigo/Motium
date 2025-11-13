package com.application.motium.presentation.individual.vehicles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.data.supabase.SupabaseVehicleRepository
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.VehicleRepository
import com.application.motium.MotiumApplication
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import java.util.UUID

class VehicleViewModel(
    private val context: Context,
    private val vehicleRepository: VehicleRepository = SupabaseVehicleRepository.getInstance(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleUiState())
    val uiState: StateFlow<VehicleUiState> = _uiState.asStateFlow()

    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

    fun loadVehicles(userId: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val vehicleList = (vehicleRepository as SupabaseVehicleRepository).getAllVehiclesForUser(userId)
                _vehicles.value = vehicleList

                MotiumApplication.logger.i("Loaded ${vehicleList.size} vehicles for user: $userId", "VehicleViewModel")
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading vehicles: ${e.message}", "VehicleViewModel", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load vehicles"
                )
            }
        }
    }

    fun addVehicle(
        userId: String,
        name: String,
        type: VehicleType,
        licensePlate: String?,
        power: VehiclePower?,
        fuelType: FuelType?,
        mileageRate: Double,
        isDefault: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val vehicle = Vehicle(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    name = name,
                    type = type,
                    licensePlate = licensePlate,
                    power = power,
                    fuelType = fuelType,
                    mileageRate = mileageRate,
                    isDefault = isDefault,
                    totalMileagePerso = 0.0,
                    totalMileagePro = 0.0,
                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                )

                vehicleRepository.insertVehicle(vehicle)

                MotiumApplication.logger.i("Vehicle added successfully: ${vehicle.id}", "VehicleViewModel")

                // Reload vehicles to update the list
                loadVehicles(userId)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showAddDialog = false,
                    successMessage = "Véhicule ajouté avec succès"
                )

                // Clear success message after 3 seconds
                kotlinx.coroutines.delay(3000)
                _uiState.value = _uiState.value.copy(successMessage = null)

            } catch (e: Exception) {
                MotiumApplication.logger.e("Error adding vehicle: ${e.message}", "VehicleViewModel", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to add vehicle"
                )
            }
        }
    }

    fun updateVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val updatedVehicle = vehicle.copy(updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()))
                vehicleRepository.updateVehicle(updatedVehicle)

                MotiumApplication.logger.i("Vehicle updated successfully: ${vehicle.id}", "VehicleViewModel")

                // Reload vehicles to update the list
                loadVehicles(vehicle.userId)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = "Véhicule modifié avec succès"
                )

                // Clear success message after 3 seconds
                kotlinx.coroutines.delay(3000)
                _uiState.value = _uiState.value.copy(successMessage = null)

            } catch (e: Exception) {
                MotiumApplication.logger.e("Error updating vehicle: ${e.message}", "VehicleViewModel", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to update vehicle"
                )
            }
        }
    }

    fun deleteVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                vehicleRepository.deleteVehicle(vehicle)

                MotiumApplication.logger.i("Vehicle deleted successfully: ${vehicle.id}", "VehicleViewModel")

                // Reload vehicles to update the list
                loadVehicles(vehicle.userId)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = "Véhicule supprimé avec succès"
                )

                // Clear success message after 3 seconds
                kotlinx.coroutines.delay(3000)
                _uiState.value = _uiState.value.copy(successMessage = null)

            } catch (e: Exception) {
                MotiumApplication.logger.e("Error deleting vehicle: ${e.message}", "VehicleViewModel", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to delete vehicle"
                )
            }
        }
    }

    fun setDefaultVehicle(userId: String, vehicleId: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                vehicleRepository.setDefaultVehicle(userId, vehicleId)

                MotiumApplication.logger.i("Default vehicle set successfully: $vehicleId", "VehicleViewModel")

                // Reload vehicles to update the list
                loadVehicles(userId)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = "Véhicule par défaut défini"
                )

                // Clear success message after 3 seconds
                kotlinx.coroutines.delay(3000)
                _uiState.value = _uiState.value.copy(successMessage = null)

            } catch (e: Exception) {
                MotiumApplication.logger.e("Error setting default vehicle: ${e.message}", "VehicleViewModel", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to set default vehicle"
                )
            }
        }
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }
}

data class VehicleUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val showAddDialog: Boolean = false
)
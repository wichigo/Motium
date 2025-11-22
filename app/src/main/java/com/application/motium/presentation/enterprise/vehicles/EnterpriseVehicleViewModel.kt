package com.application.motium.presentation.enterprise.vehicles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseVehicleRepository
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.AuthRepository
import com.application.motium.domain.repository.VehicleRepository
import com.application.motium.MotiumApplication
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import java.util.UUID

class EnterpriseVehicleViewModel(
    private val context: Context,
    private val vehicleRepository: VehicleRepository = SupabaseVehicleRepository.getInstance(context),
    private val authRepository: AuthRepository = SupabaseAuthRepository.getInstance(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleUiState())
    val uiState: StateFlow<VehicleUiState> = _uiState.asStateFlow()

    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                val newUserId = authState.user?.id
                if (_userId.value != newUserId) {
                    _userId.value = newUserId
                    if (newUserId != null) {
                        loadVehicles()
                    } else {
                        // User is logged out, clear the vehicle list
                        _vehicles.value = emptyList()
                        MotiumApplication.logger.i("User logged out, vehicles cleared.", "VehicleViewModel")
                    }
                }
            }
        }
    }

    fun loadVehicles() {
        val userId = _userId.value
        if (userId == null) {
            MotiumApplication.logger.w("loadVehicles called but user is not authenticated.", "VehicleViewModel")
            // Optionally, you can post an error to the UI state
            // _uiState.value = _uiState.value.copy(error = "User not authenticated")
            return
        }

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
        name: String,
        type: VehicleType,
        licensePlate: String?,
        power: VehiclePower?,
        fuelType: FuelType?,
        mileageRate: Double,
        isDefault: Boolean = false
    ) {
        val userId = _userId.value
        if (userId == null) {
            _uiState.value = _uiState.value.copy(error = "User not authenticated to add vehicle")
            return
        }

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
                loadVehicles()

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
                loadVehicles()

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
                loadVehicles()

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

    fun setDefaultVehicle(vehicleId: String) {
        val userId = _userId.value
        if (userId == null) {
            _uiState.value = _uiState.value.copy(error = "User not authenticated to set default vehicle")
            return
        }
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                vehicleRepository.setDefaultVehicle(userId, vehicleId)

                MotiumApplication.logger.i("Default vehicle set successfully: $vehicleId", "VehicleViewModel")

                // Reload vehicles to update the list
                loadVehicles()

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

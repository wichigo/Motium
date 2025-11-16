package com.application.motium.presentation.individual.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseVehicleRepository
import com.application.motium.domain.model.Vehicle
import com.application.motium.domain.repository.AuthRepository
import com.application.motium.utils.ExportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class ExportFilters(
    val startDate: Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }.timeInMillis,
    val endDate: Long = System.currentTimeMillis(),
    val vehicleId: String? = null,
    val tripType: String? = null,
    val expenseMode: String = "trips_only", // "trips_only", "trips_with_expenses", "expenses_only"
    val includePhotos: Boolean = false
)

data class ExportStats(
    val totalTrips: Int = 0,
    val totalDistance: Double = 0.0,
    val totalIndemnities: Double = 0.0
) {
    fun getFormattedDistance(): String = String.format("%.1f km", totalDistance / 1000)
    fun getFormattedIndemnities(): String = String.format("€%.2f", totalIndemnities)
}

class ExportViewModel(private val context: Context) : ViewModel() {

    private val tripRepository = TripRepository.getInstance(context)
    private val vehicleRepository = SupabaseVehicleRepository.getInstance(context)
    private val authRepository: AuthRepository = SupabaseAuthRepository.getInstance(context)
    private val exportManager = ExportManager(context)

    private val _filters = MutableStateFlow(ExportFilters())
    val filters: StateFlow<ExportFilters> = _filters.asStateFlow()

    private val _filteredTrips = MutableStateFlow<List<Trip>>(emptyList())
    val filteredTrips: StateFlow<List<Trip>> = _filteredTrips.asStateFlow()

    private val _stats = MutableStateFlow(ExportStats())
    val stats: StateFlow<ExportStats> = _stats.asStateFlow()

    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                if (authState.isAuthenticated && authState.user != null) {
                    // User is authenticated, now load data
                    loadVehicles(authState.user.id)
                    loadTrips()
                } else {
                    // User logged out, clear data
                    _vehicles.value = emptyList()
                    _filteredTrips.value = emptyList()
                    _stats.value = ExportStats()
                }
            }
        }
    }

    private fun loadVehicles(userId: String) {
        viewModelScope.launch {
            try {
                val vehicleList = vehicleRepository.getAllVehiclesForUser(userId)
                _vehicles.value = vehicleList
            } catch (e: Exception) {
                // Handle error silently, e.g., log it
            }
        }
    }

    private fun loadTrips() {
        viewModelScope.launch {
            val allTrips = tripRepository.getAllTrips()
            applyFilters(allTrips)
        }
    }

    fun setStartDate(timestamp: Long) {
        _filters.value = _filters.value.copy(startDate = timestamp)
        loadTrips()
    }

    fun setEndDate(timestamp: Long) {
        _filters.value = _filters.value.copy(endDate = timestamp)
        loadTrips()
    }

    fun setVehicleFilter(vehicleId: String?) {
        _filters.value = _filters.value.copy(vehicleId = vehicleId)
        loadTrips()
    }

    fun setTripTypeFilter(tripType: String?) {
        _filters.value = _filters.value.copy(tripType = tripType)
        loadTrips()
    }

    fun setExpenseModeFilter(expenseMode: String) {
        _filters.value = _filters.value.copy(expenseMode = expenseMode)
        loadTrips()
    }

    fun setIncludePhotos(includePhotos: Boolean) {
        _filters.value = _filters.value.copy(includePhotos = includePhotos)
    }

    private fun applyFilters(trips: List<Trip>) {
        val currentFilters = _filters.value

        var filtered = trips.filter { trip ->
            trip.endTime != null &&
            trip.startTime >= currentFilters.startDate &&
            trip.startTime <= currentFilters.endDate
        }

        // Filter by vehicle
        if (currentFilters.vehicleId != null) {
            filtered = filtered.filter { it.vehicleId == currentFilters.vehicleId }
        }

        // Filter by trip type (if implemented)
        if (currentFilters.tripType != null && currentFilters.tripType != "All Types") {
            // TODO: Filter by trip type when implemented
        }

        _filteredTrips.value = filtered
        calculateStats(filtered)
    }

    private fun calculateStats(trips: List<Trip>) {
        val totalTrips = trips.size
        val totalDistance = trips.sumOf { it.totalDistance }

        // Calculate indemnities based on distance and mileage rate
        // Using default rate of 0.50€/km for professional trips
        val totalIndemnities = trips.sumOf { trip ->
            val distanceKm = trip.totalDistance / 1000.0
            distanceKm * 0.50 // Default mileage rate
        }

        _stats.value = ExportStats(
            totalTrips = totalTrips,
            totalDistance = totalDistance,
            totalIndemnities = totalIndemnities
        )
    }

    fun formatDate(timestamp: Long, pattern: String = "MMMM dd, yyyy"): String {
        return SimpleDateFormat(pattern, Locale.ENGLISH).format(Date(timestamp))
    }

    fun exportToCSV(onSuccess: (File) -> Unit, onError: (String) -> Unit) {
        val currentFilters = _filters.value
        exportManager.exportToCSV(
            trips = _filteredTrips.value,
            startDate = currentFilters.startDate,
            endDate = currentFilters.endDate,
            expenseMode = currentFilters.expenseMode,
            includePhotos = currentFilters.includePhotos,
            onSuccess = { file ->
                onSuccess(file)
                exportManager.shareFile(file)
            },
            onError = onError
        )
    }

    fun exportToPDF(onSuccess: (File) -> Unit, onError: (String) -> Unit) {
        val currentFilters = _filters.value
        exportManager.exportToPDF(
            trips = _filteredTrips.value,
            startDate = currentFilters.startDate,
            endDate = currentFilters.endDate,
            expenseMode = currentFilters.expenseMode,
            includePhotos = currentFilters.includePhotos,
            onSuccess = { file ->
                onSuccess(file)
                exportManager.shareFile(file)
            },
            onError = onError
        )
    }

    fun exportToExcel(onSuccess: (File) -> Unit, onError: (String) -> Unit) {
        val currentFilters = _filters.value
        exportManager.exportToExcel(
            trips = _filteredTrips.value,
            startDate = currentFilters.startDate,
            endDate = currentFilters.endDate,
            expenseMode = currentFilters.expenseMode,
            includePhotos = currentFilters.includePhotos,
            onSuccess = { file ->
                onSuccess(file)
                exportManager.shareFile(file)
            },
            onError = onError
        )
    }
}

package com.application.motium.presentation.individual.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.domain.model.Vehicle
import com.application.motium.domain.repository.AuthRepository
import com.application.motium.utils.ExportManager
import com.application.motium.utils.TripCalculator
import com.application.motium.domain.model.TripType
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
    private val vehicleRepository = VehicleRepository.getInstance(context)  // Room cache
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

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                if (authState.isAuthenticated && authState.user != null) {
                    _isLoading.value = true
                    val userId = authState.user.id
                    try {
                        val vehicleList = vehicleRepository.getAllVehiclesForUser(userId)
                        _vehicles.value = vehicleList

                        val allTrips = tripRepository.getAllTrips()
                        applyFilters(allTrips)
                    } finally {
                        _isLoading.value = false
                    }
                } else {
                    // User logged out, clear data
                    _vehicles.value = emptyList()
                    _filteredTrips.value = emptyList()
                    _stats.value = ExportStats()
                    _isLoading.value = false
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
            trip.isValidated &&  // Seulement les trajets validés
            trip.startTime >= currentFilters.startDate &&
            trip.startTime <= currentFilters.endDate
        }

        // Filter by vehicle
        if (currentFilters.vehicleId != null) {
            filtered = filtered.filter { it.vehicleId == currentFilters.vehicleId }
        }

        // Filter by trip type
        if (currentFilters.tripType != null && currentFilters.tripType != "All Types") {
            val filterType = currentFilters.tripType.uppercase()
            filtered = filtered.filter { it.tripType?.uppercase() == filterType }
        }

        _filteredTrips.value = filtered
        calculateStats(filtered)
    }

    private fun calculateStats(trips: List<Trip>) {
        val totalTrips = trips.size
        val totalDistance = trips.sumOf { it.totalDistance }

        // Créer un map des véhicules pour le calcul des indemnités
        val vehiclesMap = _vehicles.value.associateBy { it.id }

        // Calculer les indemnités en utilisant le barème progressif par véhicule
        val totalIndemnities = trips.sumOf { trip ->
            val distanceKm = trip.totalDistance / 1000.0
            val vehicleId = trip.vehicleId

            if (vehicleId.isNullOrBlank()) {
                // Pas de véhicule, utiliser le taux par défaut
                distanceKm * 0.50
            } else {
                val vehicle = vehiclesMap[vehicleId]
                if (vehicle == null) {
                    distanceKm * 0.50
                } else {
                    // Utiliser le calcul progressif avec le barème fiscal
                    val tripType = when (trip.tripType) {
                        "PROFESSIONAL" -> TripType.PROFESSIONAL
                        "PERSONAL" -> TripType.PERSONAL
                        else -> TripType.PROFESSIONAL
                    }
                    TripCalculator.calculateMileageCost(distanceKm, vehicle, tripType)
                }
            }
        }

        _stats.value = ExportStats(
            totalTrips = totalTrips,
            totalDistance = totalDistance,
            totalIndemnities = totalIndemnities
        )
    }

    fun formatDate(timestamp: Long, pattern: String = "MMMM dd, yyyy"): String {
        return SimpleDateFormat(pattern, Locale.FRENCH).format(Date(timestamp))
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

    // ================================================================================
    // QUICK EXPORT SHORTCUTS
    // ================================================================================

    /**
     * Quick export presets for common date ranges
     */
    enum class QuickExportPeriod {
        TODAY,      // Aujourd'hui
        THIS_WEEK,  // Semaine en cours (lundi -> dimanche)
        THIS_MONTH, // Mois en cours
        THIS_YEAR   // Année en cours
    }

    /**
     * Get start and end dates for a quick export period
     */
    private fun getDateRangeForPeriod(period: QuickExportPeriod): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val endDate: Long
        val startDate: Long

        when (period) {
            QuickExportPeriod.TODAY -> {
                // Today: start of day to now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                startDate = calendar.timeInMillis
                endDate = System.currentTimeMillis()
            }
            QuickExportPeriod.THIS_WEEK -> {
                // This week: Monday to now (European week starts on Monday)
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                // Calculate days since Monday (Sunday = 1, Monday = 2, ..., Saturday = 7)
                val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
                calendar.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                startDate = calendar.timeInMillis
                endDate = System.currentTimeMillis()
            }
            QuickExportPeriod.THIS_MONTH -> {
                // This month: 1st to today
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                startDate = calendar.timeInMillis
                endDate = System.currentTimeMillis()
            }
            QuickExportPeriod.THIS_YEAR -> {
                // This year: January 1st to today
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                startDate = calendar.timeInMillis
                endDate = System.currentTimeMillis()
            }
        }
        return Pair(startDate, endDate)
    }

    /**
     * Apply a quick period preset - sets date range and enables all options
     * User then uses existing export buttons at the bottom
     */
    fun applyQuickPeriod(period: QuickExportPeriod) {
        val (startDate, endDate) = getDateRangeForPeriod(period)
        _filters.value = _filters.value.copy(
            startDate = startDate,
            endDate = endDate,
            expenseMode = "trips_with_expenses", // Include expenses
            includePhotos = true // Include photos
        )
        loadTrips() // Reload trips with new filters
    }

    /**
     * Quick export with all options enabled:
     * - All trip types (pro + perso)
     * - Trips with expenses
     * - Include photos
     * - Only trips with indemnities (reimbursementAmount > 0 or validated)
     */
    fun quickExportPDF(
        period: QuickExportPeriod,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val (startDate, endDate) = getDateRangeForPeriod(period)

            // Load all trips
            val allTrips = tripRepository.getAllTrips()

            // Filter trips for the period with indemnities only
            val tripsWithIndemnities = allTrips.filter { trip ->
                trip.endTime != null &&
                trip.isValidated &&
                trip.startTime >= startDate &&
                trip.startTime <= endDate &&
                // Only trips with indemnities: either has reimbursementAmount or is validated professional
                (trip.reimbursementAmount != null && trip.reimbursementAmount > 0) ||
                (trip.tripType == "PROFESSIONAL" && trip.isValidated)
            }

            if (tripsWithIndemnities.isEmpty()) {
                onError("Aucun trajet avec indemnités pour cette période")
                return@launch
            }

            // Export with all options
            exportManager.exportToPDF(
                trips = tripsWithIndemnities,
                startDate = startDate,
                endDate = endDate,
                expenseMode = "trips_with_expenses", // Include expenses
                includePhotos = true, // Include photos
                onSuccess = { file ->
                    onSuccess(file)
                    exportManager.shareFile(file)
                },
                onError = onError
            )
        }
    }

    /**
     * Quick export to CSV
     */
    fun quickExportCSV(
        period: QuickExportPeriod,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val (startDate, endDate) = getDateRangeForPeriod(period)

            val allTrips = tripRepository.getAllTrips()

            val tripsWithIndemnities = allTrips.filter { trip ->
                trip.endTime != null &&
                trip.isValidated &&
                trip.startTime >= startDate &&
                trip.startTime <= endDate &&
                (trip.reimbursementAmount != null && trip.reimbursementAmount > 0) ||
                (trip.tripType == "PROFESSIONAL" && trip.isValidated)
            }

            if (tripsWithIndemnities.isEmpty()) {
                onError("Aucun trajet avec indemnités pour cette période")
                return@launch
            }

            exportManager.exportToCSV(
                trips = tripsWithIndemnities,
                startDate = startDate,
                endDate = endDate,
                expenseMode = "trips_with_expenses",
                includePhotos = true,
                onSuccess = { file ->
                    onSuccess(file)
                    exportManager.shareFile(file)
                },
                onError = onError
            )
        }
    }

    /**
     * Quick export to Excel
     */
    fun quickExportExcel(
        period: QuickExportPeriod,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val (startDate, endDate) = getDateRangeForPeriod(period)

            val allTrips = tripRepository.getAllTrips()

            val tripsWithIndemnities = allTrips.filter { trip ->
                trip.endTime != null &&
                trip.isValidated &&
                trip.startTime >= startDate &&
                trip.startTime <= endDate &&
                (trip.reimbursementAmount != null && trip.reimbursementAmount > 0) ||
                (trip.tripType == "PROFESSIONAL" && trip.isValidated)
            }

            if (tripsWithIndemnities.isEmpty()) {
                onError("Aucun trajet avec indemnités pour cette période")
                return@launch
            }

            exportManager.exportToExcel(
                trips = tripsWithIndemnities,
                startDate = startDate,
                endDate = endDate,
                expenseMode = "trips_with_expenses",
                includePhotos = true,
                onSuccess = { file ->
                    onSuccess(file)
                    exportManager.shareFile(file)
                },
                onError = onError
            )
        }
    }

    /**
     * Get period label for display
     */
    fun getPeriodLabel(period: QuickExportPeriod): String {
        return when (period) {
            QuickExportPeriod.TODAY -> "Aujourd'hui"
            QuickExportPeriod.THIS_WEEK -> "Cette semaine"
            QuickExportPeriod.THIS_MONTH -> "Ce mois"
            QuickExportPeriod.THIS_YEAR -> "Cette année"
        }
    }
}



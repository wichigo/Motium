package com.application.motium.presentation.pro.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.ProAccountRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.SupabaseTripRepository
import com.application.motium.data.supabase.SupabaseVehicleRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.Trip
import com.application.motium.domain.model.Vehicle
import com.application.motium.utils.EmployeeExportData
import com.application.motium.utils.ExportManager
import com.application.motium.utils.ProExportData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UI State for Pro Export Advanced screen
 */
data class ProExportAdvancedUiState(
    val isLoading: Boolean = true,
    val linkedUsers: List<LinkedUserDto> = emptyList(),
    val selectedUserIds: Set<String> = emptySet(),
    val startDate: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000),
    val endDate: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    val exportFormat: ExportFormatOption = ExportFormatOption.CSV,
    val includeProTrips: Boolean = true,
    val includePersoTrips: Boolean = false,
    val includeExpenses: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
) {
    val allSelected: Boolean
        get() = linkedUsers.isNotEmpty() && selectedUserIds.size == linkedUsers.size
}

/**
 * ViewModel for Pro Export Advanced functionality
 */
class ProExportAdvancedViewModel(
    private val context: Context,
    private val linkedAccountRepository: LinkedAccountRepository = LinkedAccountRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context),
    private val proAccountRepository: ProAccountRepository = ProAccountRepository.getInstance(context),
    private val tripRepository: SupabaseTripRepository = SupabaseTripRepository.getInstance(context),
    private val vehicleRepository: SupabaseVehicleRepository = SupabaseVehicleRepository.getInstance(context),
    private val expenseRepository: ExpenseRepository = ExpenseRepository.getInstance(context),
    private val exportManager: ExportManager = ExportManager(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProExportAdvancedUiState())
    val uiState: StateFlow<ProExportAdvancedUiState> = _uiState.asStateFlow()

    init {
        loadLinkedUsers()
    }

    /**
     * Load all active linked users
     */
    private fun loadLinkedUsers() {
        MotiumApplication.logger.i("📋 loadLinkedUsers() called", "ProExportVM")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                MotiumApplication.logger.d("📋 Getting pro account ID...", "ProExportVM")
                val proAccountId = authRepository.getCurrentProAccountId()
                MotiumApplication.logger.d("📋 Pro account ID: $proAccountId", "ProExportVM")
                if (proAccountId == null) {
                    MotiumApplication.logger.w("📋 Pro account not found - cannot load linked users", "ProExportVM")
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Compte Pro non trouvé"
                    )}
                    return@launch
                }

                MotiumApplication.logger.d("📋 Fetching linked users for pro account: $proAccountId", "ProExportVM")
                val result = linkedAccountRepository.getLinkedUsers(proAccountId)
                result.fold(
                    onSuccess = { users ->
                        val activeUsers = users.filter { it.status == LinkStatus.ACTIVE }
                        MotiumApplication.logger.i("📋 Loaded ${activeUsers.size} active linked users (${users.size} total)", "ProExportVM")
                        _uiState.update { it.copy(
                            isLoading = false,
                            linkedUsers = activeUsers,
                            selectedUserIds = activeUsers.map { u -> u.userId }.toSet()
                        )}
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("📋 Failed to load linked users: ${e.message}", "ProExportVM", e)
                        _uiState.update { it.copy(
                            isLoading = false,
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("📋 Exception loading linked users: ${e.message}", "ProExportVM", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Toggle selection for a user
     */
    fun toggleUserSelection(userId: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedUserIds.contains(userId)) {
                state.selectedUserIds - userId
            } else {
                state.selectedUserIds + userId
            }
            state.copy(selectedUserIds = newSelection)
        }
    }

    /**
     * Toggle select all users
     */
    fun toggleSelectAll() {
        _uiState.update { state ->
            if (state.allSelected) {
                state.copy(selectedUserIds = emptySet())
            } else {
                state.copy(selectedUserIds = state.linkedUsers.map { it.userId }.toSet())
            }
        }
    }

    /**
     * Set start date
     */
    fun setStartDate(date: Instant) {
        _uiState.update { it.copy(startDate = date) }
    }

    /**
     * Set end date
     */
    fun setEndDate(date: Instant) {
        _uiState.update { it.copy(endDate = date) }
    }

    /**
     * Set export format
     */
    fun setExportFormat(format: ExportFormatOption) {
        _uiState.update { it.copy(exportFormat = format) }
    }

    /**
     * Set include pro trips option
     */
    fun setIncludeProTrips(include: Boolean) {
        _uiState.update { it.copy(includeProTrips = include) }
    }

    /**
     * Set include personal trips option
     */
    fun setIncludePersoTrips(include: Boolean) {
        _uiState.update { it.copy(includePersoTrips = include) }
    }

    /**
     * Set include expenses option
     */
    fun setIncludeExpenses(include: Boolean) {
        _uiState.update { it.copy(includeExpenses = include) }
    }

    /**
     * Export data - Real implementation with company legal format
     */
    fun exportData() {
        MotiumApplication.logger.i("📤 exportData() called - starting export process", "ProExportVM")
        viewModelScope.launch {
            MotiumApplication.logger.d("📤 viewModelScope.launch started", "ProExportVM")
            _uiState.update { it.copy(isExporting = true, error = null) }

            try {
                val state = _uiState.value
                MotiumApplication.logger.d("📤 Current state: selectedUserIds=${state.selectedUserIds.size}, format=${state.exportFormat}", "ProExportVM")

                if (state.selectedUserIds.isEmpty()) {
                    MotiumApplication.logger.w("📤 No users selected - aborting export", "ProExportVM")
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = "Sélectionnez au moins un compte"
                    )}
                    return@launch
                }

                // 1. Get Pro account data for company legal info
                MotiumApplication.logger.d("📤 Step 1: Getting auth user...", "ProExportVM")
                val authUser = authRepository.getCurrentAuthUser()
                val proUserId = authUser?.id
                MotiumApplication.logger.d("📤 Auth user: ${authUser?.email}, id=$proUserId", "ProExportVM")
                if (proUserId == null) {
                    MotiumApplication.logger.e("📤 No auth user found - user not connected", "ProExportVM")
                    _uiState.update { it.copy(isExporting = false, error = "Non connecté") }
                    return@launch
                }

                MotiumApplication.logger.d("📤 Step 2: Getting pro account...", "ProExportVM")
                val proAccountResult = proAccountRepository.getProAccount(proUserId)
                val proAccount = proAccountResult.getOrNull()
                MotiumApplication.logger.d("📤 Pro account: ${proAccount?.companyName}", "ProExportVM")
                if (proAccount == null) {
                    MotiumApplication.logger.e("📤 Pro account not found for user $proUserId", "ProExportVM")
                    _uiState.update { it.copy(isExporting = false, error = "Compte Pro non trouvé") }
                    return@launch
                }

                // 2. Build trip types filter
                MotiumApplication.logger.d("📤 Step 3: Building trip filters...", "ProExportVM")
                val tripTypes = buildList {
                    if (state.includeProTrips) add("PROFESSIONAL")
                    if (state.includePersoTrips) add("PERSONAL")
                }
                MotiumApplication.logger.d("📤 Trip types: $tripTypes", "ProExportVM")

                if (tripTypes.isEmpty()) {
                    MotiumApplication.logger.w("📤 No trip types selected", "ProExportVM")
                    _uiState.update { it.copy(isExporting = false, error = "Sélectionnez au moins un type de trajet") }
                    return@launch
                }

                // 3. Fetch trips for all selected users
                MotiumApplication.logger.d("📤 Step 4: Fetching trips for ${state.selectedUserIds.size} users...", "ProExportVM")
                val tripsResult = tripRepository.getTripsForUsers(
                    userIds = state.selectedUserIds.toList(),
                    startDate = state.startDate,
                    endDate = state.endDate,
                    tripTypes = tripTypes
                )

                val tripsByUser = tripsResult.getOrElse { error ->
                    MotiumApplication.logger.e("📤 Failed to fetch trips: ${error.message}", "ProExportVM", error)
                    _uiState.update { it.copy(isExporting = false, error = "Erreur de chargement des trajets: ${error.message}") }
                    return@launch
                }
                MotiumApplication.logger.d("📤 Trips fetched: ${tripsByUser.values.sumOf { it.size }} total", "ProExportVM")

                // 4. Build employee export data with vehicle info
                val employees = state.linkedUsers
                    .filter { state.selectedUserIds.contains(it.userId) }
                    .map { user ->
                        val userTrips = tripsByUser[user.userId] ?: emptyList()

                        // Get vehicle info for the user
                        val vehicle = try {
                            vehicleRepository.getDefaultVehicle(user.userId)
                        } catch (e: Exception) {
                            MotiumApplication.logger.w("Could not get vehicle for ${user.userId}: ${e.message}", "ProExportVM")
                            null
                        }

                        // Get expenses if included
                        val expenses: List<Expense> = if (state.includeExpenses) {
                            try {
                                expenseRepository.getExpensesForDateRange(
                                    userId = user.userId,
                                    startDate = state.startDate.toEpochMilliseconds(),
                                    endDate = state.endDate.toEpochMilliseconds()
                                )
                            } catch (e: Exception) {
                                MotiumApplication.logger.w("Could not get expenses for ${user.userId}: ${e.message}", "ProExportVM")
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }

                        EmployeeExportData(
                            userId = user.userId,
                            displayName = user.userName ?: user.userEmail.substringBefore("@"),
                            email = user.userEmail,
                            trips = userTrips,
                            expenses = expenses,
                            vehicle = vehicle
                        )
                    }

                // 5. Build ProExportData
                MotiumApplication.logger.d("📤 Step 5: Building export data with ${employees.size} employees", "ProExportVM")
                val exportData = ProExportData(
                    companyName = proAccount.companyName,
                    siret = proAccount.siret,
                    vatNumber = proAccount.vatNumber,
                    legalForm = proAccount.legalForm,
                    billingAddress = proAccount.billingAddress,
                    employees = employees,
                    startDate = state.startDate.toEpochMilliseconds(),
                    endDate = state.endDate.toEpochMilliseconds(),
                    includeExpenses = state.includeExpenses
                )
                MotiumApplication.logger.d("📤 Export data built: company=${exportData.companyName}", "ProExportVM")

                // 6. Export in selected format
                MotiumApplication.logger.i("📤 Step 6: Starting ${state.exportFormat} export...", "ProExportVM")
                val onSuccess: (java.io.File) -> Unit = { file ->
                    MotiumApplication.logger.i("📤 ✅ Export SUCCESS: ${file.absolutePath}", "ProExportVM")
                    val totalTrips = employees.sumOf { it.trips.size }
                    _uiState.update { it.copy(
                        isExporting = false,
                        successMessage = "Export réussi! $totalTrips trajet(s) pour ${employees.size} collaborateur(s)"
                    )}
                    // Share the file
                    exportManager.shareFile(file)
                }

                val onError: (String) -> Unit = { error ->
                    MotiumApplication.logger.e("📤 ❌ Export ERROR: $error", "ProExportVM")
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = error
                    )}
                }

                when (state.exportFormat) {
                    ExportFormatOption.CSV -> {
                        MotiumApplication.logger.d("📤 Calling exportProToCSV...", "ProExportVM")
                        exportManager.exportProToCSV(exportData, onSuccess, onError)
                    }
                    ExportFormatOption.PDF -> {
                        MotiumApplication.logger.d("📤 Calling exportProToPDF...", "ProExportVM")
                        exportManager.exportProToPDF(exportData, onSuccess, onError)
                    }
                    ExportFormatOption.EXCEL -> {
                        MotiumApplication.logger.d("📤 Calling exportProToExcel...", "ProExportVM")
                        exportManager.exportProToExcel(exportData, onSuccess, onError)
                    }
                }

                MotiumApplication.logger.i("📤 Export function called, waiting for callback...", "ProExportVM")
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isExporting = false,
                    error = "Erreur d'export: ${e.message}"
                )}
                MotiumApplication.logger.e("Pro export failed: ${e.message}", "ProExportVM", e)
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    // ================================================================================
    // QUICK EXPORT SHORTCUTS FOR PRO
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
    private fun getDateRangeForPeriod(period: QuickExportPeriod): Pair<Instant, Instant> {
        val calendar = Calendar.getInstance()
        val endDate: Long
        val startDate: Long

        when (period) {
            QuickExportPeriod.TODAY -> {
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
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                startDate = calendar.timeInMillis
                endDate = System.currentTimeMillis()
            }
            QuickExportPeriod.THIS_YEAR -> {
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
        return Pair(
            Instant.fromEpochMilliseconds(startDate),
            Instant.fromEpochMilliseconds(endDate)
        )
    }

    /**
     * Apply a quick period preset - sets date range, selects all users and enables all options
     * User then uses existing export buttons at the bottom
     */
    fun applyQuickPeriod(period: QuickExportPeriod) {
        val (startDate, endDate) = getDateRangeForPeriod(period)
        _uiState.update { state ->
            state.copy(
                startDate = startDate,
                endDate = endDate,
                // Select all users
                selectedUserIds = state.linkedUsers.map { it.userId }.toSet(),
                // Enable all trip types
                includeProTrips = true,
                includePersoTrips = true,
                // Include expenses
                includeExpenses = true
            )
        }
    }

    /**
     * Quick export Pro with all options enabled:
     * - All linked users selected
     * - All trip types (pro + perso)
     * - Include expenses
     * - Only trips with indemnities
     */
    fun quickExportPro(
        period: QuickExportPeriod,
        format: ExportFormatOption = ExportFormatOption.PDF
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, error = null) }

            try {
                val state = _uiState.value
                val (startDate, endDate) = getDateRangeForPeriod(period)

                // Get Pro account data
                val authUser = authRepository.getCurrentAuthUser()
                val proUserId = authUser?.id
                if (proUserId == null) {
                    _uiState.update { it.copy(isExporting = false, error = "Non connecté") }
                    return@launch
                }

                val proAccountResult = proAccountRepository.getProAccount(proUserId)
                val proAccount = proAccountResult.getOrNull()
                if (proAccount == null) {
                    _uiState.update { it.copy(isExporting = false, error = "Compte Pro non trouvé") }
                    return@launch
                }

                // Select ALL users for quick export
                val allUserIds = state.linkedUsers.map { it.userId }
                if (allUserIds.isEmpty()) {
                    _uiState.update { it.copy(isExporting = false, error = "Aucun collaborateur lié") }
                    return@launch
                }

                // Fetch ALL trips (pro + perso) for the period
                val tripTypes = listOf("PROFESSIONAL", "PERSONAL")
                val tripsResult = tripRepository.getTripsForUsers(
                    userIds = allUserIds,
                    startDate = startDate,
                    endDate = endDate,
                    tripTypes = tripTypes
                )

                val tripsByUser = tripsResult.getOrElse { error ->
                    _uiState.update { it.copy(isExporting = false, error = "Erreur: ${error.message}") }
                    return@launch
                }

                // Build employee export data with all options
                val employees = state.linkedUsers.map { user ->
                    val userTrips = (tripsByUser[user.userId] ?: emptyList())
                        // Filter to only trips with indemnities
                        .filter { trip ->
                            (trip.reimbursementAmount != null && trip.reimbursementAmount > 0) ||
                            (trip.type.name == "PROFESSIONAL" && trip.isValidated)
                        }

                    val vehicle = try {
                        vehicleRepository.getDefaultVehicle(user.userId)
                    } catch (e: Exception) { null }

                    val expenses: List<Expense> = try {
                        expenseRepository.getExpensesForDateRange(
                            userId = user.userId,
                            startDate = startDate.toEpochMilliseconds(),
                            endDate = endDate.toEpochMilliseconds()
                        )
                    } catch (e: Exception) { emptyList() }

                    EmployeeExportData(
                        userId = user.userId,
                        displayName = user.userName ?: user.userEmail.substringBefore("@"),
                        email = user.userEmail,
                        trips = userTrips,
                        expenses = expenses,
                        vehicle = vehicle
                    )
                }.filter { it.trips.isNotEmpty() || it.expenses.isNotEmpty() }

                if (employees.isEmpty()) {
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = "Aucun trajet avec indemnités pour cette période"
                    )}
                    return@launch
                }

                val exportData = ProExportData(
                    companyName = proAccount.companyName,
                    siret = proAccount.siret,
                    vatNumber = proAccount.vatNumber,
                    legalForm = proAccount.legalForm,
                    billingAddress = proAccount.billingAddress,
                    employees = employees,
                    startDate = startDate.toEpochMilliseconds(),
                    endDate = endDate.toEpochMilliseconds(),
                    includeExpenses = true // Always include expenses for quick export
                )

                val onSuccess: (java.io.File) -> Unit = { file ->
                    val totalTrips = employees.sumOf { it.trips.size }
                    _uiState.update { it.copy(
                        isExporting = false,
                        successMessage = "Export rapide: $totalTrips trajet(s) pour ${employees.size} collaborateur(s)"
                    )}
                    exportManager.shareFile(file)
                }

                val onError: (String) -> Unit = { error ->
                    _uiState.update { it.copy(isExporting = false, error = error) }
                }

                when (format) {
                    ExportFormatOption.CSV -> exportManager.exportProToCSV(exportData, onSuccess, onError)
                    ExportFormatOption.PDF -> exportManager.exportProToPDF(exportData, onSuccess, onError)
                    ExportFormatOption.EXCEL -> exportManager.exportProToExcel(exportData, onSuccess, onError)
                }

                MotiumApplication.logger.i("Quick Pro export: $period, format=$format", "ProExportVM")
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isExporting = false,
                    error = "Erreur: ${e.message}"
                )}
            }
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

/**
 * ViewModelFactory for ProExportAdvancedViewModel
 */
class ProExportAdvancedViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProExportAdvancedViewModel::class.java)) {
            return ProExportAdvancedViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

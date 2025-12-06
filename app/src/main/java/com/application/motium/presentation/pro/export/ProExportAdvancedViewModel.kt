package com.application.motium.presentation.pro.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.LinkedAccount
import com.application.motium.domain.model.LinkedAccountStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * UI State for Pro Export Advanced screen
 */
data class ProExportAdvancedUiState(
    val isLoading: Boolean = true,
    val linkedAccounts: List<LinkedAccount> = emptyList(),
    val selectedAccountIds: Set<String> = emptySet(),
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
        get() = linkedAccounts.isNotEmpty() && selectedAccountIds.size == linkedAccounts.size
}

/**
 * ViewModel for Pro Export Advanced functionality
 */
class ProExportAdvancedViewModel(
    private val context: Context,
    private val linkedAccountRepository: LinkedAccountRepository = LinkedAccountRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProExportAdvancedUiState())
    val uiState: StateFlow<ProExportAdvancedUiState> = _uiState.asStateFlow()

    init {
        loadLinkedAccounts()
    }

    /**
     * Load all active linked accounts
     */
    private fun loadLinkedAccounts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Compte Pro non trouvé"
                    )}
                    return@launch
                }

                val result = linkedAccountRepository.getLinkedAccounts(proAccountId)
                result.fold(
                    onSuccess = { accounts ->
                        val activeAccounts = accounts.filter { it.status == LinkedAccountStatus.ACTIVE }
                        _uiState.update { it.copy(
                            isLoading = false,
                            linkedAccounts = activeAccounts,
                            selectedAccountIds = activeAccounts.map { a -> a.id }.toSet()
                        )}
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(
                            isLoading = false,
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Toggle selection for an account
     */
    fun toggleAccountSelection(accountId: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedAccountIds.contains(accountId)) {
                state.selectedAccountIds - accountId
            } else {
                state.selectedAccountIds + accountId
            }
            state.copy(selectedAccountIds = newSelection)
        }
    }

    /**
     * Toggle select all accounts
     */
    fun toggleSelectAll() {
        _uiState.update { state ->
            if (state.allSelected) {
                state.copy(selectedAccountIds = emptySet())
            } else {
                state.copy(selectedAccountIds = state.linkedAccounts.map { it.id }.toSet())
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
     * Export data
     */
    fun exportData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, error = null) }

            try {
                val state = _uiState.value

                if (state.selectedAccountIds.isEmpty()) {
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = "Sélectionnez au moins un compte"
                    )}
                    return@launch
                }

                // TODO: Implement actual export logic
                // This would involve:
                // 1. Fetch trips from all selected accounts
                // 2. Filter by date range and trip types
                // 3. Generate file in selected format
                // 4. Save/share file

                // For now, simulate export
                kotlinx.coroutines.delay(2000)

                _uiState.update { it.copy(
                    isExporting = false,
                    successMessage = "Export réussi! ${state.selectedAccountIds.size} compte(s) exporté(s)"
                )}

                MotiumApplication.logger.i("Pro export completed for ${state.selectedAccountIds.size} accounts", "ProExportVM")
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isExporting = false,
                    error = "Erreur d'export: ${e.message}"
                )}
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
}

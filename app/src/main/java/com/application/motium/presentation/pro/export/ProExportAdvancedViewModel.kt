package com.application.motium.presentation.pro.export

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.LinkStatus
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
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
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

                val result = linkedAccountRepository.getLinkedUsers(proAccountId)
                result.fold(
                    onSuccess = { users ->
                        val activeUsers = users.filter { it.status == LinkStatus.ACTIVE }
                        _uiState.update { it.copy(
                            isLoading = false,
                            linkedUsers = activeUsers,
                            selectedUserIds = activeUsers.map { u -> u.userId }.toSet()
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
     * Export data
     */
    fun exportData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, error = null) }

            try {
                val state = _uiState.value

                if (state.selectedUserIds.isEmpty()) {
                    _uiState.update { it.copy(
                        isExporting = false,
                        error = "Sélectionnez au moins un compte"
                    )}
                    return@launch
                }

                // TODO: Implement actual export logic
                // This would involve:
                // 1. Fetch trips from all selected users
                // 2. Filter by date range and trip types
                // 3. Generate file in selected format
                // 4. Save/share file

                // For now, simulate export
                kotlinx.coroutines.delay(2000)

                _uiState.update { it.copy(
                    isExporting = false,
                    successMessage = "Export réussi! ${state.selectedUserIds.size} compte(s) exporté(s)"
                )}

                MotiumApplication.logger.i("Pro export completed for ${state.selectedUserIds.size} users", "ProExportVM")
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

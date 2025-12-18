package com.application.motium.presentation.pro.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.ProAccountRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.LinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for Linked Accounts screen
 */
data class LinkedAccountsUiState(
    val isLoading: Boolean = true,
    val linkedUsers: List<LinkedUserDto> = emptyList(),
    val availableDepartments: List<String> = emptyList(),
    val selectedDepartments: Set<String> = emptySet(),
    val error: String? = null,
    val successMessage: String? = null,
    val showInviteDialog: Boolean = false,
    val isInviting: Boolean = false
) {
    // Filtered users based on selected departments
    val filteredUsers: List<LinkedUserDto>
        get() = if (selectedDepartments.isEmpty()) {
            linkedUsers
        } else {
            linkedUsers.filter { user ->
                val userDept = user.department ?: "Sans département"
                selectedDepartments.contains(userDept)
            }
        }

    val allDepartmentsSelected: Boolean
        get() = availableDepartments.isNotEmpty() && selectedDepartments.size == availableDepartments.size
}

/**
 * ViewModel for managing linked accounts (Pro feature)
 */
class LinkedAccountsViewModel(
    private val context: Context,
    private val linkedAccountRepository: LinkedAccountRepository = LinkedAccountRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context),
    private val proAccountRepository: ProAccountRepository = ProAccountRepository.getInstance(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkedAccountsUiState())
    val uiState: StateFlow<LinkedAccountsUiState> = _uiState.asStateFlow()

    init {
        loadLinkedUsers()
    }

    /**
     * Load all linked users for the current Pro account
     */
    fun loadLinkedUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Compte Pro non trouvé. Veuillez configurer votre compte professionnel."
                    )}
                    return@launch
                }

                val result = linkedAccountRepository.getLinkedUsers(proAccountId)
                result.fold(
                    onSuccess = { users ->
                        // Extract unique departments
                        val departments = users
                            .map { it.department ?: "Sans département" }
                            .distinct()
                            .sorted()
                        _uiState.update { it.copy(
                            isLoading = false,
                            linkedUsers = users,
                            availableDepartments = departments,
                            selectedDepartments = departments.toSet() // Select all by default
                        )}
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("Failed to load linked users: ${e.message}", "LinkedAccountsVM", e)
                        _uiState.update { it.copy(
                            isLoading = false,
                            error = "Erreur lors du chargement: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading linked users: ${e.message}", "LinkedAccountsVM", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Show the invite dialog
     */
    fun showInviteDialog() {
        _uiState.update { it.copy(showInviteDialog = true) }
    }

    /**
     * Hide the invite dialog
     */
    fun hideInviteDialog() {
        _uiState.update { it.copy(showInviteDialog = false) }
    }

    /**
     * Send an invitation to link a user
     */
    fun inviteUser(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInviting = true) }

            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(
                        isInviting = false,
                        error = "Compte Pro non trouvé"
                    )}
                    return@launch
                }

                // Get company name from pro account
                val authState = authRepository.authState.first()
                val currentUser = authState.user
                if (currentUser == null) {
                    _uiState.update { it.copy(
                        isInviting = false,
                        error = "Utilisateur non connecté"
                    )}
                    return@launch
                }
                val proAccountResult = proAccountRepository.getProAccount(currentUser.id)
                val companyName = proAccountResult.getOrNull()?.companyName
                if (companyName == null) {
                    _uiState.update { it.copy(
                        isInviting = false,
                        error = "Nom de l'entreprise non trouvé"
                    )}
                    return@launch
                }

                val result = linkedAccountRepository.inviteUser(proAccountId, companyName, email)
                result.fold(
                    onSuccess = { userId ->
                        val message = if (userId != null) {
                            "Invitation envoyée à $email"
                        } else {
                            "Utilisateur non trouvé. L'invitation sera envoyée par email."
                        }
                        _uiState.update { it.copy(
                            isInviting = false,
                            showInviteDialog = false,
                            successMessage = message
                        )}
                        loadLinkedUsers()
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("Failed to invite user: ${e.message}", "LinkedAccountsVM", e)
                        _uiState.update { it.copy(
                            isInviting = false,
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isInviting = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Revoke access for a linked user
     */
    fun revokeUser(userId: String) {
        viewModelScope.launch {
            try {
                val result = linkedAccountRepository.revokeUser(userId)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            successMessage = "Accès révoqué"
                        )}
                        loadLinkedUsers()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Erreur: ${e.message}") }
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

    /**
     * Toggle selection for a department
     */
    fun toggleDepartmentSelection(department: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedDepartments.contains(department)) {
                state.selectedDepartments - department
            } else {
                state.selectedDepartments + department
            }
            state.copy(selectedDepartments = newSelection)
        }
    }

    /**
     * Toggle select all departments
     */
    fun toggleSelectAllDepartments() {
        _uiState.update { state ->
            if (state.allDepartmentsSelected) {
                state.copy(selectedDepartments = emptySet())
            } else {
                state.copy(selectedDepartments = state.availableDepartments.toSet())
            }
        }
    }

    /**
     * Get count of users by status (from filtered users)
     */
    fun getActiveCount(): Int = _uiState.value.filteredUsers.count { it.status == LinkStatus.ACTIVE }
    fun getPendingCount(): Int = _uiState.value.filteredUsers.count { it.status == LinkStatus.PENDING }
}

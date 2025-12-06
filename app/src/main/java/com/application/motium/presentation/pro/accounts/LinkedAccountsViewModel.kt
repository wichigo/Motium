package com.application.motium.presentation.pro.accounts

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

/**
 * UI State for Linked Accounts screen
 */
data class LinkedAccountsUiState(
    val isLoading: Boolean = true,
    val linkedAccounts: List<LinkedAccount> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null,
    val showInviteDialog: Boolean = false,
    val isInviting: Boolean = false
)

/**
 * ViewModel for managing linked accounts (Pro feature)
 */
class LinkedAccountsViewModel(
    private val context: Context,
    private val linkedAccountRepository: LinkedAccountRepository = LinkedAccountRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkedAccountsUiState())
    val uiState: StateFlow<LinkedAccountsUiState> = _uiState.asStateFlow()

    init {
        loadLinkedAccounts()
    }

    /**
     * Load all linked accounts for the current Pro user
     */
    fun loadLinkedAccounts() {
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

                val result = linkedAccountRepository.getLinkedAccounts(proAccountId)
                result.fold(
                    onSuccess = { accounts ->
                        _uiState.update { it.copy(
                            isLoading = false,
                            linkedAccounts = accounts.sortedByDescending { a -> a.createdAt }
                        )}
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("Failed to load linked accounts: ${e.message}", "LinkedAccountsVM", e)
                        _uiState.update { it.copy(
                            isLoading = false,
                            error = "Erreur lors du chargement: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading linked accounts: ${e.message}", "LinkedAccountsVM", e)
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
     * Send an invitation to link an account
     */
    fun inviteAccount(email: String) {
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

                val result = linkedAccountRepository.inviteAccount(proAccountId, email)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            isInviting = false,
                            showInviteDialog = false,
                            successMessage = "Invitation envoyée à $email"
                        )}
                        loadLinkedAccounts()
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("Failed to invite account: ${e.message}", "LinkedAccountsVM", e)
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
     * Revoke access for a linked account
     */
    fun revokeAccount(accountId: String) {
        viewModelScope.launch {
            try {
                val result = linkedAccountRepository.revokeAccount(accountId)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            successMessage = "Accès révoqué"
                        )}
                        loadLinkedAccounts()
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
     * Get count of accounts by status
     */
    fun getActiveCount(): Int = _uiState.value.linkedAccounts.count { it.status == LinkedAccountStatus.ACTIVE }
    fun getPendingCount(): Int = _uiState.value.linkedAccounts.count { it.status == LinkedAccountStatus.PENDING }
}

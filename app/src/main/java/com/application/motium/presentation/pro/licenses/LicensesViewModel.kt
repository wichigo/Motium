package com.application.motium.presentation.pro.licenses

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LicenseRepository
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicensesSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for Licenses screen
 */
data class LicensesUiState(
    val isLoading: Boolean = true,
    val licenses: List<License> = emptyList(),
    val summary: LicensesSummary = LicensesSummary(
        totalLicenses = 0,
        availableLicenses = 0,
        assignedLicenses = 0,
        pendingUnlinkLicenses = 0,
        pendingPaymentLicenses = 0,
        expiredLicenses = 0,
        lifetimeLicenses = 0,
        monthlyLicenses = 0,
        monthlyTotalHT = 0.0,
        monthlyTotalTTC = 0.0,
        monthlyVAT = 0.0
    ),
    val error: String? = null,
    val successMessage: String? = null,
    val showPurchaseDialog: Boolean = false,
    val isPurchasing: Boolean = false,
    // Assignment dialog state
    val showAssignDialog: Boolean = false,
    val selectedLicenseForAssignment: License? = null,
    val linkedAccountsForAssignment: List<LinkedUserDto> = emptyList(),
    val isLoadingAccounts: Boolean = false,
    // Unlink confirm dialog state
    val showUnlinkConfirmDialog: Boolean = false,
    val licenseToUnlink: License? = null,
    val isUnlinking: Boolean = false
)

/**
 * ViewModel for managing licenses
 */
class LicensesViewModel(
    private val context: Context,
    private val licenseRepository: LicenseRepository = LicenseRepository.getInstance(context),
    private val linkedAccountRepository: LinkedAccountRepository = LinkedAccountRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
) : ViewModel() {

    private val _uiState = MutableStateFlow(LicensesUiState())
    val uiState: StateFlow<LicensesUiState> = _uiState.asStateFlow()

    init {
        loadLicenses()
    }

    /**
     * Load all licenses for the current Pro account
     */
    fun loadLicenses() {
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

                val result = licenseRepository.getLicenses(proAccountId)
                result.fold(
                    onSuccess = { licenses ->
                        val summary = LicensesSummary.fromLicenses(licenses)
                        _uiState.update { it.copy(
                            isLoading = false,
                            licenses = licenses,
                            summary = summary
                        )}
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("Failed to load licenses: ${e.message}", "LicensesVM", e)
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
     * Show purchase dialog
     */
    fun showPurchaseDialog() {
        _uiState.update { it.copy(showPurchaseDialog = true) }
    }

    /**
     * Hide purchase dialog
     */
    fun hidePurchaseDialog() {
        _uiState.update { it.copy(showPurchaseDialog = false) }
    }

    /**
     * Purchase licenses via Stripe (monthly or lifetime)
     */
    fun purchaseLicenses(quantity: Int, isLifetime: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPurchasing = true) }

            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(
                        isPurchasing = false,
                        error = "Compte Pro non trouve"
                    )}
                    return@launch
                }

                // TODO: Initialize Stripe payment before creating licenses
                // val paymentResult = subscriptionManager.initializeProLicensePayment(
                //     proAccountId = proAccountId,
                //     email = currentUserEmail,
                //     quantity = quantity,
                //     isLifetime = isLifetime
                // )

                // For now, create placeholder licenses
                val result = if (isLifetime) {
                    licenseRepository.createLifetimeLicenses(proAccountId, quantity)
                } else {
                    licenseRepository.createLicenses(proAccountId, quantity)
                }

                result.fold(
                    onSuccess = {
                        val typeText = if (isLifetime) "a vie" else "mensuelles"
                        _uiState.update { it.copy(
                            isPurchasing = false,
                            showPurchaseDialog = false,
                            successMessage = "$quantity licence(s) $typeText creee(s)"
                        )}
                        loadLicenses()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(
                            isPurchasing = false,
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isPurchasing = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Cancel a license (monthly only)
     */
    fun cancelLicense(licenseId: String) {
        viewModelScope.launch {
            try {
                val result = licenseRepository.cancelLicense(licenseId)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            successMessage = "Licence annulee"
                        )}
                        loadLicenses()
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

    // ========================================
    // Assignment Dialog Methods
    // ========================================

    /**
     * Show assignment dialog for an available license
     */
    fun showAssignmentDialog(license: License) {
        _uiState.update { it.copy(
            showAssignDialog = true,
            selectedLicenseForAssignment = license,
            isLoadingAccounts = true
        )}
        loadUnlicensedAccounts()
    }

    /**
     * Hide assignment dialog
     */
    fun hideAssignmentDialog() {
        _uiState.update { it.copy(
            showAssignDialog = false,
            selectedLicenseForAssignment = null,
            linkedAccountsForAssignment = emptyList()
        )}
    }

    /**
     * Load linked accounts that don't have a license yet
     */
    private fun loadUnlicensedAccounts() {
        viewModelScope.launch {
            try {
                val proAccountId = authRepository.getCurrentProAccountId() ?: return@launch

                // Get all linked accounts
                val linkedUsersResult = linkedAccountRepository.getLinkedUsers(proAccountId)
                val licensesResult = licenseRepository.getLicenses(proAccountId)

                if (linkedUsersResult.isSuccess && licensesResult.isSuccess) {
                    val linkedUsers = linkedUsersResult.getOrDefault(emptyList())
                    val licenses = licensesResult.getOrDefault(emptyList())

                    // Get IDs of accounts that already have a license
                    val licensedAccountIds = licenses
                        .filter { it.isAssigned }
                        .mapNotNull { it.linkedAccountId }
                        .toSet()

                    // Filter to only show accounts without a license
                    val unlicensedAccounts = linkedUsers.filter { user ->
                        user.userId !in licensedAccountIds && user.isActive
                    }

                    _uiState.update { it.copy(
                        linkedAccountsForAssignment = unlicensedAccounts,
                        isLoadingAccounts = false
                    )}
                } else {
                    _uiState.update { it.copy(
                        isLoadingAccounts = false,
                        error = "Erreur lors du chargement des comptes"
                    )}
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoadingAccounts = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Assign a license to a linked account
     */
    fun assignLicenseToAccount(licenseId: String, linkedAccountId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                val result = licenseRepository.assignLicenseToAccount(
                    licenseId = licenseId,
                    proAccountId = proAccountId,
                    linkedAccountId = linkedAccountId
                )

                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            showAssignDialog = false,
                            selectedLicenseForAssignment = null,
                            successMessage = "Licence assignee avec succes"
                        )}
                        loadLicenses()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(
                            error = e.message ?: "Erreur lors de l'assignation"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    // ========================================
    // Unlink Methods (30-day notice period)
    // ========================================

    /**
     * Show unlink confirmation dialog
     */
    fun showUnlinkConfirmDialog(license: License) {
        _uiState.update { it.copy(
            showUnlinkConfirmDialog = true,
            licenseToUnlink = license
        )}
    }

    /**
     * Hide unlink confirmation dialog
     */
    fun hideUnlinkConfirmDialog() {
        _uiState.update { it.copy(
            showUnlinkConfirmDialog = false,
            licenseToUnlink = null
        )}
    }

    /**
     * Request to unlink a license (starts 30-day notice period)
     */
    fun confirmUnlinkRequest() {
        val license = _uiState.value.licenseToUnlink ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlinking = true) }

            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(
                        isUnlinking = false,
                        error = "Compte Pro non trouve"
                    )}
                    return@launch
                }

                val result = licenseRepository.requestUnlink(
                    licenseId = license.id,
                    proAccountId = proAccountId
                )

                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            isUnlinking = false,
                            showUnlinkConfirmDialog = false,
                            licenseToUnlink = null,
                            successMessage = "Demande de deliaison enregistree. La licence sera liberee dans 30 jours."
                        )}
                        loadLicenses()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(
                            isUnlinking = false,
                            error = e.message ?: "Erreur lors de la demande de deliaison"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isUnlinking = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Cancel an unlink request
     */
    fun cancelUnlinkRequest(licenseId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                val result = licenseRepository.cancelUnlinkRequest(
                    licenseId = licenseId,
                    proAccountId = proAccountId
                )

                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            successMessage = "Demande de deliaison annulee"
                        )}
                        loadLicenses()
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(
                            error = e.message ?: "Erreur lors de l'annulation"
                        )}
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    // ========================================
    // Utility Methods
    // ========================================

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
     * Get the display name for a linked account by ID
     */
    fun getLinkedAccountName(accountId: String): String {
        val licenses = _uiState.value.licenses
        // This would need to be loaded from LinkedAccountRepository
        // For now, return the account ID or load it async
        return accountId.take(8) + "..."
    }
}

package com.application.motium.presentation.pro.licenses

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LicenseRepository
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
    val summary: LicensesSummary = LicensesSummary(0, 0, 0, 0, 0.0, 0.0, 0.0),
    val error: String? = null,
    val successMessage: String? = null,
    val showPurchaseDialog: Boolean = false,
    val isPurchasing: Boolean = false
)

/**
 * ViewModel for managing licenses
 */
class LicensesViewModel(
    private val context: Context,
    private val licenseRepository: LicenseRepository = LicenseRepository.getInstance(context),
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
     * Purchase licenses via Stripe
     */
    fun purchaseLicenses(quantity: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPurchasing = true) }

            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    _uiState.update { it.copy(
                        isPurchasing = false,
                        error = "Compte Pro non trouvé"
                    )}
                    return@launch
                }

                // For now, just create placeholder licenses
                // Real Stripe integration would happen here
                val result = licenseRepository.createLicenses(proAccountId, quantity)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            isPurchasing = false,
                            showPurchaseDialog = false,
                            successMessage = "$quantity licence(s) créée(s) avec succès"
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
     * Cancel a license
     */
    fun cancelLicense(licenseId: String) {
        viewModelScope.launch {
            try {
                val result = licenseRepository.cancelLicense(licenseId)
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(
                            successMessage = "Licence annulée"
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

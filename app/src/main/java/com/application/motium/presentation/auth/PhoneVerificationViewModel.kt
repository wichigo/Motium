package com.application.motium.presentation.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.DeviceEligibility
import com.application.motium.data.supabase.DeviceFingerprintRepository
import com.application.motium.data.supabase.PhoneEligibility
import com.application.motium.data.supabase.PhoneVerificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for phone verification during registration.
 * Handles the two-step verification process: phone entry and OTP verification.
 */
class PhoneVerificationViewModel(application: Application) : AndroidViewModel(application) {

    private val phoneVerificationRepository = PhoneVerificationRepository.getInstance(application)
    private val deviceFingerprintRepository = DeviceFingerprintRepository.getInstance(application)

    private val _uiState = MutableStateFlow(PhoneVerificationUiState())
    val uiState: StateFlow<PhoneVerificationUiState> = _uiState.asStateFlow()

    /**
     * Check device eligibility before allowing registration.
     * This is called when the screen is first shown.
     */
    fun checkDeviceEligibility() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingDevice = true) }

            when (val eligibility = deviceFingerprintRepository.checkDeviceEligibility()) {
                is DeviceEligibility.Eligible -> {
                    _uiState.update {
                        it.copy(
                            isCheckingDevice = false,
                            deviceEligible = true
                        )
                    }
                }
                is DeviceEligibility.AlreadyRegistered -> {
                    _uiState.update {
                        it.copy(
                            isCheckingDevice = false,
                            deviceEligible = false,
                            error = "Cet appareil a déjà été utilisé pour créer un compte. " +
                                    "Veuillez vous connecter avec votre compte existant."
                        )
                    }
                }
                is DeviceEligibility.Blocked -> {
                    _uiState.update {
                        it.copy(
                            isCheckingDevice = false,
                            deviceEligible = false,
                            error = "Cet appareil a été bloqué. Veuillez contacter le support."
                        )
                    }
                }
            }
        }
    }

    /**
     * Update the phone number in the UI state.
     */
    fun updatePhoneNumber(phoneNumber: String) {
        _uiState.update { it.copy(phoneNumber = phoneNumber, error = null) }
    }

    /**
     * Update the selected country code.
     */
    fun updateCountryCode(countryCode: String) {
        _uiState.update { it.copy(countryCode = countryCode) }
    }

    /**
     * Send OTP to the entered phone number.
     */
    fun sendOtp() {
        val fullPhoneNumber = "${_uiState.value.countryCode}${_uiState.value.phoneNumber}"

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // First check if phone is eligible
            when (val eligibility = phoneVerificationRepository.checkPhoneEligibility(fullPhoneNumber)) {
                is PhoneEligibility.Eligible -> {
                    // Phone is eligible, send OTP
                    phoneVerificationRepository.sendOtp(fullPhoneNumber)
                        .onSuccess {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    step = VerificationStep.ENTER_OTP,
                                    otpSentTo = fullPhoneNumber
                                )
                            }
                            MotiumApplication.logger.i(
                                "OTP sent successfully to $fullPhoneNumber",
                                "PhoneVerificationViewModel"
                            )
                        }
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Échec de l'envoi du code: ${e.message}"
                                )
                            }
                            MotiumApplication.logger.e(
                                "Failed to send OTP: ${e.message}",
                                "PhoneVerificationViewModel",
                                e
                            )
                        }
                }
                is PhoneEligibility.AlreadyRegistered -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Ce numéro de téléphone est déjà associé à un compte. " +
                                    "Veuillez vous connecter ou utiliser un autre numéro."
                        )
                    }
                }
                is PhoneEligibility.Blocked -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Ce numéro de téléphone a été bloqué. " +
                                    "Veuillez contacter le support."
                        )
                    }
                }
            }
        }
    }

    /**
     * Update the OTP code in the UI state.
     */
    fun updateOtpCode(code: String) {
        // Only accept digits, max 6
        val cleanedCode = code.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(otpCode = cleanedCode, error = null) }
    }

    /**
     * Verify the entered OTP code.
     */
    fun verifyOtp() {
        val phoneNumber = _uiState.value.otpSentTo ?: return
        val code = _uiState.value.otpCode

        if (code.length != 6) {
            _uiState.update { it.copy(error = "Veuillez entrer le code à 6 chiffres") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            phoneVerificationRepository.verifyOtp(phoneNumber, code)
                .onSuccess { verified ->
                    if (verified) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isVerified = true,
                                verifiedPhoneNumber = phoneNumber
                            )
                        }
                        MotiumApplication.logger.i(
                            "Phone verified successfully: $phoneNumber",
                            "PhoneVerificationViewModel"
                        )
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Code incorrect. Veuillez réessayer."
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Échec de la vérification: ${e.message}"
                        )
                    }
                    MotiumApplication.logger.e(
                        "Failed to verify OTP: ${e.message}",
                        "PhoneVerificationViewModel",
                        e
                    )
                }
        }
    }

    /**
     * Resend the OTP code.
     */
    fun resendOtp() {
        val phoneNumber = _uiState.value.otpSentTo ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            phoneVerificationRepository.resendOtp(phoneNumber)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            resendCount = it.resendCount + 1
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Échec du renvoi: ${e.message}"
                        )
                    }
                }
        }
    }

    /**
     * Go back to phone number entry step.
     */
    fun goBackToPhoneEntry() {
        _uiState.update {
            it.copy(
                step = VerificationStep.ENTER_PHONE,
                otpCode = "",
                error = null
            )
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

/**
 * UI state for phone verification
 */
data class PhoneVerificationUiState(
    val step: VerificationStep = VerificationStep.ENTER_PHONE,
    val phoneNumber: String = "",
    val countryCode: String = "+33", // Default to France
    val otpCode: String = "",
    val otpSentTo: String? = null,
    val isLoading: Boolean = false,
    val isCheckingDevice: Boolean = false,
    val deviceEligible: Boolean = true,
    val isVerified: Boolean = false,
    val verifiedPhoneNumber: String? = null,
    val error: String? = null,
    val resendCount: Int = 0
)

/**
 * Steps in the verification flow
 */
enum class VerificationStep {
    ENTER_PHONE,
    ENTER_OTP
}

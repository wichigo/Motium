package com.application.motium.presentation.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.security.DeviceFingerprintManager
import com.application.motium.data.supabase.DeviceFingerprintRepository
import com.application.motium.data.supabase.PhoneVerificationRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.AuthRepository
import com.application.motium.service.SupabaseConnectionService
import com.application.motium.utils.CredentialManagerHelper
import com.application.motium.utils.GoogleSignInHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AuthViewModel(
    private val context: Context,
    private val authRepository: AuthRepository = SupabaseAuthRepository.getInstance(context),
    private val googleSignInHelper: GoogleSignInHelper? = null,
    private val deviceFingerprintRepository: DeviceFingerprintRepository = DeviceFingerprintRepository.getInstance(context),
    private val phoneVerificationRepository: PhoneVerificationRepository = PhoneVerificationRepository.getInstance(context),
    private val deviceFingerprintManager: DeviceFingerprintManager = DeviceFingerprintManager.getInstance(context)
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AuthState(isLoading = true)  // ⚠️ CRITICAL: Must start as loading to wait for repository initialization
        )

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _registerState = MutableStateFlow(RegisterUiState())
    val registerState: StateFlow<RegisterUiState> = _registerState.asStateFlow()

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = _loginState.value.copy(isLoading = true, error = null)

            val result = authRepository.signIn(LoginRequest(email, password))
            when (result) {
                is AuthResult.Success -> {
                    _loginState.value = _loginState.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        // Signal to save credentials after successful login
                        credentialsToSave = CredentialsToSave(email, password)
                    )

                    // Démarrer le service de connexion permanente après connexion réussie
                    MotiumApplication.logger.i("✅ Login successful - starting connection service", "AuthViewModel")
                    SupabaseConnectionService.startService(context)
                }
                is AuthResult.Error -> {
                    _loginState.value = _loginState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                AuthResult.Loading -> {
                    _loginState.value = _loginState.value.copy(isLoading = true)
                }
            }
        }
    }

    fun signUp(email: String, password: String, name: String, isProfessional: Boolean = false, organizationName: String = "") {
        viewModelScope.launch {
            _registerState.value = _registerState.value.copy(isLoading = true, error = null)

            // 🔧 FIX: Utiliser le role ENTERPRISE si professionnel, sinon INDIVIDUAL
            val userRole = if (isProfessional) UserRole.ENTERPRISE else UserRole.INDIVIDUAL
            val result = authRepository.signUp(RegisterRequest(email, password, name, userRole))
            when (result) {
                is AuthResult.Success -> {
                    // Create user profile after successful signup
                    val profileResult = authRepository.createUserProfile(result.data, name, isProfessional, organizationName)
                    when (profileResult) {
                        is AuthResult.Success -> {
                            _registerState.value = _registerState.value.copy(
                                isLoading = false,
                                isSuccess = true
                            )

                            // Démarrer le service de connexion permanente après inscription réussie
                            MotiumApplication.logger.i("✅ Registration successful - starting connection service", "AuthViewModel")
                            SupabaseConnectionService.startService(context)
                        }
                        is AuthResult.Error -> {
                            _registerState.value = _registerState.value.copy(
                                isLoading = false,
                                error = profileResult.message
                            )
                        }
                        AuthResult.Loading -> {
                            _registerState.value = _registerState.value.copy(isLoading = true)
                        }
                    }
                }
                is AuthResult.Error -> {
                    _registerState.value = _registerState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                AuthResult.Loading -> {
                    _registerState.value = _registerState.value.copy(isLoading = true)
                }
            }
        }
    }

    /**
     * Complete registration with phone verification and device fingerprint.
     * Called after phone verification is complete.
     */
    fun signUpWithVerification(
        email: String,
        password: String,
        name: String,
        isProfessional: Boolean = false,
        organizationName: String = "",
        verifiedPhone: String
    ) {
        viewModelScope.launch {
            _registerState.value = _registerState.value.copy(isLoading = true, error = null)

            try {
                // Get device fingerprint
                val deviceId = deviceFingerprintManager.getDeviceId()

                // Create the user account
                val userRole = if (isProfessional) UserRole.ENTERPRISE else UserRole.INDIVIDUAL
                val result = authRepository.signUp(RegisterRequest(email, password, name, userRole))

                when (result) {
                    is AuthResult.Success -> {
                        val userId = result.data.id

                        // Register device fingerprint
                        if (deviceId != null) {
                            deviceFingerprintRepository.registerDevice(userId)
                                .onFailure { e ->
                                    MotiumApplication.logger.w(
                                        "Failed to register device fingerprint: ${e.message}",
                                        "AuthViewModel"
                                    )
                                }
                        }

                        // Register verified phone
                        phoneVerificationRepository.registerVerifiedPhone(verifiedPhone, userId)
                            .onFailure { e ->
                                MotiumApplication.logger.w(
                                    "Failed to register verified phone: ${e.message}",
                                    "AuthViewModel"
                                )
                            }

                        // Create user profile with trial subscription
                        val profileResult = authRepository.createUserProfileWithTrial(
                            userId = userId,
                            name = name,
                            isProfessional = isProfessional,
                            organizationName = organizationName,
                            verifiedPhone = verifiedPhone,
                            deviceFingerprintId = deviceId
                        )

                        when (profileResult) {
                            is AuthResult.Success -> {
                                _registerState.value = _registerState.value.copy(
                                    isLoading = false,
                                    isSuccess = true
                                )

                                MotiumApplication.logger.i(
                                    "Registration with verification successful",
                                    "AuthViewModel"
                                )
                                SupabaseConnectionService.startService(context)
                            }
                            is AuthResult.Error -> {
                                _registerState.value = _registerState.value.copy(
                                    isLoading = false,
                                    error = profileResult.message
                                )
                            }
                            AuthResult.Loading -> {
                                _registerState.value = _registerState.value.copy(isLoading = true)
                            }
                        }
                    }
                    is AuthResult.Error -> {
                        _registerState.value = _registerState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                    AuthResult.Loading -> {
                        _registerState.value = _registerState.value.copy(isLoading = true)
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Registration failed: ${e.message}",
                    "AuthViewModel",
                    e
                )
                _registerState.value = _registerState.value.copy(
                    isLoading = false,
                    error = "Registration failed: ${e.message}"
                )
            }
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _loginState.value = _loginState.value.copy(isLoading = true, error = null)

            val result = authRepository.signInWithGoogle(idToken)
            when (result) {
                is AuthResult.Success -> {
                    _loginState.value = _loginState.value.copy(isLoading = false, isSuccess = true)
                }
                is AuthResult.Error -> {
                    _loginState.value = _loginState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                AuthResult.Loading -> {
                    _loginState.value = _loginState.value.copy(isLoading = true)
                }
            }
        }
    }

    fun initiateGoogleSignIn() {
        googleSignInHelper?.signIn { result ->
            result.fold(
                onSuccess = { idToken ->
                    signInWithGoogle(idToken)
                },
                onFailure = { exception ->
                    _loginState.value = _loginState.value.copy(
                        isLoading = false,
                        error = "Google Sign-In failed: ${exception.message}"
                    )
                }
            )
        } ?: run {
            _loginState.value = _loginState.value.copy(
                isLoading = false,
                error = "Google Sign-In not properly configured"
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            // Arrêter le service de connexion permanente avant déconnexion
            MotiumApplication.logger.i("🔌 Stopping connection service before sign out", "AuthViewModel")
            SupabaseConnectionService.stopService(context)

            // Clear credential state to signal logout to password managers
            CredentialManagerHelper.getInstance(context).clearCredentialState()

            authRepository.signOut()
        }
    }

    fun sendPasswordResetEmail(email: String) {
        viewModelScope.launch {
            authRepository.sendPasswordResetEmail(email)
        }
    }

    /**
     * Met à jour le profil utilisateur
     */
    fun updateUserProfile(
        user: User,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = authRepository.updateUserProfile(user)
            when (result) {
                is AuthResult.Success -> {
                    MotiumApplication.logger.i("✅ User profile updated successfully", "AuthViewModel")
                    onSuccess()
                }
                is AuthResult.Error -> {
                    MotiumApplication.logger.e("❌ Failed to update profile: ${result.message}", "AuthViewModel")
                    onError(result.message)
                }
                AuthResult.Loading -> { /* ignore */ }
            }
        }
    }

    /**
     * Met à jour l'email de l'utilisateur
     * Note: Supabase enverra un email de confirmation
     */
    fun updateEmail(
        newEmail: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = authRepository.updateEmail(newEmail)
            when (result) {
                is AuthResult.Success -> {
                    MotiumApplication.logger.i("✅ Email update request sent", "AuthViewModel")
                    onSuccess()
                }
                is AuthResult.Error -> {
                    MotiumApplication.logger.e("❌ Failed to update email: ${result.message}", "AuthViewModel")
                    onError(result.message)
                }
                AuthResult.Loading -> { /* ignore */ }
            }
        }
    }

    /**
     * Met à jour le mot de passe de l'utilisateur
     */
    fun updatePassword(
        newPassword: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = authRepository.updatePassword(newPassword)
            when (result) {
                is AuthResult.Success -> {
                    MotiumApplication.logger.i("✅ Password updated successfully", "AuthViewModel")
                    onSuccess()
                }
                is AuthResult.Error -> {
                    MotiumApplication.logger.e("❌ Failed to update password: ${result.message}", "AuthViewModel")
                    onError(result.message)
                }
                AuthResult.Loading -> { /* ignore */ }
            }
        }
    }

    fun clearLoginError() {
        _loginState.value = _loginState.value.copy(error = null)
    }

    /**
     * Clear credentials after save attempt (success or failure)
     */
    fun clearCredentialsToSave() {
        _loginState.value = _loginState.value.copy(credentialsToSave = null)
    }

    fun clearRegisterError() {
        _registerState.value = _registerState.value.copy(error = null)
    }

    fun resetLoginState() {
        _loginState.value = LoginUiState()
    }

    fun resetRegisterState() {
        _registerState.value = RegisterUiState()
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
    val credentialsToSave: CredentialsToSave? = null
)

/**
 * Data class to hold credentials that should be saved after successful login
 */
data class CredentialsToSave(
    val email: String,
    val password: String
)

data class RegisterUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)
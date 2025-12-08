package com.application.motium.presentation.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.AuthRepository
import com.application.motium.service.SupabaseConnectionService
import com.application.motium.utils.GoogleSignInHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AuthViewModel(
    private val context: Context,
    private val authRepository: AuthRepository = SupabaseAuthRepository.getInstance(context),
    private val googleSignInHelper: GoogleSignInHelper? = null
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
                    _loginState.value = _loginState.value.copy(isLoading = false, isSuccess = true)

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
    val error: String? = null
)

data class RegisterUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)
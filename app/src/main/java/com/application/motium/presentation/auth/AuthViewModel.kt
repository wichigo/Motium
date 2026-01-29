package com.application.motium.presentation.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.preferences.ProLicenseCache
import com.application.motium.data.repository.LicenseCacheManager
import com.application.motium.data.security.DeviceFingerprintManager
import com.application.motium.data.supabase.DeviceFingerprintRepository
import com.application.motium.data.supabase.EmailRepository
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.data.supabase.ProAccountRemoteDataSource
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.sync.ProDataSyncTrigger
import com.application.motium.domain.model.*
import com.application.motium.domain.repository.AuthRepository
import com.application.motium.service.SupabaseConnectionService
import com.application.motium.utils.CredentialManagerHelper
import com.application.motium.utils.GoogleSignInHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AuthViewModel(
    private val context: Context,
    private val authRepository: AuthRepository = SupabaseAuthRepository.getInstance(context),
    private val googleSignInHelper: GoogleSignInHelper? = null,
    private val deviceFingerprintRepository: DeviceFingerprintRepository = DeviceFingerprintRepository.getInstance(context),
    private val deviceFingerprintManager: DeviceFingerprintManager = DeviceFingerprintManager.getInstance(context),
    private val emailRepository: EmailRepository = EmailRepository.getInstance(context),
    private val proAccountRemoteDataSource: ProAccountRemoteDataSource = ProAccountRemoteDataSource.getInstance(context),
    private val licenseRemoteDataSource: LicenseRemoteDataSource = LicenseRemoteDataSource.getInstance(context),
    private val proLicenseCache: ProLicenseCache = ProLicenseCache.getInstance(context),
    private val proDataSyncTrigger: ProDataSyncTrigger = ProDataSyncTrigger.getInstance(context)
) : ViewModel() {

    companion object {
        private const val TAG = "AuthViewModel"
        private const val LICENSE_CHECK_TIMEOUT_MS = 10_000L // 10 seconds timeout
    }

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

    private val _proLicenseState = MutableStateFlow<ProLicenseState>(ProLicenseState.Idle)
    val proLicenseState: StateFlow<ProLicenseState> = _proLicenseState.asStateFlow()

    /**
     * Check if a Pro user has a valid license assigned.
     * Uses CACHE-FIRST approach for immediate navigation, then refreshes in background.
     *
     * Flow:
     * 1. IMMEDIATELY check cache - if valid Licensed, navigate instantly (no loading state)
     * 2. Launch background network validation to refresh cache
     * 3. Only update state from network if it's DIFFERENT from cache
     * 4. Never override Licensed cache with network failure - require explicit unlicense
     */
    fun checkProLicense(userId: String) {
        viewModelScope.launch {
            // DEBUG: Log entry with full details
            MotiumApplication.logger.w(
                "🔍 DEBUG checkProLicense() START - userId: $userId",
                TAG
            )

            // === STEP 1: CACHE-FIRST - Check cache IMMEDIATELY before any loading ===
            val cachedState = proLicenseCache.getCachedState(userId)
            val cacheValid = cachedState?.isValid() == true

            // DEBUG: Log cache state details
            MotiumApplication.logger.w(
                "🔍 DEBUG checkProLicense() - Cache state:\n" +
                "   cachedState: ${cachedState}\n" +
                "   cacheValid: $cacheValid\n" +
                "   isLicensed: ${cachedState?.isLicensed}\n" +
                "   proAccountId: ${cachedState?.proAccountId}",
                TAG
            )

            if (cacheValid && cachedState.isLicensed && cachedState.proAccountId != null) {
                // Cache says Licensed - navigate immediately, no Loading state shown
                MotiumApplication.logger.i(
                    "✅ Cache-first: Licensed (cached ${cachedState.getAgeHours()}h ago, proAccountId=${cachedState.proAccountId})",
                    TAG
                )
                _proLicenseState.value = ProLicenseState.Licensed

                // Continue to refresh cache in background (non-blocking)
                refreshLicenseInBackground(userId)
                return@launch
            }

            // Cache not valid or not licensed - need to check network
            // Show loading only when we don't have a valid Licensed cache
            _proLicenseState.value = ProLicenseState.Loading
            MotiumApplication.logger.d("Cache miss or not licensed - checking network (cacheValid=$cacheValid)", TAG)

            // === STEP 2: NETWORK CHECK with timeout ===
            val networkResult = try {
                withTimeoutOrNull(LICENSE_CHECK_TIMEOUT_MS) {
                    checkProLicenseFromNetwork(userId)
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("License check exception: ${e.message}", TAG, e)
                null
            }

            // DEBUG: Log network result
            MotiumApplication.logger.w(
                "🔍 DEBUG checkProLicense() - Network result: $networkResult",
                TAG
            )

            when (networkResult) {
                is LicenseCheckResult.Licensed -> {
                    // Cache and return success
                    MotiumApplication.logger.w("🟢 DEBUG: Network says LICENSED → setting ProLicenseState.Licensed", TAG)
                    proLicenseCache.saveLicenseState(
                        userId = userId,
                        isLicensed = true,
                        proAccountId = networkResult.proAccountId
                    )
                    _proLicenseState.value = ProLicenseState.Licensed
                }
                is LicenseCheckResult.NotLicensed -> {
                    // Network confirms not licensed - update cache and state
                    MotiumApplication.logger.w("🔴 DEBUG: Network says NOT_LICENSED → setting ProLicenseState.NotLicensed", TAG)
                    proLicenseCache.saveLicenseState(
                        userId = userId,
                        isLicensed = false,
                        proAccountId = networkResult.proAccountId
                    )
                    _proLicenseState.value = ProLicenseState.NotLicensed
                }
                is LicenseCheckResult.NoProAccount -> {
                    // No pro account found - BUT check if we have a stale cache first
                    // This handles the case where network sync is incomplete
                    MotiumApplication.logger.w(
                        "🟠 DEBUG: Network says NO_PRO_ACCOUNT - checking cached state...",
                        TAG
                    )
                    if (cachedState != null && cachedState.isLicensed) {
                        // We had a Licensed cache but network says no account - trust cache temporarily
                        MotiumApplication.logger.w(
                            "Network says NoProAccount but cache says Licensed - trusting cache (possible sync issue)",
                            TAG
                        )
                        _proLicenseState.value = ProLicenseState.Licensed
                        // Don't update cache - let it expire naturally or be updated by successful network call
                    } else {
                        // No cache or cache says not licensed - trust network
                        MotiumApplication.logger.w(
                            "🔴 DEBUG: No cache or cache not licensed → setting ProLicenseState.NoProAccount",
                            TAG
                        )
                        proLicenseCache.saveLicenseState(
                            userId = userId,
                            isLicensed = false
                        )
                        _proLicenseState.value = ProLicenseState.NoProAccount
                    }
                }
                null -> {
                    // Network failed or timed out - try cache (even stale cache is better than error)
                    MotiumApplication.logger.w(
                        "🟠 DEBUG: Network result is NULL (timeout/failure) → calling handleNetworkFailure()",
                        TAG
                    )
                    handleNetworkFailure(userId)
                }
            }
        }
    }

    /**
     * Refresh license status in background without affecting current state.
     * Only updates cache and state if result differs from current.
     */
    private fun refreshLicenseInBackground(userId: String) {
        viewModelScope.launch {
            try {
                val networkResult = withTimeoutOrNull(LICENSE_CHECK_TIMEOUT_MS) {
                    checkProLicenseFromNetwork(userId)
                }

                when (networkResult) {
                    is LicenseCheckResult.Licensed -> {
                        // Refresh cache with latest data
                        proLicenseCache.saveLicenseState(
                            userId = userId,
                            isLicensed = true,
                            proAccountId = networkResult.proAccountId
                        )
                        MotiumApplication.logger.d("Background refresh: License confirmed", TAG)
                    }
                    is LicenseCheckResult.NotLicensed -> {
                        // License was revoked - update state and cache
                        MotiumApplication.logger.w("Background refresh: License REVOKED - updating state", TAG)
                        proLicenseCache.saveLicenseState(
                            userId = userId,
                            isLicensed = false,
                            proAccountId = networkResult.proAccountId
                        )
                        _proLicenseState.value = ProLicenseState.NotLicensed
                    }
                    is LicenseCheckResult.NoProAccount -> {
                        // Pro account gone - this is unusual, log but don't immediately kick user out
                        MotiumApplication.logger.w(
                            "Background refresh: No pro account found - keeping current state",
                            TAG
                        )
                        // Don't update cache - might be a temporary network/sync issue
                    }
                    null -> {
                        MotiumApplication.logger.d("Background refresh: Network unavailable", TAG)
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.d("Background refresh failed: ${e.message}", TAG)
            }
        }
    }

    /**
     * Perform network validation for pro license.
     * Returns null if network call fails.
     */
    private suspend fun checkProLicenseFromNetwork(userId: String): LicenseCheckResult {
        return withContext(Dispatchers.IO) {
            val proAccount = proAccountRemoteDataSource.getProAccount(userId).getOrNull()
            if (proAccount == null) {
                MotiumApplication.logger.w("Pro account not found for user $userId", TAG)
                return@withContext LicenseCheckResult.NoProAccount
            }

            val isLicensed = licenseRemoteDataSource.isOwnerLicensed(
                proAccountId = proAccount.id,
                ownerUserId = userId
            ).getOrDefault(false)

            if (isLicensed) {
                MotiumApplication.logger.i("Pro user has license (network check)", TAG)
                LicenseCheckResult.Licensed(proAccount.id)
            } else {
                MotiumApplication.logger.i("Pro user has no license (network check)", TAG)
                LicenseCheckResult.NotLicensed(proAccount.id)
            }
        }
    }

    /**
     * Handle network failure by falling back to cached license state.
     * Only sets error state if no valid cache exists.
     */
    private fun handleNetworkFailure(userId: String) {
        val cachedLicenseStatus = proLicenseCache.getValidCachedLicenseStatus(userId)

        if (cachedLicenseStatus != null) {
            // Use cached state
            if (cachedLicenseStatus) {
                MotiumApplication.logger.i("Using cached license state: LICENSED (network unavailable)", TAG)
                _proLicenseState.value = ProLicenseState.Licensed
            } else {
                MotiumApplication.logger.i("Using cached license state: NOT LICENSED (network unavailable)", TAG)
                _proLicenseState.value = ProLicenseState.NotLicensed
            }
        } else {
            // No valid cache - set error state but don't kick user out
            MotiumApplication.logger.w("License check failed and no valid cache - setting error state", TAG)
            _proLicenseState.value = ProLicenseState.Error("Network unavailable. Please check your connection.")
        }
    }

    /**
     * Result of network license check.
     */
    private sealed class LicenseCheckResult {
        data class Licensed(val proAccountId: String) : LicenseCheckResult()
        data class NotLicensed(val proAccountId: String) : LicenseCheckResult()
        data object NoProAccount : LicenseCheckResult()
    }

    /**
     * Reset pro license state (e.g., on logout)
     * Also clears the license cache to force re-validation on next login.
     */
    fun resetProLicenseState() {
        _proLicenseState.value = ProLicenseState.Idle
        proLicenseCache.clearCache()
        MotiumApplication.logger.i("Pro license state and cache reset", TAG)
    }

    /**
     * Trigger Pro data sync for Enterprise users (ProAccount, Licenses, LinkedUsers).
     * Runs in background - does not block login flow.
     * This ensures Room has data for offline access on Pro screens.
     */
    private fun triggerProDataSyncIfNeeded() {
        viewModelScope.launch {
            val user = authRepository.authState.first().user ?: return@launch

            if (user.role == UserRole.ENTERPRISE) {
                MotiumApplication.logger.i("Enterprise user detected - triggering Pro data sync", TAG)
                proDataSyncTrigger.syncProData(user.id)
            }
        }
    }

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

                    // Trigger Pro data sync for Enterprise users (in background)
                    triggerProDataSyncIfNeeded()
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

            val userRole = if (isProfessional) UserRole.ENTERPRISE else UserRole.INDIVIDUAL
            val result = authRepository.signUp(RegisterRequest(email, password, name, userRole))
            when (result) {
                is AuthResult.Success -> {
                    val userId = result.data.id

                    // Step 1: Create user profile first (without device_fingerprint_id)
                    // This must happen BEFORE registerDevice because device_fingerprints.user_id
                    // has a FK to public.users.id
                    val profileResult = authRepository.createUserProfileWithTrial(
                        userId = userId,
                        name = name,
                        isProfessional = isProfessional,
                        organizationName = organizationName,
                        verifiedPhone = "",
                        deviceFingerprintId = null
                    )

                    when (profileResult) {
                        is AuthResult.Success -> {
                            // Step 2: Now register device (profile exists, FK constraint satisfied)
                            val deviceId = deviceFingerprintManager.getDeviceId()
                            if (deviceId != null) {
                                deviceFingerprintRepository.registerDevice(profileResult.data.id)
                                    .onSuccess { fingerprintId ->
                                        // Step 3: Update user profile with device_fingerprint_id
                                        viewModelScope.launch {
                                            val updatedUser = profileResult.data.copy(
                                                deviceFingerprintId = fingerprintId
                                            )
                                            authRepository.updateUserProfile(updatedUser)
                                            MotiumApplication.logger.i(
                                                "Device fingerprint linked: $fingerprintId",
                                                "AuthViewModel"
                                            )
                                        }
                                    }
                                    .onFailure { e ->
                                        MotiumApplication.logger.w(
                                            "Failed to register device: ${e.message}",
                                            "AuthViewModel"
                                        )
                                    }
                            }

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
     * Complete registration with device fingerprint check (no phone verification).
     */
    fun signUpWithVerification(
        email: String,
        password: String,
        name: String,
        isProfessional: Boolean = false,
        organizationName: String = ""
    ) {
        viewModelScope.launch {
            _registerState.value = _registerState.value.copy(isLoading = true, error = null)

            try {
                val deviceId = deviceFingerprintManager.getDeviceId()
                val userRole = if (isProfessional) UserRole.ENTERPRISE else UserRole.INDIVIDUAL
                val result = authRepository.signUp(RegisterRequest(email, password, name, userRole))

                when (result) {
                    is AuthResult.Success -> {
                        val userId = result.data.id

                        // Step 1: Create profile first (without device_fingerprint_id)
                        // This must happen BEFORE registerDevice because device_fingerprints.user_id
                        // has a FK to public.users.id
                        val profileResult = authRepository.createUserProfileWithTrial(
                            userId = userId,
                            name = name,
                            isProfessional = isProfessional,
                            organizationName = organizationName,
                            verifiedPhone = "",
                            deviceFingerprintId = null
                        )

                        when (profileResult) {
                            is AuthResult.Success -> {
                                // Step 2: Now register device (profile exists, FK constraint satisfied)
                                if (deviceId != null) {
                                    deviceFingerprintRepository.registerDevice(profileResult.data.id)
                                        .onSuccess { fingerprintId ->
                                            // Step 3: Update user profile with device_fingerprint_id
                                            viewModelScope.launch {
                                                val updatedUser = profileResult.data.copy(
                                                    deviceFingerprintId = fingerprintId
                                                )
                                                authRepository.updateUserProfile(updatedUser)
                                                MotiumApplication.logger.i(
                                                    "Device fingerprint linked: $fingerprintId",
                                                    "AuthViewModel"
                                                )
                                            }
                                        }
                                        .onFailure { e ->
                                            MotiumApplication.logger.w(
                                                "Failed to register device: ${e.message}",
                                                "AuthViewModel"
                                            )
                                        }
                                }

                                _registerState.value = _registerState.value.copy(
                                    isLoading = false,
                                    isSuccess = true
                                )

                                // Send welcome email (fire-and-forget)
                                viewModelScope.launch {
                                    try {
                                        emailRepository.sendWelcomeEmail(
                                            email, name,
                                            if (isProfessional) "ENTERPRISE" else "INDIVIDUAL"
                                        )
                                    } catch (e: Exception) {
                                        MotiumApplication.logger.w(
                                            "Failed to send welcome email: ${e.message}",
                                            "AuthViewModel"
                                        )
                                    }
                                }

                                MotiumApplication.logger.i(
                                    "Registration successful",
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

                    // Trigger Pro data sync for Enterprise users (in background)
                    triggerProDataSyncIfNeeded()
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

            // BATTERY OPTIMIZATION: Cleanup LicenseCacheManager background tasks
            LicenseCacheManager.getInstance(context).cleanup()

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

    /**
     * Save credentials to password manager (Samsung Pass, Google, etc.)
     * This runs in viewModelScope to survive composable recompositions.
     * The navigation will only happen after this completes.
     *
     * @param skipSave If true, skip the save dialog (e.g., credentials were auto-filled,
     *                 meaning they're already saved in the password manager)
     */
    fun saveCredentialsAndNavigate(
        activity: android.app.Activity,
        credentialManager: CredentialManagerHelper,
        skipSave: Boolean = false
    ) {
        val credentials = _loginState.value.credentialsToSave ?: return

        // If credentials were auto-filled, they're already in the password manager
        // Skip the save prompt to avoid annoying "save already saved account" dialogs
        if (skipSave) {
            MotiumApplication.logger.d("Skipping credential save (credentials were auto-filled)", "AuthViewModel")
            _loginState.value = _loginState.value.copy(
                credentialsToSave = null,
                isReadyToNavigate = true
            )
            return
        }

        // Mark as saving to prevent duplicate calls
        if (_loginState.value.isSavingCredentials) return
        _loginState.value = _loginState.value.copy(isSavingCredentials = true)

        viewModelScope.launch {
            try {
                MotiumApplication.logger.i("Starting credential save in ViewModel scope", "AuthViewModel")

                // This will show the Samsung Pass / Google / other password manager dialog
                // and wait for user interaction (save or dismiss)
                credentialManager.saveCredentials(
                    activity = activity,
                    email = credentials.email,
                    password = credentials.password
                )

                MotiumApplication.logger.i("Credential save completed", "AuthViewModel")
            } catch (e: Exception) {
                MotiumApplication.logger.w("Credential save failed: ${e.message}", "AuthViewModel")
            } finally {
                // Clear credentials and mark ready to navigate
                _loginState.value = _loginState.value.copy(
                    credentialsToSave = null,
                    isSavingCredentials = false,
                    isReadyToNavigate = true
                )
            }
        }
    }

    /**
     * Reset the navigation flag after navigating
     */
    fun onNavigated() {
        _loginState.value = _loginState.value.copy(isReadyToNavigate = false)
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

    /**
     * Force le rafraîchissement de l'état d'authentification depuis Supabase.
     * Utile après un paiement réussi pour mettre à jour le statut d'abonnement.
     */
    fun refreshAuthState(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                authRepository.refreshAuthState()
                MotiumApplication.logger.i("✅ Auth state refreshed", "AuthViewModel")
                onComplete()
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to refresh auth state: ${e.message}", "AuthViewModel", e)
                onComplete()
            }
        }
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
    val credentialsToSave: CredentialsToSave? = null,
    /** True while credentials are being saved to password manager (Samsung Pass, etc.) */
    val isSavingCredentials: Boolean = false,
    /** True when the full login flow is complete (auth + credential save) and ready to navigate */
    val isReadyToNavigate: Boolean = false
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

/**
 * State representing the license check status for Pro users.
 * Used to determine if a Pro user has a valid license assigned.
 */
sealed class ProLicenseState {
    /** Initial state before any check */
    data object Idle : ProLicenseState()
    /** License check in progress */
    data object Loading : ProLicenseState()
    /** Pro user has a valid license assigned */
    data object Licensed : ProLicenseState()
    /** Pro user exists but has no license assigned */
    data object NotLicensed : ProLicenseState()
    /** No pro account found for this user */
    data object NoProAccount : ProLicenseState()
    /** Error occurred during license check */
    data class Error(val message: String?) : ProLicenseState()
}
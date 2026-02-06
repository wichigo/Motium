package com.application.motium.presentation.pro.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.repository.OfflineFirstLicenseRepository
import com.application.motium.data.repository.OfflineFirstLinkedUserRepository
import com.application.motium.data.repository.OfflineFirstProAccountRepository
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.ProAccountRemoteDataSource
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * License status for display - based on whether a license is assigned
 */
enum class AccountLicenseStatus {
    LICENSED,      // License assigned and active
    UNLICENSED,    // No license assigned
    PENDING_UNLINK // License with pending unlink request
}

/**
 * Dialog/action state (non-reactive)
 */
data class LinkedAccountsDialogState(
    val error: String? = null,
    val successMessage: String? = null,
    val showInviteDialog: Boolean = false,
    val isInviting: Boolean = false,
    val selectedDepartments: Set<String> = emptySet()
)

/**
 * UI State for Linked Accounts screen
 */
data class LinkedAccountsUiState(
    val isLoading: Boolean = true,
    val linkedUsers: List<LinkedUserDto> = emptyList(),
    val userLicenses: Map<String, License?> = emptyMap(), // userId -> License
    val personalSubscriptionTypes: Map<String, String> = emptyMap(), // userId -> users.subscription_type
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

    /**
     * Get the license status for a user based on license assignment
     */
    fun getLicenseStatus(userId: String): AccountLicenseStatus {
        val license = userLicenses[userId]
        return when {
            license == null -> AccountLicenseStatus.UNLICENSED
            license.isPendingUnlink -> AccountLicenseStatus.PENDING_UNLINK
            else -> AccountLicenseStatus.LICENSED
        }
    }

    fun isPersonalLifetime(userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        return personalSubscriptionTypes[userId]?.equals("LIFETIME", ignoreCase = true) == true
    }
}

/**
 * ViewModel for managing linked accounts (Pro feature) - Offline-first architecture.
 * Uses OfflineFirstLicenseRepository for reactive license data, remote data sources for linked accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedAccountsViewModel(
    private val context: Context,
    // Offline-first repositories for reactive reads
    private val offlineFirstLicenseRepo: OfflineFirstLicenseRepository = OfflineFirstLicenseRepository.getInstance(context),
    private val offlineFirstProAccountRepo: OfflineFirstProAccountRepository = OfflineFirstProAccountRepository.getInstance(context),
    private val offlineFirstLinkedUserRepo: OfflineFirstLinkedUserRepository = OfflineFirstLinkedUserRepository.getInstance(context),
    // Remote data sources for write operations
    private val linkedAccountRemoteDataSource: LinkedAccountRemoteDataSource = LinkedAccountRemoteDataSource.getInstance(context),
    private val proAccountRemoteDataSource: ProAccountRemoteDataSource = ProAccountRemoteDataSource.getInstance(context),
    private val licenseRemoteDataSource: LicenseRemoteDataSource = LicenseRemoteDataSource.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
) : ViewModel() {

    companion object {
        private const val TAG = "LinkedAccountsVM"
    }

    // Pro account ID flow
    private val _proAccountId = MutableStateFlow<String?>(null)

    // Dialog/action state (non-reactive)
    private val _dialogState = MutableStateFlow(LinkedAccountsDialogState())

    // Loading state for initial data load
    private val _isInitialLoading = MutableStateFlow(true)
    private val _personalSubscriptionTypes = MutableStateFlow<Map<String, String>>(emptyMap())

    // Reactive linked users flow from offline-first repository (cache-first)
    private val linkedUsersFlow = _proAccountId.flatMapLatest { proAccountId ->
        if (proAccountId != null) {
            offlineFirstLinkedUserRepo.getLinkedUsers(proAccountId)
        } else {
            flowOf(emptyList())
        }
    }

    // Reactive licenses flow from offline-first repository
    private val licensesFlow = _proAccountId.flatMapLatest { proAccountId ->
        if (proAccountId != null) {
            offlineFirstLicenseRepo.getLicensesByProAccount(proAccountId)
        } else {
            flowOf(emptyList())
        }
    }

    private val dialogAndSubscriptionFlow = combine(
        _dialogState,
        _personalSubscriptionTypes
    ) { dialogState, personalSubscriptionTypes ->
        dialogState to personalSubscriptionTypes
    }

    // Combined UI state
    val uiState: StateFlow<LinkedAccountsUiState> = combine(
        linkedUsersFlow,
        licensesFlow,
        dialogAndSubscriptionFlow,
        _proAccountId,
        _isInitialLoading
    ) { linkedUsers, licenses, dialogAndSubscription, proAccountId, isInitialLoading ->
        val dialogState = dialogAndSubscription.first
        val personalSubscriptionTypes = dialogAndSubscription.second

        // Build userLicenses map from licenses
        val userLicenses = licenses
            .filter { it.isAssigned && it.linkedAccountId != null }
            .associateBy({ it.linkedAccountId!! }, { it })

        // Extract unique departments
        val departments = linkedUsers
            .map { it.department ?: "Sans département" }
            .distinct()
            .sorted()

        // If no departments selected yet, select all
        val selectedDepts = if (dialogState.selectedDepartments.isEmpty() && departments.isNotEmpty()) {
            departments.toSet()
        } else {
            dialogState.selectedDepartments
        }

        LinkedAccountsUiState(
            // Show loading only during initial load, not during background refresh
            isLoading = proAccountId == null && isInitialLoading,
            linkedUsers = linkedUsers,
            userLicenses = userLicenses,
            personalSubscriptionTypes = personalSubscriptionTypes,
            availableDepartments = departments,
            selectedDepartments = selectedDepts,
            error = dialogState.error,
            successMessage = dialogState.successMessage,
            showInviteDialog = dialogState.showInviteDialog,
            isInviting = dialogState.isInviting
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LinkedAccountsUiState()
    )

    init {
        loadProAccountId()
    }

    /**
     * Load pro account ID using cache-first pattern.
     * Tries local cache first, falls back to remote if needed.
     */
    private fun loadProAccountId() {
        viewModelScope.launch {
            try {
                val currentUser = authRepository.authState.first().user
                if (currentUser == null) {
                    _dialogState.update { it.copy(
                        error = "Utilisateur non connecté"
                    )}
                    _isInitialLoading.value = false
                    return@launch
                }

                // Try local cache first (offline-first)
                val localProAccount = offlineFirstProAccountRepo.getProAccountForUserOnce(currentUser.id)
                if (localProAccount != null) {
                    MotiumApplication.logger.d("Found ProAccount in local cache: ${localProAccount.id}", TAG)
                    _proAccountId.value = localProAccount.id
                    refreshPersonalSubscriptionTypes(localProAccount.id)
                    _isInitialLoading.value = false
                    return@launch
                }

                // Fall back to remote API
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId != null) {
                    _proAccountId.value = proAccountId
                    refreshPersonalSubscriptionTypes(proAccountId)
                } else {
                    _dialogState.update { it.copy(
                        error = "Compte Pro non trouvé. Veuillez configurer votre compte professionnel."
                    )}
                }
                _isInitialLoading.value = false
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading Pro account ID: ${e.message}", TAG, e)
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
                _isInitialLoading.value = false
            }
        }
    }

    /**
     * Refresh linked users from the server.
     * The Flow subscription (linkedUsersFlow) handles automatic updates.
     * This is for manual refresh / pull-to-refresh scenarios.
     */
    fun loadLinkedUsers() {
        viewModelScope.launch {
            val proAccountId = _proAccountId.value ?: return@launch

            try {
                // Force refresh from server - updates will come through the Flow
                val result = offlineFirstLinkedUserRepo.forceRefresh(proAccountId)
                result.fold(
                    onSuccess = { users ->
                        updatePersonalSubscriptionTypes(users)
                        MotiumApplication.logger.d("Refreshed ${users.size} linked users", TAG)
                    },
                    onFailure = { e ->
                        // Don't show error for network issues - cached data is already displayed
                        MotiumApplication.logger.w("Failed to refresh linked users (using cache): ${e.message}", TAG)
                    }
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error refreshing linked users: ${e.message}", TAG, e)
            }
        }
    }

    private fun refreshPersonalSubscriptionTypes(proAccountId: String) {
        viewModelScope.launch {
            linkedAccountRemoteDataSource.getLinkedUsers(proAccountId).onSuccess { users ->
                updatePersonalSubscriptionTypes(users)
            }
        }
    }

    private fun updatePersonalSubscriptionTypes(users: List<LinkedUserDto>) {
        val typesByUserId = users
            .mapNotNull { user ->
                val userId = user.userId
                val type = user.personalSubscriptionType
                if (!userId.isNullOrBlank() && !type.isNullOrBlank()) {
                    userId to type
                } else {
                    null
                }
            }
            .toMap()

        _personalSubscriptionTypes.value = typesByUserId
    }

    /**
     * Show the invite dialog
     */
    fun showInviteDialog() {
        _dialogState.update { it.copy(showInviteDialog = true) }
    }

    /**
     * Hide the invite dialog
     */
    fun hideInviteDialog() {
        _dialogState.update { it.copy(showInviteDialog = false) }
    }

    /**
     * Send an invitation to link a user
     */
    fun inviteUser(email: String) {
        viewModelScope.launch {
            _dialogState.update { it.copy(isInviting = true) }

            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(
                        isInviting = false,
                        error = "Compte Pro non trouvé"
                    )}
                    return@launch
                }

                // Get company name from pro account
                val authState = authRepository.authState.first()
                val currentUser = authState.user
                if (currentUser == null) {
                    _dialogState.update { it.copy(
                        isInviting = false,
                        error = "Utilisateur non connecté"
                    )}
                    return@launch
                }

                // Try to get company name from local first, then remote
                val localProAccount = offlineFirstProAccountRepo.getProAccountForUserOnce(currentUser.id)
                val companyName = localProAccount?.companyName
                    ?: proAccountRemoteDataSource.getProAccount(currentUser.id).getOrNull()?.companyName
                if (companyName == null) {
                    _dialogState.update { it.copy(
                        isInviting = false,
                        error = "Nom de l'entreprise non trouvé"
                    )}
                    return@launch
                }

                val result = linkedAccountRemoteDataSource.inviteUser(proAccountId, companyName, email)
                result.fold(
                    onSuccess = { userId ->
                        val message = if (userId != null) {
                            "Invitation envoyée à $email"
                        } else {
                            "Utilisateur non trouvé. L'invitation sera envoyée par email."
                        }
                        _dialogState.update { it.copy(
                            isInviting = false,
                            showInviteDialog = false,
                            successMessage = message
                        )}
                        loadLinkedUsers()
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.e("Failed to invite user: ${e.message}", TAG, e)
                        _dialogState.update { it.copy(
                            isInviting = false,
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                _dialogState.update { it.copy(
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
                val result = linkedAccountRemoteDataSource.revokeUser(userId)
                result.fold(
                    onSuccess = {
                        _dialogState.update { it.copy(
                            successMessage = "Accès révoqué"
                        )}
                        loadLinkedUsers()
                    },
                    onFailure = { e ->
                        _dialogState.update { it.copy(
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    /**
     * Resend invitation email to a pending user
     */
    fun resendInvitation(user: LinkedUserDto) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouvé") }
                    return@launch
                }

                // Get company name
                val authState = authRepository.authState.first()
                val currentUser = authState.user ?: return@launch

                val localProAccount = offlineFirstProAccountRepo.getProAccountForUserOnce(currentUser.id)
                val companyName = localProAccount?.companyName
                    ?: proAccountRemoteDataSource.getProAccount(currentUser.id).getOrNull()?.companyName
                if (companyName == null) {
                    _dialogState.update { it.copy(error = "Nom de l'entreprise non trouvé") }
                    return@launch
                }

                val result = linkedAccountRemoteDataSource.resendInvitation(
                    companyLinkId = user.linkId,
                    companyName = companyName,
                    email = user.userEmail,
                    userName = user.userName ?: user.userEmail  // Fallback to email if no name
                )
                result.fold(
                    onSuccess = {
                        _dialogState.update { it.copy(
                            successMessage = "Invitation renvoyée à ${user.userEmail}"
                        )}
                    },
                    onFailure = { e ->
                        _dialogState.update { it.copy(
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to resend invitation: ${e.message}", TAG, e)
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    /**
     * Delete a linked account.
     * If the account had a license assigned, the license will be set to pending unlink.
     * The linked user loses Pro access and reverts to TRIAL/EXPIRED (unless they have their own subscription).
     */
    fun deleteLinkedAccount(user: LinkedUserDto) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouvé") }
                    return@launch
                }

                // Check if user has an assigned license
                val userId = user.userId
                val license = if (userId != null) {
                    uiState.value.userLicenses[userId]
                } else null

                // If licensed, first request unlink on the license
                if (license != null && !license.isPendingUnlink) {
                    MotiumApplication.logger.i("User has license ${license.id}, requesting unlink before deletion", TAG)
                    val unlinkResult = licenseRemoteDataSource.requestUnlink(license.id, proAccountId)
                    unlinkResult.onFailure { e ->
                        MotiumApplication.logger.w("Failed to request license unlink: ${e.message}", TAG)
                        // Continue with deletion anyway
                    }
                }

                // Delete the company link
                val result = linkedAccountRemoteDataSource.deleteLinkedAccount(user.linkId)
                result.fold(
                    onSuccess = {
                        val message = if (license != null) {
                            "Compte supprimé. La licence sera libérée à la date de renouvellement."
                        } else {
                            "Compte supprimé"
                        }
                        _dialogState.update { it.copy(successMessage = message) }
                        loadLinkedUsers()
                    },
                    onFailure = { e ->
                        _dialogState.update { it.copy(
                            error = "Erreur: ${e.message}"
                        )}
                    }
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to delete linked account: ${e.message}", TAG, e)
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _dialogState.update { it.copy(error = null) }
    }

    /**
     * Clear success message
     */
    fun clearSuccessMessage() {
        _dialogState.update { it.copy(successMessage = null) }
    }

    /**
     * Toggle selection for a department
     */
    fun toggleDepartmentSelection(department: String) {
        _dialogState.update { state ->
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
        val currentState = uiState.value
        _dialogState.update { state ->
            if (currentState.allDepartmentsSelected) {
                state.copy(selectedDepartments = emptySet())
            } else {
                state.copy(selectedDepartments = currentState.availableDepartments.toSet())
            }
        }
    }

    /**
     * Get count of users by license status (from filtered users)
     */
    fun getLicensedCount(): Int = uiState.value.filteredUsers.count { user ->
        user.userId?.let { uiState.value.getLicenseStatus(it) } == AccountLicenseStatus.LICENSED
    }
    fun getUnlicensedCount(): Int = uiState.value.filteredUsers.count { user ->
        user.userId?.let { uiState.value.getLicenseStatus(it) } == AccountLicenseStatus.UNLICENSED
    }

    // Keep old methods for backwards compatibility if needed
    fun getActiveCount(): Int = getLicensedCount()
    fun getPendingCount(): Int = getUnlicensedCount()
}

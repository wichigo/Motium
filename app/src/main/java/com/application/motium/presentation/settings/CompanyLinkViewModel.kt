package com.application.motium.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.CompanyLinkRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.CompanyLink
import com.application.motium.domain.model.CompanyLinkPreferences
import com.application.motium.domain.model.LinkStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing company links in Settings screen.
 */
class CompanyLinkViewModel(
    private val context: Context,
    private val companyLinkRepository: CompanyLinkRepository = CompanyLinkRepository.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context),
    private val localUserRepository: LocalUserRepository = LocalUserRepository.getInstance(context)
) : ViewModel() {

    companion object {
        private const val TAG = "CompanyLinkViewModel"
    }

    // UI State
    private val _uiState = MutableStateFlow(CompanyLinkUiState())
    val uiState: StateFlow<CompanyLinkUiState> = _uiState.asStateFlow()

    // Dialog states
    private val _showUnlinkConfirmation = MutableStateFlow<CompanyLink?>(null)
    val showUnlinkConfirmation: StateFlow<CompanyLink?> = _showUnlinkConfirmation.asStateFlow()

    private val _showActivationDialog = MutableStateFlow<String?>(null)
    val showActivationDialog: StateFlow<String?> = _showActivationDialog.asStateFlow()

    private val _activationResult = MutableStateFlow<LinkActivationResult?>(null)
    val activationResult: StateFlow<LinkActivationResult?> = _activationResult.asStateFlow()

    // Cache the current user ID for synchronous access
    private var currentUserId: String? = null

    init {
        observeAuthAndLoadLinks()
    }

    private fun observeAuthAndLoadLinks() {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                // Les utilisateurs Pro (ENTERPRISE) n'ont pas de company links
                // Ils SONT l'entreprise qui crée les liens avec les utilisateurs individuels
                val isEnterprise = authState.user?.role?.name == "ENTERPRISE"
                if (isEnterprise) {
                    MotiumApplication.logger.d("Skipping company links load for ENTERPRISE user", TAG)
                    _uiState.update { it.copy(companyLinks = emptyList(), isLoading = false) }
                    return@collect
                }

                // Utiliser localUserRepository pour obtenir le bon users.id (compatible RLS)
                val userId = if (authState.user != null && authState.isAuthenticated) {
                    localUserRepository.getLoggedInUser()?.id
                } else {
                    null
                }
                currentUserId = userId
                if (userId != null) {
                    loadCompanyLinks(userId)
                } else {
                    _uiState.update { it.copy(companyLinks = emptyList(), isLoading = false) }
                }
            }
        }
    }

    /**
     * Load company links for the current user.
     */
    fun loadCompanyLinks(userId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val links = companyLinkRepository.getCompanyLinks(userId)
                _uiState.update { it.copy(companyLinks = links, isLoading = false) }
                MotiumApplication.logger.i("Loaded ${links.size} company links", TAG)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading company links: ${e.message}", TAG, e)
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    /**
     * Observe company links as a Flow for reactive updates.
     */
    fun observeCompanyLinks(userId: String): Flow<List<CompanyLink>> {
        return companyLinkRepository.getCompanyLinksFlow(userId)
    }

    /**
     * Handle a pending deep link token.
     * Shows activation dialog if token is present.
     */
    fun handlePendingToken(token: String?) {
        if (token != null) {
            _showActivationDialog.value = token
        }
    }

    /**
     * Activate a company link using an invitation token.
     */
    fun activateLinkByToken(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val userId = currentUserId
            if (userId == null) {
                _activationResult.value = LinkActivationResult.Error("Utilisateur non authentifié")
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val result = companyLinkRepository.activateLinkByToken(token, userId)

            result.fold(
                onSuccess = { link ->
                    _activationResult.value = LinkActivationResult.Success(link.companyName)
                    // Reload links to show the new one
                    loadCompanyLinks(userId)
                    MotiumApplication.logger.i("Successfully activated link with ${link.companyName}", TAG)
                },
                onFailure = { error ->
                    val errorMessage = error.message ?: "Erreur inconnue"
                    val pendingLink = _uiState.value.companyLinks.firstOrNull {
                        it.status == LinkStatus.PENDING && it.invitationToken == token
                    }

                    // Fallback for stale/expired invitation token:
                    // if the link is already visible to this authenticated user, activate by link ID.
                    if (pendingLink != null && shouldFallbackToDirectActivation(errorMessage)) {
                        val fallbackResult = companyLinkRepository.activatePendingLinkDirectly(
                            linkId = pendingLink.id,
                            userId = userId
                        )
                        fallbackResult.fold(
                            onSuccess = { link ->
                                _activationResult.value = LinkActivationResult.Success(link.companyName)
                                loadCompanyLinks(userId)
                                MotiumApplication.logger.i("Activated link via direct fallback for ${link.companyName}", TAG)
                            },
                            onFailure = { fallbackError ->
                                _activationResult.value = LinkActivationResult.Error(
                                    fallbackError.message ?: "Impossible d'activer la liaison."
                                )
                                _uiState.update { it.copy(isLoading = false) }
                                MotiumApplication.logger.e("Direct activation fallback failed: ${fallbackError.message}", TAG)
                            }
                        )
                    } else {
                        val friendlyMessage = if (shouldFallbackToDirectActivation(errorMessage)) {
                            "Invitation expirée. Demandez à votre entreprise de renvoyer l'invitation."
                        } else {
                            errorMessage
                        }
                        _activationResult.value = LinkActivationResult.Error(friendlyMessage)
                        _uiState.update { it.copy(isLoading = false) }
                        MotiumApplication.logger.e("Failed to activate link: $errorMessage", TAG)
                    }
                }
            )

            // Clear the activation dialog
            _showActivationDialog.value = null
        }
    }

    private fun shouldFallbackToDirectActivation(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("token invalide") ||
            normalized.contains("token invalid") ||
            normalized.contains("invalid token") ||
            normalized.contains("already used") ||
            normalized.contains("expire")
    }

    /**
     * Dismiss the activation dialog without activating.
     */
    fun dismissActivationDialog() {
        _showActivationDialog.value = null
    }

    /**
     * Clear the activation result (after showing success/error message).
     */
    fun clearActivationResult() {
        _activationResult.value = null
    }

    /**
     * Update sharing preferences for a company link.
     */
    fun updateSharingPreferences(linkId: String, preferences: CompanyLinkPreferences) {
        viewModelScope.launch {
            val result = companyLinkRepository.updateSharingPreferences(linkId, preferences)

            result.fold(
                onSuccess = {
                    // Update local state
                    _uiState.update { state ->
                        val updatedLinks = state.companyLinks.map { link ->
                            if (link.id == linkId) {
                                link.copy(
                                    shareProfessionalTrips = preferences.shareProfessionalTrips,
                                    sharePersonalTrips = preferences.sharePersonalTrips,
                                    sharePersonalInfo = preferences.sharePersonalInfo
                                )
                            } else link
                        }
                        state.copy(companyLinks = updatedLinks)
                    }
                    MotiumApplication.logger.i("Updated sharing preferences for link $linkId", TAG)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message) }
                    MotiumApplication.logger.e("Failed to update preferences: ${error.message}", TAG)
                }
            )
        }
    }

    /**
     * Request to show unlink confirmation dialog.
     */
    fun requestUnlink(link: CompanyLink) {
        _showUnlinkConfirmation.value = link
    }

    /**
     * Confirm unlinking from a company.
     * This initiates the confirmation flow - an email will be sent to confirm the unlink.
     */
    fun confirmUnlink() {
        val link = _showUnlinkConfirmation.value ?: return
        _showUnlinkConfirmation.value = null

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = companyLinkRepository.requestUnlink(link.id)

            result.fold(
                onSuccess = {
                    // Update local state to show PENDING_UNLINK status
                    // The actual INACTIVE status will be set when user confirms via email
                    _uiState.update { state ->
                        val updatedLinks = state.companyLinks.map { l ->
                            if (l.id == link.id) {
                                l.copy(status = LinkStatus.PENDING_UNLINK)
                            } else l
                        }
                        state.copy(
                            companyLinks = updatedLinks,
                            isLoading = false,
                            successMessage = "Un email de confirmation a ete envoye a votre adresse email."
                        )
                    }
                    MotiumApplication.logger.i("Unlink confirmation requested for ${link.companyName}", TAG)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = error.message, isLoading = false) }
                    MotiumApplication.logger.e("Failed to request unlink: ${error.message}", TAG)
                }
            )
        }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    /**
     * Cancel unlink confirmation dialog.
     */
    fun cancelUnlink() {
        _showUnlinkConfirmation.value = null
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Sync company links with Supabase.
     */
    fun syncCompanyLinks() {
        viewModelScope.launch {
            val userId = currentUserId ?: return@launch

            companyLinkRepository.syncFromSupabase(userId)
            companyLinkRepository.syncToSupabase(userId)
            loadCompanyLinks(userId)
        }
    }
}

/**
 * UI state for company links screen.
 */
data class CompanyLinkUiState(
    val companyLinks: List<CompanyLink> = emptyList(),
    val isLoading: Boolean = true, // Start with true to show loading indicator immediately
    val error: String? = null,
    val successMessage: String? = null
) {
    val hasActiveLinks: Boolean
        get() = companyLinks.any { it.status == LinkStatus.ACTIVE }

    val activeLinksCount: Int
        get() = companyLinks.count { it.status == LinkStatus.ACTIVE }

    val hasPendingUnlinks: Boolean
        get() = companyLinks.any { it.status == LinkStatus.PENDING_UNLINK }
}

/**
 * Result of link activation attempt.
 */
sealed class LinkActivationResult {
    data class Success(val companyName: String) : LinkActivationResult()
    data class Error(val message: String) : LinkActivationResult()
}



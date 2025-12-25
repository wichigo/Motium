package com.application.motium.presentation.pro.licenses

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.repository.OfflineFirstLicenseRepository
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.ProAccountRemoteDataSource
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicensesSummary
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for Licenses screen (dialog/action state only - licenses come from reactive Flow)
 */
data class LicensesDialogState(
    val error: String? = null,
    val successMessage: String? = null,
    val showPurchaseDialog: Boolean = false,
    val isPurchasing: Boolean = false,
    // Stripe Payment state (legacy)
    val paymentReady: PaymentReadyState? = null,
    // Stripe Deferred Payment state (preferred)
    val deferredPaymentReady: DeferredPaymentReadyState? = null,
    val pendingPurchase: PendingPurchase? = null,
    val isRefreshingAfterPayment: Boolean = false,
    // Pro account ID for payments
    val proAccountId: String? = null,
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
 * Combined UI State for Licenses screen
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
    // Stripe Payment state (legacy)
    val paymentReady: PaymentReadyState? = null,
    // Stripe Deferred Payment state (preferred)
    val deferredPaymentReady: DeferredPaymentReadyState? = null,
    val pendingPurchase: PendingPurchase? = null,
    val isRefreshingAfterPayment: Boolean = false,
    // Pro account ID for payments
    val proAccountId: String? = null,
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
 * State when payment is ready to present via PaymentSheet (legacy mode)
 */
data class PaymentReadyState(
    val clientSecret: String,
    val customerId: String,
    val ephemeralKey: String?,
    val amountCents: Int
)

/**
 * State for deferred payment (IntentConfiguration mode)
 */
data class DeferredPaymentReadyState(
    val amountCents: Long,
    val priceType: String,
    val quantity: Int
)

/**
 * Tracks a pending purchase before and during payment
 */
data class PendingPurchase(
    val quantity: Int,
    val isLifetime: Boolean
)

/**
 * ViewModel for managing licenses - Offline-first architecture.
 * Reads from local Room database via Flow (through LicenseCacheManager), writes to Supabase with background sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LicensesViewModel(
    private val context: Context,
    // Cache-first manager for reactive reads with background refresh
    private val licenseCacheManager: com.application.motium.data.repository.LicenseCacheManager = com.application.motium.data.repository.LicenseCacheManager.getInstance(context),
    // Offline-first repository for reactive reads (legacy, still used for some operations)
    private val offlineFirstLicenseRepo: OfflineFirstLicenseRepository = OfflineFirstLicenseRepository.getInstance(context),
    // Remote data sources for write operations requiring server validation
    private val licenseRemoteDataSource: LicenseRemoteDataSource = LicenseRemoteDataSource.getInstance(context),
    private val linkedAccountRemoteDataSource: LinkedAccountRemoteDataSource = LinkedAccountRemoteDataSource.getInstance(context),
    private val proAccountRemoteDataSource: ProAccountRemoteDataSource = ProAccountRemoteDataSource.getInstance(context),
    private val authRepository: SupabaseAuthRepository = SupabaseAuthRepository.getInstance(context),
    private val localUserRepository: LocalUserRepository = LocalUserRepository.getInstance(context),
    private val subscriptionManager: SubscriptionManager = SubscriptionManager.getInstance(context)
) : ViewModel() {

    companion object {
        private const val TAG = "LicensesViewModel"
    }

    // Dialog/action state (non-reactive)
    private val _dialogState = MutableStateFlow(LicensesDialogState())

    // Pro account ID flow
    private val _proAccountId = MutableStateFlow<String?>(null)

    // Reactive licenses flow from cache-first manager (Room DB with background network refresh)
    private val licensesFlow = _proAccountId.flatMapLatest { proAccountId ->
        if (proAccountId != null) {
            licenseCacheManager.getLicensesByProAccount(proAccountId)
        } else {
            flowOf(emptyList())
        }
    }

    // Combined UI state: reactive licenses + dialog state
    val uiState: StateFlow<LicensesUiState> = combine(
        licensesFlow,
        _dialogState,
        _proAccountId
    ) { licenses, dialogState, proAccountId ->
        LicensesUiState(
            isLoading = proAccountId == null,
            licenses = licenses,
            summary = LicensesSummary.fromLicenses(licenses),
            error = dialogState.error,
            successMessage = dialogState.successMessage,
            showPurchaseDialog = dialogState.showPurchaseDialog,
            isPurchasing = dialogState.isPurchasing,
            paymentReady = dialogState.paymentReady,
            deferredPaymentReady = dialogState.deferredPaymentReady,
            pendingPurchase = dialogState.pendingPurchase,
            isRefreshingAfterPayment = dialogState.isRefreshingAfterPayment,
            proAccountId = dialogState.proAccountId ?: proAccountId,
            showAssignDialog = dialogState.showAssignDialog,
            selectedLicenseForAssignment = dialogState.selectedLicenseForAssignment,
            linkedAccountsForAssignment = dialogState.linkedAccountsForAssignment,
            isLoadingAccounts = dialogState.isLoadingAccounts,
            showUnlinkConfirmDialog = dialogState.showUnlinkConfirmDialog,
            licenseToUnlink = dialogState.licenseToUnlink,
            isUnlinking = dialogState.isUnlinking
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LicensesUiState()
    )

    init {
        loadProAccountId()
    }

    /**
     * Load pro account ID to start the licenses flow
     */
    private fun loadProAccountId() {
        viewModelScope.launch {
            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId != null) {
                    _proAccountId.value = proAccountId
                    MotiumApplication.logger.d("Pro account ID loaded: $proAccountId", TAG)
                } else {
                    _dialogState.update { it.copy(error = "Compte Pro non trouvé") }
                }
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    /**
     * Force refresh from server (triggers delta sync).
     * Uses LicenseCacheManager for cache-first pattern with graceful offline handling.
     */
    fun refreshLicenses() {
        viewModelScope.launch {
            val proAccountId = _proAccountId.value ?: return@launch
            try {
                // Force refresh through cache manager (updates local DB)
                val result = licenseCacheManager.forceRefresh(proAccountId)
                result.onFailure { e ->
                    MotiumApplication.logger.e("Failed to refresh licenses: ${e.message}", TAG, e)
                    // Don't show error to user - cache-first means we keep showing cached data
                }
                // Flow will automatically update when local DB changes
            } catch (e: Exception) {
                MotiumApplication.logger.e("Refresh error: ${e.message}", TAG, e)
            }
        }
    }

    /**
     * Show purchase dialog
     */
    fun showPurchaseDialog() {
        _dialogState.update { it.copy(showPurchaseDialog = true) }
    }

    /**
     * Hide purchase dialog
     */
    fun hidePurchaseDialog() {
        _dialogState.update { it.copy(showPurchaseDialog = false) }
    }

    /**
     * Purchase licenses via Stripe (monthly or lifetime) - Deferred payment mode.
     * Opens PaymentSheet immediately without pre-creating a PaymentIntent.
     */
    fun purchaseLicenses(quantity: Int, isLifetime: Boolean = false) {
        viewModelScope.launch {
            _dialogState.update { it.copy(isPurchasing = true) }

            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(
                        isPurchasing = false,
                        error = "Compte Pro non trouve"
                    )}
                    return@launch
                }

                val priceType = if (isLifetime) "pro_license_lifetime" else "pro_license_monthly"
                val amountCents = subscriptionManager.getAmountCents(priceType, quantity)

                MotiumApplication.logger.i("Initializing deferred license payment: $quantity x $priceType (${amountCents / 100.0}€)", TAG)

                // Store pending purchase and open PaymentSheet
                _dialogState.update { it.copy(
                    isPurchasing = false,
                    showPurchaseDialog = false,
                    pendingPurchase = PendingPurchase(quantity, isLifetime),
                    proAccountId = proAccountId,
                    deferredPaymentReady = DeferredPaymentReadyState(
                        amountCents = amountCents,
                        priceType = priceType,
                        quantity = quantity
                    )
                )}

            } catch (e: Exception) {
                MotiumApplication.logger.e("Purchase error: ${e.message}", TAG, e)
                _dialogState.update { it.copy(
                    isPurchasing = false,
                    pendingPurchase = null,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Called from PaymentSheet's createIntentCallback to create the PaymentIntent on the server.
     */
    suspend fun confirmPayment(paymentMethodId: String): Result<String> {
        val state = _dialogState.value
        val proAccountId = state.proAccountId ?: _proAccountId.value
            ?: return Result.failure(Exception("Compte Pro non trouvé"))
        val deferredState = state.deferredPaymentReady
            ?: return Result.failure(Exception("Payment not initialized"))

        return subscriptionManager.confirmPaymentWithMethod(
            paymentMethodId = paymentMethodId,
            userId = null,
            proAccountId = proAccountId,
            priceType = deferredState.priceType,
            quantity = deferredState.quantity
        )
    }

    /**
     * Handle PaymentSheet result
     */
    fun handlePaymentResult(result: PaymentSheetResult) {
        when (result) {
            is PaymentSheetResult.Completed -> {
                _dialogState.update { it.copy(
                    paymentReady = null,
                    deferredPaymentReady = null,
                    isRefreshingAfterPayment = true
                )}
                MotiumApplication.logger.i("Payment completed successfully", TAG)
                // After successful payment, poll for new licenses from server
                refreshLicensesAfterPayment()
            }
            is PaymentSheetResult.Canceled -> {
                _dialogState.update { it.copy(
                    paymentReady = null,
                    deferredPaymentReady = null,
                    pendingPurchase = null
                )}
                MotiumApplication.logger.i("Payment was canceled", TAG)
            }
            is PaymentSheetResult.Failed -> {
                _dialogState.update { it.copy(
                    paymentReady = null,
                    deferredPaymentReady = null,
                    pendingPurchase = null,
                    error = result.error.message ?: "Le paiement a echoue"
                )}
                MotiumApplication.logger.e("Payment failed: ${result.error.message}", TAG, result.error)
            }
        }
    }

    /**
     * Refresh licenses after successful payment.
     * Polls server as webhook may have short delay, then updates local DB.
     * Uses cache-first pattern with fallback to local DB.
     */
    private fun refreshLicensesAfterPayment() {
        viewModelScope.launch {
            val pendingPurchase = _dialogState.value.pendingPurchase
            var attempts = 0
            val maxAttempts = 5
            val delayMs = 2000L

            val initialLicenseCount = uiState.value.licenses.size
            val expectedNewLicenses = pendingPurchase?.quantity ?: 0
            val proAccountId = _proAccountId.value ?: return@launch

            while (attempts < maxAttempts) {
                delay(delayMs)
                attempts++

                try {
                    // Try fetching from server via cache manager
                    val result = licenseCacheManager.forceRefresh(proAccountId)

                    result.onSuccess { licenses ->
                        // Check if new licenses have been created
                        if (licenses.size >= initialLicenseCount + expectedNewLicenses) {
                            val typeText = if (pendingPurchase?.isLifetime == true) "a vie" else "mensuelles"
                            _dialogState.update { it.copy(
                                isRefreshingAfterPayment = false,
                                pendingPurchase = null,
                                successMessage = "${pendingPurchase?.quantity ?: expectedNewLicenses} licence(s) $typeText creee(s) avec succes"
                            )}
                            MotiumApplication.logger.i("Licenses created after payment: ${licenses.size}", TAG)
                            return@launch
                        }
                    }

                    // Also check local DB in case webhook already updated it
                    val localLicenses = licenseCacheManager.getLicensesByProAccountOnce(proAccountId)
                    if (localLicenses.size >= initialLicenseCount + expectedNewLicenses) {
                        val typeText = if (pendingPurchase?.isLifetime == true) "a vie" else "mensuelles"
                        _dialogState.update { it.copy(
                            isRefreshingAfterPayment = false,
                            pendingPurchase = null,
                            successMessage = "${pendingPurchase?.quantity ?: expectedNewLicenses} licence(s) $typeText creee(s) avec succes"
                        )}
                        MotiumApplication.logger.i("Licenses found in local DB: ${localLicenses.size}", TAG)
                        return@launch
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Failed to refresh licenses: ${e.message}", TAG, e)
                }
            }

            // After max attempts, show generic success message
            _dialogState.update { it.copy(
                isRefreshingAfterPayment = false,
                pendingPurchase = null,
                successMessage = "Paiement reussi. Vos licences seront disponibles sous peu."
            )}
            MotiumApplication.logger.w("License refresh timed out, may need manual refresh", TAG)
        }
    }

    /**
     * Cancel a license (monthly only) - requires server validation
     */
    fun cancelLicense(licenseId: String) {
        viewModelScope.launch {
            try {
                val result = licenseRemoteDataSource.cancelLicense(licenseId)
                result.fold(
                    onSuccess = {
                        _dialogState.update { it.copy(
                            successMessage = "Licence annulee"
                        )}
                        // Flow will auto-update when sync pulls changes
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

    // ========================================
    // Assignment Dialog Methods
    // ========================================

    /**
     * Show assignment dialog for an available license
     */
    fun showAssignmentDialog(license: License) {
        _dialogState.update { it.copy(
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
        _dialogState.update { it.copy(
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
                val proAccountId = _proAccountId.value ?: return@launch

                // Get all linked accounts from remote
                val linkedUsersResult = linkedAccountRemoteDataSource.getLinkedUsers(proAccountId)
                val licenses = uiState.value.licenses

                if (linkedUsersResult.isSuccess) {
                    val linkedUsers = linkedUsersResult.getOrDefault(emptyList())

                    // Get IDs of accounts that already have a license
                    val licensedAccountIds = licenses
                        .filter { it.isAssigned }
                        .mapNotNull { it.linkedAccountId }
                        .toSet()

                    // Filter to only show accounts without a license
                    val unlicensedAccounts = linkedUsers.filter { user ->
                        user.userId !in licensedAccountIds && user.isActive
                    }

                    _dialogState.update { it.copy(
                        linkedAccountsForAssignment = unlicensedAccounts,
                        isLoadingAccounts = false
                    )}
                } else {
                    _dialogState.update { it.copy(
                        isLoadingAccounts = false,
                        error = "Erreur lors du chargement des comptes"
                    )}
                }
            } catch (e: Exception) {
                _dialogState.update { it.copy(
                    isLoadingAccounts = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Assign a license to a linked account - requires server validation
     */
    fun assignLicenseToAccount(licenseId: String, linkedAccountId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                val result = licenseRemoteDataSource.assignLicenseToAccount(
                    licenseId = licenseId,
                    proAccountId = proAccountId,
                    linkedAccountId = linkedAccountId
                )

                result.fold(
                    onSuccess = {
                        _dialogState.update { it.copy(
                            showAssignDialog = false,
                            selectedLicenseForAssignment = null,
                            successMessage = "Licence assignee avec succes"
                        )}
                        // Flow will auto-update when sync pulls changes
                    },
                    onFailure = { e ->
                        _dialogState.update { it.copy(
                            error = e.message ?: "Erreur lors de l'assignation"
                        )}
                    }
                )
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
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
        _dialogState.update { it.copy(
            showUnlinkConfirmDialog = true,
            licenseToUnlink = license
        )}
    }

    /**
     * Hide unlink confirmation dialog
     */
    fun hideUnlinkConfirmDialog() {
        _dialogState.update { it.copy(
            showUnlinkConfirmDialog = false,
            licenseToUnlink = null
        )}
    }

    /**
     * Request to unlink a license (starts 30-day notice period) - requires server
     */
    fun confirmUnlinkRequest() {
        val license = _dialogState.value.licenseToUnlink ?: return

        viewModelScope.launch {
            _dialogState.update { it.copy(isUnlinking = true) }

            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(
                        isUnlinking = false,
                        error = "Compte Pro non trouve"
                    )}
                    return@launch
                }

                val result = licenseRemoteDataSource.requestUnlink(
                    licenseId = license.id,
                    proAccountId = proAccountId
                )

                result.fold(
                    onSuccess = {
                        _dialogState.update { it.copy(
                            isUnlinking = false,
                            showUnlinkConfirmDialog = false,
                            licenseToUnlink = null,
                            successMessage = "Demande de deliaison enregistree. La licence sera liberee dans 30 jours."
                        )}
                        // Flow will auto-update when sync pulls changes
                    },
                    onFailure = { e ->
                        _dialogState.update { it.copy(
                            isUnlinking = false,
                            error = e.message ?: "Erreur lors de la demande de deliaison"
                        )}
                    }
                )
            } catch (e: Exception) {
                _dialogState.update { it.copy(
                    isUnlinking = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Cancel an unlink request - requires server
     */
    fun cancelUnlinkRequest(licenseId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                val result = licenseRemoteDataSource.cancelUnlinkRequest(
                    licenseId = licenseId,
                    proAccountId = proAccountId
                )

                result.fold(
                    onSuccess = {
                        _dialogState.update { it.copy(
                            successMessage = "Demande de deliaison annulee"
                        )}
                        // Flow will auto-update when sync pulls changes
                    },
                    onFailure = { e ->
                        _dialogState.update { it.copy(
                            error = e.message ?: "Erreur lors de l'annulation"
                        )}
                    }
                )
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
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
        _dialogState.update { it.copy(error = null) }
    }

    /**
     * Clear success message
     */
    fun clearSuccessMessage() {
        _dialogState.update { it.copy(successMessage = null) }
    }

    /**
     * Get the display name for a linked account by ID
     */
    fun getLinkedAccountName(accountId: String): String {
        val licenses = uiState.value.licenses
        // This would need to be loaded from LinkedAccountRepository
        // For now, return the account ID or load it async
        return accountId.take(8) + "..."
    }
}

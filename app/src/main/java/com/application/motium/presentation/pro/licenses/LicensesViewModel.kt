package com.application.motium.presentation.pro.licenses

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.repository.OfflineFirstLicenseRepository
import com.application.motium.data.supabase.LicenseAssignmentResult
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.ProAccountRemoteDataSource
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicensesSummary
import com.application.motium.domain.model.LinkStatus
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.CancellationException
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
    val assignmentErrorMessage: String? = null,
    // Unlink confirm dialog state
    val showUnlinkConfirmDialog: Boolean = false,
    val licenseToUnlink: License? = null,
    val isUnlinking: Boolean = false,
    // Billing anchor day state
    val billingAnchorDay: Int? = null,
    val showBillingAnchorDialog: Boolean = false,
    val isUpdatingBillingAnchor: Boolean = false
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
        suspendedLicenses = 0,
        canceledLicenses = 0,
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
    val assignmentErrorMessage: String? = null,
    // Unlink confirm dialog state
    val showUnlinkConfirmDialog: Boolean = false,
    val licenseToUnlink: License? = null,
    val isUnlinking: Boolean = false,
    // Billing anchor day state
    val billingAnchorDay: Int? = null,
    val showBillingAnchorDialog: Boolean = false,
    val isUpdatingBillingAnchor: Boolean = false
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
        // Sort licenses: assigned (linked) first, then unassigned
        val sortedLicenses = licenses.sortedByDescending { it.isAssigned }
        LicensesUiState(
            isLoading = proAccountId == null,
            licenses = sortedLicenses,
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
            assignmentErrorMessage = dialogState.assignmentErrorMessage,
            showUnlinkConfirmDialog = dialogState.showUnlinkConfirmDialog,
            licenseToUnlink = dialogState.licenseToUnlink,
            isUnlinking = dialogState.isUnlinking,
            billingAnchorDay = dialogState.billingAnchorDay,
            showBillingAnchorDialog = dialogState.showBillingAnchorDialog,
            isUpdatingBillingAnchor = dialogState.isUpdatingBillingAnchor
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

                    // Load pro account to get billing anchor day
                    loadBillingAnchorDay(proAccountId)
                } else {
                    _dialogState.update { it.copy(error = "Compte Pro non trouvé") }
                }
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    /**
     * Load billing anchor day from pro account
     */
    private fun loadBillingAnchorDay(proAccountId: String) {
        viewModelScope.launch {
            try {
                val result = proAccountRemoteDataSource.getProAccountById(proAccountId)
                result.onSuccess { proAccountDto ->
                    _dialogState.update { it.copy(
                        billingAnchorDay = proAccountDto?.billingAnchorDay
                    )}
                    MotiumApplication.logger.d("Billing anchor day loaded: ${proAccountDto?.billingAnchorDay}", TAG)
                }
            } catch (e: CancellationException) {
                // Expected during scope cleanup/navigation - don't log as error
                MotiumApplication.logger.d("Billing anchor day loading cancelled", TAG)
            } catch (e: Exception) {
                MotiumApplication.logger.e("Error loading billing anchor day: ${e.message}", TAG, e)
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
                // Provide actionable error message based on error type
                val errorMessage = when {
                    e.message?.contains("network", ignoreCase = true) == true ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("connect", ignoreCase = true) == true ->
                        "Erreur de connexion. Verifiez votre connexion internet et reessayez."
                    e.message?.contains("unauthorized", ignoreCase = true) == true ||
                    e.message?.contains("auth", ignoreCase = true) == true ->
                        "Session expiree. Veuillez vous reconnecter et reessayer."
                    e.message?.contains("stripe", ignoreCase = true) == true ->
                        "Erreur de paiement. Reessayez ou contactez support@motium.fr"
                    else ->
                        "Erreur inattendue: ${e.message}. Si le probleme persiste, contactez support@motium.fr"
                }
                _dialogState.update { it.copy(
                    isPurchasing = false,
                    pendingPurchase = null,
                    error = errorMessage
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
            ?: return Result.failure(Exception("Paiement non initialisé"))

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
                // Provide actionable error message based on Stripe error
                val errorMessage = when {
                    result.error.message?.contains("declined", ignoreCase = true) == true ->
                        "Carte refusee. Verifiez vos informations bancaires ou utilisez une autre carte."
                    result.error.message?.contains("expired", ignoreCase = true) == true ->
                        "Carte expiree. Veuillez utiliser une carte valide."
                    result.error.message?.contains("insufficient", ignoreCase = true) == true ->
                        "Fonds insuffisants. Veuillez utiliser une autre carte."
                    result.error.message?.contains("network", ignoreCase = true) == true ||
                    result.error.message?.contains("timeout", ignoreCase = true) == true ->
                        "Erreur de connexion. Verifiez votre internet et reessayez."
                    else ->
                        "Paiement echoue: ${result.error.message ?: "Erreur inconnue"}. Reessayez ou contactez support@motium.fr"
                }
                _dialogState.update { it.copy(
                    paymentReady = null,
                    deferredPaymentReady = null,
                    pendingPurchase = null,
                    error = errorMessage
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

            // After max attempts, show actionable success message
            // Fix P2: Message plus actionnable pour guider l'utilisateur
            _dialogState.update { it.copy(
                isRefreshingAfterPayment = false,
                pendingPurchase = null,
                successMessage = "Paiement reussi ! Si vos licences n'apparaissent pas, tirez vers le bas pour rafraichir ou contactez support@motium.fr"
            )}
            MotiumApplication.logger.w("License refresh timed out, may need manual refresh", TAG)
        }
    }

    /**
     * Cancel a license (résiliation) - offline-first with sync.
     * Sets status to 'canceled' but user KEEPS access until end_date.
     * The actual unlinking happens automatically when end_date is reached.
     */
    fun cancelLicense(licenseId: String) {
        viewModelScope.launch {
            try {
                MotiumApplication.logger.i(
                    "cancelLicense() called - licenseId: $licenseId - Setting status='canceled' (user keeps access until end_date)",
                    TAG
                )
                offlineFirstLicenseRepo.cancelLicense(licenseId)
                _dialogState.update { it.copy(
                    successMessage = "Licence resiliee (acces maintenu jusqu'a la fin de periode)"
                )}
                // Flow will auto-update when local DB changes
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
            isLoadingAccounts = true,
            assignmentErrorMessage = null
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
            linkedAccountsForAssignment = emptyList(),
            assignmentErrorMessage = null
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

                    // Filter to show accounts eligible for license assignment:
                    // - Has a userId (not a pending invitation)
                    // - Not already licensed
                    // - Not in unlinking process or revoked
                    val unlicensedAccounts = linkedUsers.filter { user ->
                        user.userId != null &&
                        user.userId !in licensedAccountIds &&
                        user.status != LinkStatus.PENDING_UNLINK &&
                        user.status != LinkStatus.REVOKED
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
     * Assign a license to a linked account - offline-first with sync
     */
    fun assignLicenseToAccount(licenseId: String, linkedAccountId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                when (val result = licenseRemoteDataSource.assignLicenseWithValidation(
                    licenseId = licenseId,
                    proAccountId = proAccountId,
                    collaboratorId = linkedAccountId
                )) {
                    is LicenseAssignmentResult.Success -> {
                        // Refresh cache to sync local DB
                        licenseCacheManager.forceRefresh(proAccountId)
                        _dialogState.update { it.copy(
                            showAssignDialog = false,
                            selectedLicenseForAssignment = null,
                            assignmentErrorMessage = null,
                            successMessage = "Licence assignee avec succes"
                        )}
                    }
                    is LicenseAssignmentResult.AlreadyLifetime -> {
                        _dialogState.update { it.copy(assignmentErrorMessage = result.message) }
                    }
                    is LicenseAssignmentResult.AlreadyLicensed -> {
                        _dialogState.update { it.copy(assignmentErrorMessage = result.message) }
                    }
                    is LicenseAssignmentResult.LicenseNotAvailable -> {
                        _dialogState.update { it.copy(assignmentErrorMessage = result.message) }
                    }
                    is LicenseAssignmentResult.CollaboratorNotFound -> {
                        _dialogState.update { it.copy(assignmentErrorMessage = result.message) }
                    }
                    is LicenseAssignmentResult.NeedsCancelExisting -> {
                        // Cancel collaborator's individual subscription, then assign license
                        val cancelResult = subscriptionManager.cancelSubscription(
                            userId = linkedAccountId,
                            cancelImmediately = true
                        )
                        if (cancelResult.isFailure) {
                            _dialogState.update { it.copy(
                                assignmentErrorMessage = cancelResult.exceptionOrNull()?.message
                                    ?: "Erreur lors de la resiliation de l'abonnement"
                            ) }
                            return@launch
                        }

                        val assignAfterCancel = licenseRemoteDataSource.assignLicenseToAccount(
                            licenseId = licenseId,
                            proAccountId = proAccountId,
                            linkedAccountId = linkedAccountId
                        )

                        if (assignAfterCancel.isSuccess) {
                            licenseCacheManager.forceRefresh(proAccountId)
                            _dialogState.update { it.copy(
                                showAssignDialog = false,
                                selectedLicenseForAssignment = null,
                                assignmentErrorMessage = null,
                                successMessage = "Abonnement resilie et licence assignee"
                            )}
                        } else {
                            _dialogState.update { it.copy(
                                assignmentErrorMessage = assignAfterCancel.exceptionOrNull()?.message
                                    ?: "Erreur lors de l'attribution de la licence"
                            ) }
                        }
                    }
                    is LicenseAssignmentResult.Error -> {
                        _dialogState.update { it.copy(assignmentErrorMessage = result.message) }
                    }
                }
            } catch (e: Exception) {
                _dialogState.update { it.copy(assignmentErrorMessage = "Erreur: ${e.message}") }
            }
        }
    }

    // ========================================
    // Unlink/Cancel Methods (effective at renewal date)
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
     * Request to unlink/cancel a license - offline-first with sync
     * - Lifetime: déliaison à la prochaine date de renouvellement
     * - Mensuelle: effective à la date de renouvellement
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

                // DEBUG: Log action for tracing
                val effectiveType = if (license.isLifetime) "prochaine date de renouvellement (lifetime)" else "date de renouvellement"
                MotiumApplication.logger.w(
                    "🟠 DEBUG confirmUnlinkRequest() called - licenseId: ${license.id}, linkedAccountId: ${license.linkedAccountId} - Type: $effectiveType",
                    TAG
                )

                offlineFirstLicenseRepo.requestUnlink(
                    licenseId = license.id,
                    proAccountId = proAccountId
                )

                // Message différent selon le type de licence
                val successMsg = if (license.isLifetime) {
                    "Déliaison planifiée. La licence sera libérée à la prochaine date de renouvellement."
                } else {
                    "Résiliation enregistrée. La licence sera libérée à la date de renouvellement."
                }

                _dialogState.update { it.copy(
                    isUnlinking = false,
                    showUnlinkConfirmDialog = false,
                    licenseToUnlink = null,
                    successMessage = successMsg
                )}
                // Flow will auto-update when local DB changes
            } catch (e: Exception) {
                _dialogState.update { it.copy(
                    isUnlinking = false,
                    error = "Erreur: ${e.message}"
                )}
            }
        }
    }

    /**
     * Cancel an unlink request - offline-first with sync
     */
    fun cancelUnlinkRequest(licenseId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                offlineFirstLicenseRepo.cancelUnlinkRequest(
                    licenseId = licenseId,
                    proAccountId = proAccountId
                )

                _dialogState.update { it.copy(
                    successMessage = "Demande de deliaison annulee"
                )}
                // Flow will auto-update when local DB changes
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    // ========================================
    // Delete License Methods
    // ========================================

    /**
     * Delete a license permanently.
     * Only unassigned monthly licenses can be deleted.
     */
    fun deleteLicense(licenseId: String) {
        viewModelScope.launch {
            try {
                val proAccountId = _proAccountId.value
                if (proAccountId == null) {
                    _dialogState.update { it.copy(error = "Compte Pro non trouve") }
                    return@launch
                }

                // DEBUG: Log action for tracing
                MotiumApplication.logger.w(
                    "🔵 DEBUG deleteLicense() called - licenseId: $licenseId - This DELETES the license from DB (only works if unassigned)",
                    TAG
                )

                val result = licenseRemoteDataSource.deleteLicense(licenseId, proAccountId)
                result.onSuccess {
                    _dialogState.update { it.copy(
                        successMessage = "Licence supprimee definitivement."
                    )}
                    // Force refresh to update the Flow
                    refreshLicenses()
                }.onFailure { e ->
                    _dialogState.update { it.copy(error = e.message ?: "Erreur lors de la suppression") }
                }
            } catch (e: Exception) {
                _dialogState.update { it.copy(error = "Erreur: ${e.message}") }
            }
        }
    }

    // ========================================
    // Billing Anchor Day Methods
    // ========================================

    /**
     * Show billing anchor dialog
     */
    fun showBillingAnchorDialog() {
        _dialogState.update { it.copy(showBillingAnchorDialog = true) }
    }

    /**
     * Hide billing anchor dialog
     */
    fun hideBillingAnchorDialog() {
        _dialogState.update { it.copy(showBillingAnchorDialog = false) }
    }

    /**
     * Update billing anchor day (1-15) for unified license renewals
     */
    fun updateBillingAnchorDay(day: Int) {
        val proAccountId = _proAccountId.value ?: run {
            _dialogState.update { it.copy(error = "Compte Pro non trouve") }
            return
        }

        viewModelScope.launch {
            _dialogState.update { it.copy(isUpdatingBillingAnchor = true) }

            try {
                val result = proAccountRemoteDataSource.updateBillingAnchorDay(proAccountId, day)
                result.onSuccess {
                    _dialogState.update { it.copy(
                        billingAnchorDay = day,
                        showBillingAnchorDialog = false,
                        isUpdatingBillingAnchor = false,
                        successMessage = "Date de renouvellement definie au $day de chaque mois"
                    )}
                    MotiumApplication.logger.i("Billing anchor day updated to $day", TAG)
                }.onFailure { e ->
                    _dialogState.update { it.copy(
                        isUpdatingBillingAnchor = false,
                        error = e.message ?: "Erreur lors de la mise a jour"
                    )}
                }
            } catch (e: Exception) {
                _dialogState.update { it.copy(
                    isUpdatingBillingAnchor = false,
                    error = "Erreur: ${e.message}"
                )}
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
     * Clear assignment error message
     */
    fun clearAssignmentError() {
        _dialogState.update { it.copy(assignmentErrorMessage = null) }
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

package com.application.motium.presentation.individual.upgrade

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.WithdrawalWaiverRepository
import com.application.motium.domain.model.AuthResult
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.domain.model.WithdrawalWaiver
import com.application.motium.domain.model.WithdrawalWaiverState
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Upgrade screen.
 * Handles plan selection, payment initialization, and post-payment state refresh.
 */
class UpgradeViewModel(
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "UpgradeViewModel"
    }

    private val subscriptionManager = SubscriptionManager.getInstance(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val supabaseAuthRepository = SupabaseAuthRepository.getInstance(context)
    private val withdrawalWaiverRepository = WithdrawalWaiverRepository.getInstance(context)

    private val _uiState = MutableStateFlow(UpgradeUiState())
    val uiState: StateFlow<UpgradeUiState> = _uiState.asStateFlow()

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                val user = localUserRepository.getLoggedInUser()
                if (user != null) {
                    _uiState.update {
                        it.copy(
                            userId = user.id,
                            userEmail = user.email,
                            currentSubscriptionType = user.subscription.type
                        )
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to load user: ${e.message}", TAG, e)
            }
        }
    }

    /**
     * Select a plan type (monthly or lifetime)
     */
    fun selectPlan(planType: PlanType) {
        _uiState.update { it.copy(selectedPlan = planType) }
    }

    /**
     * Update withdrawal waiver state - immediate execution checkbox
     */
    fun setImmediateExecutionAccepted(accepted: Boolean) {
        _uiState.update {
            it.copy(
                waiverState = it.waiverState.copy(acceptedImmediateExecution = accepted)
            )
        }
    }

    /**
     * Update withdrawal waiver state - waiver checkbox
     */
    fun setWaiverAccepted(accepted: Boolean) {
        _uiState.update {
            it.copy(
                waiverState = it.waiverState.copy(acceptedWaiver = accepted)
            )
        }
    }

    /**
     * Initialize payment for the selected plan (deferred mode).
     * Opens PaymentSheet immediately without pre-creating a PaymentIntent.
     *
     * For individual users in trial period, withdrawal waiver consent is required
     * before proceeding to payment.
     */
    fun initializePayment() {
        val state = _uiState.value
        val userId = state.userId ?: return

        // Check if withdrawal waiver is required and complete
        if (state.requiresWithdrawalWaiver && !state.waiverState.isComplete) {
            _uiState.update {
                it.copy(
                    error = "Veuillez accepter les conditions de renonciation au droit de rétractation pour continuer."
                )
            }
            return
        }

        val isLifetime = state.selectedPlan == PlanType.LIFETIME
        val priceType = if (isLifetime) "individual_lifetime" else "individual_monthly"
        val amountCents = subscriptionManager.getAmountCents(priceType, 1)

        MotiumApplication.logger.i("Initializing deferred payment: $priceType (${amountCents / 100.0}€)", TAG)

        // If waiver is required, save it before proceeding to payment
        if (state.requiresWithdrawalWaiver && state.waiverState.isComplete) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }

                val waiver = WithdrawalWaiver(
                    acceptedImmediateExecution = state.waiverState.acceptedImmediateExecution,
                    acceptedWaiver = state.waiverState.acceptedWaiver,
                    appVersion = BuildConfig.VERSION_NAME
                )

                val result = withdrawalWaiverRepository.saveWaiver(waiver)
                if (result.isFailure) {
                    MotiumApplication.logger.e("Failed to save withdrawal waiver: ${result.exceptionOrNull()?.message}", TAG)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Erreur lors de l'enregistrement du consentement. Veuillez réessayer."
                        )
                    }
                    return@launch
                }

                MotiumApplication.logger.i("Withdrawal waiver saved successfully", TAG)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        deferredPaymentReady = DeferredPaymentReadyState(
                            amountCents = amountCents,
                            priceType = priceType
                        )
                    )
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    deferredPaymentReady = DeferredPaymentReadyState(
                        amountCents = amountCents,
                        priceType = priceType
                    )
                )
            }
        }
    }

    /**
     * Called from PaymentSheet's createIntentCallback to create the PaymentIntent on the server.
     */
    suspend fun confirmPayment(paymentMethodId: String): Result<String> {
        val state = _uiState.value
        val userId = state.userId ?: return Result.failure(Exception("Utilisateur non connecté"))
        val deferredState = state.deferredPaymentReady ?: return Result.failure(Exception("Payment not initialized"))

        return subscriptionManager.confirmPaymentWithMethod(
            paymentMethodId = paymentMethodId,
            userId = userId,
            proAccountId = null,
            priceType = deferredState.priceType,
            quantity = 1
        )
    }

    /**
     * Handle PaymentSheet result
     */
    fun handlePaymentResult(result: PaymentSheetResult) {
        val targetType = when (_uiState.value.selectedPlan) {
            PlanType.MONTHLY -> SubscriptionType.PREMIUM
            PlanType.LIFETIME -> SubscriptionType.LIFETIME
        }

        subscriptionManager.handlePaymentResult(result, targetType)

        when (result) {
            is PaymentSheetResult.Completed -> {
                _uiState.update {
                    it.copy(
                        paymentReady = null,
                        deferredPaymentReady = null,
                        paymentSuccess = true,
                        isRefreshing = true
                    )
                }
                // Refresh user subscription after payment
                refreshUserSubscription()
            }
            is PaymentSheetResult.Canceled -> {
                _uiState.update {
                    it.copy(
                        paymentReady = null,
                        deferredPaymentReady = null,
                        error = null // Don't show error for cancellation
                    )
                }
            }
            is PaymentSheetResult.Failed -> {
                _uiState.update {
                    it.copy(
                        paymentReady = null,
                        deferredPaymentReady = null,
                        error = result.error.message ?: "Le paiement a échoué"
                    )
                }
            }
        }
    }

    /**
     * Refresh user subscription after successful payment.
     * Fetches fresh data from Supabase and updates local cache.
     * Retries a few times as webhook processing may have a short delay.
     */
    private fun refreshUserSubscription() {
        viewModelScope.launch {
            var attempts = 0
            val maxAttempts = 5
            val delayMs = 2000L

            val expectedType = when (_uiState.value.selectedPlan) {
                PlanType.MONTHLY -> SubscriptionType.PREMIUM
                PlanType.LIFETIME -> SubscriptionType.LIFETIME
            }

            while (attempts < maxAttempts) {
                delay(delayMs)
                attempts++

                try {
                    // Fetch fresh user data from Supabase
                    val userId = _uiState.value.userId ?: continue
                    val result = supabaseAuthRepository.getUserProfile(userId)

                    if (result is AuthResult.Success) {
                        val freshUser = result.data

                        // Update local cache with fresh data
                        localUserRepository.saveUser(freshUser, isLocallyConnected = true)
                        MotiumApplication.logger.i("🔄 User cache updated from Supabase: ${freshUser.subscription.type}", TAG)

                        if (freshUser.subscription.type == expectedType) {
                            _uiState.update {
                                it.copy(
                                    isRefreshing = false,
                                    currentSubscriptionType = freshUser.subscription.type
                                )
                            }
                            MotiumApplication.logger.i("✅ Subscription updated to $expectedType", TAG)
                            return@launch
                        } else {
                            MotiumApplication.logger.d("Attempt $attempts: subscription still ${freshUser.subscription.type}, expected $expectedType", TAG)
                        }
                    } else if (result is AuthResult.Error) {
                        MotiumApplication.logger.w("Failed to fetch user from Supabase: ${result.message}", TAG)
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Failed to refresh subscription: ${e.message}", TAG, e)
                }
            }

            // After max attempts, force refresh one more time and accept whatever status we get
            try {
                val userId = _uiState.value.userId
                if (userId != null) {
                    val result = supabaseAuthRepository.getUserProfile(userId)
                    if (result is AuthResult.Success) {
                        localUserRepository.saveUser(result.data, isLocallyConnected = true)
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                currentSubscriptionType = result.data.subscription.type
                            )
                        }
                        MotiumApplication.logger.i("📦 Final sync: subscription is ${result.data.subscription.type}", TAG)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Final sync failed: ${e.message}", TAG, e)
            }

            // After all attempts, mark as done
            _uiState.update { it.copy(isRefreshing = false) }
            MotiumApplication.logger.w("Subscription refresh completed, may not have reached expected state", TAG)
        }
    }

    /**
     * Clear any error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Reset success state (when navigating away)
     */
    fun resetSuccessState() {
        _uiState.update { it.copy(paymentSuccess = false) }
    }

    /**
     * Factory for creating UpgradeViewModel with context
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(UpgradeViewModel::class.java)) {
                return UpgradeViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

/**
 * UI State for the Upgrade screen
 */
data class UpgradeUiState(
    val userId: String? = null,
    val userEmail: String? = null,
    val currentSubscriptionType: SubscriptionType = SubscriptionType.TRIAL,
    val selectedPlan: PlanType = PlanType.MONTHLY,
    val isLoading: Boolean = false,
    val paymentReady: PaymentReadyState? = null,
    val deferredPaymentReady: DeferredPaymentReadyState? = null,
    val paymentSuccess: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val waiverState: WithdrawalWaiverState = WithdrawalWaiverState()
) {
    /**
     * Withdrawal waiver is required for individual users subscribing during trial period.
     * This is to comply with French consumer law (Article L221-28).
     */
    val requiresWithdrawalWaiver: Boolean
        get() = currentSubscriptionType == SubscriptionType.TRIAL

    /**
     * Can proceed to payment only if:
     * - User is logged in
     * - Not currently loading
     * - If waiver required, it must be complete
     */
    val canProceedToPayment: Boolean
        get() = userId != null &&
                !isLoading &&
                (!requiresWithdrawalWaiver || waiverState.isComplete)
}

/**
 * State when payment is ready to present (legacy mode with pre-created intent)
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
    val priceType: String
)

/**
 * Available plan types for upgrade
 */
enum class PlanType {
    MONTHLY,
    LIFETIME
}

package com.application.motium.data.subscription

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.domain.model.Subscription
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.domain.model.User
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * SubscriptionManager handles all Stripe payment and subscription logic.
 *
 * Subscription Plans:
 * - FREE: 20 trips/month, no export
 * - PREMIUM: Unlimited trips, all features, monthly subscription
 * - LIFETIME: Unlimited trips, all features, one-time purchase
 */
class SubscriptionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SubscriptionManager"

        // ===== INDIVIDUAL PRICING (TTC - taxes incluses) =====
        const val INDIVIDUAL_MONTHLY_PRICE = 5.00   // 5€ TTC/mois
        const val INDIVIDUAL_LIFETIME_PRICE = 100.00 // 100€ TTC one-time

        // ===== PRO LICENSE PRICING (HT - hors taxes) =====
        const val PRO_LICENSE_MONTHLY_PRICE_HT = 5.00   // 5€ HT/mois par licence
        const val PRO_LICENSE_LIFETIME_PRICE_HT = 100.00 // 100€ HT one-time par licence

        // ===== STRIPE PRICE IDs - TODO: Replace with real Stripe price IDs =====
        // Individual
        const val PRICE_ID_INDIVIDUAL_MONTHLY = "price_individual_monthly_placeholder"
        const val PRICE_ID_INDIVIDUAL_LIFETIME = "price_individual_lifetime_placeholder"
        // Pro licenses
        const val PRICE_ID_PRO_LICENSE_MONTHLY = "price_pro_license_monthly_placeholder"
        const val PRICE_ID_PRO_LICENSE_LIFETIME = "price_pro_license_lifetime_placeholder"

        // Legacy constants (for backward compatibility)
        const val PRICE_ID_PREMIUM_MONTHLY = PRICE_ID_INDIVIDUAL_MONTHLY
        const val PRICE_ID_LIFETIME = PRICE_ID_INDIVIDUAL_LIFETIME
        const val PREMIUM_MONTHLY_PRICE = INDIVIDUAL_MONTHLY_PRICE
        const val LIFETIME_PRICE = INDIVIDUAL_LIFETIME_PRICE

        @Volatile
        private var instance: SubscriptionManager? = null

        fun getInstance(context: Context): SubscriptionManager {
            return instance ?: synchronized(this) {
                instance ?: SubscriptionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val localUserRepository = LocalUserRepository.getInstance(context)

    // State for payment process
    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState.asStateFlow()

    // State for subscription info
    private val _subscriptionState = MutableStateFlow<SubscriptionState>(SubscriptionState.Loading)
    val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    init {
        // Initialize Stripe SDK
        try {
            PaymentConfiguration.init(
                context,
                BuildConfig.STRIPE_PUBLISHABLE_KEY
            )
            MotiumApplication.logger.i("✅ Stripe SDK initialized", TAG)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to initialize Stripe SDK: ${e.message}", TAG, e)
        }
    }

    /**
     * Data class representing the payment intent response from backend
     */
    @Serializable
    data class PaymentIntentResponse(
        val client_secret: String,
        val customer_id: String,
        val ephemeral_key: String? = null
    )

    /**
     * Sealed class representing payment states
     */
    sealed class PaymentState {
        data object Idle : PaymentState()
        data object Loading : PaymentState()
        data class Ready(val clientSecret: String, val customerId: String) : PaymentState()
        data class Success(val subscriptionType: SubscriptionType) : PaymentState()
        data class Error(val message: String) : PaymentState()
        data object Cancelled : PaymentState()
    }

    /**
     * Sealed class representing subscription states
     */
    sealed class SubscriptionState {
        data object Loading : SubscriptionState()
        data class Active(
            val type: SubscriptionType,
            val expiresAt: Instant?,
            val canExport: Boolean,
            val tripLimit: Int?
        ) : SubscriptionState()
        data class Error(val message: String) : SubscriptionState()
    }

    /**
     * Check current subscription status
     */
    suspend fun checkSubscriptionStatus(): SubscriptionState = withContext(Dispatchers.IO) {
        try {
            _subscriptionState.value = SubscriptionState.Loading

            val user = localUserRepository.getLoggedInUser()
            if (user == null) {
                val error = SubscriptionState.Error("Utilisateur non connecté")
                _subscriptionState.value = error
                return@withContext error
            }

            val subscription = user.subscription
            val state = SubscriptionState.Active(
                type = subscription.type,
                expiresAt = subscription.expiresAt,
                canExport = subscription.canExport(),
                tripLimit = subscription.getTripLimit()
            )
            _subscriptionState.value = state
            state
        } catch (e: Exception) {
            val error = SubscriptionState.Error("Erreur: ${e.message}")
            _subscriptionState.value = error
            error
        }
    }

    /**
     * Check if user can create more trips this month
     */
    suspend fun canCreateTrip(): TripLimitResult = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext TripLimitResult.Error("Utilisateur non connecté")

            val subscription = user.subscription
            val tripLimit = subscription.getTripLimit()

            if (tripLimit == null) {
                // Unlimited trips
                return@withContext TripLimitResult.Allowed(
                    remaining = null,
                    isUnlimited = true
                )
            }

            val currentCount = user.monthlyTripCount
            val remaining = tripLimit - currentCount

            if (remaining > 0) {
                TripLimitResult.Allowed(
                    remaining = remaining,
                    isUnlimited = false
                )
            } else {
                TripLimitResult.LimitReached(
                    limit = tripLimit,
                    subscriptionType = subscription.type
                )
            }
        } catch (e: Exception) {
            TripLimitResult.Error("Erreur: ${e.message}")
        }
    }

    /**
     * Check if user can export data
     */
    suspend fun canExport(): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext false
            user.subscription.canExport()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking export permission: ${e.message}", TAG, e)
            false
        }
    }

    /**
     * Initialize payment for subscription upgrade
     *
     * Note: This requires a backend endpoint to create PaymentIntent securely.
     * The backend should:
     * 1. Create or retrieve Stripe customer
     * 2. Create PaymentIntent or Subscription
     * 3. Return client_secret for PaymentSheet
     */
    suspend fun initializePayment(
        userId: String,
        email: String,
        priceId: String
    ): Result<PaymentIntentResponse> = withContext(Dispatchers.IO) {
        try {
            _paymentState.value = PaymentState.Loading

            // TODO: Implement Supabase Edge Function call to create payment intent
            // This keeps Stripe secret key secure on the server
            // The Edge Function should:
            // 1. Create or retrieve Stripe customer
            // 2. Create PaymentIntent or Subscription
            // 3. Return client_secret for PaymentSheet

            // For now, return a placeholder error
            _paymentState.value = PaymentState.Error("Backend payment endpoint not configured")
            Result.failure(Exception("Backend payment endpoint not configured. Please set up Supabase Edge Functions for Stripe."))

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Payment initialization failed: ${e.message}", TAG, e)
            _paymentState.value = PaymentState.Error(e.message ?: "Erreur inconnue")
            Result.failure(e)
        }
    }

    /**
     * Initialize payment for Individual user subscription
     * @param userId The user ID
     * @param email The user's email
     * @param isLifetime True for lifetime purchase, false for monthly subscription
     */
    suspend fun initializeIndividualPayment(
        userId: String,
        email: String,
        isLifetime: Boolean
    ): Result<PaymentIntentResponse> = withContext(Dispatchers.IO) {
        try {
            _paymentState.value = PaymentState.Loading

            val priceId = if (isLifetime) {
                PRICE_ID_INDIVIDUAL_LIFETIME
            } else {
                PRICE_ID_INDIVIDUAL_MONTHLY
            }

            val price = if (isLifetime) {
                INDIVIDUAL_LIFETIME_PRICE
            } else {
                INDIVIDUAL_MONTHLY_PRICE
            }

            MotiumApplication.logger.i("Initializing individual payment: $priceId (${price}€)", TAG)

            // TODO: Call Supabase Edge Function to create PaymentIntent
            // The Edge Function should:
            // 1. Create or retrieve Stripe customer for userId/email
            // 2. Create PaymentIntent (one-time) or Subscription (recurring)
            // 3. Return client_secret for PaymentSheet

            _paymentState.value = PaymentState.Error("Backend payment endpoint not configured")
            Result.failure(Exception(
                "Backend payment endpoint not configured. " +
                    "Please set up Supabase Edge Functions for Stripe."
            ))

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Individual payment initialization failed: ${e.message}", TAG, e)
            _paymentState.value = PaymentState.Error(e.message ?: "Erreur inconnue")
            Result.failure(e)
        }
    }

    /**
     * Initialize payment for Pro license purchase
     * @param proAccountId The Pro account ID
     * @param email The billing email
     * @param quantity Number of licenses to purchase
     * @param isLifetime True for lifetime licenses, false for monthly
     */
    suspend fun initializeProLicensePayment(
        proAccountId: String,
        email: String,
        quantity: Int,
        isLifetime: Boolean
    ): Result<PaymentIntentResponse> = withContext(Dispatchers.IO) {
        try {
            _paymentState.value = PaymentState.Loading

            val priceId = if (isLifetime) {
                PRICE_ID_PRO_LICENSE_LIFETIME
            } else {
                PRICE_ID_PRO_LICENSE_MONTHLY
            }

            val priceHT = if (isLifetime) {
                PRO_LICENSE_LIFETIME_PRICE_HT * quantity
            } else {
                PRO_LICENSE_MONTHLY_PRICE_HT * quantity
            }

            val priceTTC = priceHT * 1.20 // +20% VAT

            MotiumApplication.logger.i(
                "Initializing pro license payment: $quantity x $priceId (${priceHT}€ HT / ${priceTTC}€ TTC)",
                TAG
            )

            // TODO: Call Supabase Edge Function
            // Should create PaymentIntent with quantity parameter

            _paymentState.value = PaymentState.Error("Backend payment endpoint not configured")
            Result.failure(Exception("Backend not configured"))

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Pro license payment initialization failed: ${e.message}", TAG, e)
            _paymentState.value = PaymentState.Error(e.message ?: "Erreur inconnue")
            Result.failure(e)
        }
    }

    /**
     * Handle payment sheet result
     */
    fun handlePaymentResult(result: PaymentSheetResult, targetPlan: SubscriptionType) {
        when (result) {
            is PaymentSheetResult.Completed -> {
                MotiumApplication.logger.i("✅ Payment completed for $targetPlan", TAG)
                _paymentState.value = PaymentState.Success(targetPlan)
            }
            is PaymentSheetResult.Canceled -> {
                MotiumApplication.logger.i("⚠️ Payment cancelled", TAG)
                _paymentState.value = PaymentState.Cancelled
            }
            is PaymentSheetResult.Failed -> {
                MotiumApplication.logger.e("❌ Payment failed: ${result.error.message}", TAG)
                _paymentState.value = PaymentState.Error(result.error.message ?: "Paiement échoué")
            }
        }
    }

    /**
     * Update subscription after successful payment
     */
    suspend fun activateSubscription(
        userId: String,
        type: SubscriptionType,
        stripeCustomerId: String?,
        stripeSubscriptionId: String?
    ): Result<User> = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext Result.failure(Exception("Utilisateur non trouvé"))

            val expiresAt = when (type) {
                SubscriptionType.PREMIUM -> Instant.fromEpochMilliseconds(
                    System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
                )
                SubscriptionType.LIFETIME -> null // Never expires
                SubscriptionType.FREE -> null
            }

            val updatedSubscription = Subscription(
                type = type,
                expiresAt = expiresAt,
                stripeCustomerId = stripeCustomerId,
                stripeSubscriptionId = stripeSubscriptionId
            )

            val updatedUser = user.copy(
                subscription = updatedSubscription
            )

            // Save locally
            localUserRepository.updateUser(updatedUser)

            // Note: Supabase sync will happen via normal user profile sync mechanisms

            // Update states
            checkSubscriptionStatus()
            _paymentState.value = PaymentState.Idle

            Result.success(updatedUser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to activate subscription: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Reset to free plan (for testing or cancellation)
     */
    suspend fun resetToFreePlan(userId: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext Result.failure(Exception("Utilisateur non trouvé"))

            val updatedSubscription = Subscription(
                type = SubscriptionType.FREE,
                expiresAt = null,
                stripeCustomerId = user.subscription.stripeCustomerId,
                stripeSubscriptionId = null
            )

            val updatedUser = user.copy(
                subscription = updatedSubscription,
                monthlyTripCount = 0 // Reset trip count
            )

            localUserRepository.updateUser(updatedUser)
            checkSubscriptionStatus()

            Result.success(updatedUser)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Increment monthly trip count
     */
    suspend fun incrementTripCount(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext Result.failure(Exception("Utilisateur non trouvé"))

            val newCount = user.monthlyTripCount + 1
            val updatedUser = user.copy(monthlyTripCount = newCount)

            localUserRepository.updateUser(updatedUser)

            // Note: Supabase sync will happen via normal user profile sync mechanisms

            Result.success(newCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reset monthly trip count (called at beginning of each month)
     */
    suspend fun resetMonthlyTripCount(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext Result.failure(Exception("Utilisateur non trouvé"))

            val updatedUser = user.copy(monthlyTripCount = 0)
            localUserRepository.updateUser(updatedUser)

            // Note: Supabase sync will happen via normal user profile sync mechanisms

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reset payment state
     */
    fun resetPaymentState() {
        _paymentState.value = PaymentState.Idle
    }
}

/**
 * Result of checking trip limit
 */
sealed class TripLimitResult {
    data class Allowed(val remaining: Int?, val isUnlimited: Boolean) : TripLimitResult()
    data class LimitReached(val limit: Int, val subscriptionType: SubscriptionType) : TripLimitResult()
    data class Error(val message: String) : TripLimitResult()
}

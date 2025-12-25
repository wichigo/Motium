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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * SubscriptionManager handles all Stripe payment and subscription logic.
 *
 * Subscription Plans:
 * - TRIAL: 7-day free trial with full access
 * - EXPIRED: Trial ended, no access until subscription
 * - PREMIUM: Unlimited trips, all features, monthly subscription
 * - LIFETIME: Unlimited trips, all features, one-time purchase
 */
class SubscriptionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SubscriptionManager"

        // ===== INDIVIDUAL PRICING (TTC - taxes incluses) =====
        const val INDIVIDUAL_MONTHLY_PRICE = 4.99    // 4.99€ TTC/mois
        const val INDIVIDUAL_LIFETIME_PRICE = 120.00 // 120€ TTC one-time

        // ===== PRO LICENSE PRICING (TTC - taxes incluses) =====
        const val PRO_LICENSE_MONTHLY_PRICE = 6.00   // 6.00€ TTC/mois par licence (5.00€ HT)
        const val PRO_LICENSE_LIFETIME_PRICE = 144.00 // 144€ TTC one-time par licence (120€ HT)

        // ===== STRIPE PRODUCT IDs (Test Mode) =====
        const val PRODUCT_INDIVIDUAL_MONTHLY = "prod_TdmBT4sDscYZer"
        const val PRODUCT_INDIVIDUAL_LIFETIME = "prod_Tdm94ZsJEGevzK"
        const val PRODUCT_PRO_LICENSE_MONTHLY = "prod_Tdm6mAbHVHJLxz"
        const val PRODUCT_PRO_LICENSE_LIFETIME = "prod_TdmC9Jq3tCk94E"

        // Legacy constants (for backward compatibility)
        const val PREMIUM_MONTHLY_PRICE = INDIVIDUAL_MONTHLY_PRICE
        const val LIFETIME_PRICE = INDIVIDUAL_LIFETIME_PRICE
        const val PRO_LICENSE_MONTHLY_PRICE_HT = 5.00   // 6.00 TTC / 1.20 = 5.00 HT
        const val PRO_LICENSE_LIFETIME_PRICE_HT = 120.00 // 144 TTC / 1.20 = 120 HT

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
        @SerialName("client_secret") val clientSecret: String,
        @SerialName("customer_id") val customerId: String,
        @SerialName("ephemeral_key") val ephemeralKey: String? = null,
        @SerialName("payment_intent_id") val paymentIntentId: String? = null,
        @SerialName("subscription_id") val subscriptionId: String? = null,
        @SerialName("product_id") val productId: String? = null,
        @SerialName("amount_cents") val amountCents: Int? = null
    )

    /**
     * Data class for error response from backend
     */
    @Serializable
    private data class ErrorResponse(
        val error: String
    )

    /**
     * Request body for create-payment-intent Edge Function
     */
    @Serializable
    private data class CreatePaymentIntentRequest(
        val userId: String? = null,
        val proAccountId: String? = null,
        val email: String,
        val priceType: String,
        val quantity: Int = 1
    )

    /**
     * Request body for confirm-payment-intent Edge Function (deferred payment)
     */
    @Serializable
    private data class ConfirmPaymentIntentRequest(
        @SerialName("payment_method_id") val paymentMethodId: String,
        @SerialName("user_id") val userId: String? = null,
        @SerialName("pro_account_id") val proAccountId: String? = null,
        @SerialName("price_type") val priceType: String,
        val quantity: Int = 1
    )

    /**
     * Response from confirm-payment-intent Edge Function
     */
    @Serializable
    data class ConfirmPaymentResponse(
        @SerialName("client_secret") val clientSecret: String,
        @SerialName("payment_intent_id") val paymentIntentId: String? = null,
        val status: String? = null
    )

    // HTTP client for Edge Function calls
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Sealed class representing payment states
     */
    sealed class PaymentState {
        data object Idle : PaymentState()
        data object Loading : PaymentState()
        data class Ready(
            val clientSecret: String,
            val customerId: String,
            val ephemeralKey: String?,
            val amountCents: Int?
        ) : PaymentState()
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
            val hasAccess: Boolean
        ) : SubscriptionState()
        data class Error(val message: String) : SubscriptionState()
    }

    /**
     * Sealed class representing trial status
     */
    sealed class TrialStatus {
        data class Active(val daysRemaining: Int) : TrialStatus()
        data object Expired : TrialStatus()
        data class Subscribed(val type: SubscriptionType) : TrialStatus()
        data object NotAuthenticated : TrialStatus()
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
                expiresAt = subscription.expiresAt ?: subscription.trialEndsAt,
                canExport = subscription.canExport(),
                hasAccess = subscription.hasValidAccess()
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
     * Check trial status for the current user
     */
    suspend fun checkTrialStatus(): TrialStatus = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext TrialStatus.NotAuthenticated

            val subscription = user.subscription

            when (subscription.type) {
                SubscriptionType.TRIAL -> {
                    val daysLeft = subscription.daysLeftInTrial()
                    if (daysLeft != null && daysLeft > 0) {
                        TrialStatus.Active(daysLeft)
                    } else {
                        TrialStatus.Expired
                    }
                }
                SubscriptionType.EXPIRED -> TrialStatus.Expired
                SubscriptionType.PREMIUM, SubscriptionType.LIFETIME, SubscriptionType.LICENSED -> {
                    TrialStatus.Subscribed(subscription.type)
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking trial status: ${e.message}", TAG, e)
            TrialStatus.NotAuthenticated
        }
    }

    /**
     * Check if user has valid access (trial active or subscribed)
     */
    suspend fun hasValidAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext false
            user.subscription.hasValidAccess()
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking valid access: ${e.message}", TAG, e)
            false
        }
    }

    /**
     * Check if user can create trips (has valid access)
     */
    suspend fun canCreateTrip(): TripAccessResult = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext TripAccessResult.Error("Utilisateur non connecté")

            val subscription = user.subscription

            if (subscription.hasValidAccess()) {
                val daysLeft = subscription.daysLeftInTrial()
                TripAccessResult.Allowed(
                    isInTrial = subscription.isInTrial(),
                    trialDaysRemaining = daysLeft
                )
            } else {
                TripAccessResult.AccessDenied(
                    reason = if (subscription.type == SubscriptionType.EXPIRED) {
                        "Votre essai gratuit est terminé"
                    } else {
                        "Abonnement requis"
                    }
                )
            }
        } catch (e: Exception) {
            TripAccessResult.Error("Erreur: ${e.message}")
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
     * Call the create-payment-intent Edge Function
     */
    private suspend fun callCreatePaymentIntent(
        userId: String?,
        proAccountId: String?,
        email: String,
        priceType: String,
        quantity: Int = 1
    ): PaymentIntentResponse {
        val url = "${BuildConfig.SUPABASE_URL}/functions/v1/create-payment-intent"

        val requestBody = CreatePaymentIntentRequest(
            userId = userId,
            proAccountId = proAccountId,
            email = email,
            priceType = priceType,
            quantity = quantity
        )

        val jsonBody = json.encodeToString(CreatePaymentIntentRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        MotiumApplication.logger.i("Calling create-payment-intent: $priceType, quantity=$quantity", TAG)

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response from server")

        if (!response.isSuccessful) {
            val errorMessage = try {
                json.decodeFromString(ErrorResponse.serializer(), responseBody).error
            } catch (e: Exception) {
                "Erreur serveur: ${response.code}"
            }
            throw Exception(errorMessage)
        }

        val paymentResponse = json.decodeFromString(PaymentIntentResponse.serializer(), responseBody)
        MotiumApplication.logger.i("✅ Payment intent created: ${paymentResponse.productId}", TAG)

        return paymentResponse
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

            val priceType = if (isLifetime) "individual_lifetime" else "individual_monthly"
            val price = if (isLifetime) INDIVIDUAL_LIFETIME_PRICE else INDIVIDUAL_MONTHLY_PRICE

            MotiumApplication.logger.i("Initializing individual payment: $priceType (${price}€)", TAG)

            val response = callCreatePaymentIntent(
                userId = userId,
                proAccountId = null,
                email = email,
                priceType = priceType,
                quantity = 1
            )

            _paymentState.value = PaymentState.Ready(
                clientSecret = response.clientSecret,
                customerId = response.customerId,
                ephemeralKey = response.ephemeralKey,
                amountCents = response.amountCents
            )

            Result.success(response)

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

            val priceType = if (isLifetime) "pro_license_lifetime" else "pro_license_monthly"
            val pricePerLicense = if (isLifetime) PRO_LICENSE_LIFETIME_PRICE else PRO_LICENSE_MONTHLY_PRICE
            val totalPrice = pricePerLicense * quantity

            MotiumApplication.logger.i(
                "Initializing pro license payment: $quantity x $priceType (${totalPrice}€ TTC)",
                TAG
            )

            val response = callCreatePaymentIntent(
                userId = null,
                proAccountId = proAccountId,
                email = email,
                priceType = priceType,
                quantity = quantity
            )

            _paymentState.value = PaymentState.Ready(
                clientSecret = response.clientSecret,
                customerId = response.customerId,
                ephemeralKey = response.ephemeralKey,
                amountCents = response.amountCents
            )

            Result.success(response)

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
                SubscriptionType.LIFETIME, SubscriptionType.LICENSED -> null // Never expires (license manages its own state)
                SubscriptionType.TRIAL, SubscriptionType.EXPIRED -> null // Handled by trialEndsAt
            }

            val updatedSubscription = Subscription(
                type = type,
                expiresAt = expiresAt,
                trialStartedAt = user.subscription.trialStartedAt,
                trialEndsAt = user.subscription.trialEndsAt,
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
     * Mark trial as expired (when trial period ends)
     */
    suspend fun markTrialExpired(userId: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext Result.failure(Exception("Utilisateur non trouvé"))

            val updatedSubscription = Subscription(
                type = SubscriptionType.EXPIRED,
                expiresAt = null,
                trialStartedAt = user.subscription.trialStartedAt,
                trialEndsAt = user.subscription.trialEndsAt,
                stripeCustomerId = user.subscription.stripeCustomerId,
                stripeSubscriptionId = null
            )

            val updatedUser = user.copy(
                subscription = updatedSubscription
            )

            localUserRepository.updateUser(updatedUser)
            checkSubscriptionStatus()

            MotiumApplication.logger.i("Trial marked as expired for user $userId", TAG)
            Result.success(updatedUser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to mark trial as expired: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Check and update subscription status if trial has expired
     * Call this on app start and periodically
     */
    suspend fun checkAndUpdateTrialExpiration(): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext false

            if (user.subscription.type == SubscriptionType.TRIAL) {
                val daysLeft = user.subscription.daysLeftInTrial()
                if (daysLeft == null || daysLeft <= 0) {
                    // Trial has expired
                    markTrialExpired(user.id)
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking trial expiration: ${e.message}", TAG, e)
            false
        }
    }

    /**
     * Reset payment state
     */
    fun resetPaymentState() {
        _paymentState.value = PaymentState.Idle
    }

    // ========================================
    // Deferred Payment API (IntentConfiguration mode)
    // ========================================

    /**
     * Confirm payment with a PaymentMethod ID (called from createIntentCallback).
     * This creates the PaymentIntent on the server AFTER the user enters their card.
     *
     * @param paymentMethodId The Stripe PaymentMethod ID from PaymentSheet
     * @param userId User ID for individual payments
     * @param proAccountId Pro account ID for license purchases
     * @param priceType Type of payment (individual_monthly, individual_lifetime, pro_license_monthly, pro_license_lifetime)
     * @param quantity Number of items (1 for individual, N for licenses)
     * @return Result containing the client_secret needed to complete payment
     */
    suspend fun confirmPaymentWithMethod(
        paymentMethodId: String,
        userId: String? = null,
        proAccountId: String? = null,
        priceType: String,
        quantity: Int = 1
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/confirm-payment-intent"

            val requestBody = ConfirmPaymentIntentRequest(
                paymentMethodId = paymentMethodId,
                userId = userId,
                proAccountId = proAccountId,
                priceType = priceType,
                quantity = quantity
            )

            val jsonBody = json.encodeToString(ConfirmPaymentIntentRequest.serializer(), requestBody)

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            MotiumApplication.logger.i("Confirming payment: $priceType, quantity=$quantity, paymentMethod=$paymentMethodId", TAG)

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response from server")

            if (!response.isSuccessful) {
                val errorMessage = try {
                    json.decodeFromString(ErrorResponse.serializer(), responseBody).error
                } catch (e: Exception) {
                    "Erreur serveur: ${response.code}"
                }
                throw Exception(errorMessage)
            }

            val confirmResponse = json.decodeFromString(ConfirmPaymentResponse.serializer(), responseBody)
            MotiumApplication.logger.i("✅ Payment confirmed: ${confirmResponse.paymentIntentId}", TAG)

            Result.success(confirmResponse.clientSecret)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Payment confirmation failed: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Get the amount in cents for a given price type and quantity
     */
    fun getAmountCents(priceType: String, quantity: Int = 1): Long {
        val pricePerUnit = when (priceType) {
            "individual_monthly" -> INDIVIDUAL_MONTHLY_PRICE
            "individual_lifetime" -> INDIVIDUAL_LIFETIME_PRICE
            "pro_license_monthly" -> PRO_LICENSE_MONTHLY_PRICE
            "pro_license_lifetime" -> PRO_LICENSE_LIFETIME_PRICE
            else -> 0.0
        }
        return (pricePerUnit * 100 * quantity).toLong()
    }
}

/**
 * Result of checking trip access
 */
sealed class TripAccessResult {
    data class Allowed(val isInTrial: Boolean, val trialDaysRemaining: Int?) : TripAccessResult()
    data class AccessDenied(val reason: String) : TripAccessResult()
    data class Error(val message: String) : TripAccessResult()
}

package com.application.motium.data.subscription

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.domain.model.Subscription
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.domain.model.User
import com.application.motium.utils.TrustedTimeProvider
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
 * - TRIAL: 14-day free trial with full access
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
    private val secureSessionStorage = SecureSessionStorage(context)

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
     * Get the authentication token for API calls.
     * Prefers the user's JWT token if available, falls back to anon key.
     * This ensures Edge Functions can validate the user's identity.
     */
    private fun getAuthToken(): String {
        val userToken = secureSessionStorage.getAccessToken()
        return if (!userToken.isNullOrBlank()) {
            MotiumApplication.logger.d("Using user JWT token for API call", TAG)
            userToken
        } else {
            MotiumApplication.logger.w("No user token available, using anon key", TAG)
            BuildConfig.SUPABASE_ANON_KEY
        }
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
            // SECURITY FIX: Use trusted time for security-critical access checks
            val trustedTimeMs = TrustedTimeProvider.getInstance(context).getTrustedTimeMs()
            val state = SubscriptionState.Active(
                type = subscription.type,
                expiresAt = subscription.expiresAt ?: subscription.trialEndsAt,
                canExport = subscription.canExportSecure(trustedTimeMs),
                hasAccess = subscription.hasValidAccessSecure(trustedTimeMs)
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
     *
     * SECURITY: Uses TrustedTimeProvider and fail-secure behavior.
     * Returns false if time is not trusted.
     */
    suspend fun hasValidAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext false
            // SECURITY FIX: Use trusted time for access checks
            val trustedTimeMs = TrustedTimeProvider.getInstance(context).getTrustedTimeMs()
            user.subscription.hasValidAccessSecure(trustedTimeMs)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking valid access: ${e.message}", TAG, e)
            false
        }
    }

    /**
     * Check if user can create trips (has valid access)
     *
     * SECURITY: Uses TrustedTimeProvider and fail-secure behavior.
     * Denies access if time is not trusted.
     */
    suspend fun canCreateTrip(): TripAccessResult = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser()
                ?: return@withContext TripAccessResult.Error("Utilisateur non connecté")

            val subscription = user.subscription
            // New policy: if the user can access the app, they can create unlimited trips.
            // Only EXPIRED is denied; TRIAL/PREMIUM/LIFETIME/LICENSED are allowed.
            when (subscription.type) {
                SubscriptionType.EXPIRED -> TripAccessResult.AccessDenied(
                    reason = "Votre abonnement est expiré"
                )
                else -> TripAccessResult.Allowed(
                    isInTrial = subscription.isInTrial(),
                    trialDaysRemaining = subscription.daysLeftInTrial()
                )
            }
        } catch (e: Exception) {
            TripAccessResult.Error("Erreur: ${e.message}")
        }
    }

    /**
     * Check if user can export data
     *
     * SECURITY: Uses TrustedTimeProvider and fail-secure behavior.
     * Returns false if time is not trusted.
     */
    suspend fun canExport(): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext false
            // SECURITY FIX: Use trusted time for export checks
            val trustedTimeMs = TrustedTimeProvider.getInstance(context).getTrustedTimeMs()
            user.subscription.canExportSecure(trustedTimeMs)
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

        val authToken = getAuthToken()
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $authToken")
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
     *
     * ARBRE 1 INDIVIDUEL: Validates that the user is not LICENSED before allowing payment.
     * A LICENSED user must first unlink from their Pro account before subscribing individually.
     *
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

            // ARBRE 1 INDIVIDUEL - Client-side validation
            val user = localUserRepository.getLoggedInUser()

            // Block LICENSED users (must unlink from Pro first)
            if (user?.subscription?.type == SubscriptionType.LICENSED) {
                val errorMsg = "Vous êtes actuellement lié à un compte Pro. Veuillez d'abord vous délier avant de souscrire un abonnement individuel."
                MotiumApplication.logger.e("❌ BLOCKED: User is LICENSED, cannot purchase Individual subscription", TAG)
                _paymentState.value = PaymentState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Block LIFETIME users trying to "downgrade" to PREMIUM (they already have the best plan)
            if (user?.subscription?.type == SubscriptionType.LIFETIME && !isLifetime) {
                val errorMsg = "Vous avez déjà un accès à vie. Pas besoin de souscrire un abonnement mensuel !"
                MotiumApplication.logger.i("ℹ️ User already has LIFETIME, no need for monthly subscription", TAG)
                _paymentState.value = PaymentState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Block double LIFETIME purchase
            if (user?.subscription?.type == SubscriptionType.LIFETIME && isLifetime) {
                val errorMsg = "Vous avez déjà un accès à vie."
                MotiumApplication.logger.i("ℹ️ User already has LIFETIME, cannot purchase again", TAG)
                _paymentState.value = PaymentState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Block PREMIUM users trying to purchase another PREMIUM (would cause double billing)
            if (user?.subscription?.type == SubscriptionType.PREMIUM && !isLifetime) {
                val errorMsg = "Vous avez déjà un abonnement Premium actif. Pour modifier votre abonnement, annulez d'abord l'abonnement actuel."
                MotiumApplication.logger.e("❌ BLOCKED: User already has active PREMIUM, cannot purchase another", TAG)
                _paymentState.value = PaymentState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            // Allow PREMIUM→LIFETIME upgrade (valid upgrade path)
            if (user?.subscription?.type == SubscriptionType.PREMIUM && isLifetime) {
                MotiumApplication.logger.i("⬆️ User upgrading from PREMIUM to LIFETIME", TAG)
                // Continue - the user will get LIFETIME and should cancel PREMIUM separately
            }

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
     * Mark subscription as expired (when trial or premium period ends).
     * Works for both TRIAL and PREMIUM subscription types.
     *
     * @param userId The user ID to mark as expired
     * @return Result containing the updated user or an error
     */
    suspend fun markSubscriptionExpired(userId: String): Result<User> = withContext(Dispatchers.IO) {
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

            MotiumApplication.logger.i("Subscription marked as expired for user $userId", TAG)
            Result.success(updatedUser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to mark trial as expired: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Check and update subscription status if trial or premium subscription has expired.
     * Call this on app start and periodically.
     *
     * SECURITY: This method checks BOTH trial AND premium expiration to ensure
     * users don't retain access after their subscription ends.
     *
     * SECURITY FIX: Uses TrustedTimeProvider instead of System.currentTimeMillis()
     * to prevent clock manipulation attacks.
     */
    suspend fun checkAndUpdateTrialExpiration(): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = localUserRepository.getLoggedInUser() ?: return@withContext false
            val subscription = user.subscription

            // SECURITY FIX: Use TrustedTimeProvider to prevent clock manipulation
            val trustedTimeMs = TrustedTimeProvider.getInstance(context).getTrustedTimeMs()

            // If time is not trusted, DO NOT expire anyone - require network sync first
            // This prevents both false positives (expiring valid users) and false negatives
            // (not expiring when clock is manipulated backward)
            if (trustedTimeMs == null) {
                MotiumApplication.logger.w(
                    "Cannot verify subscription expiration: time not trusted (possible clock manipulation). " +
                    "Skipping expiration check until network sync.",
                    TAG
                )
                return@withContext false
            }

            val now = Instant.fromEpochMilliseconds(trustedTimeMs)

            when (subscription.type) {
                SubscriptionType.TRIAL -> {
                    // SECURITY FIX: trialEndsAt null is now treated as expired (fail-secure)
                    val trialEndsAt = subscription.trialEndsAt
                    if (trialEndsAt == null || now >= trialEndsAt) {
                        // Trial has expired or never had a valid end date
                        MotiumApplication.logger.i("Trial expired for user ${user.id}", TAG)
                        markSubscriptionExpired(user.id)
                        return@withContext true
                    }
                }
                SubscriptionType.PREMIUM -> {
                    // SECURITY FIX: Also check PREMIUM subscription expiration
                    val expiresAt = subscription.expiresAt
                    if (expiresAt != null && now >= expiresAt) {
                        // Premium subscription has expired
                        MotiumApplication.logger.i("Premium subscription expired for user ${user.id}", TAG)
                        markSubscriptionExpired(user.id) // Reuse existing method to mark as EXPIRED
                        return@withContext true
                    }
                }
                SubscriptionType.LICENSED -> {
                    // LICENSED expiration is handled by LicenseCacheManager
                    // which checks licenses.end_date with TrustedTimeProvider
                }
                SubscriptionType.LIFETIME, SubscriptionType.EXPIRED -> {
                    // No expiration check needed
                }
            }
            false
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking subscription expiration: ${e.message}", TAG, e)
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

            val authToken = getAuthToken()
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            MotiumApplication.logger.i("Confirming payment: $priceType, quantity=$quantity, paymentMethod=REDACTED", TAG)

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
     * Cancel a user's subscription.
     * By default, cancels at end of billing period (user keeps access until then).
     *
     * @param userId The user's auth ID
     * @param subscriptionId Optional specific subscription ID (will be looked up if not provided)
     * @param cancelImmediately If true, cancel immediately instead of at period end
     * @return Result containing cancellation details
     */
    suspend fun cancelSubscription(
        userId: String,
        subscriptionId: String? = null,
        cancelImmediately: Boolean = false
    ): Result<CancelSubscriptionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/cancel-subscription"

            val requestBody = CancelSubscriptionRequest(
                userId = userId,
                subscriptionId = subscriptionId,
                cancelImmediately = cancelImmediately
            )

            val jsonBody = json.encodeToString(CancelSubscriptionRequest.serializer(), requestBody)

            val authToken = getAuthToken()
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            MotiumApplication.logger.i("Canceling subscription for user: $userId, immediate=$cancelImmediately", TAG)

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

            val cancelResponse = json.decodeFromString(CancelSubscriptionResponse.serializer(), responseBody)
            MotiumApplication.logger.i("✅ Subscription canceled: ${cancelResponse.subscriptionId}, atPeriodEnd=${cancelResponse.cancelAtPeriodEnd}", TAG)

            Result.success(cancelResponse)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Subscription cancellation failed: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Resume a previously canceled subscription.
     * This reactivates a subscription that was scheduled to cancel at the end of the billing period.
     *
     * @param userId The user's auth ID
     * @param subscriptionId Optional specific subscription ID (will be looked up if not provided)
     * @return Result containing reactivation details
     */
    suspend fun resumeSubscription(
        userId: String,
        subscriptionId: String? = null
    ): Result<ResumeSubscriptionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/resume-subscription"

            val requestBody = ResumeSubscriptionRequest(
                userId = userId,
                subscriptionId = subscriptionId
            )

            val jsonBody = json.encodeToString(ResumeSubscriptionRequest.serializer(), requestBody)

            val authToken = getAuthToken()
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            MotiumApplication.logger.i("Resuming subscription for user: $userId", TAG)

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

            val resumeResponse = json.decodeFromString(ResumeSubscriptionResponse.serializer(), responseBody)
            MotiumApplication.logger.i("✅ Subscription resumed: ${resumeResponse.subscriptionId}", TAG)

            Result.success(resumeResponse)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Subscription resume failed: ${e.message}", TAG, e)
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
 * Request to cancel a subscription
 */
@Serializable
data class CancelSubscriptionRequest(
    @SerialName("userId") val userId: String,
    @SerialName("subscriptionId") val subscriptionId: String? = null,
    @SerialName("cancelImmediately") val cancelImmediately: Boolean = false
)

/**
 * Response from cancel subscription endpoint
 */
@Serializable
data class CancelSubscriptionResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("subscriptionId") val subscriptionId: String,
    @SerialName("cancelAtPeriodEnd") val cancelAtPeriodEnd: Boolean,
    @SerialName("currentPeriodEnd") val currentPeriodEnd: String? = null,
    @SerialName("message") val message: String
)

/**
 * Request to resume a subscription
 */
@Serializable
data class ResumeSubscriptionRequest(
    @SerialName("userId") val userId: String,
    @SerialName("subscriptionId") val subscriptionId: String? = null
)

/**
 * Response from resume subscription endpoint
 */
@Serializable
data class ResumeSubscriptionResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("subscriptionId") val subscriptionId: String,
    @SerialName("cancelAtPeriodEnd") val cancelAtPeriodEnd: Boolean,
    @SerialName("currentPeriodEnd") val currentPeriodEnd: String? = null,
    @SerialName("message") val message: String
)

/**
 * Result of checking trip access
 */
sealed class TripAccessResult {
    data class Allowed(val isInTrial: Boolean, val trialDaysRemaining: Int?) : TripAccessResult()
    data class AccessDenied(val reason: String) : TripAccessResult()
    data class Error(val message: String) : TripAccessResult()
}

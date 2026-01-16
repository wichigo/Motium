package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.StripePayment
import com.application.motium.domain.model.StripePaymentStatus
import com.application.motium.domain.model.StripePaymentType
import com.application.motium.domain.model.StripeSubscription
import com.application.motium.domain.model.StripeSubscriptionStatus
import com.application.motium.domain.model.StripeSubscriptionType
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API-DIRECT: Paiements temps réel - Intentionnellement sans cache offline.
 *
 * Repository for managing Stripe subscriptions and payments in Supabase.
 * This is the main data source for subscription status.
 *
 * Not suitable for offline-first because:
 * - Payment status must always be real-time for security
 * - Subscription verification requires server-side validation
 * - Cached payment data could lead to fraud or access issues
 */
class StripeRepository private constructor(
    private val context: Context
) {
    private val supabaseClient = SupabaseClient.client
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        private const val TAG = "StripeRepo"

        @Volatile
        private var instance: StripeRepository? = null

        fun getInstance(context: Context): StripeRepository {
            return instance ?: synchronized(this) {
                instance ?: StripeRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // ========================================
    // SUBSCRIPTIONS
    // ========================================

    /**
     * Get all subscriptions for a user
     */
    suspend fun getUserSubscriptions(userId: String): Result<List<StripeSubscription>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_subscriptions")
                .select {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<StripeSubscriptionDto>()

            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            handleError("getUserSubscriptions", e)
        }
    }

    /**
     * Get active subscription for a user (if any)
     */
    suspend fun getUserActiveSubscription(userId: String): Result<StripeSubscription?> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_subscriptions")
                .select {
                    filter {
                        eq("user_id", userId)
                        or {
                            eq("status", "active")
                            eq("status", "trialing")
                            eq("status", "past_due")
                        }
                    }
                    order("created_at", Order.DESCENDING)
                    limit(1)
                }
                .decodeList<StripeSubscriptionDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: Exception) {
            handleError("getUserActiveSubscription", e)
        }
    }

    /**
     * Get all subscriptions for a Pro account
     */
    suspend fun getProAccountSubscriptions(proAccountId: String): Result<List<StripeSubscription>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_subscriptions")
                .select {
                    filter { eq("pro_account_id", proAccountId) }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<StripeSubscriptionDto>()

            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            handleError("getProAccountSubscriptions", e)
        }
    }

    /**
     * Get active subscriptions for a Pro account (licenses)
     */
    suspend fun getProAccountActiveSubscriptions(proAccountId: String): Result<List<StripeSubscription>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_subscriptions")
                .select {
                    filter {
                        eq("pro_account_id", proAccountId)
                        eq("status", "active")
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<StripeSubscriptionDto>()

            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            handleError("getProAccountActiveSubscriptions", e)
        }
    }

    /**
     * Get a subscription by Stripe ID
     */
    suspend fun getSubscriptionByStripeId(stripeSubscriptionId: String): Result<StripeSubscription?> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_subscriptions")
                .select {
                    filter { eq("stripe_subscription_id", stripeSubscriptionId) }
                    limit(1)
                }
                .decodeList<StripeSubscriptionDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: Exception) {
            handleError("getSubscriptionByStripeId", e)
        }
    }

    // ========================================
    // PAYMENTS
    // ========================================

    /**
     * Get all payments for a user
     */
    suspend fun getUserPayments(userId: String, limit: Int = 50): Result<List<StripePayment>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_payments")
                .select {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<StripePaymentDto>()

            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            handleError("getUserPayments", e)
        }
    }

    /**
     * Get all payments for a Pro account
     */
    suspend fun getProAccountPayments(proAccountId: String, limit: Int = 50): Result<List<StripePayment>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_payments")
                .select {
                    filter { eq("pro_account_id", proAccountId) }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<StripePaymentDto>()

            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            handleError("getProAccountPayments", e)
        }
    }

    /**
     * Get successful payments for a user
     */
    suspend fun getUserSuccessfulPayments(userId: String): Result<List<StripePayment>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_payments")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("status", "succeeded")
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<StripePaymentDto>()

            Result.success(response.map { it.toDomain() })
        } catch (e: Exception) {
            handleError("getUserSuccessfulPayments", e)
        }
    }

    /**
     * Get a payment by Stripe Payment Intent ID
     */
    suspend fun getPaymentByIntentId(paymentIntentId: String): Result<StripePayment?> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseClient.from("stripe_payments")
                .select {
                    filter { eq("stripe_payment_intent_id", paymentIntentId) }
                    limit(1)
                }
                .decodeList<StripePaymentDto>()

            Result.success(response.firstOrNull()?.toDomain())
        } catch (e: Exception) {
            handleError("getPaymentByIntentId", e)
        }
    }

    // ========================================
    // ERROR HANDLING
    // ========================================

    private suspend fun <T> handleError(operation: String, e: Exception): Result<T> {
        if (e.message?.contains("JWT expired") == true) {
            MotiumApplication.logger.w("JWT expired in $operation, attempting refresh...", TAG)
            val refreshed = tokenRefreshCoordinator.refreshIfNeeded(force = true)
            if (!refreshed) {
                MotiumApplication.logger.e("Token refresh failed in $operation", TAG)
            }
        }
        MotiumApplication.logger.e("Error in $operation: ${e.message}", TAG, e)
        return Result.failure(e)
    }
}

// ========================================
// DTOs
// ========================================

@Serializable
data class StripeSubscriptionDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("pro_account_id") val proAccountId: String? = null,
    @SerialName("stripe_subscription_id") val stripeSubscriptionId: String,
    @SerialName("stripe_customer_id") val stripeCustomerId: String,
    @SerialName("stripe_price_id") val stripePriceId: String? = null,
    @SerialName("stripe_product_id") val stripeProductId: String? = null,
    @SerialName("subscription_type") val subscriptionType: String,
    val status: String,
    val quantity: Int = 1,
    val currency: String = "eur",
    @SerialName("unit_amount_cents") val unitAmountCents: Int? = null,
    @SerialName("current_period_start") val currentPeriodStart: String? = null,
    @SerialName("current_period_end") val currentPeriodEnd: String? = null,
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    @SerialName("canceled_at") val canceledAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(): StripeSubscription = StripeSubscription(
        id = id,
        userId = userId,
        proAccountId = proAccountId,
        stripeSubscriptionId = stripeSubscriptionId,
        stripeCustomerId = stripeCustomerId,
        stripePriceId = stripePriceId,
        stripeProductId = stripeProductId,
        subscriptionType = StripeSubscriptionType.fromDbValue(subscriptionType),
        status = StripeSubscriptionStatus.fromDbValue(status),
        quantity = quantity,
        currency = currency,
        unitAmountCents = unitAmountCents,
        currentPeriodStart = currentPeriodStart?.let { parseInstant(it) },
        currentPeriodEnd = currentPeriodEnd?.let { parseInstant(it) },
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        canceledAt = canceledAt?.let { parseInstant(it) },
        endedAt = endedAt?.let { parseInstant(it) },
        metadata = metadata ?: emptyMap(),
        createdAt = createdAt?.let { parseInstant(it) },
        updatedAt = updatedAt?.let { parseInstant(it) }
    )
}

@Serializable
data class StripePaymentDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("pro_account_id") val proAccountId: String? = null,
    @SerialName("stripe_subscription_ref") val stripeSubscriptionRef: String? = null,
    @SerialName("stripe_payment_intent_id") val stripePaymentIntentId: String? = null,
    @SerialName("stripe_invoice_id") val stripeInvoiceId: String? = null,
    @SerialName("stripe_charge_id") val stripeChargeId: String? = null,
    @SerialName("stripe_customer_id") val stripeCustomerId: String,
    @SerialName("payment_type") val paymentType: String,
    @SerialName("amount_cents") val amountCents: Int,
    @SerialName("amount_received_cents") val amountReceivedCents: Int? = null,
    val currency: String = "eur",
    val status: String,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("failure_message") val failureMessage: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("invoice_pdf_url") val invoicePdfUrl: String? = null,
    @SerialName("hosted_invoice_url") val hostedInvoiceUrl: String? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    @SerialName("refund_id") val refundId: String? = null,
    @SerialName("refund_amount_cents") val refundAmountCents: Int? = null,
    @SerialName("refund_reason") val refundReason: String? = null,
    @SerialName("refunded_at") val refundedAt: String? = null,
    @SerialName("receipt_url") val receiptUrl: String? = null,
    @SerialName("receipt_email") val receiptEmail: String? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null
) {
    fun toDomain(): StripePayment = StripePayment(
        id = id,
        userId = userId,
        proAccountId = proAccountId,
        stripeSubscriptionRef = stripeSubscriptionRef,
        stripePaymentIntentId = stripePaymentIntentId,
        stripeInvoiceId = stripeInvoiceId,
        stripeChargeId = stripeChargeId,
        stripeCustomerId = stripeCustomerId,
        paymentType = StripePaymentType.fromDbValue(paymentType),
        amountCents = amountCents,
        amountReceivedCents = amountReceivedCents,
        currency = currency,
        status = StripePaymentStatus.fromDbValue(status),
        failureCode = failureCode,
        failureMessage = failureMessage,
        invoiceNumber = invoiceNumber,
        invoicePdfUrl = invoicePdfUrl,
        hostedInvoiceUrl = hostedInvoiceUrl,
        periodStart = periodStart?.let { parseInstant(it) },
        periodEnd = periodEnd?.let { parseInstant(it) },
        refundId = refundId,
        refundAmountCents = refundAmountCents,
        refundReason = refundReason,
        refundedAt = refundedAt?.let { parseInstant(it) },
        receiptUrl = receiptUrl,
        receiptEmail = receiptEmail,
        metadata = metadata ?: emptyMap(),
        createdAt = createdAt?.let { parseInstant(it) },
        updatedAt = updatedAt?.let { parseInstant(it) },
        paidAt = paidAt?.let { parseInstant(it) }
    )
}

/**
 * Parse ISO 8601 timestamp string to Instant
 */
private fun parseInstant(timestamp: String): Instant? {
    return try {
        Instant.parse(timestamp)
    } catch (e: Exception) {
        try {
            // Try parsing as timestamp with timezone offset
            val cleaned = timestamp.replace(" ", "T").replace("+00:00", "Z").replace("+00", "Z")
            Instant.parse(cleaned)
        } catch (e2: Exception) {
            null
        }
    }
}

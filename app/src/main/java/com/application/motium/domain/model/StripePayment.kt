package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain model for a Stripe payment/invoice.
 * Tracks all payment history including subscription payments and one-time purchases.
 */
@Serializable
data class StripePayment(
    val id: String,
    val userId: String? = null,
    val proAccountId: String? = null,
    val stripeSubscriptionRef: String? = null,
    val stripePaymentIntentId: String? = null,
    val stripeInvoiceId: String? = null,
    val stripeChargeId: String? = null,
    val stripeCustomerId: String,
    val paymentType: StripePaymentType,
    val amountCents: Int,
    val amountReceivedCents: Int? = null,
    val currency: String = "eur",
    val status: StripePaymentStatus,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val invoiceNumber: String? = null,
    val invoicePdfUrl: String? = null,
    val hostedInvoiceUrl: String? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant? = null,
    val refundId: String? = null,
    val refundAmountCents: Int? = null,
    val refundReason: String? = null,
    val refundedAt: Instant? = null,
    val receiptUrl: String? = null,
    val receiptEmail: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val paidAt: Instant? = null
) {
    /**
     * Get the amount in euros
     */
    fun getAmountEuros(): Double = amountCents / 100.0

    /**
     * Get the received amount in euros
     */
    fun getAmountReceivedEuros(): Double? = amountReceivedCents?.let { it / 100.0 }

    /**
     * Get the refund amount in euros
     */
    fun getRefundAmountEuros(): Double? = refundAmountCents?.let { it / 100.0 }

    /**
     * Check if the payment succeeded
     */
    fun isSuccessful(): Boolean = status == StripePaymentStatus.SUCCEEDED

    /**
     * Check if the payment failed
     */
    fun isFailed(): Boolean = status == StripePaymentStatus.FAILED

    /**
     * Check if the payment was refunded (partially or fully)
     */
    fun isRefunded(): Boolean = status in listOf(
        StripePaymentStatus.REFUNDED,
        StripePaymentStatus.PARTIALLY_REFUNDED
    )

    /**
     * Check if this is a subscription payment (recurring)
     */
    fun isRecurring(): Boolean = paymentType == StripePaymentType.SUBSCRIPTION_PAYMENT

    /**
     * Check if this is a one-time payment (lifetime)
     */
    fun isOneTime(): Boolean = paymentType == StripePaymentType.ONE_TIME_PAYMENT

    /**
     * Get formatted amount for display (e.g., "4,99 EUR")
     */
    fun getFormattedAmount(): String {
        val euros = getAmountEuros()
        return String.format("%.2f %s", euros, currency.uppercase())
    }
}

/**
 * Type of payment
 */
@Serializable
enum class StripePaymentType {
    @SerialName("subscription_payment")
    SUBSCRIPTION_PAYMENT,       // Recurring monthly invoice

    @SerialName("one_time_payment")
    ONE_TIME_PAYMENT,           // Lifetime purchase

    @SerialName("setup_payment")
    SETUP_PAYMENT;              // Initial subscription setup

    fun toDbValue(): String = when (this) {
        SUBSCRIPTION_PAYMENT -> "subscription_payment"
        ONE_TIME_PAYMENT -> "one_time_payment"
        SETUP_PAYMENT -> "setup_payment"
    }

    companion object {
        fun fromDbValue(value: String): StripePaymentType = when (value) {
            "subscription_payment" -> SUBSCRIPTION_PAYMENT
            "one_time_payment" -> ONE_TIME_PAYMENT
            "setup_payment" -> SETUP_PAYMENT
            else -> throw IllegalArgumentException("Unknown payment type: $value")
        }
    }
}

/**
 * Status of a payment
 */
@Serializable
enum class StripePaymentStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("processing")
    PROCESSING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    @SerialName("refunded")
    REFUNDED,

    @SerialName("partially_refunded")
    PARTIALLY_REFUNDED,

    @SerialName("disputed")
    DISPUTED,

    @SerialName("canceled")
    CANCELED;

    fun toDbValue(): String = when (this) {
        PENDING -> "pending"
        PROCESSING -> "processing"
        SUCCEEDED -> "succeeded"
        FAILED -> "failed"
        REFUNDED -> "refunded"
        PARTIALLY_REFUNDED -> "partially_refunded"
        DISPUTED -> "disputed"
        CANCELED -> "canceled"
    }

    companion object {
        fun fromDbValue(value: String): StripePaymentStatus = when (value) {
            "pending" -> PENDING
            "processing" -> PROCESSING
            "succeeded" -> SUCCEEDED
            "failed" -> FAILED
            "refunded" -> REFUNDED
            "partially_refunded" -> PARTIALLY_REFUNDED
            "disputed" -> DISPUTED
            "canceled" -> CANCELED
            else -> throw IllegalArgumentException("Unknown payment status: $value")
        }
    }
}

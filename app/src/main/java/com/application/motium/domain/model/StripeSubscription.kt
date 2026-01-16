package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain model for a Stripe subscription.
 * This represents both Individual subscriptions and Pro license subscriptions.
 */
@Serializable
data class StripeSubscription(
    val id: String,
    val userId: String? = null,
    val proAccountId: String? = null,
    val stripeSubscriptionId: String,
    val stripeCustomerId: String,
    val stripePriceId: String? = null,
    val stripeProductId: String? = null,
    val subscriptionType: StripeSubscriptionType,
    val status: StripeSubscriptionStatus,
    val quantity: Int = 1,
    val currency: String = "eur",
    val unitAmountCents: Int? = null,
    val currentPeriodStart: Instant? = null,
    val currentPeriodEnd: Instant? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val canceledAt: Instant? = null,
    val endedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    /**
     * Check if the subscription is currently active
     */
    fun isActive(): Boolean = status in listOf(
        StripeSubscriptionStatus.ACTIVE,
        StripeSubscriptionStatus.TRIALING
    )

    /**
     * Check if this is a lifetime (one-time purchase) subscription
     */
    fun isLifetime(): Boolean = subscriptionType in listOf(
        StripeSubscriptionType.INDIVIDUAL_LIFETIME,
        StripeSubscriptionType.PRO_LICENSE_LIFETIME
    )

    /**
     * Check if this is an Individual subscription (vs Pro)
     */
    fun isIndividual(): Boolean = subscriptionType in listOf(
        StripeSubscriptionType.INDIVIDUAL_MONTHLY,
        StripeSubscriptionType.INDIVIDUAL_LIFETIME
    )

    /**
     * Check if this is a Pro license subscription
     */
    fun isProLicense(): Boolean = subscriptionType in listOf(
        StripeSubscriptionType.PRO_LICENSE_MONTHLY,
        StripeSubscriptionType.PRO_LICENSE_LIFETIME
    )

    /**
     * Get the total amount in euros
     */
    fun getTotalAmountEuros(): Double? {
        return unitAmountCents?.let { (it * quantity) / 100.0 }
    }

    /**
     * Get days remaining until expiration (null for lifetime)
     */
    fun getDaysRemaining(): Int? {
        if (isLifetime()) return null
        val end = currentPeriodEnd ?: return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val diffMs = end.toEpochMilliseconds() - now.toEpochMilliseconds()
        return (diffMs / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
    }

    companion object {
        // Stripe Product IDs (Test Mode)
        const val PRODUCT_INDIVIDUAL_MONTHLY = "prod_TdmBT4sDscYZer"
        const val PRODUCT_INDIVIDUAL_LIFETIME = "prod_Tdm94ZsJEGevzK"
        const val PRODUCT_PRO_LICENSE_MONTHLY = "prod_Tdm6mAbHVHJLxz"
        const val PRODUCT_PRO_LICENSE_LIFETIME = "prod_TdmC9Jq3tCk94E"

        // Prices in cents
        const val PRICE_INDIVIDUAL_MONTHLY_CENTS = 499      // 4.99 EUR TTC
        const val PRICE_INDIVIDUAL_LIFETIME_CENTS = 12000   // 120.00 EUR TTC
        const val PRICE_PRO_LICENSE_MONTHLY_CENTS = 600     // 6.00 EUR TTC (5.00 EUR HT)
        const val PRICE_PRO_LICENSE_LIFETIME_CENTS = 14400  // 144.00 EUR TTC (120.00 EUR HT)
    }
}

/**
 * Type of Stripe subscription
 */
@Serializable
enum class StripeSubscriptionType {
    @SerialName("individual_monthly")
    INDIVIDUAL_MONTHLY,

    @SerialName("individual_lifetime")
    INDIVIDUAL_LIFETIME,

    @SerialName("pro_license_monthly")
    PRO_LICENSE_MONTHLY,

    @SerialName("pro_license_lifetime")
    PRO_LICENSE_LIFETIME;

    fun toDbValue(): String = when (this) {
        INDIVIDUAL_MONTHLY -> "individual_monthly"
        INDIVIDUAL_LIFETIME -> "individual_lifetime"
        PRO_LICENSE_MONTHLY -> "pro_license_monthly"
        PRO_LICENSE_LIFETIME -> "pro_license_lifetime"
    }

    companion object {
        fun fromDbValue(value: String): StripeSubscriptionType = when (value) {
            "individual_monthly" -> INDIVIDUAL_MONTHLY
            "individual_lifetime" -> INDIVIDUAL_LIFETIME
            "pro_license_monthly" -> PRO_LICENSE_MONTHLY
            "pro_license_lifetime" -> PRO_LICENSE_LIFETIME
            else -> throw IllegalArgumentException("Unknown subscription type: $value")
        }
    }
}

/**
 * Status of a Stripe subscription (mirrors Stripe's subscription statuses)
 */
@Serializable
enum class StripeSubscriptionStatus {
    @SerialName("incomplete")
    INCOMPLETE,

    @SerialName("incomplete_expired")
    INCOMPLETE_EXPIRED,

    @SerialName("trialing")
    TRIALING,

    @SerialName("active")
    ACTIVE,

    @SerialName("past_due")
    PAST_DUE,

    @SerialName("canceled")
    CANCELED,

    @SerialName("unpaid")
    UNPAID,

    @SerialName("paused")
    PAUSED;

    fun toDbValue(): String = when (this) {
        INCOMPLETE -> "incomplete"
        INCOMPLETE_EXPIRED -> "incomplete_expired"
        TRIALING -> "trialing"
        ACTIVE -> "active"
        PAST_DUE -> "past_due"
        CANCELED -> "canceled"
        UNPAID -> "unpaid"
        PAUSED -> "paused"
    }

    companion object {
        fun fromDbValue(value: String): StripeSubscriptionStatus = when (value) {
            "incomplete" -> INCOMPLETE
            "incomplete_expired" -> INCOMPLETE_EXPIRED
            "trialing" -> TRIALING
            "active" -> ACTIVE
            "past_due" -> PAST_DUE
            "canceled" -> CANCELED
            "unpaid" -> UNPAID
            "paused" -> PAUSED
            else -> throw IllegalArgumentException("Unknown subscription status: $value")
        }
    }
}

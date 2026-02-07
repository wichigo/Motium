package com.application.motium.domain.model

/**
 * Shared discount policy for Pro license purchases.
 *
 * Rule:
 * - Single purchase: -1% per existing license
 * - Bulk purchase (>1): -1% per (existing licenses + purchase quantity)
 * - Capped at -50%
 */
object ProLicensePricing {
    const val DISCOUNT_PER_LICENSE_PERCENT = 1
    const val MAX_DISCOUNT_PERCENT = 50

    fun calculateDiscountPercent(existingLicenses: Int, purchaseQuantity: Int): Int {
        if (purchaseQuantity <= 0) return 0

        val normalizedExisting = existingLicenses.coerceAtLeast(0)
        val baseLicensesForDiscount = if (purchaseQuantity == 1) {
            normalizedExisting
        } else {
            normalizedExisting + purchaseQuantity
        }
        val rawPercent = baseLicensesForDiscount * DISCOUNT_PER_LICENSE_PERCENT

        return rawPercent.coerceIn(0, MAX_DISCOUNT_PERCENT)
    }

    fun applyDiscount(amount: Double, discountPercent: Int): Double {
        val safePercent = discountPercent.coerceIn(0, MAX_DISCOUNT_PERCENT)
        return amount * (1 - safePercent / 100.0)
    }

    fun applyDiscountCents(unitPriceCents: Int, discountPercent: Int): Int {
        val safePercent = discountPercent.coerceIn(0, MAX_DISCOUNT_PERCENT)
        // Integer cent rounding (half-up).
        return ((unitPriceCents * (100 - safePercent)) + 50) / 100
    }
}

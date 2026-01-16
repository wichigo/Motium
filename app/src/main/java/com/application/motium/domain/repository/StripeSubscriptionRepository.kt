package com.application.motium.domain.repository

import com.application.motium.domain.model.StripeSubscription
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for offline-first Stripe subscription management.
 * Uses local Room database as source of truth with background sync to Supabase.
 *
 * Note: Actual Stripe API calls go through StripeRepository (data/supabase/).
 * This repository is for cached subscription data display.
 */
interface StripeSubscriptionRepository {
    /**
     * Get all subscriptions for a user (reactive Flow).
     */
    fun getSubscriptionsForUser(userId: String): Flow<List<StripeSubscription>>

    /**
     * Get active subscription for a user (if any).
     */
    fun getActiveSubscription(userId: String): Flow<StripeSubscription?>

    /**
     * Get subscriptions for a Pro account.
     */
    fun getSubscriptionsForProAccount(proAccountId: String): Flow<List<StripeSubscription>>

    /**
     * Check if user has an active subscription.
     */
    suspend fun hasActiveSubscription(userId: String): Boolean
}

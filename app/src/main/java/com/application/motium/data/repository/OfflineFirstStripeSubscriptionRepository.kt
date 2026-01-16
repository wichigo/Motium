package com.application.motium.data.repository

import android.content.Context
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.dao.StripeSubscriptionDao
import com.application.motium.domain.model.StripeSubscription
import com.application.motium.domain.repository.StripeSubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline-first implementation of StripeSubscriptionRepository.
 * Uses local Room database as source of truth.
 *
 * Note: This repository is read-only for cached data.
 * Actual Stripe API calls go through StripeRepository (data/supabase/).
 * Subscriptions are synced FROM Supabase TO local (one-way sync).
 */
class OfflineFirstStripeSubscriptionRepository private constructor(
    private val context: Context
) : StripeSubscriptionRepository {

    companion object {
        private const val TAG = "OfflineFirstStripeSubRepo"

        @Volatile
        private var instance: OfflineFirstStripeSubscriptionRepository? = null

        fun getInstance(context: Context): OfflineFirstStripeSubscriptionRepository {
            return instance ?: synchronized(this) {
                instance ?: OfflineFirstStripeSubscriptionRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = MotiumDatabase.getInstance(context)
    private val stripeSubscriptionDao: StripeSubscriptionDao = database.stripeSubscriptionDao()

    // ==================== FLOW-BASED QUERIES ====================

    override fun getSubscriptionsForUser(userId: String): Flow<List<StripeSubscription>> {
        return stripeSubscriptionDao.getByUserId(userId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getActiveSubscription(userId: String): Flow<StripeSubscription?> {
        return stripeSubscriptionDao.getActiveSubscription(userId).map { it?.toDomain() }
    }

    override fun getSubscriptionsForProAccount(proAccountId: String): Flow<List<StripeSubscription>> {
        return stripeSubscriptionDao.getByProAccount(proAccountId).map { list ->
            list.map { it.toDomain() }
        }
    }

    // ==================== ONE-SHOT QUERIES ====================

    override suspend fun hasActiveSubscription(userId: String): Boolean {
        return stripeSubscriptionDao.getActiveSubscriptionOnce(userId) != null
    }
}

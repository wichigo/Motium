package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.application.motium.data.local.entities.StripeSubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StripeSubscriptionDao {
    @Query("SELECT * FROM stripe_subscriptions WHERE oduserId = :userId")
    fun getByUserId(userId: String): Flow<List<StripeSubscriptionEntity>>

    @Query("SELECT * FROM stripe_subscriptions WHERE oduserId = :userId AND status IN ('active', 'trialing') ORDER BY createdAt DESC LIMIT 1")
    fun getActiveSubscription(userId: String): Flow<StripeSubscriptionEntity?>

    @Query("SELECT * FROM stripe_subscriptions WHERE proAccountId = :proAccountId")
    fun getByProAccount(proAccountId: String): Flow<List<StripeSubscriptionEntity>>

    @Query("SELECT * FROM stripe_subscriptions WHERE id = :id")
    fun getById(id: String): Flow<StripeSubscriptionEntity?>

    @Query("SELECT * FROM stripe_subscriptions WHERE syncStatus != 'SYNCED'")
    suspend fun getPendingSync(): List<StripeSubscriptionEntity>

    @Query("SELECT * FROM stripe_subscriptions WHERE oduserId = :userId AND status IN ('active', 'trialing') LIMIT 1")
    suspend fun getActiveSubscriptionOnce(userId: String): StripeSubscriptionEntity?

    @Upsert
    suspend fun upsert(subscription: StripeSubscriptionEntity)

    @Upsert
    suspend fun upsertAll(subscriptions: List<StripeSubscriptionEntity>)

    @Query("UPDATE stripe_subscriptions SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("DELETE FROM stripe_subscriptions WHERE oduserId = :userId")
    suspend fun deleteByUserId(userId: String)

    @Query("DELETE FROM stripe_subscriptions")
    suspend fun deleteAll()
}

package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.WithdrawalWaiver
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository pour gérer les renonciations au droit de rétractation.
 * Conformément à l'article L221-28 du Code de la consommation français.
 */
class WithdrawalWaiverRepository(private val context: Context) {

    private val client = SupabaseClient.client
    private val auth: Auth = client.auth
    private val postgres = client.postgrest
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val secureSessionStorage by lazy { SecureSessionStorage(context) }
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    companion object {
        private const val TAG = "WithdrawalWaiverRepo"

        @Volatile
        private var instance: WithdrawalWaiverRepository? = null

        fun getInstance(context: Context): WithdrawalWaiverRepository {
            return instance ?: synchronized(this) {
                instance ?: WithdrawalWaiverRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // === DTO ===

    @Serializable
    data class WithdrawalWaiverDto(
        val id: String? = null,
        @SerialName("user_id") val userId: String,
        @SerialName("accepted_immediate_execution") val acceptedImmediateExecution: Boolean,
        @SerialName("accepted_waiver") val acceptedWaiver: Boolean,
        @SerialName("ip_address") val ipAddress: String? = null,
        @SerialName("user_agent") val userAgent: String? = null,
        @SerialName("app_version") val appVersion: String,
        @SerialName("consented_at") val consentedAt: String,
        @SerialName("subscription_id") val subscriptionId: String? = null,
        @SerialName("created_at") val createdAt: String? = null
    )

    // === Private helpers ===

    private suspend fun ensureValidToken() {
        if (secureSessionStorage.isTokenExpired() || secureSessionStorage.isTokenExpiringSoon(1)) {
            MotiumApplication.logger.i("Token expired or expiring soon, refreshing...", TAG)
            tokenRefreshCoordinator.refreshIfNeeded(force = true)
            kotlinx.coroutines.delay(100)
        }
    }

    private suspend fun getCurrentAuthUserId(): String? {
        return auth.currentUserOrNull()?.id
    }

    // === Public API ===

    /**
     * Sauvegarde le consentement de renonciation au droit de rétractation.
     * Doit être appelé AVANT le paiement.
     */
    suspend fun saveWaiver(waiver: WithdrawalWaiver): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()

            val authUserId = getCurrentAuthUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            val dto = WithdrawalWaiverDto(
                userId = authUserId,
                acceptedImmediateExecution = waiver.acceptedImmediateExecution,
                acceptedWaiver = waiver.acceptedWaiver,
                appVersion = waiver.appVersion,
                consentedAt = waiver.consentedAt.toString(),
                userAgent = "Motium Android ${BuildConfig.VERSION_NAME}"
            )

            postgres.from("withdrawal_waivers").insert(dto)

            MotiumApplication.logger.i("Withdrawal waiver saved for user $authUserId", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to save withdrawal waiver: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Met à jour le subscription_id après un paiement réussi.
     */
    suspend fun updateSubscriptionId(subscriptionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()

            val authUserId = getCurrentAuthUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            // Récupérer tous les waivers de l'utilisateur et filtrer côté client
            val allWaivers = postgres.from("withdrawal_waivers")
                .select {
                    filter {
                        eq("user_id", authUserId)
                    }
                    order("consented_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
                .decodeList<WithdrawalWaiverDto>()

            // Trouver le dernier waiver sans subscription_id
            val latestWaiver = allWaivers.firstOrNull { it.subscriptionId == null }

            if (latestWaiver != null && latestWaiver.id != null) {
                postgres.from("withdrawal_waivers")
                    .update({
                        set("subscription_id", subscriptionId)
                    }) {
                        filter { eq("id", latestWaiver.id) }
                    }
                MotiumApplication.logger.i("Subscription ID $subscriptionId linked to waiver", TAG)
            } else {
                MotiumApplication.logger.w("No pending waiver found to link subscription", TAG)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to update subscription ID: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    /**
     * Vérifie si l'utilisateur a un waiver valide récent (non associé à un paiement).
     */
    suspend fun hasValidWaiver(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureValidToken()

            val authUserId = getCurrentAuthUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            val allWaivers = postgres.from("withdrawal_waivers")
                .select {
                    filter {
                        eq("user_id", authUserId)
                        eq("accepted_immediate_execution", true)
                        eq("accepted_waiver", true)
                    }
                }
                .decodeList<WithdrawalWaiverDto>()

            // Filtrer côté client pour trouver ceux sans subscription_id
            val validWaivers = allWaivers.filter { it.subscriptionId == null }

            Result.success(validWaivers.isNotEmpty())
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to check waiver status: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }
}

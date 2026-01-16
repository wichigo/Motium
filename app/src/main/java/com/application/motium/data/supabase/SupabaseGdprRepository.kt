package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.preferences.SecureSessionStorage
import com.application.motium.data.sync.TokenRefreshCoordinator
import com.application.motium.domain.model.ConsentInfo
import com.application.motium.domain.model.ConsentType
import com.application.motium.domain.model.DataRetentionPolicy
import com.application.motium.domain.model.DeletionCounts
import com.application.motium.domain.model.DeletionSummary
import com.application.motium.domain.model.GdprDeletionResult
import com.application.motium.domain.model.GdprExportResult
import com.application.motium.domain.model.GdprRequestStatus
import com.application.motium.domain.model.GdprRequestType
import com.application.motium.domain.model.PrivacyPolicyAcceptance
import com.application.motium.domain.repository.GdprRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Implémentation Supabase du repository RGPD
 */
class SupabaseGdprRepository(private val context: Context) : GdprRepository {

    private val client = SupabaseClient.client
    private val auth: Auth = client.auth
    private val postgres = client.postgrest
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val secureSessionStorage by lazy { SecureSessionStorage(context) }
    private val tokenRefreshCoordinator by lazy { TokenRefreshCoordinator.getInstance(context) }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val TAG = "SupabaseGdprRepo"
        private const val CURRENT_POLICY_VERSION = "1.0"

        @Volatile
        private var instance: SupabaseGdprRepository? = null

        fun getInstance(context: Context): SupabaseGdprRepository {
            return instance ?: synchronized(this) {
                instance ?: SupabaseGdprRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // === DTOs ===

    @Serializable
    data class UserConsentDto(
        val id: String? = null,
        @SerialName("user_id") val userId: String,
        @SerialName("consent_type") val consentType: String,
        val granted: Boolean,
        @SerialName("granted_at") val grantedAt: String? = null,
        @SerialName("revoked_at") val revokedAt: String? = null,
        @SerialName("consent_version") val consentVersion: String,
        @SerialName("ip_address") val ipAddress: String? = null,
        @SerialName("user_agent") val userAgent: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null
    )

    @Serializable
    data class PrivacyPolicyAcceptanceDto(
        val id: String? = null,
        @SerialName("user_id") val userId: String,
        @SerialName("policy_version") val policyVersion: String,
        @SerialName("policy_url") val policyUrl: String,
        @SerialName("policy_hash") val policyHash: String? = null,
        @SerialName("accepted_at") val acceptedAt: String,
        @SerialName("ip_address") val ipAddress: String? = null
    )

    @Serializable
    data class GdprDataRequestDto(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("request_type") val requestType: String,
        val status: String,
        @SerialName("export_file_url") val exportFileUrl: String? = null,
        @SerialName("expires_at") val expiresAt: String? = null,
        @SerialName("deletion_summary") val deletionSummary: JsonObject? = null,
        @SerialName("created_at") val createdAt: String
    )

    // === Implémentation ===

    /**
     * Ensures we have a valid token before making API requests.
     * Proactively refreshes if token is expired or expiring soon (within 1 minute).
     */
    private suspend fun ensureValidToken() {
        if (secureSessionStorage.isTokenExpired() || secureSessionStorage.isTokenExpiringSoon(1)) {
            MotiumApplication.logger.i("🔄 Token expired or expiring soon, refreshing proactively...", TAG)
            tokenRefreshCoordinator.refreshIfNeeded(force = true)
            // Add small delay to ensure Supabase client picks up the new token
            kotlinx.coroutines.delay(100)
        }
    }

    private suspend fun getCurrentUserId(): String? {
        // D'abord essayer depuis le cache local (offline-first)
        val localUser = localUserRepository.getLoggedInUser()
        if (localUser != null) {
            return localUser.id
        }

        // Sinon, essayer depuis Supabase Auth
        return auth.currentUserOrNull()?.id
    }

    override suspend fun getConsents(): Result<List<ConsentInfo>> = withContext(Dispatchers.IO) {
        try {
            // Proactively refresh token if expired before making request
            ensureValidToken()

            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            val consentsDto = postgres.from("user_consents")
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<UserConsentDto>()

            // Mapper les DTOs vers ConsentInfo
            val consents = mapConsentsFromDto(consentsDto)

            MotiumApplication.logger.i("✅ Consentements récupérés: ${consents.size}", TAG)
            Result.success(consents)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once (fallback if proactive refresh missed it)
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired (proactive refresh failed?), retrying...", TAG)
                tokenRefreshCoordinator.refreshIfNeeded(force = true)
                kotlinx.coroutines.delay(200) // Give client time to update

                return@withContext try {
                    val userId = getCurrentUserId()
                        ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

                    val consentsDto = postgres.from("user_consents")
                        .select {
                            filter { eq("user_id", userId) }
                        }
                        .decodeList<UserConsentDto>()

                    val consents = mapConsentsFromDto(consentsDto)
                    MotiumApplication.logger.i("✅ Consentements récupérés après refresh: ${consents.size}", TAG)
                    Result.success(consents)
                } catch (retryError: Exception) {
                    MotiumApplication.logger.e("❌ Erreur après refresh token: ${retryError.message}", TAG, retryError)
                    Result.failure(retryError)
                }
            }
            MotiumApplication.logger.e("❌ Erreur récupération consentements: ${e.message}", TAG, e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur récupération consentements: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    private fun mapConsentsFromDto(consentsDto: List<UserConsentDto>): List<ConsentInfo> {
        return ConsentType.entries.map { type ->
            val dto = consentsDto.find { it.consentType == type.name.lowercase() }
            val required = isConsentRequired(type)

            ConsentInfo(
                type = type,
                title = getConsentTitle(type),
                description = getConsentDescription(type),
                isRequired = required,
                // Les consentements obligatoires sont true par défaut
                granted = dto?.granted ?: required,
                grantedAt = dto?.grantedAt?.let { Instant.parse(it) },
                revokedAt = dto?.revokedAt?.let { Instant.parse(it) },
                version = dto?.consentVersion ?: CURRENT_POLICY_VERSION
            )
        }
    }

    override suspend fun updateConsent(
        type: ConsentType,
        granted: Boolean,
        version: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Proactively refresh token if expired before making request
            ensureValidToken()
            performConsentUpdate(type, granted, version)
        } catch (e: PostgrestRestException) {
            // JWT expired - refresh token and retry once (fallback)
            if (e.message?.contains("JWT expired") == true) {
                MotiumApplication.logger.w("JWT expired (proactive refresh failed?), retrying...", TAG)
                tokenRefreshCoordinator.refreshIfNeeded(force = true)
                kotlinx.coroutines.delay(200) // Give client time to update

                return@withContext try {
                    performConsentUpdate(type, granted, version)
                } catch (retryError: Exception) {
                    MotiumApplication.logger.e("❌ Erreur après refresh token: ${retryError.message}", TAG, retryError)
                    Result.failure(retryError)
                }
            }
            MotiumApplication.logger.e("❌ Erreur mise à jour consentement: ${e.message}", TAG, e)
            Result.failure(e)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur mise à jour consentement: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    private suspend fun performConsentUpdate(
        type: ConsentType,
        granted: Boolean,
        version: String
    ): Result<Unit> {
        val userId = getCurrentUserId()
            ?: return Result.failure(Exception("Utilisateur non connecté"))

        // Vérifier si c'est un consentement requis
        if (isConsentRequired(type) && !granted) {
            return Result.failure(
                Exception("Le consentement '${getConsentTitle(type)}' est obligatoire")
            )
        }

        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
        val consentDto = UserConsentDto(
            userId = userId,
            consentType = type.name.lowercase(),
            granted = granted,
            grantedAt = if (granted) now else null,
            revokedAt = if (!granted) now else null,
            consentVersion = version
        )

        // Upsert le consentement
        postgres.from("user_consents")
            .upsert(consentDto) {
                onConflict = "user_id,consent_type"
            }

        // Logger l'action pour l'audit
        logGdprAction(
            action = if (granted) "consent_granted" else "consent_revoked",
            details = "Consentement ${type.name.lowercase()} ${if (granted) "accordé" else "révoqué"}"
        )

        MotiumApplication.logger.i("✅ Consentement ${type.name} mis à jour: $granted", TAG)
        return Result.success(Unit)
    }

    override suspend fun initializeDefaultConsents(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()

            // Créer les consentements par défaut
            val defaultConsents = listOf(
                UserConsentDto(
                    userId = userId,
                    consentType = ConsentType.LOCATION_TRACKING.name.lowercase(),
                    granted = true,
                    grantedAt = now,
                    consentVersion = CURRENT_POLICY_VERSION
                ),
                UserConsentDto(
                    userId = userId,
                    consentType = ConsentType.DATA_COLLECTION.name.lowercase(),
                    granted = true,
                    grantedAt = now,
                    consentVersion = CURRENT_POLICY_VERSION
                ),
                UserConsentDto(
                    userId = userId,
                    consentType = ConsentType.COMPANY_DATA_SHARING.name.lowercase(),
                    granted = false,
                    consentVersion = CURRENT_POLICY_VERSION
                ),
                UserConsentDto(
                    userId = userId,
                    consentType = ConsentType.ANALYTICS.name.lowercase(),
                    granted = false,
                    consentVersion = CURRENT_POLICY_VERSION
                ),
                UserConsentDto(
                    userId = userId,
                    consentType = ConsentType.MARKETING.name.lowercase(),
                    granted = false,
                    consentVersion = CURRENT_POLICY_VERSION
                )
            )

            defaultConsents.forEach { consent ->
                postgres.from("user_consents")
                    .upsert(consent) {
                        onConflict = "user_id,consent_type"
                    }
            }

            MotiumApplication.logger.i("✅ Consentements par défaut initialisés", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur initialisation consentements: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    override suspend fun hasAcceptedPolicy(version: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext false

            val acceptances = postgres.from("privacy_policy_acceptances")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("policy_version", version)
                    }
                }
                .decodeList<PrivacyPolicyAcceptanceDto>()

            acceptances.isNotEmpty()
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur vérification politique: ${e.message}", TAG, e)
            false
        }
    }

    override suspend fun acceptPrivacyPolicy(
        version: String,
        policyUrl: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
            val acceptance = PrivacyPolicyAcceptanceDto(
                userId = userId,
                policyVersion = version,
                policyUrl = policyUrl,
                acceptedAt = now
            )

            postgres.from("privacy_policy_acceptances").insert(acceptance)

            logGdprAction(
                action = "policy_accepted",
                details = "Politique de confidentialité v$version acceptée"
            )

            MotiumApplication.logger.i("✅ Politique v$version acceptée", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur acceptation politique: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    override suspend fun getPolicyAcceptances(): Result<List<PrivacyPolicyAcceptance>> =
        withContext(Dispatchers.IO) {
            try {
                val userId = getCurrentUserId()
                    ?: return@withContext Result.failure(Exception("Utilisateur non connecté"))

                val dtos = postgres.from("privacy_policy_acceptances")
                    .select {
                        filter { eq("user_id", userId) }
                    }
                    .decodeList<PrivacyPolicyAcceptanceDto>()

                val acceptances = dtos.map { dto ->
                    PrivacyPolicyAcceptance(
                        userId = dto.userId,
                        policyVersion = dto.policyVersion,
                        policyUrl = dto.policyUrl,
                        acceptedAt = Instant.parse(dto.acceptedAt)
                    )
                }

                Result.success(acceptances)
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Erreur récupération acceptations: ${e.message}", TAG, e)
                Result.failure(e)
            }
        }

    override suspend fun requestDataExport(): Result<GdprExportResult> = withContext(Dispatchers.IO) {
        try {
            val accessToken = auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Session expirée"))

            // Appeler l'Edge Function gdpr-export
            val supabaseUrl = com.application.motium.BuildConfig.SUPABASE_URL
            val requestBody = "{}".toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$supabaseUrl/functions/v1/gdpr-export")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorJson = json.parseToJsonElement(responseBody).jsonObject
                val errorMessage = errorJson["error"]?.jsonPrimitive?.content
                    ?: "Erreur lors de la demande d'export"
                return@withContext Result.failure(Exception(errorMessage))
            }

            val resultJson = json.parseToJsonElement(responseBody).jsonObject

            val exportResult = GdprExportResult(
                success = true,
                requestId = resultJson["request_id"]?.jsonPrimitive?.content,
                downloadUrl = resultJson["download_url"]?.jsonPrimitive?.content,
                expiresAt = resultJson["expires_at"]?.jsonPrimitive?.content?.let { Instant.parse(it) },
                errorMessage = null
            )

            MotiumApplication.logger.i("✅ Demande d'export créée: ${exportResult.requestId}", TAG)
            Result.success(exportResult)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur demande export: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    override suspend fun checkExportStatus(requestId: String): Result<GdprExportResult> =
        withContext(Dispatchers.IO) {
            try {
                val dto = postgres.from("gdpr_data_requests")
                    .select {
                        filter { eq("id", requestId) }
                    }
                    .decodeSingle<GdprDataRequestDto>()

                val exportResult = GdprExportResult(
                    success = dto.status == "completed",
                    requestId = dto.id,
                    downloadUrl = dto.exportFileUrl,
                    expiresAt = dto.expiresAt?.let { Instant.parse(it) },
                    errorMessage = if (dto.status == "failed") "L'export a échoué" else null
                )

                Result.success(exportResult)
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Erreur vérification statut: ${e.message}", TAG, e)
                Result.failure(e)
            }
        }

    override suspend fun requestAccountDeletion(
        confirmation: String,
        reason: String?
    ): Result<GdprDeletionResult> = withContext(Dispatchers.IO) {
        try {
            if (confirmation != "DELETE_MY_ACCOUNT") {
                return@withContext Result.failure(
                    Exception("Confirmation incorrecte. Tapez exactement 'DELETE_MY_ACCOUNT'")
                )
            }

            val accessToken = auth.currentSessionOrNull()?.accessToken
                ?: return@withContext Result.failure(Exception("Session expirée"))

            // Appeler l'Edge Function gdpr-delete-account
            val supabaseUrl = com.application.motium.BuildConfig.SUPABASE_URL
            val requestBodyJson = buildJsonObject {
                put("confirmation", confirmation)
                reason?.let { put("reason", it) }
            }.toString()

            val requestBody = requestBodyJson.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$supabaseUrl/functions/v1/gdpr-delete-account")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorJson = json.parseToJsonElement(responseBody).jsonObject
                val errorMessage = errorJson["error"]?.jsonPrimitive?.content
                    ?: "Erreur lors de la suppression du compte"
                return@withContext Result.failure(Exception(errorMessage))
            }

            val resultJson = json.parseToJsonElement(responseBody).jsonObject

            val deletionResult = GdprDeletionResult(
                success = true,
                message = resultJson["message"]?.jsonPrimitive?.content,
                deletionSummary = null, // Sera parsé du JSON si présent
                errorMessage = null
            )

            MotiumApplication.logger.i("✅ Compte supprimé avec succès", TAG)
            Result.success(deletionResult)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur suppression compte: ${e.message}", TAG, e)
            Result.failure(e)
        }
    }

    override suspend fun getRetentionPolicies(): Result<List<DataRetentionPolicy>> =
        withContext(Dispatchers.IO) {
            try {
                val policies = postgres.from("data_retention_policies")
                    .select()
                    .decodeList<DataRetentionPolicy>()

                Result.success(policies)
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Erreur récupération politiques: ${e.message}", TAG, e)
                Result.failure(e)
            }
        }

    override suspend fun logGdprAction(action: String, details: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val userId = getCurrentUserId() ?: return@withContext Result.success(Unit)

                postgres.rpc(
                    function = "log_gdpr_action",
                    parameters = buildJsonObject {
                        put("p_user_id", userId)
                        put("p_action", action)
                        put("p_details", details)
                    }
                )

                Result.success(Unit)
            } catch (e: Exception) {
                // Ne pas faire échouer l'opération principale si le log échoue
                MotiumApplication.logger.w("⚠️ Erreur log GDPR: ${e.message}", TAG)
                Result.success(Unit)
            }
        }

    // === Helpers ===

    private fun getConsentTitle(type: ConsentType): String = when (type) {
        ConsentType.LOCATION_TRACKING -> "Suivi de localisation"
        ConsentType.DATA_COLLECTION -> "Collecte des données"
        ConsentType.COMPANY_DATA_SHARING -> "Partage avec entreprise"
        ConsentType.ANALYTICS -> "Analyses anonymisées"
        ConsentType.MARKETING -> "Communications marketing"
    }

    private fun getConsentDescription(type: ConsentType): String = when (type) {
        ConsentType.LOCATION_TRACKING -> "Permet l'enregistrement de vos trajets via GPS pour le calcul des distances et indemnités kilométriques."
        ConsentType.DATA_COLLECTION -> "Stockage sécurisé de vos trajets, véhicules et dépenses professionnelles."
        ConsentType.COMPANY_DATA_SHARING -> "Partage automatique de vos trajets avec le compte Pro de votre entreprise si vous êtes lié."
        ConsentType.ANALYTICS -> "Données anonymisées utilisées pour améliorer l'application (aucune donnée personnelle)."
        ConsentType.MARKETING -> "Recevoir des actualités, conseils et offres spéciales par email."
    }

    private fun isConsentRequired(type: ConsentType): Boolean = when (type) {
        ConsentType.LOCATION_TRACKING -> true
        ConsentType.DATA_COLLECTION -> true
        ConsentType.COMPANY_DATA_SHARING -> false
        ConsentType.ANALYTICS -> false
        ConsentType.MARKETING -> false
    }
}

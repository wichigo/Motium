package com.application.motium.domain.repository

import com.application.motium.domain.model.ConsentInfo
import com.application.motium.domain.model.ConsentType
import com.application.motium.domain.model.DataRetentionPolicy
import com.application.motium.domain.model.GdprDeletionResult
import com.application.motium.domain.model.GdprExportResult
import com.application.motium.domain.model.PrivacyPolicyAcceptance
import kotlinx.coroutines.flow.Flow

/**
 * Repository pour la gestion RGPD
 */
interface GdprRepository {

    // === Gestion des consentements ===

    /**
     * Récupère tous les consentements de l'utilisateur
     */
    suspend fun getConsents(): Result<List<ConsentInfo>>

    /**
     * Met à jour un consentement
     */
    suspend fun updateConsent(type: ConsentType, granted: Boolean, version: String): Result<Unit>

    /**
     * Initialise les consentements par défaut pour un nouvel utilisateur
     */
    suspend fun initializeDefaultConsents(): Result<Unit>

    // === Politique de confidentialité ===

    /**
     * Vérifie si l'utilisateur a accepté la version actuelle de la politique
     */
    suspend fun hasAcceptedPolicy(version: String): Boolean

    /**
     * Enregistre l'acceptation de la politique de confidentialité
     */
    suspend fun acceptPrivacyPolicy(version: String, policyUrl: String): Result<Unit>

    /**
     * Récupère l'historique des acceptations de politique
     */
    suspend fun getPolicyAcceptances(): Result<List<PrivacyPolicyAcceptance>>

    // === Export des données (Article 15) ===

    /**
     * Demande un export de toutes les données de l'utilisateur
     */
    suspend fun requestDataExport(): Result<GdprExportResult>

    /**
     * Vérifie le statut d'une demande d'export
     */
    suspend fun checkExportStatus(requestId: String): Result<GdprExportResult>

    // === Suppression du compte (Article 17) ===

    /**
     * Demande la suppression complète du compte
     * @param confirmation Doit être "DELETE_MY_ACCOUNT" pour confirmer
     * @param reason Raison optionnelle de la suppression
     */
    suspend fun requestAccountDeletion(confirmation: String, reason: String?): Result<GdprDeletionResult>

    // === Politiques de rétention ===

    /**
     * Récupère les politiques de rétention des données
     */
    suspend fun getRetentionPolicies(): Result<List<DataRetentionPolicy>>

    // === Audit ===

    /**
     * Log une action RGPD pour l'audit
     */
    suspend fun logGdprAction(action: String, details: String): Result<Unit>
}

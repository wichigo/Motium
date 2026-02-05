package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Représente un compte professionnel (entreprise) avec les données légales
 */
@Serializable
data class ProAccount(
    val id: String,
    val userId: String,

    // Informations légales entreprise
    val companyName: String,
    val siret: String? = null,
    val vatNumber: String? = null,
    val legalForm: LegalForm? = null,

    // Facturation
    val billingAddress: String? = null,
    val billingEmail: String? = null,

    // Jour de facturation mensuel (1-28)
    // Toutes les licences mensuelles sont facturées ce jour
    @SerialName("billing_day")
    val billingDay: Int = 5,

    // Jour de renouvellement unifié pour les licences mensuelles (1-28)
    // Utilisé par Stripe pour ancrer les subscriptions
    @SerialName("billing_anchor_day")
    val billingAnchorDay: Int? = null,

    // Statut du compte Pro (conformément aux specs)
    // trial: Période d'essai 7 jours
    // active: Au moins 1 licence achetée
    // expired: Essai terminé sans achat
    // suspended: Impayé
    val status: ProAccountStatus = ProAccountStatus.TRIAL,

    // Date de fin de période d'essai
    @SerialName("trial_ends_at")
    val trialEndsAt: Instant? = null,

    // ID subscription Stripe principale (pour la gestion des licences)
    @SerialName("stripe_subscription_id")
    val stripeSubscriptionId: String? = null,

    // Départements/services de l'entreprise
    val departments: List<String> = emptyList(),

    // Timestamps
    @SerialName("created_at")
    val createdAt: Instant? = null,
    @SerialName("updated_at")
    val updatedAt: Instant? = null,
) {
    /**
     * Vérifie si le jour de facturation est valide (1-28)
     * On limite à 28 pour éviter les problèmes avec février
     */
    val isValidBillingDay: Boolean
        get() = billingDay in 1..28

    /**
     * SIREN = 9 premiers chiffres du SIRET
     */
    val siren: String?
        get() = siret?.takeIf { it.length >= 9 }?.take(9)

    /**
     * Vérifie si le SIRET est valide (14 chiffres)
     */
    val isValidSiret: Boolean
        get() = siret?.let { it.length == 14 && it.all { c -> c.isDigit() } } ?: true

    /**
     * Vérifie si le numéro de TVA est valide (format FR + 11 chiffres)
     */
    val isValidVat: Boolean
        get() = vatNumber?.let {
            it.matches(Regex("^FR[0-9]{11}$"))
        } ?: true
}

/**
 * Formes juridiques françaises
 */
@Serializable
enum class LegalForm(val displayName: String, val shortName: String) {
    @SerialName("AUTO_ENTREPRENEUR")
    AUTO_ENTREPRENEUR("Auto-entrepreneur", "AE"),

    @SerialName("EI")
    EI("Entreprise Individuelle", "EI"),

    @SerialName("EIRL")
    EIRL("EIRL", "EIRL"),

    @SerialName("EURL")
    EURL("EURL", "EURL"),

    @SerialName("SARL")
    SARL("SARL", "SARL"),

    @SerialName("SAS")
    SAS("SAS", "SAS"),

    @SerialName("SASU")
    SASU("SASU", "SASU"),

    @SerialName("SA")
    SA("SA", "SA"),

    @SerialName("SCI")
    SCI("SCI", "SCI"),

    @SerialName("SNC")
    SNC("SNC", "SNC"),

    @SerialName("ASSOCIATION")
    ASSOCIATION("Association", "ASSO"),

    @SerialName("OTHER")
    OTHER("Autre", "AUTRE")
}

/**
 * Statut d'un compte Pro
 * Conformément aux spécifications:
 * - trial: Période d'essai 7 jours
 * - active: Au moins 1 licence achetée
 * - expired: Essai terminé sans achat
 * - suspended: Impayé
 */
@Serializable
enum class ProAccountStatus {
    @SerialName("trial")
    TRIAL,      // Période d'essai 7 jours

    @SerialName("active")
    ACTIVE,     // Au moins 1 licence achetée

    @SerialName("expired")
    EXPIRED,    // Essai terminé sans achat

    @SerialName("suspended")
    SUSPENDED;  // Impayé

    companion object {
        fun fromDbValue(value: String): ProAccountStatus = when (value.lowercase()) {
            "trial" -> TRIAL
            "active" -> ACTIVE
            "expired" -> EXPIRED
            "suspended" -> SUSPENDED
            else -> TRIAL
        }
    }
}

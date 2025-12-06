package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Représente un compte professionnel (entreprise) avec toutes les données légales
 */
@Serializable
data class ProAccount(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    
    // Informations légales entreprise
    @SerialName("company_name")
    val companyName: String,
    val siret: String,
    val siren: String = siret.take(9),
    @SerialName("vat_number")
    val vatNumber: String? = null,
    @SerialName("legal_form")
    val legalForm: LegalForm = LegalForm.SARL,
    @SerialName("share_capital")
    val shareCapital: Double? = null,
    @SerialName("rcs_number")
    val rcsNumber: String? = null,
    @SerialName("ape_code")
    val apeCode: String? = null,
    
    // Adresse de facturation
    @SerialName("billing_street")
    val billingStreet: String,
    @SerialName("billing_street_complement")
    val billingStreetComplement: String? = null,
    @SerialName("billing_postal_code")
    val billingPostalCode: String,
    @SerialName("billing_city")
    val billingCity: String,
    @SerialName("billing_country")
    val billingCountry: String = "FR",
    
    // Contact facturation
    @SerialName("billing_email")
    val billingEmail: String,
    @SerialName("billing_phone")
    val billingPhone: String? = null,
    
    // Informations bancaires (optionnel pour prélèvement SEPA)
    val iban: String? = null,
    val bic: String? = null,
    
    // Stripe
    @SerialName("stripe_customer_id")
    val stripeCustomerId: String? = null,
    @SerialName("stripe_payment_method_id")
    val stripePaymentMethodId: String? = null,
    
    // Métadonnées
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    val updatedAt: Instant
) {
    /**
     * Adresse de facturation formatée sur une ligne
     */
    val fullBillingAddress: String
        get() = buildString {
            append(billingStreet)
            billingStreetComplement?.let { append(", $it") }
            append(", $billingPostalCode $billingCity")
            if (billingCountry != "FR") append(", $billingCountry")
        }
    
    /**
     * Vérifie si le SIRET est valide (14 chiffres)
     */
    val isValidSiret: Boolean
        get() = siret.length == 14 && siret.all { it.isDigit() }
    
    /**
     * Vérifie si le numéro de TVA est valide (format FR + 11 chiffres)
     */
    val isValidVat: Boolean
        get() = vatNumber?.let { 
            it.matches(Regex("^FR[0-9]{11}$")) 
        } ?: true // null is valid (not required)
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

package com.application.motium.domain.model

import kotlinx.datetime.Instant

/**
 * Modèle représentant la renonciation au droit de rétractation.
 * Conformément à l'article L221-28 du Code de la consommation français,
 * l'utilisateur doit consentir explicitement à :
 * 1. L'exécution immédiate du service
 * 2. La renonciation au droit de rétractation de 14 jours
 */
data class WithdrawalWaiver(
    val acceptedImmediateExecution: Boolean = false,
    val acceptedWaiver: Boolean = false,
    val appVersion: String,
    val consentedAt: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
) {
    /**
     * Indique si le consentement est valide (les deux cases cochées)
     */
    val isValid: Boolean
        get() = acceptedImmediateExecution && acceptedWaiver
}

/**
 * État du formulaire de renonciation au droit de rétractation
 */
data class WithdrawalWaiverState(
    val acceptedImmediateExecution: Boolean = false,
    val acceptedWaiver: Boolean = false
) {
    /**
     * Indique si les deux consentements ont été donnés
     */
    val isComplete: Boolean
        get() = acceptedImmediateExecution && acceptedWaiver
}

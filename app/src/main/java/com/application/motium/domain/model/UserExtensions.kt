package com.application.motium.domain.model

/**
 * Extension functions pour faciliter les vérifications sur le modèle User
 */

/**
 * Vérifie si l'utilisateur a un abonnement payant actif (Premium, Lifetime ou Licensed)
 * LICENSED = l'utilisateur a une licence Pro attribuée par une entreprise
 *
 * NOTE: Pour vérifier l'accès aux fonctionnalités, utiliser hasFullAccess() qui inclut TRIAL
 */
fun User.isPremium(): Boolean {
    return subscription.type == SubscriptionType.PREMIUM ||
           subscription.type == SubscriptionType.LIFETIME ||
           subscription.type == SubscriptionType.LICENSED
}

/**
 * Vérifie si l'utilisateur a accès complet aux fonctionnalités de l'app.
 * Inclut: TRIAL actif, PREMIUM, LIFETIME, LICENSED
 *
 * Utiliser cette fonction pour les checks d'autorisation (export, etc.)
 * Utiliser isPremium() uniquement pour l'affichage du badge/statut payant
 */
fun User.hasFullAccess(): Boolean {
    return subscription.isActive()
}

/**
 * Vérifie si l'utilisateur peut enregistrer un nouveau trajet
 * Require either active trial or active subscription
 *
 * ⚠️ SECURITY WARNING: This method uses System.currentTimeMillis() which can be
 * manipulated. Only use this for UI display purposes.
 * For security-critical checks, use [canSaveTripSecure] with TrustedTimeProvider.
 */
fun User.canSaveTrip(): Boolean {
    return subscription.hasValidAccess()
}

/**
 * SECURE version: Check if user can save trips using trusted time.
 * @param trustedTimeMs Trusted time from TrustedTimeProvider.getTrustedTimeMs()
 * @return true if can save, false if not or time not trusted (fail-secure)
 */
fun User.canSaveTripSecure(trustedTimeMs: Long?): Boolean {
    return subscription.hasValidAccessSecure(trustedTimeMs)
}

/**
 * Vérifie si l'utilisateur est en période d'essai
 */
fun User.isInTrial(): Boolean {
    return subscription.isInTrial()
}

/**
 * Retourne le nombre de jours restants dans l'essai
 * Retourne null si pas en essai ou si abonné
 */
fun User.getTrialDaysRemaining(): Int? {
    return subscription.daysLeftInTrial()
}

/**
 * Retourne un message descriptif du statut de l'abonnement
 */
fun User.getSubscriptionStatusMessage(): String {
    return when (subscription.type) {
        SubscriptionType.TRIAL -> {
            val remaining = subscription.daysLeftInTrial() ?: 0
            if (remaining > 0) {
                "Essai gratuit - $remaining jour(s) restant(s)"
            } else {
                "Essai expiré"
            }
        }
        SubscriptionType.EXPIRED -> "Essai terminé - Abonnez-vous pour continuer"
        SubscriptionType.PREMIUM -> "Premium - Trajets illimités"
        SubscriptionType.LIFETIME -> "À vie - Trajets illimités"
        SubscriptionType.LICENSED -> "Licence Pro - Trajets illimités"
    }
}

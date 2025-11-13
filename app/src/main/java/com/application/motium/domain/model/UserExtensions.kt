package com.application.motium.domain.model

/**
 * Extension functions pour faciliter les vérifications sur le modèle User
 */

/**
 * Vérifie si l'utilisateur a un abonnement premium (Premium ou Lifetime)
 */
fun User.isPremium(): Boolean {
    return subscription.type == SubscriptionType.PREMIUM ||
           subscription.type == SubscriptionType.LIFETIME
}

/**
 * Vérifie si l'utilisateur peut enregistrer un nouveau trajet
 * Les comptes free sont limités à 10 trajets par mois
 */
fun User.canSaveTrip(): Boolean {
    if (isPremium()) return true

    val limit = subscription.type.tripLimit ?: return true
    return monthlyTripCount < limit
}

/**
 * Retourne le nombre de trajets restants pour le mois
 * Retourne null si illimité (premium/lifetime)
 */
fun User.getRemainingTrips(): Int? {
    if (isPremium()) return null

    val limit = subscription.type.tripLimit ?: return null
    return maxOf(0, limit - monthlyTripCount)
}

/**
 * Retourne un message descriptif du statut de l'abonnement
 */
fun User.getSubscriptionStatusMessage(): String {
    return when (subscription.type) {
        SubscriptionType.FREE -> {
            val remaining = getRemainingTrips() ?: 0
            "Gratuit - $remaining/${ subscription.type.tripLimit} trajets restants ce mois"
        }
        SubscriptionType.PREMIUM -> "Premium - Trajets illimités"
        SubscriptionType.LIFETIME -> "À vie - Trajets illimités"
    }
}

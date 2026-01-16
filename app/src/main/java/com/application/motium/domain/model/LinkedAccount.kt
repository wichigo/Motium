package com.application.motium.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Préférences de partage de données définies par l'utilisateur individuel
 * Ces préférences contrôlent ce que le Pro peut voir
 */
@Serializable
data class SharingPreferences(
    @SerialName("share_professional_trips")
    val shareProfessionalTrips: Boolean = true,  // Par défaut: Pro voit les trajets pro

    @SerialName("share_personal_trips")
    val sharePersonalTrips: Boolean = false,     // Par défaut: Pro ne voit PAS les trajets perso

    @SerialName("share_vehicle_info")
    val shareVehicleInfo: Boolean = true,        // Par défaut: Pro voit les véhicules

    @SerialName("share_expenses")
    val shareExpenses: Boolean = false           // Par défaut: Pro ne voit PAS les dépenses
)

// LinkedAccountStatus has been removed - use LinkStatus from User.kt instead

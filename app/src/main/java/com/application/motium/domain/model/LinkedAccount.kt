package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Représente un compte utilisateur lié à un compte Pro
 * Le Pro peut voir les données de l'utilisateur selon ses préférences de partage
 */
@Serializable
data class LinkedAccount(
    val id: String,
    @SerialName("pro_account_id")
    val proAccountId: String,
    @SerialName("user_id")
    val userId: String? = null, // null si invitation en attente
    
    // Infos de l'utilisateur (rempli après acceptation)
    @SerialName("user_email")
    val userEmail: String,
    @SerialName("user_name")
    val userName: String? = null,
    
    // Statut de la liaison
    val status: LinkedAccountStatus = LinkedAccountStatus.PENDING,
    
    // Préférences de partage (définies par l'utilisateur individuel)
    @SerialName("sharing_preferences")
    val sharingPreferences: SharingPreferences = SharingPreferences(),
    
    // Invitation (si status = PENDING)
    @SerialName("invitation_token")
    val invitationToken: String? = null,
    @SerialName("invitation_expires_at")
    val invitationExpiresAt: Instant? = null,
    @SerialName("invited_email")
    val invitedEmail: String? = null,
    
    // Métadonnées
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    val updatedAt: Instant
) {
    /**
     * Nom d'affichage (email si pas de nom)
     */
    val displayName: String
        get() = userName ?: userEmail
    
    /**
     * Vérifie si l'invitation a expiré
     */
    val isInvitationExpired: Boolean
        get() = invitationExpiresAt?.let { expiry ->
            Instant.fromEpochMilliseconds(System.currentTimeMillis()) > expiry
        } ?: false
    
    /**
     * Vérifie si le compte est actif (lié et non révoqué)
     */
    val isActive: Boolean
        get() = status == LinkedAccountStatus.ACTIVE && userId != null
}

/**
 * Statut de la liaison entre un compte Pro et un compte individuel
 */
@Serializable
enum class LinkedAccountStatus {
    @SerialName("pending")
    PENDING,     // Invitation envoyée, en attente d'acceptation
    
    @SerialName("active")
    ACTIVE,      // Liaison active, compte lié et fonctionnel
    
    @SerialName("revoked")
    REVOKED      // Liaison révoquée par le Pro ou l'utilisateur
}

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

package com.application.motium.service

/**
 * Represents the current trip state to display in the foreground notification.
 * Used for communication between LocationTrackingService and ActivityRecognitionService.
 */
sealed class TripNotificationState {
    /** Idle state - auto-tracking is enabled but no trip in progress */
    object Standby : TripNotificationState()

    /** Activity detected, GPS buffering in progress */
    object Detecting : TripNotificationState()

    /** Active trip with distance tracking */
    data class TripActive(val distanceKm: Double) : TripNotificationState()

    /** Trip temporarily paused (unreliable activity) */
    object Paused : TripNotificationState()

    /** Stop detected, in grace period before finalization */
    object StopDetected : TripNotificationState()

    /** Collecting final GPS points before saving */
    object Finalizing : TripNotificationState()

    /** Trip successfully saved (temporary state, 3 seconds) */
    object TripSaved : TripNotificationState()

    /**
     * Converts the state to a user-friendly notification text in French
     */
    fun toNotificationText(): String = when (this) {
        is Standby -> "Détection automatique activée"
        is Detecting -> "Détection en cours..."
        is TripActive -> if (distanceKm >= 0.1) {
            "Trajet en cours - %.1f km".format(distanceKm)
        } else {
            "Trajet en cours"
        }
        is Paused -> "Trajet en pause..."
        is StopDetected -> "Arrêt détecté..."
        is Finalizing -> "Finalisation..."
        is TripSaved -> "Trajet sauvegardé"
    }
}

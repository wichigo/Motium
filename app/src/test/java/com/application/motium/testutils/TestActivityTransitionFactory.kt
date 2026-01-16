package com.application.motium.testutils

import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.mockk.every
import io.mockk.mockk

/**
 * Factory pour créer des événements Activity Recognition pour les tests
 * Permet de simuler les transitions d'activité (IN_VEHICLE, WALKING, etc.)
 */
object TestActivityTransitionFactory {

    /**
     * Crée un événement de transition d'activité
     *
     * @param activityType Type d'activité (DetectedActivity.IN_VEHICLE, WALKING, etc.)
     * @param transitionType Type de transition (ENTER ou EXIT)
     * @param elapsedRealtimeNanos Timestamp de l'événement (par défaut: maintenant)
     */
    fun createTransitionEvent(
        activityType: Int,
        transitionType: Int,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent {
        val event = mockk<ActivityTransitionEvent>()
        every { event.activityType } returns activityType
        every { event.transitionType } returns transitionType
        every { event.elapsedRealTimeNanos } returns elapsedRealtimeNanos
        return event
    }

    /**
     * Crée un résultat de transition contenant un ou plusieurs événements
     */
    fun createTransitionResult(vararg events: ActivityTransitionEvent): ActivityTransitionResult {
        val result = mockk<ActivityTransitionResult>()
        every { result.transitionEvents } returns events.toList()
        return result
    }

    /**
     * Crée un Intent contenant un résultat de transition
     */
    fun createIntentWithResult(result: ActivityTransitionResult): Intent {
        val intent = mockk<Intent>()
        // Simuler que l'intent contient un résultat valide
        return intent
    }

    // ════════════════════════════════════════════════════════════════
    // SHORTCUTS POUR ÉVÉNEMENTS COURANTS
    // ════════════════════════════════════════════════════════════════

    /**
     * Événement: L'utilisateur entre dans un véhicule
     * Devrait déclencher: ACTION_START_BUFFERING
     */
    fun vehicleEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.IN_VEHICLE,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur sort du véhicule
     * Devrait: Attendre WALKING pour confirmer la fin
     */
    fun vehicleExit(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.IN_VEHICLE,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_EXIT,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur commence à marcher
     * Devrait déclencher: ACTION_END_TRIP
     */
    fun walkingEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.WALKING,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur commence à courir
     * Devrait déclencher: ACTION_END_TRIP (traité comme marche)
     */
    fun runningEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.RUNNING,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur est à pied (générique)
     * Devrait déclencher: ACTION_END_TRIP
     */
    fun onFootEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.ON_FOOT,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur est immobile
     * Devrait déclencher: ACTION_END_TRIP (confirmation fin)
     */
    fun stillEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.STILL,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur monte sur un vélo
     * Devrait déclencher: ACTION_START_BUFFERING (même que véhicule)
     */
    fun bicycleEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.ON_BICYCLE,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: L'utilisateur descend du vélo
     */
    fun bicycleExit(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.ON_BICYCLE,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_EXIT,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    /**
     * Événement: Activité inconnue
     * Devrait déclencher: ACTION_PAUSE_TRACKING
     */
    fun unknownEnter(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): ActivityTransitionEvent = createTransitionEvent(
        activityType = DetectedActivity.UNKNOWN,
        transitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )

    // ════════════════════════════════════════════════════════════════
    // SCÉNARIOS DE TEST
    // ════════════════════════════════════════════════════════════════

    /**
     * Scénario: Trajet complet en voiture
     * IN_VEHICLE ENTER → ... temps ... → WALKING ENTER
     */
    fun createFullTripScenario(): List<ActivityTransitionEvent> {
        val now = SystemClock.elapsedRealtimeNanos()
        return listOf(
            vehicleEnter(now),
            walkingEnter(now + 600_000_000_000) // 10 minutes plus tard
        )
    }

    /**
     * Scénario: Événement obsolète (plus de 3 minutes)
     * Devrait être ignoré par le receiver
     */
    fun createObsoleteEvent(): ActivityTransitionEvent {
        // Événement vieux de 5 minutes (300 secondes)
        val oldTimestamp = SystemClock.elapsedRealtimeNanos() - (300 * 1_000_000_000L)
        return vehicleEnter(oldTimestamp)
    }

    /**
     * Scénario: Événement récent (moins de 3 minutes)
     * Devrait être traité normalement
     */
    fun createRecentEvent(): ActivityTransitionEvent {
        // Événement vieux de 30 secondes
        val recentTimestamp = SystemClock.elapsedRealtimeNanos() - (30 * 1_000_000_000L)
        return vehicleEnter(recentTimestamp)
    }

    /**
     * Scénario: Transition rapide véhicule → marche (faux positif)
     * Moins de 30 secondes entre VEHICLE et WALKING
     */
    fun createFalsePositiveScenario(): List<ActivityTransitionEvent> {
        val now = SystemClock.elapsedRealtimeNanos()
        return listOf(
            vehicleEnter(now),
            walkingEnter(now + 15_000_000_000) // 15 secondes plus tard (trop rapide)
        )
    }

    /**
     * Scénario: Alternance véhicule/immobile (bouchon)
     */
    fun createTrafficJamScenario(): List<ActivityTransitionEvent> {
        val now = SystemClock.elapsedRealtimeNanos()
        return listOf(
            vehicleEnter(now),
            stillEnter(now + 60_000_000_000),    // Arrêt après 1 min
            vehicleEnter(now + 120_000_000_000), // Reprise après 2 min
            stillEnter(now + 180_000_000_000),   // Nouvel arrêt
            vehicleEnter(now + 240_000_000_000), // Reprise
            walkingEnter(now + 600_000_000_000), // Fin du trajet
        )
    }

    // ════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════

    /**
     * Convertit un type d'activité en nom lisible
     */
    fun getActivityName(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.ON_FOOT -> "ON_FOOT"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.TILTING -> "TILTING"
            DetectedActivity.UNKNOWN -> "UNKNOWN"
            else -> "OTHER($activityType)"
        }
    }

    /**
     * Convertit un type de transition en nom lisible
     */
    fun getTransitionName(transitionType: Int): String {
        return when (transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
            else -> "UNKNOWN($transitionType)"
        }
    }
}

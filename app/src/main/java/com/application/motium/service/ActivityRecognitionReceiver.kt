package com.application.motium.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.application.motium.MotiumApplication
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BroadcastReceiver pour recevoir les transitions d'activité (ActivityTransition API)
 *
 * IMPORTANT: Ce receiver DOIT être exporté dans le manifest pour recevoir
 * les broadcasts de Google Play Services (processus externe)
 *
 * Architecture simplifiée avec nouvelle API:
 * 1. Google Play Services → ActivityRecognitionReceiver (broadcast avec transitions)
 * 2. ActivityRecognitionReceiver → LocationTrackingService (actions directes)
 *
 * Différence clé avec l'ancienne API:
 * - AVANT: Reçoit périodiquement TOUTES les activités détectées avec confiance
 * - APRÈS: Reçoit uniquement les TRANSITIONS (ENTER/EXIT) des activités surveillées
 *
 * Cette API est plus fiable, consomme moins de batterie, et élimine le besoin
 * de gérer manuellement la détection des changements d'état.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        MotiumApplication.logger.i(
            "🔔 ActivityRecognitionReceiver.onReceive() called! Action: ${intent.action}",
            "ActivityReceiver"
        )

        MotiumApplication.logger.d(
            "Intent extras: ${intent.extras?.keySet()?.joinToString(", ") { "$it=${intent.extras?.get(it)}" }}",
            "ActivityReceiver"
        )

        // Utiliser la nouvelle API ActivityTransition
        if (ActivityTransitionResult.hasResult(intent)) {
            MotiumApplication.logger.i("✅ ActivityTransitionResult.hasResult() = true", "ActivityReceiver")

            val result = ActivityTransitionResult.extractResult(intent)
            if (result != null) {
                MotiumApplication.logger.i("✅ Extracted transition result is not null", "ActivityReceiver")
                handleActivityTransitions(context, result)
            } else {
                MotiumApplication.logger.e("❌ Extracted transition result is NULL!", "ActivityReceiver")
            }
        } else {
            MotiumApplication.logger.w(
                "❌ ActivityTransitionResult.hasResult() = false - Intent does not contain activity transition result!",
                "ActivityReceiver"
            )
        }
    }

    /**
     * Traite les événements de transition d'activité
     * Cette méthode est appelée uniquement quand l'utilisateur ENTRE ou SORT d'une activité
     */
    private fun handleActivityTransitions(context: Context, result: ActivityTransitionResult) {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        MotiumApplication.logger.i(
            "📱 Received ${result.transitionEvents.size} activity transition(s)",
            "ActivityReceiver"
        )

        // Traiter chaque transition
        result.transitionEvents.forEach { event ->
            val activityName = getActivityName(event.activityType)
            val transitionType = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                "ENTER"
            } else {
                "EXIT"
            }
            val timestamp = dateFormat.format(Date(event.elapsedRealTimeNanos / 1_000_000))

            MotiumApplication.logger.i(
                "🎯 Transition: $activityName $transitionType at $timestamp",
                "ActivityReceiver"
            )

            // Traiter selon le type de transition
            handleTransitionEvent(context, event)
        }
    }

    /**
     * Gère une transition individuelle et déclenche l'action appropriée
     * Logique simplifiée grâce à l'API Transition
     */
    private fun handleTransitionEvent(context: Context, event: com.google.android.gms.location.ActivityTransitionEvent) {
        val activityType = event.activityType
        val transitionType = event.transitionType

        // FIX BUG CRITIQUE: Filtrer les événements obsolètes
        // Google Play Services cache le dernier événement et le rejoue à chaque keep-alive (15 min)
        // Ces événements peuvent dater de plusieurs heures et bloquent l'autotracking
        val eventTimestampMs = event.elapsedRealTimeNanos / 1_000_000 // Convertir nanos → millis
        val currentTimeMs = SystemClock.elapsedRealtime()
        val eventAgeMs = currentTimeMs - eventTimestampMs
        val eventAgeSeconds = eventAgeMs / 1000

        // Ignorer événements de plus de 3 minutes (180 secondes)
        // Un événement légitime arrive en temps réel, pas 3 min plus tard
        val MAX_EVENT_AGE_MS = 180000L // 3 minutes

        if (eventAgeMs > MAX_EVENT_AGE_MS) {
            val activityName = getActivityName(activityType)
            val transitionName = if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"

            MotiumApplication.logger.w(
                "⏰ OBSOLETE EVENT IGNORED (age: ${eventAgeSeconds}s / ${eventAgeMs/60000}min)\n" +
                "   Event: $activityName $transitionName\n" +
                "   Cause: Google Play Services cache replay\n" +
                "   Impact: Would have blocked autotracking if not filtered",
                "ActivityReceiver"
            )
            return // Ignorer complètement cet événement
        }

        // Log événement valide (récent)
        MotiumApplication.logger.d(
            "✅ Event is RECENT (age: ${eventAgeSeconds}s) - processing",
            "ActivityReceiver"
        )

        when {
            // L'utilisateur ENTRE dans un véhicule → Démarrer le buffering
            activityType == DetectedActivity.IN_VEHICLE &&
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                MotiumApplication.logger.i(
                    "🚗 IN_VEHICLE ENTER détecté → Démarrage buffering",
                    "ActivityReceiver"
                )
                LocationTrackingService.startBuffering(context)
            }

            // L'utilisateur SORT du véhicule → Potentiellement fin de trajet
            activityType == DetectedActivity.IN_VEHICLE &&
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                MotiumApplication.logger.i(
                    "🏁 IN_VEHICLE EXIT détecté → Véhicule quitté",
                    "ActivityReceiver"
                )
                // Note: On attend la détection WALKING ENTER pour vraiment terminer le trajet
                // Car EXIT peut être temporaire (feu rouge, arrêt bref)
            }

            // L'utilisateur ENTRE dans une activité de marche → Fin de trajet
            activityType == DetectedActivity.WALKING &&
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                MotiumApplication.logger.i(
                    "🚶 WALKING ENTER détecté → Utilisateur marche, fin du trajet probable",
                    "ActivityReceiver"
                )
                LocationTrackingService.endTrip(context)
            }

            // L'utilisateur ENTRE dans un état immobile → Confirmation de fin de trajet
            activityType == DetectedActivity.STILL &&
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                MotiumApplication.logger.i(
                    "🛑 STILL ENTER détecté → Tentative de fin de trajet\n" +
                    "   (Ignoré si aucun trip actif - normal au démarrage)",
                    "ActivityReceiver"
                )
                // NOTE: endTrip() sera ignoré par LocationTrackingService si état = STANDBY
                // C'est le comportement souhaité (pas d'erreur)
                LocationTrackingService.endTrip(context)
            }

            // L'utilisateur ENTRE sur un vélo → Démarrer le buffering
            activityType == DetectedActivity.ON_BICYCLE &&
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                MotiumApplication.logger.i(
                    "🚴 ON_BICYCLE ENTER détecté → Démarrage buffering",
                    "ActivityReceiver"
                )
                LocationTrackingService.startBuffering(context)
            }

            // L'utilisateur SORT du vélo
            activityType == DetectedActivity.ON_BICYCLE &&
            transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                MotiumApplication.logger.i(
                    "🏁 ON_BICYCLE EXIT détecté → Vélo quitté",
                    "ActivityReceiver"
                )
                // Même logique que véhicule - attendre WALKING ENTER
            }

            else -> {
                MotiumApplication.logger.d(
                    "ℹ️ Transition non traitée: ${getActivityName(activityType)} " +
                    "${if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"}",
                    "ActivityReceiver"
                )
            }
        }
    }

    private fun getActivityName(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.IN_VEHICLE -> "Véhicule"
            DetectedActivity.ON_BICYCLE -> "Vélo"
            DetectedActivity.ON_FOOT -> "À pied"
            DetectedActivity.WALKING -> "Marche"
            DetectedActivity.RUNNING -> "Course"
            DetectedActivity.STILL -> "Immobile"
            DetectedActivity.TILTING -> "Inclinaison"
            DetectedActivity.UNKNOWN -> "Inconnu"
            else -> "Autre ($activityType)"
        }
    }
}

package com.application.motium.service

import android.content.Context
import android.content.SharedPreferences
import com.application.motium.MotiumApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton thread-safe pour gérer l'état des trajets auto-tracking.
 *
 * Partagé entre ActivityRecognitionService et LocationTrackingService.
 *
 * ## Machine d'État
 * ```
 * IDLE ──[VehicleEnter/BicycleEnter]──> MOVING
 * MOVING ──[StillEnter]──> reste en MOVING (ignore feux rouges)
 * MOVING ──[WalkingEnter/RunningEnter]──> IDLE (trajet terminé)
 * POSSIBLY_STOPPED ──[VehicleEnter/BicycleEnter]──> MOVING (même tripId)
 * POSSIBLY_STOPPED ──[WalkingEnter/RunningEnter]──> reste en POSSIBLY_STOPPED
 * ```
 *
 * ## Comportements clés
 * - **IDLE**: GPS complètement coupé, 0 consommation batterie GPS
 * - **Démarrage immédiat**: IN_VEHICLE ENTER → MOVING sans délai
 * - **Feu rouge/bouchon**: STILL ignoré → même trajet continue
 * - **Fin du trajet**: Walking/Running détecté en MOVING → fin immédiate
 * - **Persistance**: L'état survit aux kills de service par l'OS
 */
object TripStateManager {

    private const val TAG = "TripStateManager"
    private const val PREFS_NAME = "TripStateManagerPrefs"
    private const val PREF_STATE_NAME = "state_name"
    private const val PREF_TRIP_ID = "trip_id"
    private const val PREF_START_TIME = "start_time"
    private const val PREF_STILL_SINCE = "still_since"

    // Safe logging for unit tests (MotiumApplication may not be initialized)
    private fun logI(message: String) {
        try {
            MotiumApplication.logger.i(message, TAG)
        } catch (e: UninitializedPropertyAccessException) {
            println("[$TAG] INFO: $message")
        }
    }

    private fun logD(message: String) {
        try {
            MotiumApplication.logger.d(message, TAG)
        } catch (e: UninitializedPropertyAccessException) {
            println("[$TAG] DEBUG: $message")
        }
    }

    private fun logW(message: String) {
        try {
            MotiumApplication.logger.w(message, TAG)
        } catch (e: UninitializedPropertyAccessException) {
            println("[$TAG] WARN: $message")
        }
    }

    private fun logE(message: String, exception: Throwable? = null) {
        try {
            MotiumApplication.logger.e(message, TAG, exception)
        } catch (e: UninitializedPropertyAccessException) {
            println("[$TAG] ERROR: $message")
            exception?.printStackTrace()
        }
    }

    // ==================== STATE ====================

    sealed class TrackingState {
        object Idle : TrackingState() {
            override fun toString() = "Idle"
        }

        data class Moving(
            val tripId: String,
            val startTimeMs: Long  // System.currentTimeMillis()
        ) : TrackingState()

        data class PossiblyStopped(
            val tripId: String,
            val stillSinceMs: Long,  // System.currentTimeMillis()
            val confirmationDeadlineMs: Long
        ) : TrackingState()
    }

    // ==================== EVENTS ====================

    sealed class TrackingEvent {
        object VehicleEnter : TrackingEvent()
        object BicycleEnter : TrackingEvent()
        object StillEnter : TrackingEvent()
        object WalkingEnter : TrackingEvent()  // Fin du trajet si en MOVING
        object RunningEnter : TrackingEvent()  // Fin du trajet si en MOVING
        object StillConfirmed : TrackingEvent()  // DEPRECATED - ignoré (timer désactivé)
        object MovementResume : TrackingEvent()  // Reprise mouvement après POSSIBLY_STOPPED

        override fun toString(): String = this::class.simpleName ?: "Unknown"
    }

    // ==================== CONFIG ====================

    object Config {
        /** Délai avant de confirmer un arrêt définitif (3 minutes) */
        const val STILL_CONFIRMATION_DELAY_MS = 3 * 60 * 1000L

        /** Durée minimum pour qu'un trajet soit enregistré (1 minute) */
        const val MIN_TRIP_DURATION_MS = 60 * 1000L
    }

    // ==================== STATE FLOW ====================

    private val _state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    /** État actuel (accès direct sans Flow) */
    val currentState: TrackingState
        get() = _state.value

    // ==================== COROUTINE SCOPE ====================

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        logE("Uncaught exception in TripStateManager: ${exception.message}", exception)
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)
    private var stillConfirmationJob: Job? = null

    // ==================== CALLBACKS ====================

    /** Appelé quand un nouveau trajet démarre (passage à MOVING) */
    var onTripStarted: ((tripId: String) -> Unit)? = null

    /** Appelé quand un trajet se termine (passage de MOVING/POSSIBLY_STOPPED à IDLE) */
    var onTripEnded: ((tripId: String) -> Unit)? = null

    /** Appelé à chaque changement d'état */
    var onStateChanged: ((TrackingState) -> Unit)? = null

    // ==================== INITIALIZATION ====================

    private val isInitialized = AtomicBoolean(false)
    private var appContext: Context? = null

    /**
     * Initialise le TripStateManager avec le contexte applicatif.
     * Doit être appelé une fois au démarrage de l'application.
     */
    fun initialize(context: Context) {
        if (isInitialized.compareAndSet(false, true)) {
            appContext = context.applicationContext
            restoreState()
            logI("TripStateManager initialized, state: ${_state.value}")
        }
    }

    // ==================== EVENT PROCESSING ====================

    /**
     * Traite un événement de transition d'activité.
     * Thread-safe et idempotent.
     */
    @Synchronized
    fun onEvent(event: TrackingEvent) {
        val previousState = _state.value
        val newState = processEvent(previousState, event)

        if (newState != previousState) {
            logI("State transition: $previousState -> $newState (event: $event)")

            _state.value = newState
            persistState()
            onStateChanged?.invoke(newState)

            // Gérer les callbacks selon la transition
            handleStateTransitionCallbacks(previousState, newState)
        } else {
            logD("State unchanged: $previousState (event: $event ignored)")
        }
    }

    /**
     * Logique de la machine d'état.
     * Retourne le nouvel état basé sur l'état actuel et l'événement.
     */
    private fun processEvent(currentState: TrackingState, event: TrackingEvent): TrackingState {
        return when (currentState) {
            is TrackingState.Idle -> processEventInIdle(event)
            is TrackingState.Moving -> processEventInMoving(currentState, event)
            is TrackingState.PossiblyStopped -> processEventInPossiblyStopped(currentState, event)
        }
    }

    private fun processEventInIdle(event: TrackingEvent): TrackingState {
        return when (event) {
            // Démarrage IMMÉDIAT sur détection véhicule/vélo
            is TrackingEvent.VehicleEnter,
            is TrackingEvent.BicycleEnter -> {
                val tripId = generateTripId()
                val startTimeMs = System.currentTimeMillis()
                logI("NEW TRIP started immediately! tripId=$tripId")
                TrackingState.Moving(tripId, startTimeMs)
            }

            // Ignorer les autres événements en IDLE
            is TrackingEvent.StillEnter,
            is TrackingEvent.WalkingEnter,
            is TrackingEvent.RunningEnter,
            is TrackingEvent.StillConfirmed,
            is TrackingEvent.MovementResume -> {
                logD("Event $event ignored in IDLE state")
                TrackingState.Idle
            }
        }
    }

    private fun processEventInMoving(
        currentState: TrackingState.Moving,
        event: TrackingEvent
    ): TrackingState {
        return when (event) {
            // STILL (IMMOBILE) → ignoré, le trajet continue
            // Les arrêts courts (feux rouges, bouchons) ne terminent pas le trajet
            is TrackingEvent.StillEnter -> {
                logD("STILL ignored while MOVING - trip continues (feu rouge/bouchon)")
                currentState
            }

            // WALKING/RUNNING → FIN IMMÉDIATE du trajet
            // L'utilisateur est descendu du véhicule
            is TrackingEvent.WalkingEnter,
            is TrackingEvent.RunningEnter -> {
                logI("TRIP ENDED - Walking/Running detected (tripId=${currentState.tripId})")
                TrackingState.Idle
            }

            // Véhicule/vélo détecté alors qu'on roule déjà → ignorer
            is TrackingEvent.VehicleEnter,
            is TrackingEvent.BicycleEnter -> {
                logD("Already MOVING, ignoring vehicle/bicycle enter")
                currentState
            }

            // Ces événements ne devraient pas arriver en MOVING
            is TrackingEvent.StillConfirmed,
            is TrackingEvent.MovementResume -> {
                logW("Unexpected event $event in MOVING state")
                currentState
            }
        }
    }

    private fun processEventInPossiblyStopped(
        currentState: TrackingState.PossiblyStopped,
        event: TrackingEvent
    ): TrackingState {
        return when (event) {
            // Reprise du mouvement AVANT expiration du timer → retour à MOVING (même trajet!)
            is TrackingEvent.VehicleEnter,
            is TrackingEvent.BicycleEnter -> {
                logI("MOVEMENT RESUMED - same trip continues (tripId=${currentState.tripId})")

                // Annuler le timer de confirmation
                cancelStillConfirmationTimer()

                // Retour à MOVING avec le MÊME tripId
                // startTimeMs conservé depuis stillSinceMs (on reprend où on était)
                TrackingState.Moving(
                    tripId = currentState.tripId,
                    startTimeMs = currentState.stillSinceMs
                )
            }

            // StillConfirmed ignoré - le trajet ne se termine que via Walking/Running
            is TrackingEvent.StillConfirmed -> {
                logD("StillConfirmed ignored - trip ends only via Walking/Running")
                currentState
            }

            // STILL/WALKING/RUNNING → rester en POSSIBLY_STOPPED
            // On attend VehicleEnter/BicycleEnter pour reprendre
            is TrackingEvent.StillEnter,
            is TrackingEvent.WalkingEnter,
            is TrackingEvent.RunningEnter -> {
                logD("Still/Walking/Running in POSSIBLY_STOPPED, waiting for vehicle")
                currentState
            }

            // MovementResume est un alias pour reprise de mouvement
            is TrackingEvent.MovementResume -> {
                logI("MOVEMENT RESUMED via explicit event (tripId=${currentState.tripId})")
                cancelStillConfirmationTimer()
                TrackingState.Moving(
                    tripId = currentState.tripId,
                    startTimeMs = currentState.stillSinceMs
                )
            }
        }
    }

    // ==================== TIMER MANAGEMENT ====================

    private fun startStillConfirmationTimer() {
        cancelStillConfirmationTimer()

        stillConfirmationJob = scope.launch {
            logD("Still confirmation timer started (${Config.STILL_CONFIRMATION_DELAY_MS / 1000}s)")

            delay(Config.STILL_CONFIRMATION_DELAY_MS)

            // Timer expiré - confirmer l'arrêt
            logI("Still confirmation timer EXPIRED")
            onEvent(TrackingEvent.StillConfirmed)
        }
    }

    private fun cancelStillConfirmationTimer() {
        stillConfirmationJob?.let {
            if (it.isActive) {
                it.cancel()
                logD("Still confirmation timer CANCELLED")
            }
        }
        stillConfirmationJob = null
    }

    // ==================== CALLBACKS HANDLING ====================

    private fun handleStateTransitionCallbacks(
        previousState: TrackingState,
        newState: TrackingState
    ) {
        // Trajet démarré: transition vers MOVING depuis IDLE
        if (previousState is TrackingState.Idle && newState is TrackingState.Moving) {
            onTripStarted?.invoke(newState.tripId)
        }

        // Trajet terminé: transition vers IDLE depuis MOVING ou POSSIBLY_STOPPED
        if (newState is TrackingState.Idle) {
            val tripId = when (previousState) {
                is TrackingState.Moving -> previousState.tripId
                is TrackingState.PossiblyStopped -> previousState.tripId
                else -> null
            }
            tripId?.let { onTripEnded?.invoke(it) }
        }
    }

    // ==================== PERSISTENCE ====================

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Persiste l'état actuel dans SharedPreferences.
     * Appelé automatiquement à chaque changement d'état.
     */
    private fun persistState() {
        val prefs = getPrefs() ?: run {
            logW("Cannot persist state: context not initialized")
            return
        }

        prefs.edit().apply {
            when (val currentState = _state.value) {
                is TrackingState.Idle -> {
                    putString(PREF_STATE_NAME, "IDLE")
                    remove(PREF_TRIP_ID)
                    remove(PREF_START_TIME)
                    remove(PREF_STILL_SINCE)
                }
                is TrackingState.Moving -> {
                    putString(PREF_STATE_NAME, "MOVING")
                    putString(PREF_TRIP_ID, currentState.tripId)
                    putLong(PREF_START_TIME, currentState.startTimeMs)
                    remove(PREF_STILL_SINCE)
                }
                is TrackingState.PossiblyStopped -> {
                    putString(PREF_STATE_NAME, "POSSIBLY_STOPPED")
                    putString(PREF_TRIP_ID, currentState.tripId)
                    putLong(PREF_STILL_SINCE, currentState.stillSinceMs)
                    // Note: confirmationDeadlineMs sera recalculé au restore
                }
            }
            apply()
        }

        logD("State persisted: ${_state.value}")
    }

    /**
     * Restaure l'état depuis SharedPreferences au démarrage.
     */
    private fun restoreState() {
        val prefs = getPrefs() ?: return

        val stateName = prefs.getString(PREF_STATE_NAME, "IDLE") ?: "IDLE"
        val tripId = prefs.getString(PREF_TRIP_ID, null)
        val startTimeMs = prefs.getLong(PREF_START_TIME, 0)
        val stillSinceMs = prefs.getLong(PREF_STILL_SINCE, 0)

        val restoredState = when (stateName) {
            "MOVING" -> {
                if (tripId != null && startTimeMs > 0) {
                    logI("Restoring MOVING state: tripId=$tripId")
                    TrackingState.Moving(
                        tripId = tripId,
                        startTimeMs = startTimeMs
                    )
                } else {
                    logW("Invalid MOVING state in prefs, resetting to IDLE")
                    TrackingState.Idle
                }
            }
            "POSSIBLY_STOPPED" -> {
                if (tripId != null && stillSinceMs > 0) {
                    // Restaurer l'état POSSIBLY_STOPPED sans timer
                    // Le trajet se terminera uniquement via Walking/Running
                    logI("Restoring POSSIBLY_STOPPED: tripId=$tripId (no timer)")
                    TrackingState.PossiblyStopped(
                        tripId = tripId,
                        stillSinceMs = stillSinceMs,
                        confirmationDeadlineMs = 0L // Timer désactivé
                    )
                } else {
                    logW("Invalid POSSIBLY_STOPPED state in prefs, resetting to IDLE")
                    TrackingState.Idle
                }
            }
            else -> TrackingState.Idle
        }

        _state.value = restoredState
    }

    /**
     * Efface l'état persisté (appelé après une fin de trajet normale).
     */
    fun clearPersistedState() {
        getPrefs()?.edit()?.clear()?.apply()
        logD("Persisted state cleared")
    }

    // ==================== UTILITIES ====================

    private fun generateTripId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Force un reset à l'état IDLE (pour debug ou situations exceptionnelles).
     */
    fun forceReset() {
        logW("Force reset to IDLE requested")
        cancelStillConfirmationTimer()

        val previousState = _state.value
        if (previousState is TrackingState.Moving || previousState is TrackingState.PossiblyStopped) {
            val tripId = when (previousState) {
                is TrackingState.Moving -> previousState.tripId
                is TrackingState.PossiblyStopped -> previousState.tripId
                else -> null
            }
            tripId?.let { onTripEnded?.invoke(it) }
        }

        _state.value = TrackingState.Idle
        clearPersistedState()
        onStateChanged?.invoke(TrackingState.Idle)
    }

    /**
     * Vérifie si un trajet est en cours (MOVING ou POSSIBLY_STOPPED).
     */
    fun isTripInProgress(): Boolean {
        return _state.value !is TrackingState.Idle
    }

    /**
     * Retourne l'ID du trajet en cours, ou null si aucun trajet.
     */
    fun getCurrentTripId(): String? {
        return when (val state = _state.value) {
            is TrackingState.Moving -> state.tripId
            is TrackingState.PossiblyStopped -> state.tripId
            is TrackingState.Idle -> null
        }
    }
}

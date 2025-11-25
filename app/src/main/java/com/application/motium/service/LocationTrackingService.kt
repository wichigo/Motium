package com.application.motium.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.application.motium.MotiumApplication
import com.application.motium.R
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.data.TripRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.*

class LocationTrackingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "LocationTrackingChannel"

        // Configuration GPS avec deux modes pour économie batterie
        // Mode STANDBY (pas de trajet): 1 appel GPS par minute pour économiser batterie
        private const val STANDBY_UPDATE_INTERVAL = 60000L // 60 secondes (1 minute)
        private const val STANDBY_FASTEST_INTERVAL = 60000L // 60 secondes

        // Mode TRIP (trajet en cours): haute fréquence pour précision
        private const val TRIP_UPDATE_INTERVAL = 10000L // 10 secondes
        private const val TRIP_FASTEST_INTERVAL = 5000L // 5 secondes minimum

        private const val MIN_DISPLACEMENT = 10f // 10 mètres pour éviter bruit GPS

        // Critères de validation des trajets (très assouplis pour tests et conditions réelles)
        private const val MIN_TRIP_DISTANCE_METERS = 10.0 // 10m minimum (très réduit pour tests)
        private const val MIN_TRIP_DURATION_MS = 15000L // 15 secondes minimum (très réduit pour tests)
        private const val MIN_AVERAGE_SPEED_MPS = 0.1 // 0.36 km/h = 0.1 m/s (très réduit pour tests)
        private const val MAX_GPS_ACCURACY_METERS = 100f // 100m précision GPS (assoupli pour conditions réelles)

        // Critères de précision pour points de départ/arrivée
        private const val START_POINT_ANCHORING_DELAY_MS = 5000L // 5 secondes d'ancrage avant de choisir le point de départ
        private const val END_POINT_SAMPLING_DELAY_MS = 15000L // 15 secondes de collecte après détection WALKING
        private const val HIGH_PRECISION_THRESHOLD = 20f // 20m de précision pour points de départ/arrivée
        private const val START_POINT_CLUSTERING_WINDOW_MS = 60000L // 60 secondes pour clustering du point de départ

        // Critères de détection d'arrêt (stop detection)
        private const val STOP_DETECTION_RADIUS = 30f // 30 mètres - rayon réduit pour détecter les arrêts courts
        private const val STOP_DETECTION_DURATION_MS = 180000L // 3 minutes pour éviter faux positifs (bouchons/feux)
        private const val MIN_TRIP_DISTANCE_BEFORE_STOP_CHECK = 300.0 // 300m minimum parcourus avant de vérifier arrêt (évite faux départ)
        private const val MAX_TRIP_DURATION_MS = 36000000L // 10 heures max par trajet (failsafe anti-boucle infinie)

        // Critères de détection de trajet fantôme (ghost trip detection)
        private const val GHOST_TRIP_TIMEOUT_MS = 600000L // 10 minutes sans GPS = trajet fantôme
        private const val TRIP_HEALTH_CHECK_INTERVAL_MS = 300000L // BATTERY OPTIMIZATION: Vérifier l'état du trajet toutes les 5 minutes (réduit consommation batterie)

        // Critères de détection d'inactivité GPS (auto-stop pour trips fantômes causés par GPS drift)
        private const val INACTIVITY_TIMEOUT_MS = 300000L // 5 minutes sans mouvement réel = auto-stop
        private const val MIN_MOVEMENT_DISTANCE = 15f // 15 mètres minimum pour considérer un mouvement réel
        private const val MIN_MOVEMENT_SPEED = 1.0f // 1 m/s (3.6 km/h) vitesse minimum pour mouvement réel

        // Actions pour communication entre services
        private const val ACTION_START_TRACKING = "com.application.motium.START_TRACKING"
        private const val ACTION_STOP_TRACKING = "com.application.motium.STOP_TRACKING"

        // NOUVELLE LOGIQUE: Actions pour gestion du buffer et états
        private const val ACTION_START_BUFFERING = "com.application.motium.START_BUFFERING"
        private const val ACTION_CONFIRM_VEHICLE = "com.application.motium.CONFIRM_VEHICLE"
        private const val ACTION_REJECT_ACTIVITY = "com.application.motium.REJECT_ACTIVITY"
        private const val ACTION_PAUSE_TRACKING = "com.application.motium.PAUSE_TRACKING"
        private const val ACTION_RESUME_TRACKING = "com.application.motium.RESUME_TRACKING"
        private const val ACTION_END_TRIP = "com.application.motium.END_TRIP"
        private const val ACTION_MANUAL_STOP_TRIP = "com.application.motium.MANUAL_STOP_TRIP"

        // LEGACY: Compatibilité avec ancien code
        private const val ACTION_VEHICLE_CONFIRMED = "com.application.motium.VEHICLE_CONFIRMED"
        private const val ACTION_VEHICLE_ENDED = "com.application.motium.VEHICLE_ENDED"

        fun startService(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_START_TRACKING
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_STOP_TRACKING
            context.stopService(intent)
        }

        // NOUVELLE LOGIQUE: Méthodes pour gestion du buffer et états

        /**
         * Démarre l'enregistrement GPS en mode buffer (activité détectée mais non confirmée)
         */
        fun startBuffering(context: Context) {
            // Vérifier que les permissions de localisation sont accordées avant de démarrer
            if (!hasLocationPermissions(context)) {
                MotiumApplication.logger.w(
                    "Cannot start buffering: location permissions not granted",
                    "LocationService"
                )
                return
            }

            try {
                val intent = Intent(context, LocationTrackingService::class.java)
                intent.action = ACTION_START_BUFFERING
                // Use startService() instead of startForegroundService() because the service
                // is already started in foreground by ActivityRecognitionService
                // This allows BroadcastReceivers to send commands without Android 14+ restrictions
                context.startService(intent)
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Error sending startBuffering command: ${e.message}",
                    "LocationService",
                    e
                )
            }
        }

        private fun hasLocationPermissions(context: Context): Boolean {
            val fineLocation = android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            val coarseLocation = android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            return fineLocation || coarseLocation
        }

        /**
         * Confirme que l'activité est un déplacement en véhicule
         * Valide le buffer et passe en mode TRIP_ACTIVE
         */
        fun confirmVehicle(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_CONFIRM_VEHICLE
            context.startService(intent)
        }

        /**
         * Rejette l'activité (pas un véhicule)
         * Vide le buffer et retourne en STANDBY
         */
        fun rejectActivity(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_REJECT_ACTIVITY
            context.startService(intent)
        }

        /**
         * Pause temporaire de l'enregistrement GPS (activité non fiable)
         * Garde le buffer mais arrête le GPS
         */
        fun pauseTracking(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_PAUSE_TRACKING
            context.startService(intent)
        }

        /**
         * Reprend l'enregistrement GPS (reprise véhicule sans passer par marche)
         * Continue dans le même trajet
         */
        fun resumeTracking(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_RESUME_TRACKING
            context.startService(intent)
        }

        /**
         * Termine le trajet (activité confirmée non-véhicule)
         * Sauvegarde le trajet avec premier et dernier point du buffer
         */
        fun endTrip(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            intent.action = ACTION_END_TRIP
            context.startService(intent)
        }

        // LEGACY: Compatibilité avec ancien code

        /**
         * @deprecated Utiliser confirmVehicle() à la place
         */
        @Deprecated("Use confirmVehicle() instead")
        fun notifyVehicleMovementConfirmed(context: Context) {
            confirmVehicle(context)
        }

        /**
         * @deprecated Utiliser endTrip() à la place
         */
        @Deprecated("Use endTrip() instead")
        fun notifyVehicleMovementEnded(context: Context) {
            endTrip(context)
        }
    }

    /**
     * États du service pour gestion du cycle de vie d'un trajet
     */
    private enum class TripState {
        STANDBY,        // En attente, pas d'enregistrement GPS
        BUFFERING,      // Enregistrement GPS en buffer temporaire (activité détectée mais non confirmée)
        TRIP_ACTIVE,    // Trajet confirmé, enregistrement GPS actif
        PAUSED,         // Pause temporaire (activité non fiable), buffer conservé mais GPS arrêté
        STOP_PENDING,   // Arrêt détecté, période de grâce de 2 min (debounce pour éviter faux positifs)
        FINALIZING      // Collecte des derniers points précis avant sauvegarde
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private lateinit var tripRepository: TripRepository
    private var isTracking = false
    private var currentTrip: TripData? = null

    // CRASH FIX: Add exception handler to catch all uncaught exceptions in coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        MotiumApplication.logger.e(
            "❌ Uncaught exception in LocationTrackingService coroutine: ${exception.message}",
            "LocationService",
            exception
        )
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // NOUVELLE LOGIQUE: État du service avec machine à états
    private var tripState = TripState.STANDBY

    // Buffer temporaire pour points GPS non confirmés
    private val gpsBuffer = mutableListOf<TripLocation>()

    // LEGACY: Compatibilité - computed property sans backing field
    @Deprecated("Use tripState instead")
    private val isVehicleMovementConfirmed: Boolean
        get() = tripState == TripState.TRIP_ACTIVE || tripState == TripState.FINALIZING

    // Système de surveillance des notifications
    private val notificationWatchHandler = Handler(Looper.getMainLooper())
    private var notificationWatchRunnable: Runnable? = null
    private var isInTrip = false
    private var isFinalizingTrip = false // Nouveau: indique qu'on collecte les derniers points avant de terminer

    // Système de précision pour points de départ/arrivée
    private var startPointCandidates = mutableListOf<TripLocation>()
    private var endPointCandidates = mutableListOf<TripLocation>()
    private var isCollectingEndPoints = false
    private var endPointCollectionStartTime: Long? = null
    private val endPointHandler = Handler(Looper.getMainLooper())

    // Système de détection de trajet fantôme
    private var lastGPSUpdateTime: Long = 0
    private var lastRecoveryTime: Long = 0  // Horodatage du dernier recovery pour éviter boucles
    private val tripHealthCheckHandler = Handler(Looper.getMainLooper())
    private var tripHealthCheckRunnable: Runnable? = null

    // Système de détection d'inactivité GPS (pour auto-stop des trips fantômes)
    private var lastSignificantMoveTime: Long = 0
    private var lastSignificantLocation: Location? = null

    // Système de debounce pour arrêts (période de grâce de 2 minutes)
    private val stopDebounceHandler = Handler(Looper.getMainLooper())
    private var stopPendingStartTime: Long? = null
    private val STOP_DEBOUNCE_DELAY_MS = 120000L // 2 minutes
    private val STOP_RESUME_SPEED_THRESHOLD = 2.7f // 10 km/h en m/s

    data class TripData(
        val id: String = java.util.UUID.randomUUID().toString(),
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long? = null,
        val locations: MutableList<TripLocation> = mutableListOf(),
        var totalDistance: Double = 0.0
    )

    override fun onCreate() {
        super.onCreate()
        MotiumApplication.logger.i("📍 LocationTrackingService created - GPS collection only during trips", "LocationService")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tripRepository = TripRepository.getInstance(this)

        createNotificationChannel()
        createLocationRequest()
        createLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        MotiumApplication.logger.i("LocationTrackingService command: $action (current state: $tripState)", "LocationService")

        when (action) {
            ACTION_START_TRACKING -> {
                // Démarrage normal du service (appelé par ActivityRecognitionService)
                startForegroundService()
                // BATTERY OPTIMIZATION: Ne pas démarrer le GPS en mode STANDBY
                // Le GPS sera démarré uniquement quand Activity Recognition détecte un mouvement (ACTION_START_BUFFERING)
                // startLocationUpdates() <-- DÉSACTIVÉ pour économie batterie
                startNotificationWatch()
                // BATTERY OPTIMIZATION: Trip health check sera démarré uniquement en mode BUFFERING/TRIP_ACTIVE
                // startTripHealthCheck() <-- DÉSACTIVÉ, sera lancé dans ACTION_START_BUFFERING
            }

            ACTION_START_BUFFERING -> {
                // Activité détectée (même non fiable) → démarre GPS en mode buffer
                MotiumApplication.logger.i("📡 ACTIVITY DETECTED - Starting GPS in BUFFERING mode", "LocationService")

                // S'assurer que le service est démarré
                if (!isTracking) {
                    startForegroundService()
                    startNotificationWatch()
                }

                when (tripState) {
                    TripState.STANDBY -> {
                        // Passer en mode BUFFERING
                        tripState = TripState.BUFFERING
                        gpsBuffer.clear()
                        lastGPSUpdateTime = 0  // 🔧 FIX: Réinitialiser le timestamp pour éviter détection ghost trip avec ancienne valeur

                        // BATTERY OPTIMIZATION: Démarrer GPS en haute fréquence pour collecter points précis
                        updateGPSFrequency(tripMode = true)
                        if (!isTracking) startLocationUpdates()

                        // BATTERY OPTIMIZATION: Démarrer trip health check uniquement maintenant
                        startTripHealthCheck()

                        MotiumApplication.logger.i("State transition: STANDBY → BUFFERING (GPS + health check started)", "TripStateMachine")
                    }
                    TripState.PAUSED -> {
                        // Reprendre depuis pause (même trajet)
                        tripState = TripState.BUFFERING
                        // Ne pas vider le buffer, on continue à accumuler
                        lastGPSUpdateTime = 0  // 🔧 FIX: Réinitialiser le timestamp pour éviter détection ghost trip avec ancienne valeur

                        // BATTERY OPTIMIZATION: Redémarrer GPS + health check
                        updateGPSFrequency(tripMode = true)
                        if (!isTracking) startLocationUpdates()
                        startTripHealthCheck()

                        MotiumApplication.logger.i("State transition: PAUSED → BUFFERING (resumed, GPS + health check started)", "TripStateMachine")
                    }
                    TripState.STOP_PENDING -> {
                        // AUTO-RESUME: Véhicule détecté pendant la période de grâce
                        MotiumApplication.logger.i(
                            "🔄 AUTO-RESUME: Vehicle activity detected during stop grace period - Cancelling stop and resuming trip",
                            "TripStateMachine"
                        )

                        // Annuler le timer de debounce
                        stopDebounceHandler.removeCallbacksAndMessages(null)
                        stopPendingStartTime = null

                        // Repasser en TRIP_ACTIVE
                        tripState = TripState.TRIP_ACTIVE

                        MotiumApplication.logger.i("State transition: STOP_PENDING → TRIP_ACTIVE (auto-resume)", "TripStateMachine")
                    }
                    TripState.FINALIZING -> {
                        // AUTO-RESUME: Véhicule détecté pendant la finalisation (résout le bug du "Trou noir")
                        MotiumApplication.logger.i(
                            "🔄 AUTO-RESUME: Vehicle activity detected during finalization - Cancelling finalization and resuming trip",
                            "TripStateMachine"
                        )

                        // Annuler le timer de finalisation
                        endPointHandler.removeCallbacksAndMessages(null)
                        isCollectingEndPoints = false
                        endPointCandidates.clear()

                        // Repasser en TRIP_ACTIVE
                        tripState = TripState.TRIP_ACTIVE

                        MotiumApplication.logger.i("State transition: FINALIZING → TRIP_ACTIVE (auto-resume)", "TripStateMachine")
                    }
                    else -> {
                        MotiumApplication.logger.w("START_BUFFERING ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_CONFIRM_VEHICLE -> {
                // Activité confirmée comme véhicule → valider buffer et passer en TRIP_ACTIVE
                MotiumApplication.logger.i("✅ VEHICLE CONFIRMED - Validating buffer and starting trip", "LocationService")

                // Tenter de passer en foreground maintenant qu'un trajet est confirmé
                tryStartForeground("Trajet en cours")

                when (tripState) {
                    TripState.BUFFERING -> {
                        // Valider le buffer: créer un trajet avec les points du buffer
                        tripState = TripState.TRIP_ACTIVE

                        if (currentTrip == null) {
                            // Créer un nouveau trajet
                            currentTrip = TripData()

                            // Transférer les points du buffer dans le trajet
                            gpsBuffer.forEach { location ->
                                currentTrip?.locations?.add(location)

                                // Calculer distance
                                val lastLoc = currentTrip?.locations?.getOrNull(currentTrip!!.locations.size - 2)
                                if (lastLoc != null) {
                                    val results = FloatArray(1)
                                    android.location.Location.distanceBetween(
                                        lastLoc.latitude, lastLoc.longitude,
                                        location.latitude, location.longitude,
                                        results
                                    )
                                    currentTrip?.totalDistance = (currentTrip?.totalDistance ?: 0.0) + results[0]
                                }
                            }

                            // Initialiser la collecte de points de départ
                            startPointCandidates.clear()
                            startPointCandidates.addAll(gpsBuffer)

                            MotiumApplication.logger.i(
                                "🎬 Trip started with ${gpsBuffer.size} buffered points (${String.format("%.0f", currentTrip?.totalDistance)}m)",
                                "TripTracker"
                            )

                            // Vider le buffer (transféré dans le trajet)
                            gpsBuffer.clear()
                        }

                        MotiumApplication.logger.i("State transition: BUFFERING → TRIP_ACTIVE", "TripStateMachine")
                    }
                    TripState.PAUSED -> {
                        // Reprise directe depuis pause
                        tripState = TripState.TRIP_ACTIVE

                        // CRITIQUE: Vérifier si le trajet existe
                        // Si on a fait: véhicule détecté → buffering → pause (activité non fiable) → véhicule confirmé
                        // alors currentTrip peut être null et on doit le créer maintenant
                        if (currentTrip == null) {
                            MotiumApplication.logger.w(
                                "⚠️ PAUSED → TRIP_ACTIVE but currentTrip is null! Creating trip from buffer (${gpsBuffer.size} points)",
                                "TripStateMachine"
                            )

                            // Créer un nouveau trajet
                            currentTrip = TripData()

                            // Transférer les points du buffer dans le trajet
                            gpsBuffer.forEach { location ->
                                currentTrip?.locations?.add(location)

                                // Calculer distance
                                val lastLoc = currentTrip?.locations?.getOrNull(currentTrip!!.locations.size - 2)
                                if (lastLoc != null) {
                                    val results = FloatArray(1)
                                    android.location.Location.distanceBetween(
                                        lastLoc.latitude, lastLoc.longitude,
                                        location.latitude, location.longitude,
                                        results
                                    )
                                    currentTrip?.totalDistance = (currentTrip?.totalDistance ?: 0.0) + results[0]
                                }
                            }

                            // Initialiser la collecte de points de départ
                            startPointCandidates.clear()
                            startPointCandidates.addAll(gpsBuffer)

                            MotiumApplication.logger.i(
                                "🎬 Trip created from PAUSED state with ${gpsBuffer.size} buffered points (${String.format("%.0f", currentTrip?.totalDistance)}m)",
                                "TripTracker"
                            )

                            // Vider le buffer (transféré dans le trajet)
                            gpsBuffer.clear()
                        }

                        // Redémarrer GPS
                        updateGPSFrequency(tripMode = true)
                        if (!isTracking) startLocationUpdates()

                        MotiumApplication.logger.i("State transition: PAUSED → TRIP_ACTIVE (resumed)", "TripStateMachine")
                    }
                    TripState.TRIP_ACTIVE -> {
                        // RECOVERY: CONFIRM_VEHICLE reçu alors qu'un trajet est déjà actif
                        // Vérifier si c'est un trajet fantôme ou un vrai trajet en cours

                        val currentTime = System.currentTimeMillis()

                        // GARDE-FOU 1: Éviter boucle de recovery - ne pas recréer si recovery récent (< 60s)
                        val timeSinceLastRecovery = currentTime - lastRecoveryTime
                        if (lastRecoveryTime > 0 && timeSinceLastRecovery < 60000L) {
                            MotiumApplication.logger.w(
                                "CONFIRM_VEHICLE ignored: recovery already done ${timeSinceLastRecovery/1000}s ago (anti-loop protection)",
                                "TripRecovery"
                            )
                            return START_STICKY
                        }

                        // GARDE-FOU 2: Si lastGPSUpdateTime jamais initialisé, l'initialiser maintenant
                        if (lastGPSUpdateTime == 0L) {
                            lastGPSUpdateTime = currentTime
                            MotiumApplication.logger.w(
                                "lastGPSUpdateTime was 0, initializing to current time (trip just started or first GPS not yet received)",
                                "TripRecovery"
                            )
                            return START_STICKY
                        }

                        val timeSinceLastGPS = currentTime - lastGPSUpdateTime

                        // Log de diagnostic
                        MotiumApplication.logger.d(
                            "Recovery check: lastGPS=${timeSinceLastGPS/1000}s ago, lastRecovery=${timeSinceLastRecovery/1000}s ago, tripPoints=${currentTrip?.locations?.size ?: 0}",
                            "TripRecovery"
                        )

                        if (timeSinceLastGPS > GHOST_TRIP_TIMEOUT_MS) {
                            // Trajet fantôme détecté: terminer l'ancien et démarrer un nouveau
                            MotiumApplication.logger.w(
                                "🔧 RECOVERY: Ghost trip detected in TRIP_ACTIVE (no GPS for ${timeSinceLastGPS/1000}s) - Terminating old trip and starting new one",
                                "TripRecovery"
                            )

                            // Marquer le recovery pour éviter boucle
                            lastRecoveryTime = currentTime

                            // Sauvegarder l'ancien trajet s'il a des points
                            if (currentTrip != null && currentTrip!!.locations.isNotEmpty()) {
                                finishCurrentTrip()
                            } else {
                                // Pas de points, simplement réinitialiser
                                currentTrip = null
                                gpsBuffer.clear()
                                startPointCandidates.clear()
                                endPointCandidates.clear()
                            }

                            // Passer en BUFFERING pour démarrer un nouveau trajet
                            tripState = TripState.BUFFERING
                            gpsBuffer.clear()
                            updateGPSFrequency(tripMode = true)
                            if (!isTracking) startLocationUpdates()

                            MotiumApplication.logger.i("State transition: TRIP_ACTIVE → BUFFERING (recovery, new trip)", "TripStateMachine")
                        } else {
                            // Trajet actif valide: continuer le trajet actuel
                            MotiumApplication.logger.w(
                                "CONFIRM_VEHICLE received in TRIP_ACTIVE with recent GPS (${timeSinceLastGPS/1000}s ago) - Continuing current trip",
                                "TripStateMachine"
                            )
                        }
                    }
                    else -> {
                        MotiumApplication.logger.w("CONFIRM_VEHICLE ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_REJECT_ACTIVITY -> {
                // Activité confirmée comme NON-véhicule → vider buffer et retour STANDBY
                MotiumApplication.logger.i("❌ ACTIVITY REJECTED - Clearing buffer and returning to STANDBY", "LocationService")

                when (tripState) {
                    TripState.BUFFERING -> {
                        // Vider le buffer
                        gpsBuffer.clear()

                        // Passer en STANDBY
                        tripState = TripState.STANDBY

                        // BATTERY OPTIMIZATION: Arrêter complètement le GPS et le trip health check
                        stopLocationUpdates()
                        stopTripHealthCheck()

                        MotiumApplication.logger.i("State transition: BUFFERING → STANDBY (rejected, buffer cleared, GPS + health check stopped)", "TripStateMachine")
                    }
                    else -> {
                        MotiumApplication.logger.w("REJECT_ACTIVITY ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_PAUSE_TRACKING -> {
                // Activité non fiable → pause GPS mais garde buffer
                MotiumApplication.logger.i("⏸️ ACTIVITY UNRELIABLE - Pausing GPS (keeping buffer)", "LocationService")

                // S'assurer que le service est en foreground (requis par Android)
                tryStartForeground("Trajet en pause")

                when (tripState) {
                    TripState.BUFFERING, TripState.TRIP_ACTIVE -> {
                        // Passer en PAUSED
                        val previousState = tripState
                        tripState = TripState.PAUSED

                        // BATTERY OPTIMIZATION: Arrêter GPS et trip health check pour économiser batterie
                        stopLocationUpdates()
                        stopTripHealthCheck()

                        MotiumApplication.logger.i("State transition: $previousState → PAUSED (GPS + health check stopped)", "TripStateMachine")
                    }
                    else -> {
                        MotiumApplication.logger.w("PAUSE_TRACKING ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_RESUME_TRACKING -> {
                // Reprise véhicule depuis pause → reprendre GPS
                MotiumApplication.logger.i("▶️ RESUMING TRACKING - Restarting GPS", "LocationService")

                // S'assurer que le service est en foreground (requis par Android)
                tryStartForeground("Reprise du trajet")

                when (tripState) {
                    TripState.PAUSED -> {
                        // Déterminer si on retourne en BUFFERING ou TRIP_ACTIVE
                        tripState = if (currentTrip != null) TripState.TRIP_ACTIVE else TripState.BUFFERING

                        // BATTERY OPTIMIZATION: Redémarrer GPS et trip health check
                        updateGPSFrequency(tripMode = true)
                        startLocationUpdates()
                        startTripHealthCheck()

                        MotiumApplication.logger.i("State transition: PAUSED → $tripState (resumed, GPS + health check started)", "TripStateMachine")
                    }
                    else -> {
                        MotiumApplication.logger.w("RESUME_TRACKING ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_END_TRIP -> {
                // Activité confirmée NON-véhicule (marche) → terminer et sauvegarder trajet
                MotiumApplication.logger.i("🏁 END TRIP - Activity confirmed as non-vehicle", "LocationService")

                // S'assurer que le service est en foreground (requis par Android)
                tryStartForeground("Finalisation du trajet")

                when (tripState) {
                    TripState.TRIP_ACTIVE -> {
                        // Si on a un trajet actif, passer en STOP_PENDING (debounce de 2 min)
                        if (currentTrip != null) {
                            tripState = TripState.STOP_PENDING
                            stopPendingStartTime = System.currentTimeMillis()

                            MotiumApplication.logger.i(
                                "State transition: TRIP_ACTIVE → STOP_PENDING (Grace period 2min) - GPS continues collecting",
                                "TripStateMachine"
                            )

                            // CRITIQUE: Ne PAS arrêter le GPS, continuer à collecter pour détecter auto-resume
                            // Le GPS reste actif pour surveiller la vitesse et permettre auto-resume

                            // Démarrer le timer de debounce (2 minutes)
                            stopDebounceHandler.postDelayed({
                                // Timer expiré = confirmation de l'arrêt
                                MotiumApplication.logger.i(
                                    "Grace period expired (2min) - confirming stop and entering finalization",
                                    "TripStateMachine"
                                )

                                // Passer en FINALIZING
                                tripState = TripState.FINALIZING

                                // Commencer la collecte de points d'arrivée
                                startEndPointCollection()

                                // Programmer la finalisation après 15s
                                endPointHandler.postDelayed({
                                    MotiumApplication.logger.i("End point collection complete - finalizing trip", "LocationService")

                                    // CRITIQUE: Utiliser le timestamp de début de STOP_PENDING pour la date de fin
                                    stopPendingStartTime?.let { startTime ->
                                        currentTrip?.endTime = startTime
                                        MotiumApplication.logger.i(
                                            "Trip end time adjusted to STOP_PENDING start (excluding 2min grace period)",
                                            "TripStateMachine"
                                        )
                                    }

                                    finishCurrentTrip()
                                    stopPendingStartTime = null
                                }, END_POINT_SAMPLING_DELAY_MS)

                                MotiumApplication.logger.i("State transition: STOP_PENDING → FINALIZING", "TripStateMachine")
                            }, STOP_DEBOUNCE_DELAY_MS)

                        } else {
                            // Pas de trajet actif, vider buffer et retour STANDBY
                            gpsBuffer.clear()
                            tripState = TripState.STANDBY
                            updateGPSFrequency(tripMode = false)

                            MotiumApplication.logger.i("State transition: TRIP_ACTIVE → STANDBY (no active trip)", "TripStateMachine")
                        }
                    }
                    TripState.BUFFERING -> {
                        // Si on a un trajet en buffer mais non confirmé, vider et retour STANDBY
                        gpsBuffer.clear()
                        tripState = TripState.STANDBY
                        updateGPSFrequency(tripMode = false)

                        MotiumApplication.logger.i("State transition: BUFFERING → STANDBY (trip not confirmed)", "TripStateMachine")
                    }
                    TripState.PAUSED -> {
                        // Finaliser le trajet en pause
                        if (currentTrip != null) {
                            tripState = TripState.FINALIZING

                            // Redémarrer GPS pour collecter points d'arrivée
                            updateGPSFrequency(tripMode = true)
                            startLocationUpdates()
                            startEndPointCollection()

                            endPointHandler.postDelayed({
                                finishCurrentTrip()
                            }, END_POINT_SAMPLING_DELAY_MS)

                            MotiumApplication.logger.i("State transition: PAUSED → FINALIZING", "TripStateMachine")
                        } else {
                            // Pas de trajet, retour STANDBY
                            gpsBuffer.clear()
                            tripState = TripState.STANDBY

                            MotiumApplication.logger.i("State transition: PAUSED → STANDBY (no active trip)", "TripStateMachine")
                        }
                    }
                    TripState.STOP_PENDING -> {
                        // Déjà en STOP_PENDING, ne rien faire (éviter de redémarrer le timer)
                        MotiumApplication.logger.w(
                            "END_TRIP ignored in STOP_PENDING (already waiting for grace period to expire)",
                            "TripStateMachine"
                        )
                    }
                    else -> {
                        MotiumApplication.logger.w("END_TRIP ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_MANUAL_STOP_TRIP -> {
                // Arrêt manuel du trajet depuis le bouton de notification
                MotiumApplication.logger.i("🛑 MANUAL STOP - User stopped trip from notification", "LocationService")

                // S'assurer que le service est en foreground (requis par Android)
                tryStartForeground("Finalisation du trajet")

                when (tripState) {
                    TripState.TRIP_ACTIVE, TripState.BUFFERING -> {
                        // Si on a un trajet actif, le finaliser
                        if (currentTrip != null) {
                            tripState = TripState.FINALIZING

                            // Commencer la collecte de points d'arrivée
                            startEndPointCollection()

                            // Programmer la finalisation après 15s
                            endPointHandler.postDelayed({
                                MotiumApplication.logger.i("End point collection complete - finalizing trip (manual stop)", "LocationService")
                                finishCurrentTrip()
                            }, END_POINT_SAMPLING_DELAY_MS)

                            MotiumApplication.logger.i("State transition: $tripState → FINALIZING (manual stop)", "TripStateMachine")
                        } else {
                            // Pas de trajet actif, vider buffer et retour STANDBY
                            gpsBuffer.clear()
                            tripState = TripState.STANDBY
                            updateGPSFrequency(tripMode = false)

                            MotiumApplication.logger.i("State transition: $tripState → STANDBY (manual stop, no active trip)", "TripStateMachine")
                        }
                    }
                    TripState.STOP_PENDING -> {
                        // Annuler le debounce et forcer l'arrêt immédiat
                        MotiumApplication.logger.i("Manual stop during STOP_PENDING - Cancelling debounce and forcing immediate stop", "TripStateMachine")

                        // Annuler le timer de debounce
                        stopDebounceHandler.removeCallbacksAndMessages(null)
                        val endTime = stopPendingStartTime ?: System.currentTimeMillis()
                        stopPendingStartTime = null

                        if (currentTrip != null) {
                            tripState = TripState.FINALIZING

                            // Commencer la collecte de points d'arrivée
                            startEndPointCollection()

                            // Programmer la finalisation après 15s
                            endPointHandler.postDelayed({
                                MotiumApplication.logger.i("End point collection complete - finalizing trip (manual stop from STOP_PENDING)", "LocationService")

                                // Utiliser le timestamp de début de STOP_PENDING
                                currentTrip?.endTime = endTime
                                finishCurrentTrip()
                            }, END_POINT_SAMPLING_DELAY_MS)

                            MotiumApplication.logger.i("State transition: STOP_PENDING → FINALIZING (manual stop)", "TripStateMachine")
                        } else {
                            // Pas de trajet, retour STANDBY
                            gpsBuffer.clear()
                            tripState = TripState.STANDBY

                            MotiumApplication.logger.i("State transition: STOP_PENDING → STANDBY (manual stop, no active trip)", "TripStateMachine")
                        }
                    }
                    TripState.PAUSED -> {
                        // Finaliser le trajet en pause
                        if (currentTrip != null) {
                            tripState = TripState.FINALIZING

                            // Redémarrer GPS pour collecter points d'arrivée
                            updateGPSFrequency(tripMode = true)
                            startLocationUpdates()
                            startEndPointCollection()

                            endPointHandler.postDelayed({
                                finishCurrentTrip()
                            }, END_POINT_SAMPLING_DELAY_MS)

                            MotiumApplication.logger.i("State transition: PAUSED → FINALIZING (manual stop)", "TripStateMachine")
                        } else {
                            // Pas de trajet, retour STANDBY
                            gpsBuffer.clear()
                            tripState = TripState.STANDBY

                            MotiumApplication.logger.i("State transition: PAUSED → STANDBY (manual stop, no active trip)", "TripStateMachine")
                        }
                    }
                    else -> {
                        MotiumApplication.logger.w("MANUAL_STOP_TRIP ignored in state $tripState", "TripStateMachine")
                    }
                }

                updateNotificationStatus()
            }

            ACTION_VEHICLE_CONFIRMED, ACTION_VEHICLE_ENDED -> {
                // LEGACY: Rediriger vers nouvelles actions
                if (action == ACTION_VEHICLE_CONFIRMED) {
                    MotiumApplication.logger.w("Using deprecated ACTION_VEHICLE_CONFIRMED, redirecting to CONFIRM_VEHICLE", "LocationService")
                    val newIntent = Intent(this, LocationTrackingService::class.java)
                    newIntent.action = ACTION_CONFIRM_VEHICLE
                    onStartCommand(newIntent, flags, startId)
                } else {
                    MotiumApplication.logger.w("Using deprecated ACTION_VEHICLE_ENDED, redirecting to END_TRIP", "LocationService")
                    val newIntent = Intent(this, LocationTrackingService::class.java)
                    newIntent.action = ACTION_END_TRIP
                    onStartCommand(newIntent, flags, startId)
                }
            }

            ACTION_STOP_TRACKING -> {
                // Arrêt complet du service
                stopSelf()
            }

            else -> {
                // Fallback pour compatibilité
                startForegroundService()
                startLocationUpdates()
                startNotificationWatch()
            }
        }

        return START_STICKY // Service persiste même si l'app est fermée
    }

    override fun onDestroy() {
        super.onDestroy()
        MotiumApplication.logger.i("📍 LocationTrackingService destroyed", "LocationService")

        stopLocationUpdates()
        stopNotificationWatch()
        stopTripHealthCheck()

        // CRITICAL FIX: Remove all pending callbacks from handlers to prevent crashes
        endPointHandler.removeCallbacksAndMessages(null)
        tripHealthCheckHandler.removeCallbacksAndMessages(null)
        stopDebounceHandler.removeCallbacksAndMessages(null)
        MotiumApplication.logger.i("Cleared all handler callbacks (including stop debounce)", "LocationService")

        serviceScope.cancel()

        // Arrêter le mode foreground si actif
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

        // Terminer le trajet en cours s'il existe
        finishCurrentTrip()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Suivi de localisation",
            NotificationManager.IMPORTANCE_LOW // LOW pour ne pas déranger avec son/vibration
        ).apply {
            description = "Notifications silencieuses pour le suivi GPS des trajets"
            setShowBadge(false)
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            enableLights(false)
            enableVibration(false) // Désactiver vibration
            setSound(null, null) // Désactiver son
            setBlockable(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private var isForeground = false

    private fun startForegroundService() {
        tryStartForeground("En attente de trajet - Standby")
    }

    private fun tryStartForeground(message: String) {
        if (isForeground) return // Déjà en foreground

        try {
            val notification = createNotification(message)
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            MotiumApplication.logger.i("Service started in foreground mode", "LocationService")
        } catch (e: SecurityException) {
            // Android 14+ rejette startForeground si l'app n'est pas dans un état "eligible" (en arrière-plan)
            // Ne pas tuer le service, juste continuer en arrière-plan sans foreground
            // Le service passera en foreground plus tard quand ce sera possible
            MotiumApplication.logger.w(
                "Cannot start foreground service from background (Android 14+ restriction): ${e.message}. " +
                "Service will continue in background mode.",
                "LocationService"
            )
            isForeground = false
            // Ne pas appeler stopSelf() - continuer en arrière-plan
        }
    }

    private fun createNotification(content: String): Notification {
        // Intent pour ouvrir l'application au clic sur la notification
        val notificationIntent = Intent(this, com.application.motium.presentation.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Builder de notification de base
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motium - Auto Tracking")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(pendingIntent) // Ouvre l'app au clic
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // LOW pour ne pas déranger
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(null)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true) // Complètement silencieux
            .setOnlyAlertOnce(true) // Alerte seulement à la première création
            .setDefaults(0) // Aucun défaut (pas de son, pas de vibration)

        // Ajouter bouton "Arrêter" si un trajet est en cours
        if (tripState == TripState.TRIP_ACTIVE || tripState == TripState.BUFFERING || tripState == TripState.STOP_PENDING) {
            val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
                action = ACTION_MANUAL_STOP_TRIP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            notificationBuilder.addAction(
                R.drawable.ic_location, // TODO: Utiliser une icône stop si disponible
                "Arrêter",
                stopPendingIntent
            )
        }

        return notificationBuilder.build().apply {
            flags = flags or
                Notification.FLAG_NO_CLEAR or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_FOREGROUND_SERVICE
        }
    }

    private fun createLocationRequest() {
        // Démarrer en mode STANDBY (économie batterie)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, STANDBY_UPDATE_INTERVAL)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(STANDBY_FASTEST_INTERVAL)
            .setMaxUpdateDelayMillis(STANDBY_UPDATE_INTERVAL * 2)
            .build()

        MotiumApplication.logger.i("GPS initialized in STANDBY mode (1 call/minute)", "LocationService")
    }

    /**
     * Change la fréquence GPS selon le mode (STANDBY vs TRIP)
     */
    private fun updateGPSFrequency(tripMode: Boolean) {
        if (!isTracking) return

        val interval = if (tripMode) TRIP_UPDATE_INTERVAL else STANDBY_UPDATE_INTERVAL
        val fastestInterval = if (tripMode) TRIP_FASTEST_INTERVAL else STANDBY_FASTEST_INTERVAL

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setMaxUpdateDelayMillis(interval * 2)
            .build()

        // Redémarrer les updates GPS avec la nouvelle fréquence
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            val mode = if (tripMode) "TRIP (10s)" else "STANDBY (1min)"
            MotiumApplication.logger.i("GPS frequency switched to $mode", "LocationService")
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)

                locationResult.locations.forEach { location ->
                    processLocationUpdate(location)
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                super.onLocationAvailability(locationAvailability)

                val isAvailable = locationAvailability.isLocationAvailable
                MotiumApplication.logger.i("Location availability: $isAvailable", "LocationService")

                // Ne pas mettre à jour la notification pour éviter vibrations constantes
                // Le GPS non disponible est déjà loggé
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            MotiumApplication.logger.w("Location permission not granted", "LocationService")
            return
        }

        isTracking = true
        MotiumApplication.logger.i("Starting location updates", "LocationService")

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        if (isTracking) {
            isTracking = false
            MotiumApplication.logger.i("Stopping location updates", "LocationService")
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun processLocationUpdate(location: Location) {
        // Filtrer les points GPS selon critères de qualité
        if (location.accuracy > MAX_GPS_ACCURACY_METERS) {
            MotiumApplication.logger.d(
                "GPS point rejected: accuracy ${location.accuracy}m > ${MAX_GPS_ACCURACY_METERS}m (lastGPSUpdateTime NOT updated)",
                "LocationService"
            )
            return
        }

        // Mettre à jour le timestamp du dernier GPS reçu (pour détection trajet fantôme)
        val previousUpdateTime = lastGPSUpdateTime
        lastGPSUpdateTime = System.currentTimeMillis()

        // Log de diagnostic pour tracker les mises à jour GPS
        if (previousUpdateTime == 0L) {
            MotiumApplication.logger.d(
                "lastGPSUpdateTime initialized (first GPS accepted, accuracy=${location.accuracy}m)",
                "LocationService"
            )
        }

        // AUTO-RESUME PAR VITESSE: Si en STOP_PENDING et vitesse > 10 km/h, reprendre le trajet
        if (tripState == TripState.STOP_PENDING && location.hasSpeed() && location.speed > STOP_RESUME_SPEED_THRESHOLD) {
            MotiumApplication.logger.i(
                "🔄 AUTO-RESUME: Speed detected > 10km/h (${String.format("%.1f", location.speed * 3.6)}km/h) during stop grace period - Resuming trip",
                "TripStateMachine"
            )

            // Annuler le timer de debounce
            stopDebounceHandler.removeCallbacksAndMessages(null)
            stopPendingStartTime = null

            // Repasser en TRIP_ACTIVE
            tripState = TripState.TRIP_ACTIVE

            MotiumApplication.logger.i("State transition: STOP_PENDING → TRIP_ACTIVE (auto-resume by speed)", "TripStateMachine")
            updateNotificationStatus()
        }

        // NOUVEAU: Détection d'inactivité GPS pour auto-stop des trips fantômes
        detectInactivityAndAutoStop(location)

        MotiumApplication.logger.logLocationUpdate(
            location.latitude,
            location.longitude,
            location.accuracy
        )

        val tripLocation = TripLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        // Si on collecte les points d'arrivée, ajouter aux candidats
        if (isCollectingEndPoints) {
            endPointCandidates.add(tripLocation)
            MotiumApplication.logger.d(
                "Collected end point candidate ${endPointCandidates.size}: accuracy=${location.accuracy}m",
                "EndPointPrecision"
            )
        }

        // Ajouter la localisation selon l'état actuel
        when (tripState) {
            TripState.BUFFERING -> {
                // Mode buffer: ajouter au buffer temporaire
                gpsBuffer.add(tripLocation)
                MotiumApplication.logger.d(
                    "Added point to buffer (${gpsBuffer.size} points, state: BUFFERING)",
                    "GPSBuffer"
                )

                // AUTO-CONFIRMATION: Détecter automatiquement le mouvement réel et confirmer le trajet
                // Critères: au moins 3 points + (distance > 50m OU vitesse > 1 m/s)
                if (gpsBuffer.size >= 3) {
                    // Calculer distance totale et durée du buffer
                    var totalDistance = 0.0
                    for (i in 1 until gpsBuffer.size) {
                        val prev = gpsBuffer[i - 1]
                        val curr = gpsBuffer[i]
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(
                            prev.latitude, prev.longitude,
                            curr.latitude, curr.longitude,
                            results
                        )
                        totalDistance += results[0]
                    }

                    // Calculer durée et vitesse moyenne
                    val firstTimestamp = gpsBuffer.first().timestamp
                    val lastTimestamp = gpsBuffer.last().timestamp
                    val durationMs = lastTimestamp - firstTimestamp
                    val durationSeconds = durationMs / 1000.0
                    val averageSpeedMps = if (durationSeconds > 0) totalDistance / durationSeconds else 0.0

                    MotiumApplication.logger.d(
                        "Buffer analysis: ${gpsBuffer.size} points, ${String.format("%.1f", totalDistance)}m, " +
                        "${String.format("%.1f", durationSeconds)}s, ${String.format("%.2f", averageSpeedMps)}m/s (${String.format("%.1f", averageSpeedMps * 3.6)}km/h)",
                        "BufferAutoConfirm"
                    )

                    // Critères de confirmation automatique
                    val MIN_BUFFER_DISTANCE = 50.0 // 50 mètres minimum
                    val MIN_BUFFER_SPEED = 1.0 // 1 m/s (3.6 km/h) minimum

                    if (totalDistance >= MIN_BUFFER_DISTANCE || averageSpeedMps >= MIN_BUFFER_SPEED) {
                        MotiumApplication.logger.i(
                            "✅ AUTO-CONFIRM: Mouvement réel détecté!\n" +
                            "   Distance: ${String.format("%.1f", totalDistance)}m (seuil: ${MIN_BUFFER_DISTANCE}m)\n" +
                            "   Vitesse moyenne: ${String.format("%.2f", averageSpeedMps)}m/s = ${String.format("%.1f", averageSpeedMps * 3.6)}km/h (seuil: ${MIN_BUFFER_SPEED * 3.6}km/h)\n" +
                            "   Points GPS: ${gpsBuffer.size}\n" +
                            "   → Transition automatique BUFFERING → TRIP_ACTIVE",
                            "BufferAutoConfirm"
                        )

                        // Confirmer automatiquement le trajet
                        val intent = Intent(this, LocationTrackingService::class.java)
                        intent.action = ACTION_CONFIRM_VEHICLE
                        startService(intent)
                    }
                }
            }
            TripState.TRIP_ACTIVE -> {
                // Mode trajet actif: ajouter au trajet
                currentTrip?.let { trip ->
                    addLocationToTrip(trip, location)
                }
            }
            TripState.STOP_PENDING -> {
                // Mode arrêt en attente: continuer à collecter pour détecter auto-resume
                currentTrip?.let { trip ->
                    addLocationToTrip(trip, location)
                    MotiumApplication.logger.d(
                        "Point collected during STOP_PENDING (monitoring for auto-resume)",
                        "LocationService"
                    )
                }
            }
            TripState.FINALIZING -> {
                // Mode finalisation: ajouter au trajet (derniers points précis)
                currentTrip?.let { trip ->
                    addLocationToTrip(trip, location)
                }
            }
            else -> {
                // STANDBY ou PAUSED: ne rien faire
                MotiumApplication.logger.d(
                    "GPS point ignored in state $tripState",
                    "LocationService"
                )
            }
        }
    }

    /**
     * Détecte l'inactivité GPS et auto-arrête le trip si nécessaire
     * Évite les trips fantômes causés par le GPS drift (1-3m de mouvement parasite)
     */
    private fun detectInactivityAndAutoStop(location: Location) {
        // Ne s'applique que pour les trips actifs
        if (tripState != TripState.TRIP_ACTIVE) {
            return
        }

        val currentTime = System.currentTimeMillis()

        // Calculer distance et vitesse par rapport à la dernière position significative
        var isSignificantMovement = false
        var distance = 0f
        var speed = 0f

        if (lastSignificantLocation != null) {
            distance = lastSignificantLocation!!.distanceTo(location)
            val timeDiff = (location.time - lastSignificantLocation!!.time) / 1000f // secondes

            if (timeDiff > 0) {
                speed = distance / timeDiff // m/s
            }

            // Mouvement significatif = distance > 15m OU vitesse > 1 m/s (3.6 km/h)
            isSignificantMovement = distance > MIN_MOVEMENT_DISTANCE || speed > MIN_MOVEMENT_SPEED

            MotiumApplication.logger.d(
                "GPS movement check: distance=${distance}m, speed=${speed}m/s (${speed * 3.6}km/h), significant=$isSignificantMovement",
                "InactivityDetection"
            )
        } else {
            // Première position, considérée comme significative
            isSignificantMovement = true
            lastSignificantLocation = location
            lastSignificantMoveTime = currentTime
            MotiumApplication.logger.d(
                "First significant location initialized",
                "InactivityDetection"
            )
            return
        }

        if (isSignificantMovement) {
            // Mouvement réel détecté, mettre à jour
            lastSignificantLocation = location
            lastSignificantMoveTime = currentTime
            MotiumApplication.logger.d(
                "Significant movement detected - resetting inactivity timer",
                "InactivityDetection"
            )
        } else {
            // Pas de mouvement significatif, vérifier timeout
            val inactiveDuration = currentTime - lastSignificantMoveTime

            if (inactiveDuration > INACTIVITY_TIMEOUT_MS) {
                // 5 minutes d'inactivité = auto-stop du trip
                MotiumApplication.logger.w(
                    "🛑 AUTO-STOP: ${inactiveDuration/1000}s (${inactiveDuration/60000}min) d'inactivité GPS détectée\n" +
                    "   Distance max depuis dernière position: ${distance}m\n" +
                    "   Vitesse max: ${speed}m/s (${speed * 3.6}km/h)\n" +
                    "   Trip ID: ${currentTrip?.id}\n" +
                    "   Cause probable: GPS drift (mouvement parasite) ou Activity Recognition non fonctionnel",
                    "InactivityDetection"
                )

                // Auto-arrêter le trip en passant en FINALIZING
                if (currentTrip != null) {
                    tripState = TripState.FINALIZING

                    // Commencer la collecte de points d'arrivée
                    startEndPointCollection()

                    // Programmer la finalisation après 15s
                    endPointHandler.postDelayed({
                        MotiumApplication.logger.i("End point collection complete - finalizing trip (auto-stop)", "LocationService")
                        finishCurrentTrip()
                    }, END_POINT_SAMPLING_DELAY_MS)

                    MotiumApplication.logger.i("State transition: TRIP_ACTIVE → FINALIZING (auto-stop inactivity)", "TripStateMachine")
                    updateNotificationStatus()
                }
            } else {
                // Toujours inactif mais timeout pas encore atteint
                val remainingTime = (INACTIVITY_TIMEOUT_MS - inactiveDuration) / 1000
                MotiumApplication.logger.d(
                    "GPS drift detected (${distance}m, ${speed}m/s) - inactivity for ${inactiveDuration/1000}s, auto-stop in ${remainingTime}s",
                    "InactivityDetection"
                )
            }
        }
    }

    private fun startNewTrip() {
        // Créer un nouveau trajet vide - les points GPS seront ajoutés au fur et à mesure
        currentTrip = TripData()

        // Initialiser la collecte de points de départ pour précision
        startPointCandidates.clear()

        // Réinitialiser la détection d'inactivité pour ce nouveau trip
        lastSignificantMoveTime = System.currentTimeMillis()
        lastSignificantLocation = null
        MotiumApplication.logger.d("Inactivity detection reset for new trip", "InactivityDetection")

        // Passer en mode TRIP (haute fréquence GPS: 10s)
        updateGPSFrequency(tripMode = true)

        // Mettre à jour la notification
        isInTrip = true
        updateNotificationStatus()

        MotiumApplication.logger.logTripStart(currentTrip!!.id)
        MotiumApplication.logger.i(
            "🎬 Trip started: ${currentTrip!!.id} - GPS collection active (collecting precise start point)",
            "TripTracker"
        )
    }

    /**
     * Démarre la collecte de points d'arrivée pour améliorer la précision
     */
    private fun startEndPointCollection() {
        endPointCandidates.clear()
        isCollectingEndPoints = true
        endPointCollectionStartTime = System.currentTimeMillis()
        MotiumApplication.logger.i(
            "Started collecting end points (will collect for ${END_POINT_SAMPLING_DELAY_MS/1000}s)",
            "EndPointPrecision"
        )
    }

    /**
     * Sélectionne le meilleur point de départ parmi les candidats
     * Utilise l'anchoring delay et privilégie les points avec haute précision
     */
    private fun selectBestStartPoint(): TripLocation? {
        if (startPointCandidates.isEmpty()) {
            MotiumApplication.logger.w("No start point candidates available", "StartPointPrecision")
            return null
        }

        MotiumApplication.logger.i(
            "Selecting best start point from ${startPointCandidates.size} candidates",
            "StartPointPrecision"
        )

        // Stratégie 1: Attendre 5 secondes et trouver le premier point avec précision < 20m
        val anchoringDeadline = startPointCandidates.first().timestamp + START_POINT_ANCHORING_DELAY_MS
        val afterAnchoringDelay = startPointCandidates.filter { it.timestamp >= anchoringDeadline }

        val highPrecisionPoint = afterAnchoringDelay.firstOrNull { it.accuracy < HIGH_PRECISION_THRESHOLD }
        if (highPrecisionPoint != null) {
            MotiumApplication.logger.i(
                "Selected high-precision start point: accuracy=${highPrecisionPoint.accuracy}m (after ${START_POINT_ANCHORING_DELAY_MS/1000}s anchoring)",
                "StartPointPrecision"
            )
            return highPrecisionPoint
        }

        // Stratégie 2: Clustering - trouver la position dominante (médiane) sur la première minute
        val clusterCandidates = startPointCandidates.take(
            minOf(startPointCandidates.size, 6) // ~60 secondes ÷ 10s interval = 6 points max
        )

        if (clusterCandidates.isNotEmpty()) {
            // Calculer la médiane des positions (clustering simple)
            val sortedLats = clusterCandidates.map { it.latitude }.sorted()
            val sortedLngs = clusterCandidates.map { it.longitude }.sorted()
            val medianLat = sortedLats[sortedLats.size / 2]
            val medianLng = sortedLngs[sortedLngs.size / 2]

            // Trouver le point le plus proche de la médiane avec la meilleure précision
            val bestPoint = clusterCandidates.minByOrNull { candidate ->
                val distanceToMedian = android.location.Location("").apply {
                    latitude = medianLat
                    longitude = medianLng
                }.distanceTo(android.location.Location("").apply {
                    latitude = candidate.latitude
                    longitude = candidate.longitude
                })
                distanceToMedian + candidate.accuracy // Facteur combiné: distance à médiane + précision
            }

            if (bestPoint != null) {
                MotiumApplication.logger.i(
                    "Selected clustered start point: accuracy=${bestPoint.accuracy}m (median of ${clusterCandidates.size} points)",
                    "StartPointPrecision"
                )
                return bestPoint
            }
        }

        // Stratégie 3 (fallback): Point le plus précis parmi tous les candidats
        val mostAccurate = startPointCandidates.minByOrNull { it.accuracy }
        MotiumApplication.logger.i(
            "Selected most accurate start point (fallback): accuracy=${mostAccurate?.accuracy}m",
            "StartPointPrecision"
        )
        return mostAccurate
    }

    /**
     * Sélectionne le meilleur point d'arrivée parmi les candidats
     * Utilise le clustering et filtre les outliers
     */
    private fun selectBestEndPoint(): TripLocation? {
        if (endPointCandidates.isEmpty()) {
            MotiumApplication.logger.w("No end point candidates available", "EndPointPrecision")
            return null
        }

        MotiumApplication.logger.i(
            "Selecting best end point from ${endPointCandidates.size} candidates",
            "EndPointPrecision"
        )

        // Stratégie 1: Filtrer les points avec haute précision (< 20m)
        val highPrecisionCandidates = endPointCandidates.filter { it.accuracy < HIGH_PRECISION_THRESHOLD }

        if (highPrecisionCandidates.isNotEmpty()) {
            // Clustering - trouver la position dominante (médiane)
            val sortedLats = highPrecisionCandidates.map { it.latitude }.sorted()
            val sortedLngs = highPrecisionCandidates.map { it.longitude }.sorted()
            val medianLat = sortedLats[sortedLats.size / 2]
            val medianLng = sortedLngs[sortedLngs.size / 2]

            // Trouver le point le plus proche de la médiane
            val bestPoint = highPrecisionCandidates.minByOrNull { candidate ->
                val distanceToMedian = android.location.Location("").apply {
                    latitude = medianLat
                    longitude = medianLng
                }.distanceTo(android.location.Location("").apply {
                    latitude = candidate.latitude
                    longitude = candidate.longitude
                })
                distanceToMedian + candidate.accuracy // Facteur combiné
            }

            if (bestPoint != null) {
                MotiumApplication.logger.i(
                    "Selected high-precision clustered end point: accuracy=${bestPoint.accuracy}m (median of ${highPrecisionCandidates.size} points)",
                    "EndPointPrecision"
                )
                return bestPoint
            }
        }

        // Stratégie 2: Clustering sur tous les candidats (si aucun point haute précision)
        val sortedLats = endPointCandidates.map { it.latitude }.sorted()
        val sortedLngs = endPointCandidates.map { it.longitude }.sorted()
        val medianLat = sortedLats[sortedLats.size / 2]
        val medianLng = sortedLngs[sortedLngs.size / 2]

        val bestPoint = endPointCandidates.minByOrNull { candidate ->
            val distanceToMedian = android.location.Location("").apply {
                latitude = medianLat
                longitude = medianLng
            }.distanceTo(android.location.Location("").apply {
                latitude = candidate.latitude
                longitude = candidate.longitude
            })
            distanceToMedian + candidate.accuracy
        }

        MotiumApplication.logger.i(
            "Selected clustered end point (fallback): accuracy=${bestPoint?.accuracy}m (median of ${endPointCandidates.size} points)",
            "EndPointPrecision"
        )
        return bestPoint
    }

    private fun addLocationToTrip(trip: TripData, location: Location) {
        val tripLocation = TripLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        // Phase 1: Collecter les points de départ candidats (première minute)
        val tripElapsedTime = System.currentTimeMillis() - trip.startTime
        if (tripElapsedTime < START_POINT_CLUSTERING_WINDOW_MS) {
            startPointCandidates.add(tripLocation)
            MotiumApplication.logger.d(
                "Collected start point candidate ${startPointCandidates.size}: accuracy=${location.accuracy}m",
                "StartPointPrecision"
            )
        }

        // Phase 2: Ajouter au trajet normal
        val lastLocation = trip.locations.lastOrNull()
        if (lastLocation != null) {
            // Calculer la distance entre les deux points GPS
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                lastLocation.latitude, lastLocation.longitude,
                location.latitude, location.longitude,
                results
            )
            trip.totalDistance += results[0].toDouble()
        }

        trip.locations.add(tripLocation)

        // Log toutes les 10 localisations pour éviter le spam
        if (trip.locations.size % 10 == 0) {
            MotiumApplication.logger.i(
                "Trip ${trip.id}: ${trip.locations.size} points, ${String.format("%.2f", trip.totalDistance / 1000)} km",
                "TripTracker"
            )
        }
    }

    private fun finishCurrentTrip() {
        // Arrêter la collecte de points d'arrivée
        isCollectingEndPoints = false

        currentTrip?.let { trip ->
            trip.endTime = System.currentTimeMillis()
            val duration = trip.endTime!! - trip.startTime

            // Validation selon critères de qualité
            if (isValidTrip(trip)) {
                MotiumApplication.logger.logTripEnd(
                    trip.id,
                    trip.totalDistance / 1000, // en km
                    duration
                )

                MotiumApplication.logger.i(
                    "✅ Valid trip finished: ${trip.id}, " +
                    "distance: ${String.format("%.2f", trip.totalDistance / 1000)} km, " +
                    "duration: ${duration / 1000 / 60} min, " +
                    "points: ${trip.locations.size}",
                    "TripTracker"
                )

                saveTripToDatabase(trip)
            } else {
                MotiumApplication.logger.w(
                    "TRIP REJECTED - validation failed: " +
                    "distance=${String.format("%.0f", trip.totalDistance)}m (min: ${MIN_TRIP_DISTANCE_METERS}), " +
                    "duration=${duration/1000}s (min: ${MIN_TRIP_DURATION_MS/1000}), " +
                    "avgSpeed=${String.format("%.1f", (trip.totalDistance / (duration / 1000.0)) * 3.6)} km/h (min: ${MIN_AVERAGE_SPEED_MPS * 3.6}), " +
                    "points=${trip.locations.size} (min: 3)",
                    "TripTracker"
                )
            }
        }

        // Réinitialiser l'état
        currentTrip = null
        startPointCandidates.clear()
        endPointCandidates.clear()
        gpsBuffer.clear()

        // BATTERY OPTIMIZATION: Repasser en mode STANDBY et arrêter GPS + trip health check
        tripState = TripState.STANDBY
        stopLocationUpdates()
        stopTripHealthCheck()

        // Réinitialiser tous les flags (legacy)
        isInTrip = false
        isFinalizingTrip = false

        MotiumApplication.logger.i("State transition: FINALIZING → STANDBY (trip completed, GPS + health check stopped)", "TripStateMachine")

        // Mettre à jour la notification
        updateNotificationStatus()
    }

    private fun isValidTrip(trip: TripData): Boolean {
        val duration = trip.endTime!! - trip.startTime
        val averageSpeed = if (duration > 0) trip.totalDistance / (duration / 1000.0) else 0.0

        // Critères de validation des trajets
        val validDistance = trip.totalDistance >= MIN_TRIP_DISTANCE_METERS
        val validDuration = duration >= MIN_TRIP_DURATION_MS
        val validSpeed = averageSpeed >= MIN_AVERAGE_SPEED_MPS
        val hasEnoughPoints = trip.locations.size >= 2

        MotiumApplication.logger.i(
            "Trip validation - Distance: ${validDistance} (${String.format("%.0f", trip.totalDistance)}m), " +
            "Duration: ${validDuration} (${duration/1000}s), " +
            "Speed: ${validSpeed} (${String.format("%.1f", averageSpeed * 3.6)} km/h), " +
            "Points: ${hasEnoughPoints} (${trip.locations.size})",
            "TripValidator"
        )

        return validDistance && validDuration && validSpeed && hasEnoughPoints
    }

    /**
     * Démarre la surveillance périodique de l'état du trajet
     * Détecte les trajets "fantômes" (TRIP_ACTIVE sans GPS depuis longtemps)
     */
    private fun startTripHealthCheck() {
        stopTripHealthCheck() // Arrêter toute vérification existante

        tripHealthCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    // Vérifier uniquement si on est en TRIP_ACTIVE ou BUFFERING
                    if (tripState == TripState.TRIP_ACTIVE || tripState == TripState.BUFFERING) {
                        val timeSinceLastGPS = System.currentTimeMillis() - lastGPSUpdateTime

                        // Détection de trajet fantôme
                        if (lastGPSUpdateTime > 0 && timeSinceLastGPS > GHOST_TRIP_TIMEOUT_MS) {
                            MotiumApplication.logger.w(
                                "👻 GHOST TRIP DETECTED! No GPS for ${timeSinceLastGPS/1000}s in state $tripState - Auto-terminating trip",
                                "TripHealthCheck"
                            )

                            // Forcer la fin du trajet pour éviter qu'il reste bloqué
                            if (currentTrip != null && currentTrip!!.locations.isNotEmpty()) {
                                MotiumApplication.logger.i("Saving ghost trip before termination", "TripHealthCheck")
                                finishCurrentTrip()
                            } else {
                                // BATTERY OPTIMIZATION: Pas de points GPS, simplement réinitialiser et arrêter GPS
                                MotiumApplication.logger.i("No GPS points, resetting to STANDBY", "TripHealthCheck")
                                currentTrip = null
                                gpsBuffer.clear()
                                tripState = TripState.STANDBY
                                stopLocationUpdates()
                                stopTripHealthCheck()
                                updateNotificationStatus()
                            }
                        }

                        // Vérifier durée maximale du trajet
                        currentTrip?.let { trip ->
                            val tripDuration = System.currentTimeMillis() - trip.startTime
                            if (tripDuration > MAX_TRIP_DURATION_MS) {
                                MotiumApplication.logger.w(
                                    "⏰ Trip exceeded maximum duration (${tripDuration/1000/60}min) - Auto-terminating",
                                    "TripHealthCheck"
                                )
                                finishCurrentTrip()
                            }
                        }
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Error in trip health check: ${e.message}", "TripHealthCheck", e)
                }

                // Planifier la prochaine vérification
                tripHealthCheckHandler.postDelayed(this, TRIP_HEALTH_CHECK_INTERVAL_MS)
            }
        }

        tripHealthCheckHandler.post(tripHealthCheckRunnable!!)
        MotiumApplication.logger.i("Trip health check started (interval: ${TRIP_HEALTH_CHECK_INTERVAL_MS/1000/60}min - battery optimized)", "TripHealthCheck")
    }

    /**
     * Arrête la surveillance périodique de l'état du trajet
     */
    private fun stopTripHealthCheck() {
        tripHealthCheckRunnable?.let {
            tripHealthCheckHandler.removeCallbacks(it)
            tripHealthCheckRunnable = null
            MotiumApplication.logger.i("Trip health check stopped", "TripHealthCheck")
        }
    }

    /**
     * Calcule la position moyenne des premiers/derniers points du trajet
     * pour améliorer la précision du géocodage
     */
    private fun getAverageLocation(locations: List<TripLocation>, fromStart: Boolean): Pair<Double, Double>? {
        if (locations.isEmpty()) return null

        // Prendre les 5 premiers ou derniers points (ou moins si le trajet est court)
        val pointsToAverage = minOf(5, locations.size)
        val relevantLocations = if (fromStart) {
            locations.take(pointsToAverage)
        } else {
            locations.takeLast(pointsToAverage)
        }

        val avgLat = relevantLocations.map { it.latitude }.average()
        val avgLng = relevantLocations.map { it.longitude }.average()

        return Pair(avgLat, avgLng)
    }

    private fun saveTripToDatabase(trip: TripData) {
        serviceScope.launch {
            try {
                // CRASH FIX: Check if service is still active before long-running operations
                if (!isActive) {
                    MotiumApplication.logger.w("Service scope cancelled, aborting trip save", "DatabaseSave")
                    return@launch
                }

                // Géocoder les adresses de départ et d'arrivée avec les points précis sélectionnés
                val nominatimService = com.application.motium.data.geocoding.NominatimService.getInstance()

                var startAddress: String? = null
                var endAddress: String? = null

                // Sélectionner le meilleur point de départ avec anchoring delay et clustering
                val bestStartPoint = selectBestStartPoint()
                bestStartPoint?.let { startPoint ->
                    try {
                        startAddress = nominatimService.reverseGeocode(startPoint.latitude, startPoint.longitude)
                        MotiumApplication.logger.i(
                            "📍 Start address geocoded (precision optimized): $startAddress " +
                            "at ${String.format("%.5f", startPoint.latitude)}, ${String.format("%.5f", startPoint.longitude)} " +
                            "(accuracy: ${startPoint.accuracy}m)",
                            "Geocoding"
                        )
                    } catch (e: Exception) {
                        MotiumApplication.logger.w(
                            "Failed to geocode start address: ${e.message}",
                            "Geocoding"
                        )
                    }
                } ?: run {
                    // Fallback: utiliser la moyenne des premiers points
                    getAverageLocation(trip.locations, fromStart = true)?.let { (avgLat, avgLng) ->
                        try {
                            startAddress = nominatimService.reverseGeocode(avgLat, avgLng)
                            MotiumApplication.logger.i(
                                "📍 Start address geocoded (fallback avg): $startAddress",
                                "Geocoding"
                            )
                        } catch (e: Exception) {
                            MotiumApplication.logger.w("Failed to geocode start address: ${e.message}", "Geocoding")
                        }
                    }
                }

                // Sélectionner le meilleur point d'arrivée avec clustering et filtrage
                val bestEndPoint = selectBestEndPoint()
                bestEndPoint?.let { endPoint ->
                    try {
                        endAddress = nominatimService.reverseGeocode(endPoint.latitude, endPoint.longitude)
                        MotiumApplication.logger.i(
                            "📍 End address geocoded (precision optimized): $endAddress " +
                            "at ${String.format("%.5f", endPoint.latitude)}, ${String.format("%.5f", endPoint.longitude)} " +
                            "(accuracy: ${endPoint.accuracy}m)",
                            "Geocoding"
                        )
                    } catch (e: Exception) {
                        MotiumApplication.logger.w(
                            "Failed to geocode end address: ${e.message}",
                            "Geocoding"
                        )
                    }
                } ?: run {
                    // Fallback: utiliser la moyenne des derniers points
                    getAverageLocation(trip.locations, fromStart = false)?.let { (avgLat, avgLng) ->
                        try {
                            endAddress = nominatimService.reverseGeocode(avgLat, avgLng)
                            MotiumApplication.logger.i(
                                "📍 End address geocoded (fallback avg): $endAddress",
                                "Geocoding"
                            )
                        } catch (e: Exception) {
                            MotiumApplication.logger.w("Failed to geocode end address: ${e.message}", "Geocoding")
                        }
                    }
                }

                val tripToSave = Trip(
                    id = trip.id,
                    startTime = trip.startTime,
                    endTime = trip.endTime,
                    locations = trip.locations,
                    totalDistance = trip.totalDistance,
                    isValidated = false,
                    vehicleId = null,
                    startAddress = startAddress,
                    endAddress = endAddress
                )

                tripRepository.saveTrip(tripToSave)

                MotiumApplication.logger.i(
                    "Trip saved successfully: ${trip.locations.size} points, " +
                    "${String.format("%.2f", trip.totalDistance / 1000)} km, " +
                    "start: ${startAddress ?: "unknown"}, " +
                    "end: ${endAddress ?: "unknown"}",
                    "DatabaseSave"
                )
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Error saving trip: ${e.message}",
                    "DatabaseSave",
                    e
                )
            }
        }
    }

    /**
     * Met à jour la notification uniquement lors de changements d'état importants
     * (démarrage/fin de trajet) pour éviter les vibrations constantes
     */
    private fun updateNotificationStatus() {
        val content = when (tripState) {
            TripState.STANDBY -> "En attente de trajet - Standby"
            TripState.BUFFERING -> "Activité détectée - Collecte GPS... (${gpsBuffer.size} pts)"
            TripState.TRIP_ACTIVE -> {
                val distance = currentTrip?.totalDistance ?: 0.0
                "Trajet en cours - ${String.format("%.2f", distance / 1000)} km"
            }
            TripState.PAUSED -> "Pause temporaire (activité non fiable)"
            TripState.STOP_PENDING -> {
                val distance = currentTrip?.totalDistance ?: 0.0
                "Arrêt détecté - Vérification... ${String.format("%.2f", distance / 1000)} km"
            }
            TripState.FINALIZING -> "Finalisation du trajet..."
        }

        val notification = createNotification(content)

        if (isForeground) {
            // Si on est en foreground, mettre à jour avec startForeground (requis pour Android)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                // Fallback sur notify si startForeground échoue
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            // Si pas en foreground, juste mettre à jour la notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startNotificationWatch() {
        notificationWatchRunnable = object : Runnable {
            override fun run() {
                try {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val activeNotifications = notificationManager.activeNotifications

                    val hasOurNotification = activeNotifications?.any {
                        it.id == NOTIFICATION_ID && it.packageName == packageName
                    } ?: false

                    if (!hasOurNotification) {
                        MotiumApplication.logger.w("Notification was removed! Recreating immediately...", "LocationService")
                        // Recréer immédiatement la notification silencieusement
                        updateNotificationStatus()
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Error in notification watch: ${e.message}", "LocationService", e)
                }

                // BATTERY OPTIMIZATION: Surveiller toutes les 5 minutes (la notification ne disparaît pratiquement jamais)
                notificationWatchHandler.postDelayed(this, 300000) // 5 minutes
            }
        }

        notificationWatchHandler.post(notificationWatchRunnable!!)
        MotiumApplication.logger.i("Notification watch started (checking every 5 minutes)", "LocationService")
    }

    private fun stopNotificationWatch() {
        notificationWatchRunnable?.let {
            notificationWatchHandler.removeCallbacks(it)
            notificationWatchRunnable = null
        }
        MotiumApplication.logger.i("Notification watch stopped", "LocationService")
    }
}
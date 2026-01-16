package com.application.motium.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.application.motium.MotiumApplication
import com.application.motium.R
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service Foreground pour la détection d'activité (Activity Recognition).
 *
 * ## Architecture Samsung-compatible
 * Utilise `PendingIntent.getForegroundService()` au lieu de `getBroadcast()` pour éviter
 * que Samsung One UI ne tue le BroadcastReceiver.
 *
 * ## Flow
 * 1. Google Play Services détecte une transition d'activité
 * 2. Le PendingIntent déclenche onStartCommand() avec ACTION_ACTIVITY_TRANSITION
 * 3. Le service extrait ActivityTransitionResult et notifie TripStateManager
 * 4. TripStateManager gère la machine d'état (IDLE/MOVING/POSSIBLY_STOPPED)
 */
class ActivityRecognitionService : Service() {

    companion object {
        private const val TAG = "ActivityRecognition"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ActivityRecognitionChannel"

        // Action pour les transitions d'activité reçues via PendingIntent.getForegroundService()
        const val ACTION_ACTIVITY_TRANSITION = "com.application.motium.ACTION_ACTIVITY_TRANSITION"

        // Action DEBUG pour injecter des transitions via ADB (tests uniquement)
        // adb shell am startservice -a com.application.motium.ACTION_DEBUG_TRANSITION --es activity "VEHICLE" --es transition "ENTER" com.application.motium/.service.ActivityRecognitionService
        const val ACTION_DEBUG_TRANSITION = "com.application.motium.ACTION_DEBUG_TRANSITION"
        private const val EXTRA_DEBUG_ACTIVITY = "activity"  // VEHICLE, BICYCLE, STILL, WALKING, RUNNING
        private const val EXTRA_DEBUG_TRANSITION = "transition"  // ENTER, EXIT

        // SharedPreferences pour stocker un request code unique par installation
        private const val PREFS_NAME = "ActivityRecognitionPrefs"
        private const val PREF_REQUEST_CODE = "activity_recognition_request_code"
        private const val PREF_LAST_TRANSITION_TIME = "last_transition_time"

        // Délai max pour considérer un événement comme valide (3 minutes)
        private const val MAX_EVENT_AGE_MS = 180000L

        // Intervalle de ré-enregistrement périodique
        private const val REREGISTER_INTERVAL_SAMSUNG_MS = 30 * 60 * 1000L  // 30 min Samsung
        private const val REREGISTER_INTERVAL_DEFAULT_MS = 60 * 60 * 1000L  // 60 min autres

        // Debounce: ignorer même type d'activité si reçu dans cet intervalle
        private const val DEBOUNCE_SAME_ACTIVITY_MS = 15_000L  // 15 secondes

        // Référence à l'instance du service
        @Volatile
        private var instance: ActivityRecognitionService? = null

        /**
         * Obtient un request code unique pour cette installation de l'app
         */
        private fun getUniqueRequestCode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var requestCode = prefs.getInt(PREF_REQUEST_CODE, 0)

            if (requestCode == 0) {
                requestCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
                MotiumApplication.logger.i(
                    "🆕 Generated NEW unique request code: $requestCode",
                    TAG
                )
                prefs.edit().putInt(PREF_REQUEST_CODE, requestCode).apply()
            }

            return requestCode
        }

        fun startService(context: Context) {
            if (!hasLocationPermissions(context)) {
                MotiumApplication.logger.w(
                    "Cannot start ActivityRecognitionService: location permissions not granted",
                    TAG
                )
                return
            }

            try {
                val intent = Intent(context, ActivityRecognitionService::class.java)
                context.startForegroundService(intent)
            } catch (e: SecurityException) {
                MotiumApplication.logger.e(
                    "SecurityException when starting ActivityRecognitionService: ${e.message}",
                    TAG, e
                )
            }
        }

        private fun hasLocationPermissions(context: Context): Boolean {
            val fineLocation = PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                )
            val coarseLocation = PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                )
            return fineLocation || coarseLocation
        }

        fun stopService(context: Context) {
            // Annuler l'alarme keep-alive UNIQUEMENT quand l'utilisateur désactive explicitement
            // l'auto-tracking. Ne PAS faire ça dans onDestroy() car Android pourrait tuer
            // le service et on veut que l'alarme le redémarre.
            DozeModeFix.cancelActivityRecognitionKeepAlive(context)
            MotiumApplication.logger.i("🛑 Stopping ActivityRecognitionService (user request)", TAG)
            context.stopService(Intent(context, ActivityRecognitionService::class.java))
        }

        /**
         * Nettoie les anciens enregistrements Activity Recognition.
         * Note: L'ancien BroadcastReceiver a été supprimé, cette méthode nettoie
         * les PendingIntents qui pourraient rester de l'ancienne implémentation.
         */
        @SuppressLint("MissingPermission")
        fun cleanupOldRegistrations(context: Context) {
            MotiumApplication.logger.i("🧹 Cleaning up old Activity Recognition registrations", TAG)
            try {
                val client = ActivityRecognition.getClient(context.applicationContext)
                val requestCode = getUniqueRequestCode(context.applicationContext)

                // Nettoyer l'ancien style broadcast (migration depuis l'ancienne implémentation)
                // L'action était utilisée par l'ancien ActivityRecognitionReceiver (supprimé)
                val broadcastIntent = Intent("com.application.motium.ACTIVITY_TRANSITION_BROADCAST")
                val broadcastPi = PendingIntent.getBroadcast(
                    context.applicationContext,
                    requestCode,
                    broadcastIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                broadcastPi?.let {
                    client.removeActivityTransitionUpdates(it)
                    MotiumApplication.logger.i("✅ Cleaned old broadcast PendingIntent", TAG)
                }
            } catch (e: Exception) {
                MotiumApplication.logger.w("Could not clean old registrations: ${e.message}", TAG)
            }
        }

        /**
         * Met à jour la notification basée sur l'état du trajet.
         */
        fun updateNotificationState(state: TripNotificationState) {
            instance?.updateNotificationFromState(state)
                ?: MotiumApplication.logger.w(
                    "Cannot update notification: service instance is null",
                    "Notification"
                )
        }

        /**
         * Réinitialise l'Activity Recognition.
         * Nettoie les anciens enregistrements et prépare pour un restart.
         * Utilisé depuis Settings pour debug.
         */
        fun resetActivityRecognition(context: Context) {
            MotiumApplication.logger.i("🔄 Resetting Activity Recognition", TAG)
            cleanupOldRegistrations(context)
            // Clear persisted state
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_TRANSITION_TIME)
                .apply()
        }

        /**
         * Force le ré-enregistrement des transitions d'activité.
         * Appelé par le HealthWorker pour maintenir l'enregistrement actif.
         */
        fun reregisterActivityRecognition(context: Context) {
            MotiumApplication.logger.i("🔄 Forcing reregister via companion object", TAG)
            instance?.let {
                it.reregisterActivityTransitions()
            } ?: run {
                MotiumApplication.logger.w(
                    "Cannot reregister: service instance is null, starting service instead",
                    TAG
                )
                startService(context)
            }
        }
    }

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private var transitionPendingIntent: PendingIntent? = null
    private var activityTransitionRequest: ActivityTransitionRequest? = null

    // Guard against redundant initialization
    private var isActivityRecognitionActive = false

    // Coroutine scope for async operations
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        MotiumApplication.logger.e(
            "❌ Uncaught exception in ActivityRecognitionService: ${exception.message}",
            TAG, exception
        )
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // Handler for notification updates
    private val notificationHandler = Handler(Looper.getMainLooper())

    // Notification monitoring to ensure it stays visible
    private val notificationMonitorHandler = Handler(Looper.getMainLooper())
    private var notificationMonitorRunnable: Runnable? = null
    private val NOTIFICATION_MONITOR_INTERVAL_MS = 60000L

    // Double registration: ré-enregistrement périodique
    private var reregisterJob: Job? = null

    // Cooldown pour éviter les re-registrations en boucle
    private var lastReregistrationTimestamp: Long = 0L
    private val REREGISTER_COOLDOWN_MS = 30_000L // 30 secondes minimum entre re-registrations

    // Debouncing: éviter le traitement des événements identiques répétés
    private var lastProcessedActivityType: Int = -1
    private var lastProcessedTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this

        MotiumApplication.logger.i("🚀 ActivityRecognitionService created", TAG)

        createNotificationChannel()
        activityRecognitionClient = ActivityRecognition.getClient(this)

        // Initialiser TripStateManager avec le contexte
        TripStateManager.initialize(applicationContext)

        // Configurer les callbacks de TripStateManager
        setupTripStateManagerCallbacks()

        // Nettoyer les anciens PendingIntents broadcast (migration)
        cleanupOldRegistrations(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        MotiumApplication.logger.i("📨 onStartCommand - action: $action", TAG)

        // ==================== TRAITEMENT DES TRANSITIONS ====================
        // Si l'intent contient une transition d'activité (via PendingIntent.getForegroundService)
        if (action == ACTION_ACTIVITY_TRANSITION) {
            handleActivityTransitionIntent(intent)
            // NOTE: Re-registration post-transition supprimée - causait une boucle infinie
            // La registration périodique (30-60min) via schedulePeriodicReregistration() suffit
            return START_STICKY
        }

        // ==================== DEBUG TRANSITION (Tests ADB) ====================
        // Permet d'injecter des transitions via ADB pour tester la state machine
        if (action == ACTION_DEBUG_TRANSITION) {
            handleDebugTransition(intent)
            return START_STICKY
        }

        // ==================== KEEP-ALIVE WAKEUP (Doze Mode Fix) ====================
        // AlarmManager envoie périodiquement ce wake-up pour forcer ré-enregistrement
        if (action == DozeModeFix.ACTION_KEEPALIVE_WAKEUP) {
            MotiumApplication.logger.i("⏰ Keep-alive wakeup received from AlarmManager", TAG)
            AutoTrackingDiagnostics.logKeepaliveTriggered(this)

            // Replanifier la prochaine alarme
            DozeModeFix.scheduleActivityRecognitionKeepAlive(this)

            // Force ré-enregistrement des transitions
            reregisterActivityTransitions()

            return START_STICKY
        }

        // ==================== INITIALISATION DU SERVICE ====================
        if (isActivityRecognitionActive) {
            MotiumApplication.logger.d("⚡ Service already active, skipping init", TAG)
            return START_STICKY
        }

        // Démarrer en foreground (DOIT être appelé rapidement)
        startForegroundService()
        startActivityRecognition()
        isActivityRecognitionActive = true

        // Démarrer le monitoring de notification
        startNotificationMonitor()

        // Double registration: planifier le ré-enregistrement périodique
        schedulePeriodicReregistration()

        // Schedule keep-alive alarm (Doze mode fix)
        DozeModeFix.scheduleActivityRecognitionKeepAlive(this)

        return START_STICKY
    }

    /**
     * Configure les callbacks de TripStateManager pour réagir aux changements d'état.
     */
    private fun setupTripStateManagerCallbacks() {
        TripStateManager.onTripStarted = { tripId ->
            MotiumApplication.logger.i("🚗 Trip started: $tripId - Starting GPS tracking", TAG)
            // Démarrer LocationTrackingService pour le GPS
            LocationTrackingService.startTrip(this, tripId)
            updateNotification("Trajet en cours...")
        }

        TripStateManager.onTripEnded = { tripId ->
            MotiumApplication.logger.i("🏁 Trip ended: $tripId - Stopping GPS tracking", TAG)
            // Arrêter le GPS via LocationTrackingService
            LocationTrackingService.endTrip(this, tripId)
            updateNotification("Détection automatique activée")
        }

        TripStateManager.onStateChanged = { state ->
            when (state) {
                is TripStateManager.TrackingState.Idle -> {
                    updateNotification("Détection automatique activée")
                }
                is TripStateManager.TrackingState.Moving -> {
                    updateNotification("Trajet en cours...")
                }
                is TripStateManager.TrackingState.PossiblyStopped -> {
                    updateNotification("Arrêt détecté...")
                }
            }
        }
    }

    /**
     * Traite une transition DEBUG injectée via ADB.
     * Usage: adb shell am startservice -a com.application.motium.ACTION_DEBUG_TRANSITION \
     *        --es activity "VEHICLE" --es transition "ENTER" \
     *        com.application.motium/.service.ActivityRecognitionService
     *
     * Activities: VEHICLE, BICYCLE, STILL, WALKING, RUNNING
     * Transitions: ENTER, EXIT
     */
    private fun handleDebugTransition(intent: Intent) {
        val activityStr = intent.getStringExtra(EXTRA_DEBUG_ACTIVITY)?.uppercase() ?: "UNKNOWN"
        val transitionStr = intent.getStringExtra(EXTRA_DEBUG_TRANSITION)?.uppercase() ?: "ENTER"

        MotiumApplication.logger.i(
            "🧪 DEBUG TRANSITION: $activityStr $transitionStr",
            TAG
        )

        // Convertir en événement TripStateManager
        val event = when {
            activityStr == "VEHICLE" && transitionStr == "ENTER" -> TripStateManager.TrackingEvent.VehicleEnter
            activityStr == "BICYCLE" && transitionStr == "ENTER" -> TripStateManager.TrackingEvent.BicycleEnter
            activityStr == "STILL" && transitionStr == "ENTER" -> TripStateManager.TrackingEvent.StillEnter
            activityStr == "WALKING" && transitionStr == "ENTER" -> TripStateManager.TrackingEvent.WalkingEnter
            activityStr == "RUNNING" && transitionStr == "ENTER" -> TripStateManager.TrackingEvent.RunningEnter
            // STILL_CONFIRMED permet de forcer l'expiration du timer 3min manuellement
            activityStr == "STILL_CONFIRMED" -> TripStateManager.TrackingEvent.StillConfirmed
            else -> {
                MotiumApplication.logger.w(
                    "🧪 Unknown debug transition: $activityStr $transitionStr (valid: VEHICLE, BICYCLE, STILL, WALKING, RUNNING, STILL_CONFIRMED)",
                    TAG
                )
                return
            }
        }

        // Log diagnostics
        AutoTrackingDiagnostics.logActivityTransition(this, activityStr, transitionStr)

        // Envoyer l'événement à la state machine
        TripStateManager.onEvent(event)

        MotiumApplication.logger.i(
            "🧪 DEBUG: Event sent to TripStateManager, current state: ${TripStateManager.currentState}",
            TAG
        )
    }

    /**
     * Traite un Intent contenant des données de transition d'activité.
     * Appelé quand Google Play Services envoie une transition via le PendingIntent.
     */
    private fun handleActivityTransitionIntent(intent: Intent) {
        MotiumApplication.logger.i("🔔 handleActivityTransitionIntent called", TAG)

        if (!ActivityTransitionResult.hasResult(intent)) {
            MotiumApplication.logger.w(
                "❌ Intent does not contain ActivityTransitionResult!",
                TAG
            )
            return
        }

        val result = ActivityTransitionResult.extractResult(intent)
        if (result == null) {
            MotiumApplication.logger.e("❌ Extracted result is NULL!", TAG)
            return
        }

        // ==================== FILTRAGE EN AMONT DES ÉVÉNEMENTS OBSOLÈTES ====================
        // Filtrer les événements trop vieux AVANT le traitement pour éviter le flood de logs
        val currentTimeMs = SystemClock.elapsedRealtime()
        val (validEvents, obsoleteEvents) = result.transitionEvents.partition { event ->
            val eventTimestampMs = event.elapsedRealTimeNanos / 1_000_000
            val eventAgeMs = currentTimeMs - eventTimestampMs
            eventAgeMs <= MAX_EVENT_AGE_MS
        }

        // Logger les événements obsolètes de façon groupée (évite le spam de logs)
        if (obsoleteEvents.isNotEmpty()) {
            MotiumApplication.logger.w(
                "⏰ ${obsoleteEvents.size} obsolete event(s) filtered (age > ${MAX_EVENT_AGE_MS / 1000}s)",
                TAG
            )
            // Logger pour les diagnostics (compteur incrémenté)
            obsoleteEvents.forEach { event ->
                val activityName = getActivityName(event.activityType)
                val transitionType = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"
                val eventAgeMs = currentTimeMs - (event.elapsedRealTimeNanos / 1_000_000)
                AutoTrackingDiagnostics.logFailedTransition(
                    this,
                    "Obsolete: $activityName $transitionType (${eventAgeMs / 1000}s old)"
                )
            }
        }

        if (validEvents.isEmpty()) {
            MotiumApplication.logger.d("All ${result.transitionEvents.size} events filtered as obsolete", TAG)
            return
        }

        MotiumApplication.logger.i(
            "📱 Processing ${validEvents.size}/${result.transitionEvents.size} valid activity transition(s)",
            TAG
        )

        // Sauvegarder le timestamp de la dernière transition reçue
        saveLastTransitionTime()

        // Traiter chaque événement VALIDE de transition
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        validEvents.forEach { event ->
            val activityName = getActivityName(event.activityType)
            val transitionType = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                "ENTER"
            } else {
                "EXIT"
            }
            val timestamp = dateFormat.format(Date(event.elapsedRealTimeNanos / 1_000_000))
            val eventTimestampMs = event.elapsedRealTimeNanos / 1_000_000
            val eventAgeMs = currentTimeMs - eventTimestampMs

            MotiumApplication.logger.i(
                "🎯 Transition: $activityName $transitionType at $timestamp (age: ${eventAgeMs / 1000}s)",
                TAG
            )

            // Log to diagnostics
            AutoTrackingDiagnostics.logActivityTransition(this, activityName, transitionType)

            // Convertir en événement TripStateManager
            val stateEvent = convertToTripStateEvent(event)
            if (stateEvent != null) {
                TripStateManager.onEvent(stateEvent)
            }
        }
    }

    /**
     * Convertit un ActivityTransitionEvent en TripStateManager.TrackingEvent.
     */
    private fun convertToTripStateEvent(event: ActivityTransitionEvent): TripStateManager.TrackingEvent? {
        val activityType = event.activityType
        val transitionType = event.transitionType

        return when {
            // Véhicule ENTER → démarrage trajet
            activityType == DetectedActivity.IN_VEHICLE &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                TripStateManager.TrackingEvent.VehicleEnter
            }

            // Vélo ENTER → démarrage trajet
            activityType == DetectedActivity.ON_BICYCLE &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                TripStateManager.TrackingEvent.BicycleEnter
            }

            // STILL ENTER → potentielle fin de trajet
            activityType == DetectedActivity.STILL &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                TripStateManager.TrackingEvent.StillEnter
            }

            // WALKING ENTER → potentielle fin de trajet (traité comme STILL)
            activityType == DetectedActivity.WALKING &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                TripStateManager.TrackingEvent.WalkingEnter
            }

            // RUNNING ENTER → potentielle fin de trajet
            activityType == DetectedActivity.RUNNING &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                TripStateManager.TrackingEvent.RunningEnter
            }

            // ON_FOOT ENTER → fallback pour marche/course
            activityType == DetectedActivity.ON_FOOT &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                TripStateManager.TrackingEvent.WalkingEnter
            }

            // EXIT events: ignorés (on attend le prochain ENTER)
            else -> {
                MotiumApplication.logger.d(
                    "Transition not mapped to state event: ${getActivityName(activityType)} " +
                            "${if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"}",
                    TAG
                )
                null
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

    private fun saveLastTransitionTime() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_TRANSITION_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Vérifie si un événement d'activité doit être traité (debouncing).
     * Ignore les événements du même type reçus dans les 15 dernières secondes.
     *
     * @return true si l'événement doit être traité, false s'il est un doublon à ignorer
     */
    private fun shouldProcessEvent(activityType: Int, transitionType: Int): Boolean {
        // Toujours traiter les EXIT (changements d'état importants)
        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
            return true
        }

        val now = SystemClock.elapsedRealtime()

        // Debounce: ignorer si même type d'activité ENTER reçu récemment
        if (activityType == lastProcessedActivityType &&
            now - lastProcessedTimestamp < DEBOUNCE_SAME_ACTIVITY_MS) {
            MotiumApplication.logger.d(
                "⏱️ Debounced: ${getActivityName(activityType)} ENTER (${(now - lastProcessedTimestamp) / 1000}s since last)",
                TAG
            )
            return false
        }

        // Mettre à jour le dernier événement traité
        lastProcessedActivityType = activityType
        lastProcessedTimestamp = now
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        MotiumApplication.logger.i("🛑 ActivityRecognitionService destroyed", TAG)

        isActivityRecognitionActive = false
        instance = null

        // NOTE: On ne cancel PAS l'alarme keep-alive ici!
        // Si Android tue le service, l'alarme doit rester active pour le redémarrer.
        // L'alarme est annulée uniquement quand l'utilisateur désactive explicitement
        // l'auto-tracking via stopService() qui appelle cancelActivityRecognitionKeepAlive().

        // ⚠️ ANCIEN CODE BUGUÉ (supprimé):
        // DozeModeFix.cancelActivityRecognitionKeepAlive(this)

        // Cancel periodic reregistration
        reregisterJob?.cancel()
        reregisterJob = null

        // Clean up handlers
        notificationHandler.removeCallbacksAndMessages(null)

        // Stop notification monitoring
        stopNotificationMonitor()

        // Stop activity recognition
        stopActivityRecognition()

        // Cancel all coroutines
        serviceScope.cancel()
    }

    /**
     * Appelé quand l'utilisateur swipe l'app dans le gestionnaire de tâches.
     * SAMSUNG FIX: Redémarrer le service pour maintenir l'auto-tracking actif.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        MotiumApplication.logger.w("📱 App task removed (user swipe) - rescheduling service", TAG)

        // Replanifier une alarme rapide pour redémarrer le service
        // (plus court que le keep-alive normal car l'utilisateur a swipé récemment)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val serviceIntent = Intent(this, ActivityRecognitionService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            1001, // Request code différent du keep-alive normal
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Redémarrer dans 5 secondes
        val triggerTime = System.currentTimeMillis() + 5000

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }

        AutoTrackingDiagnostics.logServiceRestart(this, "Task removed (user swipe)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Détection d'activité",
            NotificationManager.IMPORTANCE_LOW // LOW pour éviter vibrations/son
        ).apply {
            description = "Détection intelligente des déplacements"
            setShowBadge(false)
            setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            enableLights(false)
            enableVibration(false) // Désactiver vibration
            setSound(null, null) // Désactiver son
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setBlockable(false)
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Suivi de vos déplacements")
            .setContentText("Détection automatique activée")
            .setSmallIcon(R.drawable.ic_location)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // LOW pour éviter vibrations
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(null) // Insupprimable
            .setShowWhen(false)
            .setLocalOnly(true)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true) // Complètement silencieux
            .setOnlyAlertOnce(true) // N'alerte qu'une seule fois
            .setDefaults(0)
            .build()

        // Appliquer des flags supplémentaires pour la rendre insupprimable
        notification.flags = notification.flags or
            Notification.FLAG_NO_CLEAR or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_FOREGROUND_SERVICE

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 14+ rejette startForeground si l'app n'est pas dans un état "eligible" (en arrière-plan)
            // Ne pas tuer le service, juste continuer en arrière-plan sans foreground
            MotiumApplication.logger.w(
                "Cannot start foreground service from background (Android 14+ restriction): ${e.message}. " +
                "Service will continue in background mode. Will retry foreground when eligible.",
                "ActivityRecognition"
            )
            // Ne pas appeler stopSelf() - continuer en arrière-plan
        }
    }

    /**
     * Crée la liste des transitions d'activité à surveiller
     * Utilise la nouvelle ActivityTransition API recommandée par Google
     *
     * Best Practices SDK Activity Recognition:
     * - Surveiller ENTER et EXIT pour les véhicules (démarrage et fin de trajet)
     * - Surveiller WALKING et RUNNING ENTER pour détecter la fin de trajet
     * - STILL ENTER confirme l'arrêt définitif
     * - ON_FOOT inclut WALKING + RUNNING mais est moins précis
     */
    private fun createActivityTransitions(): List<ActivityTransition> {
        return listOf(
            // === VÉHICULE ===
            // IN_VEHICLE ENTER - L'utilisateur monte dans un véhicule
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),

            // IN_VEHICLE EXIT - L'utilisateur sort du véhicule
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),

            // === MARCHE ET COURSE (fin de trajet) ===
            // WALKING ENTER - L'utilisateur commence à marcher (fin de trajet probable)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),

            // RUNNING ENTER - L'utilisateur court (fin de trajet - souvent confondu avec marche rapide)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),

            // ON_FOOT ENTER - Générique à pied (fallback si WALKING/RUNNING pas détecté)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_FOOT)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),

            // === IMMOBILE (confirmation fin de trajet) ===
            // STILL ENTER - L'utilisateur est immobile (confirmation de fin de trajet)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),

            // === VÉLO ===
            // ON_BICYCLE ENTER - Support pour les trajets à vélo
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),

            // ON_BICYCLE EXIT
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
    }

    /**
     * Démarre la détection d'activité avec PendingIntent.getForegroundService().
     *
     * ## SAMSUNG FIX
     * Utilise getForegroundService() au lieu de getBroadcast() car Samsung One UI
     * tue les BroadcastReceivers même avec exemption batterie.
     *
     * ## FLAG_MUTABLE
     * Requis pour que Google Play Services puisse ajouter les extras ActivityTransitionResult.
     */
    private fun startActivityRecognition() {
        MotiumApplication.logger.i("🔧 Starting Activity Recognition with getForegroundService()", TAG)

        // Vérifier Google Play Services
        checkGooglePlayServices()

        // Vérifier permission ACTIVITY_RECOGNITION
        if (!checkActivityRecognitionPermission()) {
            MotiumApplication.logger.e("❌ ACTIVITY_RECOGNITION permission not granted!", TAG)
            return
        }

        // Diagnostics Samsung
        performSamsungDiagnostics()

        // Créer le PendingIntent qui pointe vers CE SERVICE (pas un BroadcastReceiver)
        val requestCode = getUniqueRequestCode(applicationContext)

        // CRITICAL: Intent vers le service avec action spécifique
        val serviceIntent = Intent(applicationContext, ActivityRecognitionService::class.java).apply {
            action = ACTION_ACTIVITY_TRANSITION
        }

        // CRITICAL: getForegroundService() + FLAG_MUTABLE (pas IMMUTABLE!)
        // FLAG_MUTABLE est requis pour que Google Play Services puisse ajouter les extras
        transitionPendingIntent = PendingIntent.getForegroundService(
            applicationContext,
            requestCode,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        MotiumApplication.logger.i(
            "📌 PendingIntent created: getForegroundService(), requestCode=$requestCode, FLAG_MUTABLE",
            TAG
        )

        try {
            // Créer la liste des transitions à surveiller
            val transitions = createActivityTransitions()
            activityTransitionRequest = ActivityTransitionRequest(transitions)

            MotiumApplication.logger.d(
                "ActivityTransition request: ${transitions.size} transitions",
                TAG
            )

            // Enregistrer les transitions
            activityRecognitionClient.requestActivityTransitionUpdates(
                activityTransitionRequest!!,
                transitionPendingIntent!!
            ).addOnSuccessListener {
                MotiumApplication.logger.i(
                    "✅ Activity Recognition started successfully!\n" +
                            "   Method: getForegroundService() (Samsung-compatible)\n" +
                            "   Transitions: IN_VEHICLE, ON_BICYCLE, STILL, WALKING, RUNNING",
                    TAG
                )
            }.addOnFailureListener { e ->
                MotiumApplication.logger.e(
                    "❌ Failed to start Activity Recognition: ${e.message}",
                    TAG, e
                )
            }
        } catch (e: SecurityException) {
            MotiumApplication.logger.e(
                "❌ SecurityException - permission not granted!",
                TAG, e
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Unexpected exception: ${e.message}",
                TAG, e
            )
        }
    }

    private fun checkGooglePlayServices(): Boolean {
        return try {
            val apiAvailability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            val resultCode = apiAvailability.isGooglePlayServicesAvailable(this)
            if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                MotiumApplication.logger.e(
                    "❌ Google Play Services NOT available! Code: $resultCode",
                    TAG
                )
                false
            } else {
                MotiumApplication.logger.i("✅ Google Play Services available", TAG)
                true
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking Play Services: ${e.message}", TAG, e)
            false
        }
    }

    private fun checkActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = PackageManager.PERMISSION_GRANTED ==
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACTIVITY_RECOGNITION
                    )
            MotiumApplication.logger.i(
                "📋 ACTIVITY_RECOGNITION permission: ${if (granted) "GRANTED ✅" else "DENIED ❌"}",
                TAG
            )
            granted
        } else {
            true // Not needed before Android 10
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityRecognition() {
        transitionPendingIntent?.let { pi ->
            try {
                activityRecognitionClient.removeActivityTransitionUpdates(pi)
                MotiumApplication.logger.i("✅ Activity Recognition stopped", TAG)
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Error stopping AR: ${e.message}", TAG, e)
            }
        }
        transitionPendingIntent = null
        activityTransitionRequest = null
    }

    // ==================== DOUBLE REGISTRATION STRATEGY ====================

    /**
     * Planifie un ré-enregistrement périodique des transitions.
     * Samsung: toutes les 30 min, autres: toutes les 60 min.
     */
    private fun schedulePeriodicReregistration() {
        reregisterJob?.cancel()

        val isSamsung = Build.MANUFACTURER.lowercase().contains("samsung")
        val intervalMs = if (isSamsung) REREGISTER_INTERVAL_SAMSUNG_MS else REREGISTER_INTERVAL_DEFAULT_MS

        MotiumApplication.logger.i(
            "⏰ Scheduling periodic reregistration every ${intervalMs / 60000} min " +
                    "(device: ${if (isSamsung) "Samsung" else "other"})",
            TAG
        )

        reregisterJob = serviceScope.launch {
            while (isActive) {
                delay(intervalMs)
                MotiumApplication.logger.i("🔄 Periodic reregistration triggered", TAG)
                reregisterActivityTransitions()
            }
        }
    }

    // NOTE: scheduleReregistrationAfterTransition() a été supprimée.
    // Elle causait une boucle infinie: chaque événement déclenchait une re-registration
    // qui re-délivrait les événements en queue, créant un flood qui bloquait le GPS.
    // La registration périodique (30-60min) via schedulePeriodicReregistration() suffit.

    /**
     * Ré-enregistre les transitions d'activité (remove + add).
     * Note: internal pour permettre l'appel depuis le companion object (HealthWorker)
     *
     * Protection anti-boucle: cooldown de 30 secondes minimum entre re-registrations
     * pour éviter le flood qui bloquait le GPS.
     */
    @SuppressLint("MissingPermission")
    internal fun reregisterActivityTransitions() {
        // Protection anti-boucle: cooldown de 30 secondes
        val now = SystemClock.elapsedRealtime()
        if (now - lastReregistrationTimestamp < REREGISTER_COOLDOWN_MS) {
            val remainingSeconds = (REREGISTER_COOLDOWN_MS - (now - lastReregistrationTimestamp)) / 1000
            MotiumApplication.logger.d(
                "⏱️ Reregistration skipped: cooldown active (${remainingSeconds}s remaining)",
                TAG
            )
            return
        }
        lastReregistrationTimestamp = now

        val pi = transitionPendingIntent
        val request = activityTransitionRequest

        if (pi == null || request == null) {
            MotiumApplication.logger.w("Cannot reregister: PendingIntent or request is null", TAG)
            return
        }

        try {
            // Remove puis add pour forcer le rafraîchissement
            activityRecognitionClient.removeActivityTransitionUpdates(pi)
                .addOnCompleteListener {
                    activityRecognitionClient.requestActivityTransitionUpdates(request, pi)
                        .addOnSuccessListener {
                            MotiumApplication.logger.i("✅ Activity transitions reregistered", TAG)
                        }
                        .addOnFailureListener { e ->
                            MotiumApplication.logger.e("❌ Reregistration failed: ${e.message}", TAG)
                        }
                }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error during reregistration: ${e.message}", TAG, e)
        }
    }

    // NOTE: Les méthodes onActivityDetected(), analyzeActivityTransition(), enableGpsFallback()
    // et disableGpsFallback() ont été SUPPRIMÉES car:
    // - ActivityTransition API simplifie la logique (plus besoin de onActivityDetected/analyzeActivityTransition)
    // - GPS fallback créait des conflits avec LocationTrackingService (double usage de FusedLocationProviderClient)
    // - GPS fallback interprétait le GPS drift (1-3m) comme du mouvement, créant des trips fantômes
    // LocationTrackingService gère maintenant SEUL le GPS avec détection d'inactivité intégrée

    /**
     * Effectue des diagnostics pour identifier les causes connues d'échec Activity Recognition sur Samsung
     * D'après les consignes: Samsung One UI, Samsung Health, capteurs, optimisations batterie
     */
    private fun performSamsungDiagnostics() {
        MotiumApplication.logger.i("🔍 SAMSUNG DIAGNOSTICS - Checking known issues", "ActivityRecognition")

        // 1. Vérifier si c'est un Samsung et la version One UI
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isSamsung = manufacturer.contains("samsung")
        if (isSamsung) {
            MotiumApplication.logger.w(
                "⚠️ Samsung device detected (${Build.MODEL})\n" +
                "   Known issues: One UI battery optimization kills BroadcastReceiver after 1-2h\n" +
                "   Recommendation: Disable battery optimization for this app",
                "ActivityRecognition"
            )
        } else {
            MotiumApplication.logger.i("Device: ${Build.MANUFACTURER} ${Build.MODEL}", "ActivityRecognition")
        }

        // 2. Vérifier Samsung Health (peut interférer avec les capteurs)
        try {
            val samsungHealthInstalled = try {
                packageManager.getPackageInfo("com.sec.android.app.shealth", 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }

            if (samsungHealthInstalled) {
                MotiumApplication.logger.w(
                    "⚠️ Samsung Health is INSTALLED - May interfere with Activity Recognition sensors",
                    "ActivityRecognition"
                )
            } else {
                MotiumApplication.logger.i("✅ Samsung Health not installed", "ActivityRecognition")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.d("Could not check Samsung Health: ${e.message}", "ActivityRecognition")
        }

        // 3. Vérifier les capteurs (accéléromètre, gyroscope)
        try {
            val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            if (accelerometer == null) {
                MotiumApplication.logger.e("❌ ACCELEROMETER NOT AVAILABLE - Activity Recognition CANNOT work!", "ActivityRecognition")
            } else {
                MotiumApplication.logger.i("✅ Accelerometer: ${accelerometer.name} (${accelerometer.vendor})", "ActivityRecognition")
            }

            if (gyroscope == null) {
                MotiumApplication.logger.w("⚠️ Gyroscope not available - May reduce accuracy", "ActivityRecognition")
            } else {
                MotiumApplication.logger.i("✅ Gyroscope: ${gyroscope.name}", "ActivityRecognition")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking sensors: ${e.message}", "ActivityRecognition", e)
        }

        // 4. Vérifier optimisations batterie
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                true
            }

            if (!isIgnoringBatteryOptimizations) {
                MotiumApplication.logger.w(
                    "⚠️ Battery optimization is ENABLED for this app\n" +
                    "   Samsung One UI may kill Activity Recognition broadcasts after 1-2 hours\n" +
                    "   CRITICAL: User must disable battery optimization in Settings",
                    "ActivityRecognition"
                )
            } else {
                MotiumApplication.logger.i("✅ Battery optimization is DISABLED (good for reliability)", "ActivityRecognition")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking battery optimization: ${e.message}", "ActivityRecognition", e)
        }

        // 5. Vérifier Google Play Services version
        try {
            val playServicesInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
            MotiumApplication.logger.i(
                "Google Play Services version: ${playServicesInfo.versionName} (${playServicesInfo.versionCode})",
                "ActivityRecognition"
            )
        } catch (e: PackageManager.NameNotFoundException) {
            MotiumApplication.logger.e("❌ Google Play Services NOT FOUND!", "ActivityRecognition")
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking Play Services: ${e.message}", "ActivityRecognition", e)
        }

        MotiumApplication.logger.i("🔍 Samsung diagnostics complete", "ActivityRecognition")
    }

    private fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Suivi de vos déplacements")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_location)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // LOW pour éviter vibrations
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(null) // Insupprimable
            .setShowWhen(false)
            .setLocalOnly(true)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true) // Complètement silencieux
            .setOnlyAlertOnce(true) // N'alerte qu'une seule fois
            .setDefaults(0) // Aucun défaut
            .build()

        // Appliquer des flags supplémentaires
        notification.flags = notification.flags or
            Notification.FLAG_NO_CLEAR or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_FOREGROUND_SERVICE

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Updates notification based on trip state from LocationTrackingService.
     * Handles the "Trajet sauvegardé" temporary message with auto-reset.
     */
    private fun updateNotificationFromState(state: TripNotificationState) {
        // Cancel any pending notification reset
        notificationHandler.removeCallbacksAndMessages(null)

        val text = state.toNotificationText()
        MotiumApplication.logger.d("Updating notification: $text", "Notification")
        updateNotification(text)

        // If trip was saved, reset to Standby after 3 seconds
        if (state is TripNotificationState.TripSaved) {
            notificationHandler.postDelayed({
                updateNotification(TripNotificationState.Standby.toNotificationText())
            }, 3000L)
        }
    }

    /**
     * Starts periodic monitoring of the notification to ensure it stays visible.
     * If the notification is dismissed (by user or system), it will be re-displayed.
     */
    private fun startNotificationMonitor() {
        stopNotificationMonitor() // Clear any existing monitor

        notificationMonitorRunnable = object : Runnable {
            override fun run() {
                ensureNotificationVisible()
                notificationMonitorHandler.postDelayed(this, NOTIFICATION_MONITOR_INTERVAL_MS)
            }
        }

        // Start monitoring after initial delay
        notificationMonitorHandler.postDelayed(notificationMonitorRunnable!!, NOTIFICATION_MONITOR_INTERVAL_MS)
        MotiumApplication.logger.d("Notification monitor started", "Notification")
    }

    /**
     * Stops the notification monitoring.
     */
    private fun stopNotificationMonitor() {
        notificationMonitorRunnable?.let {
            notificationMonitorHandler.removeCallbacks(it)
            notificationMonitorRunnable = null
        }
    }

    /**
     * Checks if the notification is still visible and re-displays it if needed.
     * This handles cases where the user swipes to dismiss or the system removes it.
     */
    private fun ensureNotificationVisible() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeNotifications = notificationManager.activeNotifications

        val isNotificationVisible = activeNotifications.any { it.id == NOTIFICATION_ID }

        if (!isNotificationVisible) {
            MotiumApplication.logger.w(
                "⚠️ Notification was dismissed - re-displaying foreground notification",
                "Notification"
            )
            // Re-start foreground service with notification
            startForegroundService()
        }
    }
}
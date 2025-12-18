package com.application.motium.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.application.motium.MotiumApplication
import com.application.motium.R
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class ActivityRecognitionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ActivityRecognitionChannel"
        private const val ACTIVITY_UPDATE_INTERVAL = 10000L // 10 secondes - DriveQuant optimal

        // Seuils de confiance optimisés pour détection fiable
        private const val VEHICLE_CONFIDENCE_THRESHOLD = 75
        private const val BICYCLE_CONFIDENCE_THRESHOLD = 70
        private const val FOOT_CONFIDENCE_THRESHOLD = 60

        // SharedPreferences pour stocker un request code unique par installation
        private const val PREFS_NAME = "ActivityRecognitionPrefs"
        private const val PREF_REQUEST_CODE = "activity_recognition_request_code"

        // Référence à l'instance du service pour permettre l'appel depuis ActivityRecognitionReceiver
        @Volatile
        private var instance: ActivityRecognitionService? = null

        /**
         * Obtient un request code unique pour cette installation de l'app
         * Le request code est généré une seule fois et sauvegardé dans SharedPreferences
         * Cela garantit que chaque installation a un PendingIntent différent
         */
        private fun getUniqueRequestCode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Vérifier si on a déjà un request code
            var requestCode = prefs.getInt(PREF_REQUEST_CODE, 0)

            if (requestCode == 0) {
                // Générer un nouveau request code basé sur le timestamp
                requestCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()

                MotiumApplication.logger.i(
                    "🆕 Generated NEW unique request code for this installation: $requestCode",
                    "ActivityRecognition"
                )

                // Sauvegarder pour les prochains démarrages
                prefs.edit().putInt(PREF_REQUEST_CODE, requestCode).apply()
            } else {
                MotiumApplication.logger.d(
                    "♻️ Using existing request code: $requestCode",
                    "ActivityRecognition"
                )
            }

            return requestCode
        }

        fun startService(context: Context) {
            // Vérifier que les permissions de localisation sont accordées avant de démarrer
            if (!hasLocationPermissions(context)) {
                MotiumApplication.logger.w(
                    "Cannot start ActivityRecognitionService: location permissions not granted",
                    "ActivityRecognition"
                )
                return
            }

            try {
                val intent = Intent(context, ActivityRecognitionService::class.java)
                context.startForegroundService(intent)
            } catch (e: SecurityException) {
                MotiumApplication.logger.e(
                    "SecurityException when starting ActivityRecognitionService: ${e.message}",
                    "ActivityRecognition",
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

        fun stopService(context: Context) {
            val intent = Intent(context, ActivityRecognitionService::class.java)
            context.stopService(intent)
        }

        /**
         * Force le ré-enregistrement du service Activity Recognition
         * Utile après une réinstallation ou un changement d'UID pour nettoyer les anciens PendingIntents
         */
        @SuppressLint("MissingPermission") // Permission checked at service startup
        fun reregisterActivityRecognition(context: Context) {
            MotiumApplication.logger.i("🔄 Force re-registering Activity Recognition to clean old UIDs", "ActivityRecognition")

            try {
                val activityRecognitionClient = ActivityRecognition.getClient(context.applicationContext)
                val activityIntent = Intent(context.applicationContext, ActivityRecognitionReceiver::class.java)
                val requestCode = getUniqueRequestCode(context.applicationContext)

                // Créer un PendingIntent pour annuler l'ancien
                val pendingIntent = PendingIntent.getBroadcast(
                    context.applicationContext,
                    requestCode,
                    activityIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                // Tenter de supprimer les anciennes mises à jour (même avec un mauvais UID)
                activityRecognitionClient.removeActivityUpdates(pendingIntent)
                    .addOnSuccessListener {
                        MotiumApplication.logger.i("✅ Old Activity Recognition registrations cleaned (requestCode=$requestCode)", "ActivityRecognition")
                    }
                    .addOnFailureListener { e ->
                        MotiumApplication.logger.w("⚠️ Could not clean old registrations (may not exist): ${e.message}", "ActivityRecognition")
                    }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Error during re-registration: ${e.message}", "ActivityRecognition", e)
            }
        }

        /**
         * Réinitialise complètement l'Activity Recognition
         * Génère un nouveau request code et nettoie tous les anciens PendingIntents
         */
        @SuppressLint("MissingPermission") // Permission checked at service startup
        fun resetActivityRecognition(context: Context) {
            MotiumApplication.logger.i("🔄 RESET Activity Recognition - generating new request code", "ActivityRecognition")

            try {
                // Supprimer l'ancien request code pour forcer la génération d'un nouveau
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val oldRequestCode = prefs.getInt(PREF_REQUEST_CODE, 0)

                if (oldRequestCode != 0) {
                    // Essayer de nettoyer l'ancien PendingIntent
                    val activityRecognitionClient = ActivityRecognition.getClient(context.applicationContext)
                    val activityIntent = Intent(context.applicationContext, ActivityRecognitionReceiver::class.java)

                    val oldPendingIntent = PendingIntent.getBroadcast(
                        context.applicationContext,
                        oldRequestCode,
                        activityIntent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
                    )

                    if (oldPendingIntent != null) {
                        activityRecognitionClient.removeActivityUpdates(oldPendingIntent)
                        MotiumApplication.logger.i("✅ Removed old PendingIntent (requestCode=$oldRequestCode)", "ActivityRecognition")
                    }
                }

                // Supprimer le request code sauvegardé
                prefs.edit().remove(PREF_REQUEST_CODE).apply()

                MotiumApplication.logger.i("✅ Activity Recognition reset complete - restart service to apply", "ActivityRecognition")

            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Error resetting Activity Recognition: ${e.message}", "ActivityRecognition", e)
            }
        }

        // NOTE: La méthode handleActivityDetection() a été supprimée car elle n'est plus utilisée
        // avec la nouvelle ActivityTransition API. Le receiver appelle maintenant directement
        // LocationTrackingService pour gérer les transitions.
    }

    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    // Guard against redundant initialization (battery optimization)
    private var isActivityRecognitionActive = false

    // CRASH FIX: Add exception handler to catch all uncaught exceptions in coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        MotiumApplication.logger.e(
            "❌ Uncaught exception in ActivityRecognitionService coroutine: ${exception.message}",
            "ActivityRecognition",
            exception
        )
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private var lastDetectedActivity = DetectedActivity.UNKNOWN
    private var lastConfirmedActivity = DetectedActivity.UNKNOWN  // Dernière activité confirmée (haute confiance)
    private var hasStartedBuffering = false  // Flag pour savoir si on a déjà démarré le buffering

    // Système de détection d'immobilité prolongée (3 minutes)
    private var stillDetectionStartTime: Long? = null // Timestamp du début de l'immobilité
    private var wasStillFor3Minutes = false // Flag pour savoir si on a été immobile pendant 3 minutes
    private val stillCheckHandler = Handler(Looper.getMainLooper())
    private val STILL_TIMEOUT_MS = 180000L // 3 minutes en millisecondes

    // NOTE: Le monitoring "NO ACTIVITY DETECTED" a été supprimé car il était obsolète
    // avec l'API ActivityTransition qui envoie uniquement les transitions (ENTER/EXIT)
    // et non plus les activités périodiquement. L'API fonctionne correctement.

    override fun onCreate() {
        super.onCreate()

        // Stocker l'instance pour permettre l'appel depuis ActivityRecognitionReceiver
        instance = this

        MotiumApplication.logger.i("ActivityRecognitionService created", "ActivityRecognition")

        createNotificationChannel()
        activityRecognitionClient = ActivityRecognition.getClient(this)

        // FIX: Nettoyer les anciens PendingIntents au démarrage pour éviter les conflits d'UID
        reregisterActivityRecognition(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // BATTERY OPTIMIZATION: Skip re-initialization if already running
        if (isActivityRecognitionActive) {
            MotiumApplication.logger.d(
                "⚡ ActivityRecognitionService already active, skipping initialization",
                "ActivityRecognition"
            )
            return START_STICKY
        }

        MotiumApplication.logger.i("🚀 ActivityRecognitionService onStartCommand - action: ${intent?.action}", "ActivityRecognition")

        // Démarrage du service - startForeground DOIT être appelé en premier
        MotiumApplication.logger.i("🔧 Starting foreground service and activity recognition", "ActivityRecognition")
        startForegroundService()
        startActivityRecognition()

        // Mark as active after successful initialization
        isActivityRecognitionActive = true

        try {
            MotiumApplication.logger.i("🔧 Starting LocationTrackingService in foreground mode", "ActivityRecognition")
            LocationTrackingService.startService(this)
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error starting LocationTrackingService: ${e.message}",
                "ActivityRecognition",
                e
            )
        }

        // CRITICAL: Schedule keep-alive alarm to prevent Doze mode from killing service
        DozeModeFix.scheduleActivityRecognitionKeepAlive(this)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        MotiumApplication.logger.i("🛑 ActivityRecognitionService destroyed", "ActivityRecognition")

        // Reset the active flag so service can be restarted properly
        isActivityRecognitionActive = false

        // Nettoyer l'instance
        instance = null

        // Cancel Doze mode keep-alive alarm
        DozeModeFix.cancelActivityRecognitionKeepAlive(this)

        // Nettoyer le handler STILL pour éviter les fuites mémoire
        stillCheckHandler.removeCallbacksAndMessages(null)

        stopActivityRecognition()
        serviceScope.cancel()
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

    private fun startActivityRecognition() {
        MotiumApplication.logger.i("🔧 Requesting activity transition updates (nouvelle API)", "ActivityRecognition")

        // Vérifier Google Play Services
        try {
            val apiAvailability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            val resultCode = apiAvailability.isGooglePlayServicesAvailable(this)
            if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                MotiumApplication.logger.e(
                    "❌ Google Play Services NOT available! Result code: $resultCode\n" +
                    "Activity Recognition CANNOT work without Google Play Services.\n" +
                    "Error: ${apiAvailability.getErrorString(resultCode)}",
                    "ActivityRecognition"
                )
                // Continue anyway to log the attempt
            } else {
                MotiumApplication.logger.i("✅ Google Play Services available", "ActivityRecognition")
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking Google Play Services: ${e.message}", "ActivityRecognition", e)
        }

        // Vérifier explicitement la permission ACTIVITY_RECOGNITION
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACTIVITY_RECOGNITION
                )
        } else {
            true // Permission not needed before Android 10
        }

        MotiumApplication.logger.i(
            "📋 ACTIVITY_RECOGNITION permission check: ${if (hasPermission) "GRANTED ✅" else "DENIED ❌"}",
            "ActivityRecognition"
        )

        // DIAGNOSTICS SAMSUNG: Vérifier les causes connues d'échec Activity Recognition
        performSamsungDiagnostics()

        // FIX: Utiliser applicationContext au lieu de "this" pour éviter les conflits d'UID
        // FIX: Utiliser FLAG_CANCEL_CURRENT pour annuler les anciens PendingIntents avec un UID obsolète
        val activityIntent = Intent(applicationContext, ActivityRecognitionReceiver::class.java)

        // Utiliser un requestCode unique par installation pour garantir un PendingIntent différent
        val requestCode = getUniqueRequestCode(applicationContext)

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,  // ✅ Utilise applicationContext au lieu de "this"
            requestCode,         // ✅ Request code unique par installation
            activityIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE  // ✅ Annule l'ancien PendingIntent
        )

        MotiumApplication.logger.d(
            "PendingIntent created: $pendingIntent (requestCode=$requestCode, context=applicationContext)",
            "ActivityRecognition"
        )

        try {
            // Créer la requête de transitions
            val transitions = createActivityTransitions()
            val request = ActivityTransitionRequest(transitions)

            MotiumApplication.logger.d(
                "ActivityTransition request created with ${transitions.size} transitions",
                "ActivityRecognition"
            )

            // Utiliser la nouvelle API ActivityTransition
            val task = activityRecognitionClient.requestActivityTransitionUpdates(
                request,
                pendingIntent
            )

            MotiumApplication.logger.d("ActivityTransition request task created", "ActivityRecognition")

            task.addOnSuccessListener {
                MotiumApplication.logger.i(
                    "✅ Activity transition tracking started successfully\n" +
                    "   Transitions: IN_VEHICLE (ENTER/EXIT), WALKING (ENTER), STILL (ENTER), ON_BICYCLE (ENTER/EXIT)\n" +
                    "   PendingIntent: $pendingIntent\n" +
                    "   API: ActivityTransition (nouvelle API recommandée par Google)\n" +
                    "   Receiver: ActivityRecognitionReceiver",
                    "ActivityRecognition"
                )
            }.addOnFailureListener { exception ->
                MotiumApplication.logger.e(
                    "❌ Failed to start activity transition tracking: ${exception.message}\n" +
                    "   Exception type: ${exception.javaClass.simpleName}\n" +
                    "   Stack trace: ${exception.stackTraceToString()}",
                    "ActivityRecognition",
                    exception
                )
            }.addOnCompleteListener { task2 ->
                MotiumApplication.logger.d(
                    "Activity transition request completed - Success: ${task2.isSuccessful}",
                    "ActivityRecognition"
                )
            }
        } catch (e: SecurityException) {
            MotiumApplication.logger.e(
                "❌ SECURITY EXCEPTION - Activity recognition permission not granted!",
                "ActivityRecognition",
                e
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ UNEXPECTED EXCEPTION starting activity recognition: ${e.message}\n" +
                "   Exception type: ${e.javaClass.simpleName}",
                "ActivityRecognition",
                e
            )
        }
    }

    @SuppressLint("MissingPermission") // Permission checked at service startup
    private fun stopActivityRecognition() {
        // FIX: Utiliser les mêmes paramètres que startActivityRecognition() pour identifier le PendingIntent
        val activityIntent = Intent(applicationContext, ActivityRecognitionReceiver::class.java)
        val requestCode = getUniqueRequestCode(applicationContext)

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,  // ✅ Utilise applicationContext
            requestCode,         // ✅ Même request code unique que dans startActivityRecognition()
            activityIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            // Utiliser la nouvelle API ActivityTransition
            activityRecognitionClient.removeActivityTransitionUpdates(pendingIntent)
            MotiumApplication.logger.i("✅ Activity transition tracking stopped successfully (requestCode=$requestCode)", "ActivityRecognition")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error stopping activity transition tracking: ${e.message}", "ActivityRecognition", e)
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
}
package com.application.motium.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.application.motium.MotiumApplication

/**
 * FIX CRITIQUE: Empêcher le Doze Mode de tuer les services en arrière-plan
 *
 * PROBLÈME IDENTIFIÉ:
 * - L'Activity Recognition API de Google Play Services peut s'arrêter après quelques heures
 * - Android Doze Mode met l'app en "deep sleep" et arrête les services
 * - Samsung One UI tue agressivement les BroadcastReceivers
 *
 * SOLUTION (2026-01):
 * 1. Demander l'exemption d'optimisation de batterie
 * 2. Utiliser AlarmManager avec PendingIntent.getForegroundService() (pas getBroadcast!)
 * 3. Réveiller directement le service (Samsung-compatible, pas de BroadcastReceiver)
 *
 * NOTE: Le BroadcastReceiver a été supprimé car Samsung One UI le tue même avec exemption batterie.
 * On utilise maintenant PendingIntent.getForegroundService() qui est plus fiable.
 */
object DozeModeFix {

    private const val TAG = "DozeModeFix"

    // Intervalles de keep-alive AlarmManager
    // BATTERY OPTIMIZATION (2026-01): Intervalles augmentés pour économiser la batterie
    // Activity Recognition ne meurt généralement qu'après plusieurs heures d'inactivité
    // Des intervalles de 4-6h sont suffisants pour maintenir le service vivant

    // Standard interval for most devices - 6 hours
    private const val ALARM_INTERVAL_DEFAULT = 6L * 60 * 60 * 1000 // 6 heures (était 1h)

    // Samsung-specific interval (more aggressive due to One UI battery management) - 4 hours
    private const val ALARM_INTERVAL_SAMSUNG = 4L * 60 * 60 * 1000 // 4 heures (était 30 min)

    // Request code unique pour le PendingIntent keep-alive
    private const val KEEPALIVE_REQUEST_CODE = 9999

    // Action pour identifier les wake-ups AlarmManager dans ActivityRecognitionService
    const val ACTION_KEEPALIVE_WAKEUP = "com.application.motium.ACTION_KEEPALIVE_WAKEUP"

    /**
     * Get the appropriate alarm interval based on device manufacturer.
     * Samsung devices get 4-hour interval (One UI is aggressive with background services).
     * Other devices use 6-hour interval.
     * BATTERY OPTIMIZATION: Reduced frequency from 30-60min to 4-6h
     */
    private fun getAlarmInterval(): Long {
        return if (AutoTrackingDiagnostics.isSamsungDevice()) {
            MotiumApplication.logger.d("Using Samsung-specific 4-hour keep-alive interval", TAG)
            ALARM_INTERVAL_SAMSUNG
        } else {
            MotiumApplication.logger.d("Using standard 6-hour keep-alive interval", TAG)
            ALARM_INTERVAL_DEFAULT
        }
    }

    /**
     * Vérifie si l'app est exemptée de l'optimisation de batterie
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = context.packageName
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true // Pas de Doze mode avant Android M
    }

    /**
     * Demande l'exemption d'optimisation de batterie
     * À appeler depuis MainActivity quand l'auto-tracking est activé
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(context)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    MotiumApplication.logger.i(
                        "📱 Requesting battery optimization exemption",
                        TAG
                    )
                } catch (e: Exception) {
                    MotiumApplication.logger.e(
                        "❌ Failed to request battery optimization exemption: ${e.message}",
                        TAG,
                        e
                    )
                }
            } else {
                MotiumApplication.logger.i(
                    "✅ App already exempted from battery optimization",
                    TAG
                )
            }
        }
    }

    /**
     * Configure un AlarmManager pour réveiller le service périodiquement.
     *
     * ## SAMSUNG FIX
     * Utilise PendingIntent.getForegroundService() au lieu de getBroadcast() car
     * Samsung One UI tue les BroadcastReceivers même avec exemption batterie.
     *
     * Le service ActivityRecognitionService gère ACTION_KEEPALIVE_WAKEUP dans onStartCommand().
     */
    fun scheduleActivityRecognitionKeepAlive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // SAMSUNG FIX: PendingIntent.getForegroundService() au lieu de getBroadcast()
        val serviceIntent = Intent(context, ActivityRecognitionService::class.java).apply {
            action = ACTION_KEEPALIVE_WAKEUP
        }
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            KEEPALIVE_REQUEST_CODE,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val interval = getAlarmInterval()
        val triggerTime = System.currentTimeMillis() + interval
        val intervalMinutes = interval / 60000

        // Planifier l'alarme selon la version Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                MotiumApplication.logger.i(
                    "⏰ Scheduled EXACT keep-alive alarm in $intervalMinutes min (getForegroundService)",
                    TAG
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                MotiumApplication.logger.w(
                    "⏰ Cannot schedule exact alarms, using inexact (~$intervalMinutes min)",
                    TAG
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            MotiumApplication.logger.i(
                "⏰ Scheduled keep-alive alarm in $intervalMinutes min",
                TAG
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            MotiumApplication.logger.i(
                "⏰ Scheduled keep-alive alarm in $intervalMinutes min",
                TAG
            )
        }
    }

    /**
     * Annule l'alarme de keep-alive
     */
    fun cancelActivityRecognitionKeepAlive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val serviceIntent = Intent(context, ActivityRecognitionService::class.java).apply {
            action = ACTION_KEEPALIVE_WAKEUP
        }
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            KEEPALIVE_REQUEST_CODE,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        MotiumApplication.logger.i(
            "🛑 Cancelled Activity Recognition keep-alive alarm",
            TAG
        )
    }
}

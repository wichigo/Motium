package com.application.motium.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
 * PROBLÈME IDENTIFIÉ DANS LES LOGS:
 * - "⚠️ No activity detected for 5251s - API may have stopped"
 * - L'Activity Recognition API de Google Play Services s'arrête après quelques heures
 * - Android Doze Mode met l'app en "deep sleep" et arrête les services
 *
 * SOLUTION:
 * 1. Demander l'exemption d'optimisation de batterie
 * 2. Utiliser AlarmManager avec setExactAndAllowWhileIdle() pour réveiller l'app
 * 3. Relancer l'Activity Recognition toutes les 1 heure (optimisé pour batterie)
 *
 * BATTERY OPTIMIZATION (2025-01-27):
 * - Intervalle augmenté de 15 minutes → 1 heure pour économiser batterie
 * - Google Play Services gère déjà l'Activity Recognition de manière efficace
 * - Un keep-alive moins fréquent réduit considérablement la consommation
 */
object DozeModeFix {

    private const val ALARM_INTERVAL = 60L * 60 * 1000 // 1 heure (optimisé pour batterie)

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
                        "DozeModeFix"
                    )
                } catch (e: Exception) {
                    MotiumApplication.logger.e(
                        "❌ Failed to request battery optimization exemption: ${e.message}",
                        "DozeModeFix",
                        e
                    )
                }
            } else {
                MotiumApplication.logger.i(
                    "✅ App already exempted from battery optimization",
                    "DozeModeFix"
                )
            }
        }
    }

    /**
     * Configure un AlarmManager pour réveiller l'app toutes les 15 minutes
     * et relancer l'Activity Recognition si nécessaire
     */
    fun scheduleActivityRecognitionKeepAlive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ActivityRecognitionKeepAliveReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + ALARM_INTERVAL

        // Vérifier si on peut utiliser les alarmes exactes (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                // On peut utiliser les alarmes exactes
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                MotiumApplication.logger.i(
                    "⏰ Scheduled EXACT Activity Recognition keep-alive alarm in ${ALARM_INTERVAL/60000} minutes",
                    "DozeModeFix"
                )
            } else {
                // Fallback: utiliser setAndAllowWhileIdle() (moins précis mais fonctionne)
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                MotiumApplication.logger.w(
                    "⚠️ Cannot schedule exact alarms, using inexact alarm (keep-alive in ~${ALARM_INTERVAL/60000} minutes)",
                    "DozeModeFix"
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-11: setExactAndAllowWhileIdle() fonctionne sans permission spéciale
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            MotiumApplication.logger.i(
                "⏰ Scheduled Activity Recognition keep-alive alarm in ${ALARM_INTERVAL/60000} minutes",
                "DozeModeFix"
            )
        } else {
            // Android 5 et plus ancien
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            MotiumApplication.logger.i(
                "⏰ Scheduled Activity Recognition keep-alive alarm in ${ALARM_INTERVAL/60000} minutes",
                "DozeModeFix"
            )
        }
    }

    /**
     * Annule l'alarme de keep-alive
     */
    fun cancelActivityRecognitionKeepAlive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ActivityRecognitionKeepAliveReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        MotiumApplication.logger.i(
            "🛑 Cancelled Activity Recognition keep-alive alarm",
            "DozeModeFix"
        )
    }
}

/**
 * Receiver déclenché toutes les 15 minutes pour maintenir l'Activity Recognition actif
 */
class ActivityRecognitionKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MotiumApplication.logger.i(
            "⏰ Activity Recognition keep-alive alarm triggered",
            "KeepAliveReceiver"
        )

        // Relancer l'alarme pour la prochaine fois (toutes les 15 minutes)
        DozeModeFix.scheduleActivityRecognitionKeepAlive(context)

        // Vérifier si l'Activity Recognition Service est toujours en cours
        // Si oui, re-demander les updates pour s'assurer que l'API est toujours active
        try {
            ActivityRecognitionService.startService(context)
            MotiumApplication.logger.i(
                "✅ Activity Recognition Service restarted from keep-alive",
                "KeepAliveReceiver"
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Failed to restart Activity Recognition Service: ${e.message}",
                "KeepAliveReceiver",
                e
            )
        }
    }
}

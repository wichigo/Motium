package com.application.motium.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import com.application.motium.MotiumApplication
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic utility for auto-tracking functionality.
 * Logs events and provides diagnostic reports to help identify
 * why auto-tracking might not be working.
 */
object AutoTrackingDiagnostics {

    private const val TAG = "AutoTrackingDiag"
    private const val PREFS_NAME = "auto_tracking_diagnostics"

    // Keys
    private const val KEY_LAST_ACTIVITY_TRANSITION = "last_activity_transition"
    private const val KEY_LAST_ACTIVITY_TYPE = "last_activity_type"
    private const val KEY_LAST_TRANSITION_TYPE = "last_transition_type"
    private const val KEY_LAST_KEEPALIVE = "last_keepalive"
    private const val KEY_SERVICE_START_TIME = "service_start_time"
    private const val KEY_TRANSITION_COUNT_TODAY = "transition_count_today"
    private const val KEY_TRANSITION_COUNT_DATE = "transition_count_date"
    private const val KEY_LAST_SERVICE_RESTART = "last_service_restart"
    private const val KEY_SERVICE_RESTART_REASON = "service_restart_reason"
    private const val KEY_FAILED_TRANSITIONS = "failed_transitions"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Log an activity transition event.
     */
    fun logActivityTransition(
        context: Context,
        activityType: String,
        transitionType: String
    ) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        // Reset counter if new day
        val countDate = prefs.getString(KEY_TRANSITION_COUNT_DATE, null)
        val currentCount = if (countDate == today) {
            prefs.getInt(KEY_TRANSITION_COUNT_TODAY, 0)
        } else {
            0
        }

        prefs.edit()
            .putLong(KEY_LAST_ACTIVITY_TRANSITION, now)
            .putString(KEY_LAST_ACTIVITY_TYPE, activityType)
            .putString(KEY_LAST_TRANSITION_TYPE, transitionType)
            .putInt(KEY_TRANSITION_COUNT_TODAY, currentCount + 1)
            .putString(KEY_TRANSITION_COUNT_DATE, today)
            .apply()

        MotiumApplication.logger.d(
            "Activity transition logged: $activityType/$transitionType (count today: ${currentCount + 1})",
            TAG
        )
    }

    /**
     * Log a keep-alive alarm trigger.
     */
    fun logKeepaliveTriggered(context: Context) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putLong(KEY_LAST_KEEPALIVE, now)
            .apply()

        MotiumApplication.logger.d("Keep-alive triggered at ${formatTime(now)}", TAG)
    }

    /**
     * Log service start time.
     */
    fun logServiceStart(context: Context) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putLong(KEY_SERVICE_START_TIME, now)
            .apply()

        MotiumApplication.logger.d("Service started at ${formatTime(now)}", TAG)
    }

    /**
     * Log service restart with reason.
     */
    fun logServiceRestart(context: Context, reason: String) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putLong(KEY_LAST_SERVICE_RESTART, now)
            .putString(KEY_SERVICE_RESTART_REASON, reason)
            .apply()

        MotiumApplication.logger.w("Service restarted: $reason at ${formatTime(now)}", TAG)
    }

    /**
     * Log a failed transition (filtered out due to age).
     */
    fun logFailedTransition(context: Context, reason: String) {
        val prefs = getPrefs(context)
        val currentFailed = prefs.getInt(KEY_FAILED_TRANSITIONS, 0)

        prefs.edit()
            .putInt(KEY_FAILED_TRANSITIONS, currentFailed + 1)
            .apply()

        MotiumApplication.logger.d("Transition filtered: $reason (total filtered: ${currentFailed + 1})", TAG)
    }

    /**
     * Get a diagnostic report for debugging.
     */
    fun getDiagnosticReport(context: Context): DiagnosticReport {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        val lastTransition = prefs.getLong(KEY_LAST_ACTIVITY_TRANSITION, 0L).takeIf { it > 0 }
        val lastKeepalive = prefs.getLong(KEY_LAST_KEEPALIVE, 0L).takeIf { it > 0 }
        val serviceStartTime = prefs.getLong(KEY_SERVICE_START_TIME, 0L).takeIf { it > 0 }
        val transitionsToday = prefs.getInt(KEY_TRANSITION_COUNT_TODAY, 0)
        val failedTransitions = prefs.getInt(KEY_FAILED_TRANSITIONS, 0)
        val lastActivityType = prefs.getString(KEY_LAST_ACTIVITY_TYPE, null)
        val lastTransitionType = prefs.getString(KEY_LAST_TRANSITION_TYPE, null)
        val lastRestartReason = prefs.getString(KEY_SERVICE_RESTART_REASON, null)
        val lastRestart = prefs.getLong(KEY_LAST_SERVICE_RESTART, 0L).takeIf { it > 0 }

        return DiagnosticReport(
            lastTransitionTime = lastTransition,
            lastTransitionAgeMinutes = lastTransition?.let { (now - it) / 60_000 },
            lastActivityType = lastActivityType,
            lastTransitionType = lastTransitionType,
            lastKeepaliveTime = lastKeepalive,
            lastKeepaliveAgeMinutes = lastKeepalive?.let { (now - it) / 60_000 },
            serviceStartTime = serviceStartTime,
            serviceUptimeMinutes = serviceStartTime?.let { (now - it) / 60_000 },
            transitionsToday = transitionsToday,
            failedTransitions = failedTransitions,
            lastRestartTime = lastRestart,
            lastRestartReason = lastRestartReason,
            isSamsungDevice = isSamsungDevice(),
            batteryOptimizationDisabled = isBatteryOptimizationDisabled(context),
            googlePlayServicesAvailable = isGooglePlayServicesAvailable(context)
        )
    }

    /**
     * Get a formatted diagnostic string for display.
     */
    fun getFormattedReport(context: Context): String {
        val report = getDiagnosticReport(context)
        val sb = StringBuilder()

        sb.appendLine("=== Auto-Tracking Diagnostics ===")
        sb.appendLine()

        // Device info
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Samsung: ${if (report.isSamsungDevice) "Yes" else "No"}")
        sb.appendLine()

        // System status
        sb.appendLine("Battery optimization disabled: ${if (report.batteryOptimizationDisabled) "Yes" else "NO - ISSUE!"}")
        sb.appendLine("Google Play Services: ${if (report.googlePlayServicesAvailable) "OK" else "NOT AVAILABLE - ISSUE!"}")
        sb.appendLine()

        // Activity transitions
        sb.appendLine("--- Activity Transitions ---")
        if (report.lastTransitionTime != null) {
            sb.appendLine("Last transition: ${formatTime(report.lastTransitionTime)} (${report.lastTransitionAgeMinutes} min ago)")
            sb.appendLine("Type: ${report.lastActivityType} / ${report.lastTransitionType}")
        } else {
            sb.appendLine("Last transition: NEVER - ISSUE!")
        }
        sb.appendLine("Transitions today: ${report.transitionsToday}")
        sb.appendLine("Filtered transitions: ${report.failedTransitions}")
        sb.appendLine()

        // Keep-alive
        sb.appendLine("--- Keep-Alive ---")
        if (report.lastKeepaliveTime != null) {
            sb.appendLine("Last keep-alive: ${formatTime(report.lastKeepaliveTime)} (${report.lastKeepaliveAgeMinutes} min ago)")
            if ((report.lastKeepaliveAgeMinutes ?: 0) > 45) {
                sb.appendLine("WARNING: Keep-alive not triggered in >45 min!")
            }
        } else {
            sb.appendLine("Last keep-alive: NEVER")
        }
        sb.appendLine()

        // Service info
        sb.appendLine("--- Service ---")
        if (report.serviceStartTime != null) {
            sb.appendLine("Started: ${formatTime(report.serviceStartTime)}")
            sb.appendLine("Uptime: ${report.serviceUptimeMinutes} minutes")
        } else {
            sb.appendLine("Service start time: Unknown")
        }

        if (report.lastRestartTime != null) {
            sb.appendLine("Last restart: ${formatTime(report.lastRestartTime)}")
            sb.appendLine("Reason: ${report.lastRestartReason ?: "Unknown"}")
        }

        return sb.toString()
    }

    /**
     * Check if this is a Samsung device.
     */
    fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.lowercase(Locale.US).contains("samsung")
    }

    /**
     * Check if battery optimization is disabled for this app.
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking battery optimization: ${e.message}", TAG, e)
            false
        }
    }

    /**
     * Check if Google Play Services is available.
     */
    fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return try {
            val result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            result == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error checking Play Services: ${e.message}", TAG, e)
            false
        }
    }

    /**
     * Clear all diagnostic data.
     */
    fun clearDiagnostics(context: Context) {
        getPrefs(context).edit().clear().apply()
        MotiumApplication.logger.i("Diagnostics cleared", TAG)
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
    }

    /**
     * Diagnostic report data class.
     */
    data class DiagnosticReport(
        val lastTransitionTime: Long?,
        val lastTransitionAgeMinutes: Long?,
        val lastActivityType: String?,
        val lastTransitionType: String?,
        val lastKeepaliveTime: Long?,
        val lastKeepaliveAgeMinutes: Long?,
        val serviceStartTime: Long?,
        val serviceUptimeMinutes: Long?,
        val transitionsToday: Int,
        val failedTransitions: Int,
        val lastRestartTime: Long?,
        val lastRestartReason: String?,
        val isSamsungDevice: Boolean,
        val batteryOptimizationDisabled: Boolean,
        val googlePlayServicesAvailable: Boolean
    ) {
        /**
         * Check if auto-tracking appears to be working.
         */
        fun isHealthy(): Boolean {
            // Consider unhealthy if:
            // 1. No transitions in 2+ hours (during normal hours)
            // 2. No keep-alive in 45+ minutes
            // 3. Battery optimization enabled
            // 4. Play Services unavailable

            if (!batteryOptimizationDisabled) return false
            if (!googlePlayServicesAvailable) return false

            val keepaliveStale = (lastKeepaliveAgeMinutes ?: Long.MAX_VALUE) > 45
            if (keepaliveStale && lastKeepaliveTime != null) return false

            return true
        }
    }
}

package com.application.motium.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppLogger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "Motium"
        private const val LOG_FILE_NAME = "motium_logs.txt"
        private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB

        @Volatile
        private var instance: AppLogger? = null

        fun getInstance(context: Context): AppLogger {
            return instance ?: synchronized(this) {
                instance ?: AppLogger(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logFile = File(context.filesDir, LOG_FILE_NAME)

    init {
        // Créer le fichier de log s'il n'existe pas
        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
                writeToFile("INFO", "========== Motium App Logging Started ==========", "AppLogger")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la création du fichier de log", e)
            }
        }

        // Nettoyer le fichier s'il devient trop gros
        if (logFile.length() > MAX_LOG_SIZE) {
            clearOldLogs()
        }
    }

    fun d(message: String, tag: String = TAG) {
        Log.d(tag, message)
        writeToFile("DEBUG", message, tag)
    }

    fun i(message: String, tag: String = TAG) {
        Log.i(tag, message)
        writeToFile("INFO", message, tag)
    }

    fun w(message: String, tag: String = TAG, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeToFile("WARN", message, tag, throwable)
    }

    fun e(message: String, tag: String = TAG, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeToFile("ERROR", message, tag, throwable)
    }

    fun logLocationUpdate(latitude: Double, longitude: Double, accuracy: Float) {
        val message = "Location update: lat=$latitude, lng=$longitude, accuracy=${accuracy}m"
        i(message, "LocationService")
    }

    fun logTripStart(tripId: String) {
        val message = "Trip started: $tripId"
        i(message, "TripTracker")
    }

    fun logTripEnd(tripId: String, distance: Double, duration: Long) {
        val message = "Trip ended: $tripId, distance=${distance}km, duration=${duration}ms"
        i(message, "TripTracker")
    }

    fun logPermissionRequest(permission: String, granted: Boolean) {
        val message = "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}"
        i(message, "PermissionManager")
    }

    fun logBackgroundOperation(operation: String) {
        val message = "Background operation: $operation"
        i(message, "BackgroundService")
    }

    fun logSyncOperation(operation: String, success: Boolean, error: String? = null) {
        val status = if (success) "SUCCESS" else "FAILED"
        val message = "Sync operation: $operation - $status${error?.let { " - Error: $it" } ?: ""}"
        if (success) i(message, "Sync") else e(message, "Sync")
    }

    private fun writeToFile(level: String, message: String, tag: String, throwable: Throwable? = null) {
        try {
            val timestamp = dateFormat.format(Date())
            val logEntry = buildString {
                append("$timestamp [$level] $tag: $message")
                throwable?.let {
                    append("\n")
                    append(it.stackTraceToString())
                }
                append("\n")
            }

            synchronized(logFile) {
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(logEntry.toByteArray())
                    fos.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de l'écriture dans le fichier de log", e)
        }
    }

    private fun clearOldLogs() {
        try {
            val fileSizeBytes = logFile.length()
            val fileSizeMB = fileSizeBytes / (1024 * 1024)

            // Si le fichier est trop gros (>10MB), on le supprime complètement
            // pour éviter les OutOfMemoryError lors de la lecture de millions de lignes
            if (fileSizeBytes > 10 * 1024 * 1024) {
                Log.w(TAG, "Log file too large ($fileSizeMB MB) - deleting entirely to prevent OOM")
                logFile.writeText("")
                writeToFile("INFO", "========== Logs cleared due to excessive size ($fileSizeMB MB) ==========", "AppLogger")
                return
            }

            // Pour les fichiers de taille raisonnable (5-10MB), garder seulement les dernières 5000 lignes
            // au lieu d'essayer de filtrer par date (qui peut causer OOM avec beaucoup de logs du jour)
            try {
                val allLines = logFile.readLines()
                if (allLines.size > 5000) {
                    val recentLines = allLines.takeLast(5000)
                    logFile.writeText(recentLines.joinToString("\n"))
                    Log.i(TAG, "Log file trimmed - kept last 5000 entries, removed ${allLines.size - 5000}")
                }
            } catch (oom: OutOfMemoryError) {
                // Si on manque quand même de mémoire, supprimer complètement
                Log.e(TAG, "OutOfMemoryError while cleaning logs - clearing entirely", oom)
                logFile.writeText("")
                writeToFile("INFO", "========== Logs cleared due to OutOfMemoryError ==========", "AppLogger")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du nettoyage des logs", e)
            // En cas d'erreur, essayer de supprimer le fichier pour éviter les crashes futurs
            try {
                logFile.writeText("")
            } catch (ignored: Exception) {
                // Ignore
            }
        }
    }

    fun getLogFile(): File = logFile

    fun getLogContent(): String {
        return try {
            logFile.readText()
        } catch (e: Exception) {
            "Erreur lors de la lecture du fichier de log: ${e.message}"
        }
    }

    fun clearAllLogs() {
        try {
            logFile.writeText("")
            writeToFile("INFO", "All logs cleared", "AppLogger")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la suppression des logs", e)
        }
    }

    fun getTodayLogs(): List<String> {
        return try {
            val todayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val today = todayFormat.format(Date())

            // Lire toutes les lignes du fichier de log
            val allLines = logFile.readLines()

            // Filtrer seulement les logs d'aujourd'hui
            val todayLogs = allLines.filter { line ->
                line.startsWith(today) // Format de timestamp: "2025-09-26 HH:mm:ss.SSS"
            }

            i("Retrieved ${todayLogs.size} logs for today", "LogExport")
            todayLogs
        } catch (e: Exception) {
            e("Error reading today's logs: ${e.message}", "LogExport", e)
            listOf("Erreur lors de la lecture des logs: ${e.message}")
        }
    }
}
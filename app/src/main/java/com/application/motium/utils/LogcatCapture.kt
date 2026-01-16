package com.application.motium.utils

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.service.AutoTrackingDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class pour capturer les logs logcat du système Android
 * Ces logs contiennent des informations détaillées sur l'exécution de l'application
 * incluant les crashs, exceptions, et messages système
 */
object LogcatCapture {

    /**
     * Capture les logs logcat et les sauvegarde dans un fichier
     * @param context Le contexte de l'application
     * @param maxLines Nombre maximum de lignes à capturer (par défaut 5000)
     * @return Le fichier contenant les logs logcat, ou null en cas d'erreur
     */
    suspend fun captureLogcat(context: Context, maxLines: Int = 5000): File? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("📋 Début de la capture des logs logcat (max $maxLines lignes)", "LogcatCapture")

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logcatFile = File(context.getExternalFilesDir(null), "motium_logcat_$timestamp.txt")

            // Commande logcat pour capturer les logs
            // -d : dump les logs et quitte (ne reste pas en mode follow)
            // -t : nombre maximum de lignes à récupérer
            // *:V : tous les tags avec niveau VERBOSE et au-dessus
            val command = arrayOf("logcat", "-d", "-t", maxLines.toString(), "*:V")

            MotiumApplication.logger.i("🔧 Exécution de la commande: ${command.joinToString(" ")}", "LogcatCapture")

            val process = Runtime.getRuntime().exec(command)
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

            logcatFile.bufferedWriter().use { writer ->
                // En-tête du fichier
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("Motium - Logs Logcat Android")
                writer.newLine()
                writer.write("Capturé le: $timestamp")
                writer.newLine()
                writer.write("Package: ${context.packageName}")
                writer.newLine()
                writer.write("Max lignes: $maxLines")
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.newLine()

                var lineCount = 0
                var line: String?

                while (bufferedReader.readLine().also { line = it } != null) {
                    writer.write(line)
                    writer.newLine()
                    lineCount++

                    // Progress log tous les 1000 lignes
                    if (lineCount % 1000 == 0) {
                        MotiumApplication.logger.d("📝 Capturé $lineCount lignes...", "LogcatCapture")
                    }
                }

                // Footer
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("Total lignes capturées: $lineCount")
                writer.newLine()
                writer.write("=".repeat(80))

                MotiumApplication.logger.i("✅ Capture logcat terminée: $lineCount lignes écrites dans ${logcatFile.absolutePath}", "LogcatCapture")
            }

            // Attendre que le processus se termine
            process.waitFor()

            // Vérifier que le fichier a bien été créé et contient des données
            if (logcatFile.exists() && logcatFile.length() > 0) {
                MotiumApplication.logger.i("✅ Fichier logcat créé avec succès: ${logcatFile.absolutePath} (${logcatFile.length()} bytes)", "LogcatCapture")
                logcatFile
            } else {
                MotiumApplication.logger.e("❌ Fichier logcat vide ou inexistant", "LogcatCapture")
                null
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de la capture des logs logcat: ${e.message}", "LogcatCapture", e)
            null
        }
    }

    /**
     * Capture les logs logcat filtrés pour l'application Motium uniquement
     * @param context Le contexte de l'application
     * @param maxLines Nombre maximum de lignes à capturer
     * @return Le fichier contenant les logs logcat filtrés
     */
    suspend fun captureMotiumLogcat(context: Context, maxLines: Int = 5000): File? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("📋 Capture des logs logcat Motium uniquement (max $maxLines lignes)", "LogcatCapture")

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logcatFile = File(context.getExternalFilesDir(null), "motium_logcat_filtered_$timestamp.txt")

            // Filtrer uniquement les logs de l'application Motium
            // --pid : filtrer par PID de l'application
            val pid = android.os.Process.myPid()
            val command = arrayOf("logcat", "-d", "-t", maxLines.toString(), "--pid=$pid", "*:V")

            MotiumApplication.logger.i("🔧 Exécution de la commande filtrée (PID=$pid): ${command.joinToString(" ")}", "LogcatCapture")

            val process = Runtime.getRuntime().exec(command)
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

            logcatFile.bufferedWriter().use { writer ->
                // En-tête
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("Motium - Logs Logcat Android (Filtrés)")
                writer.newLine()
                writer.write("Capturé le: $timestamp")
                writer.newLine()
                writer.write("Package: ${context.packageName}")
                writer.newLine()
                writer.write("PID: $pid")
                writer.newLine()
                writer.write("Max lignes: $maxLines")
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.newLine()

                var lineCount = 0
                var line: String?

                while (bufferedReader.readLine().also { line = it } != null) {
                    writer.write(line)
                    writer.newLine()
                    lineCount++
                }

                // Footer
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("Total lignes capturées: $lineCount")
                writer.newLine()
                writer.write("=".repeat(80))

                MotiumApplication.logger.i("✅ Capture logcat Motium terminée: $lineCount lignes", "LogcatCapture")
            }

            process.waitFor()

            if (logcatFile.exists() && logcatFile.length() > 0) {
                logcatFile
            } else {
                MotiumApplication.logger.e("❌ Fichier logcat filtré vide", "LogcatCapture")
                null
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur capture logcat filtré: ${e.message}", "LogcatCapture", e)
            null
        }
    }

    /**
     * Capture les logs spécifiques à l'auto-tracking
     * Inclut: ActivityReceiver, LocationService, TripStateMachine, etc.
     * Ajoute également le rapport diagnostique AutoTrackingDiagnostics
     * @param context Le contexte de l'application
     * @param maxLines Nombre maximum de lignes à scanner
     * @return Le fichier contenant les logs auto-tracking filtrés
     */
    suspend fun captureAutoTrackingLogs(context: Context, maxLines: Int = 10000): File? = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("🚗 Capture des logs auto-tracking", "LogcatCapture")

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logFile = File(context.getExternalFilesDir(null), "motium_autotracking_$timestamp.txt")

            // Tags à filtrer pour l'auto-tracking
            val autoTrackingTags = listOf(
                "ActivityReceiver",
                "LocationService",
                "TripStateMachine",
                "AutoTrackingDiag",
                "TripTracker",
                "TripValidator",
                "InactivityDetection",
                "BufferAutoConfirm",
                "TripHealthCheck",
                "StartPointPrecision",
                "EndPointPrecision",
                "OutlierFilter",
                "DatabaseSave",
                "AutoTracking"
            )

            // Capturer le logcat filtré par PID (même pattern que captureMotiumLogcat)
            val pid = android.os.Process.myPid()
            val command = arrayOf("logcat", "-d", "-t", maxLines.toString(), "--pid=$pid", "*:V")
            val process = Runtime.getRuntime().run { this@run.exec(command) }
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

            logFile.bufferedWriter().use { writer ->
                // En-tête
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("MOTIUM - LOGS AUTO-TRACKING")
                writer.newLine()
                writer.write("Exporté le: $timestamp")
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.newLine()

                // Inclure le rapport diagnostique
                writer.write(AutoTrackingDiagnostics.getFormattedReport(context))
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("LOGS DÉTAILLÉS")
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.newLine()

                // Filtrer les lignes par tags auto-tracking
                var line: String?
                var matchCount = 0

                while (bufferedReader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (autoTrackingTags.any { tag -> currentLine.contains(tag) }) {
                        writer.write(currentLine)
                        writer.newLine()
                        matchCount++
                    }
                }

                // Footer
                writer.newLine()
                writer.write("=".repeat(80))
                writer.newLine()
                writer.write("Total: $matchCount lignes auto-tracking")
                writer.newLine()
                writer.write("=".repeat(80))

                MotiumApplication.logger.i("✅ Capture auto-tracking: $matchCount lignes", "LogcatCapture")
            }

            process.waitFor()

            if (logFile.exists() && logFile.length() > 0) logFile else null

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur capture auto-tracking: ${e.message}", "LogcatCapture", e)
            null
        }
    }

    /**
     * Efface les logs logcat du système
     * Utile pour obtenir des logs "propres" après avoir effacé l'historique
     */
    fun clearLogcat() {
        try {
            MotiumApplication.logger.i("🧹 Effacement des logs logcat", "LogcatCapture")
            Runtime.getRuntime().run { this@run.exec("logcat -c") }
            MotiumApplication.logger.i("✅ Logs logcat effacés", "LogcatCapture")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de l'effacement des logs: ${e.message}", "LogcatCapture", e)
        }
    }
}

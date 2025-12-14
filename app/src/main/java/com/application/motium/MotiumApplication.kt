package com.application.motium

import android.app.Application
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseClient
import com.application.motium.data.sync.LicenseScheduler
import com.application.motium.data.sync.SyncScheduler
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.utils.AppLogger

class MotiumApplication : Application() {

    companion object {
        lateinit var logger: AppLogger
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Initialiser le système de logging
        logger = AppLogger.getInstance(this)
        logger.i("Motium Application started", "Application")

        // Initialiser le client Supabase avec le contexte de l'application
        // DOIT être fait avant toute utilisation de Supabase
        try {
            SupabaseClient.initialize(this)
            logger.i("SupabaseClient initialized successfully", "Application")
        } catch (e: Exception) {
            logger.e("Failed to initialize SupabaseClient: ${e.message}", "Application", e)
        }

        // Planifier la synchronisation périodique de session en arrière-plan
        // WorkManager va rafraîchir automatiquement la session toutes les 20 minutes
        try {
            SyncScheduler.scheduleSyncWork(this)
            logger.i("Session sync scheduled successfully with WorkManager", "Application")
        } catch (e: Exception) {
            logger.e("Failed to schedule session sync: ${e.message}", "Application", e)
        }

        // Planifier le traitement des déliaisons de licences expirées
        // WorkManager va traiter les licences avec préavis de 30 jours expiré
        try {
            LicenseScheduler.scheduleLicenseProcessing(this)
            logger.i("License processing scheduled successfully with WorkManager", "Application")
        } catch (e: Exception) {
            logger.e("Failed to schedule license processing: ${e.message}", "Application", e)
        }

        // Log des informations système
        logger.i("App version: ${packageManager.getPackageInfo(packageName, 0).versionName}", "Application")
        logger.i("Android version: ${android.os.Build.VERSION.RELEASE}", "Application")
        logger.i("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", "Application")

        // Redémarrer les services si auto-tracking était activé
        // Note: L'auto-tracking est maintenant géré par MainActivity après authentification
        // pour éviter les problèmes d'initialisation et respecter les restrictions Android 14+
        logger.i("Application initialized - services will start from MainActivity", "Application")
    }
}
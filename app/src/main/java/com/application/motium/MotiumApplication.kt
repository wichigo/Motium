package com.application.motium

import android.app.Application
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseClient
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.data.sync.LicenseScheduler
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.data.sync.SyncScheduler
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.OkHttpClient
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

class MotiumApplication : Application() {

    companion object {
        lateinit var logger: AppLogger
            private set
    }

    // Application-scoped coroutine scope for background tasks
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Initialiser le système de logging
        logger = AppLogger.getInstance(this)
        logger.i("Motium Application started", "Application")

        // Initialiser MapLibre (doit être fait avant toute utilisation de MapView)
        try {
            MapLibre.getInstance(this)

            // Configure HTTP cache for MapLibre (fonts, sprites, tiles)
            // This reduces network requests by caching resources locally
            val cacheDir = File(cacheDir, "maplibre_http_cache")
            val cacheSize = 100L * 1024 * 1024 // 100 MB cache for offline mode

            // Cache-first interceptor: Use cached version without network validation
            // Only fetch from network if not in cache (first load)
            val cacheFirstInterceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()

                // Identify cacheable assets (including style.json)
                val isStaticAsset = url.contains("/fonts/") ||
                        url.contains("/sprites/") ||
                        url.endsWith(".pbf") ||
                        url.endsWith(".mvt") ||
                        (url.endsWith(".png") && url.contains("sprite"))

                // style.json changes rarely - cache for 5 minutes to avoid repeated requests
                val isStyleJson = url.endsWith("style.json")

                if (isStaticAsset || isStyleJson) {
                    // Try cache-only first (no network validation)
                    val maxStale = if (isStyleJson) 5 else 365 // 5 min for style, 1 year for static
                    val staleUnit = if (isStyleJson) TimeUnit.MINUTES else TimeUnit.DAYS

                    val cacheOnlyRequest = request.newBuilder()
                        .cacheControl(CacheControl.Builder()
                            .onlyIfCached()
                            .maxStale(maxStale, staleUnit)
                            .build())
                        .build()

                    val cacheResponse = chain.proceed(cacheOnlyRequest)

                    // If cache hit (not 504), return cached response
                    if (cacheResponse.code != 504) {
                        return@Interceptor cacheResponse
                    }

                    // Cache miss - close failed response and fetch from network
                    cacheResponse.close()
                    chain.proceed(request)
                } else {
                    // For other assets, use network with cache fallback
                    chain.proceed(request)
                }
            }

            // Network interceptor to force long cache headers on responses
            val forceCacheHeadersInterceptor = Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val url = chain.request().url.toString()

                val isStaticAsset = url.contains("/fonts/") ||
                        url.contains("/sprites/") ||
                        url.endsWith(".pbf") ||
                        url.endsWith(".mvt") ||
                        (url.endsWith(".png") && url.contains("sprite"))

                val isStyleJson = url.endsWith("style.json")

                if (response.isSuccessful) {
                    when {
                        isStaticAsset -> {
                            // Override cache headers to cache for 1 year
                            response.newBuilder()
                                .removeHeader("Pragma")
                                .removeHeader("Cache-Control")
                                .header("Cache-Control", "public, max-age=31536000")
                                .build()
                        }
                        isStyleJson -> {
                            // Cache style.json for 5 minutes (300 seconds)
                            response.newBuilder()
                                .removeHeader("Pragma")
                                .removeHeader("Cache-Control")
                                .header("Cache-Control", "public, max-age=300")
                                .build()
                        }
                        else -> response
                    }
                } else {
                    response
                }
            }

            val httpClient = OkHttpClient.Builder()
                .cache(Cache(cacheDir, cacheSize))
                .addInterceptor(cacheFirstInterceptor)
                .addNetworkInterceptor(forceCacheHeadersInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            HttpRequestUtil.setOkHttpClient(httpClient)

            logger.i("MapLibre initialized with 100MB cache-first HTTP cache", "Application")
        } catch (e: Exception) {
            logger.e("Failed to initialize MapLibre: ${e.message}", "Application", e)
        }

        // Initialiser le client Supabase avec le contexte de l'application
        // DOIT être fait avant toute utilisation de Supabase
        try {
            SupabaseClient.initialize(this)
            logger.i("SupabaseClient initialized successfully", "Application")
        } catch (e: Exception) {
            logger.e("Failed to initialize SupabaseClient: ${e.message}", "Application", e)
        }

        // Vérifier et mettre à jour l'expiration du trial au démarrage
        // Fix P0: Empêche les utilisateurs avec trialEndsAt expiré de garder l'accès
        applicationScope.launch {
            try {
                val subscriptionManager = SubscriptionManager.getInstance(this@MotiumApplication)
                val wasExpired = subscriptionManager.checkAndUpdateTrialExpiration()
                if (wasExpired) {
                    logger.i("Trial expired and marked as EXPIRED at startup", "Application")
                }
            } catch (e: Exception) {
                logger.e("Failed to check trial expiration: ${e.message}", "Application", e)
            }
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
        // WorkManager va traiter les licences dont la date effective de déliaison est passée
        try {
            LicenseScheduler.scheduleLicenseProcessing(this)
            logger.i("License processing scheduled successfully with WorkManager", "Application")
        } catch (e: Exception) {
            logger.e("Failed to schedule license processing: ${e.message}", "Application", e)
        }

        // Planifier la synchronisation périodique des données (trips, vehicles, etc.)
        // WorkManager va exécuter DeltaSyncWorker toutes les 30 minutes avec sync_changes() RPC
        try {
            OfflineFirstSyncManager.getInstance(this).startPeriodicSync()
            logger.i("Data sync scheduled successfully with WorkManager (DeltaSyncWorker)", "Application")
        } catch (e: Exception) {
            logger.e("Failed to schedule data sync: ${e.message}", "Application", e)
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
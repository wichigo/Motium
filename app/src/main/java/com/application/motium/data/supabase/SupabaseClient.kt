package com.application.motium.data.supabase

import android.content.Context
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.android.Android
import kotlin.time.Duration.Companion.seconds

object SupabaseClient {

    private lateinit var applicationContext: Context

    /**
     * Initialiser le client avec le contexte de l'application
     * À appeler depuis Application.onCreate()
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    val client: SupabaseClient by lazy {
        if (!::applicationContext.isInitialized) {
            MotiumApplication.logger.e("SupabaseClient not initialized with context!", "SupabaseClient")
            throw IllegalStateException("SupabaseClient must be initialized with context first")
        }

        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                // Utiliser notre SessionManager personnalisé qui utilise SecureSessionStorage (chiffré)
                // au lieu de SharedPreferences (non chiffré)
                alwaysAutoRefresh = true
                autoSaveToStorage = true  // ✅ Activé - utilise SecureSessionManager
                autoLoadFromStorage = true  // ✅ Activé - utilise SecureSessionManager

                // Utiliser notre SessionManager personnalisé basé sur SecureSessionStorage
                sessionManager = SecureSessionManager(applicationContext)
            }
            install(Postgrest)
            install(Realtime) {
                // Garder la connexion active avec des heartbeats réguliers et agressifs
                // Le module Realtime gère automatiquement la reconnexion
                heartbeatInterval = 15.seconds // Réduit à 15s pour détection rapide de déconnexion
            }

            // Configurer le moteur HTTP Android avec timeouts plus longs et retry
            httpEngine = Android.create {
                // Configurer les timeouts (augmentés pour résilience réseau faible)
                connectTimeout = 60_000 // 60 secondes (augmenté de 30s)
                socketTimeout = 120_000 // 120 secondes (augmenté de 60s)

                // Configuration réseau pour meilleure résilience
                // Note: Les retry automatiques sont gérés par le SyncManager
            }
        }.also {
            MotiumApplication.logger.i(
                "✅ Supabase client initialized with:\n" +
                "   - Auto-refresh session enabled\n" +
                "   - Secure encrypted session storage (SecureSessionManager)\n" +
                "   - Automatic session save/load\n" +
                "   - WebSocket with 15s heartbeat\n" +
                "   - Extended timeouts (60s/120s)\n" +
                "   - Automatic reconnection",
                "SupabaseClient"
            )
        }
    }
}
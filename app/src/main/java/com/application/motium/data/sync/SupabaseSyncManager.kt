package com.application.motium.data.sync

import android.content.Context
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.utils.NetworkConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * @deprecated OBSOLÈTE (2026-01) - Utiliser OfflineFirstSyncManager à la place.
 *
 * Ce gestionnaire de synchronisation legacy est remplacé par OfflineFirstSyncManager
 * qui utilise WorkManager (plus respectueux de la batterie) et l'approche delta sync
 * via sync_changes() RPC.
 *
 * Les méthodes de ce manager ne font plus rien pour éviter les duplications de sync
 * et économiser la batterie.
 */
@Deprecated("Use OfflineFirstSyncManager instead - this manager is now a no-op stub for backwards compatibility")
class SupabaseSyncManager private constructor(private val context: Context) {

    companion object {
        // SYNC OPTIMIZATION: Intervalle augmenté de 5 → 15 minutes pour économie batterie
        // Les trajets sont rarement modifiés après création, donc sync moins fréquent suffit
        private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes (optimisé batterie)
        // BATTERY OPTIMIZATION: Quick sync augmenté de 30s → 2min pour économiser la batterie
        private const val QUICK_SYNC_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes (was 30 secondes)

        @Volatile
        private var instance: SupabaseSyncManager? = null

        fun getInstance(context: Context): SupabaseSyncManager {
            return instance ?: synchronized(this) {
                instance ?: SupabaseSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingQueue = PendingSyncQueue.getInstance(context)
    private val tripRepository = TripRepository.getInstance(context)
    private val vehicleRepository = com.application.motium.data.VehicleRepository.getInstance(context)
    private val authRepository = SupabaseAuthRepository.getInstance(context)
    private val localUserRepository = LocalUserRepository.getInstance(context)
    private val networkManager = NetworkConnectionManager.getInstance(context)

    private var periodicSyncJob: Job? = null
    private var isSyncing = false
    private var lastSuccessfulSync: Long = 0

    /**
     * @deprecated NO-OP - Sync is now handled by OfflineFirstSyncManager via WorkManager
     */
    @Deprecated("Use OfflineFirstSyncManager.startPeriodicSync() instead")
    fun startPeriodicSync() {
        MotiumApplication.logger.i(
            "⚠️ SupabaseSyncManager.startPeriodicSync() is deprecated - sync now handled by WorkManager",
            "SyncManager"
        )
        // NO-OP: Don't start any background tasks to save battery
    }

    /**
     * @deprecated NO-OP
     */
    @Deprecated("No longer needed")
    fun stopPeriodicSync() {
        MotiumApplication.logger.d("SupabaseSyncManager.stopPeriodicSync() is deprecated", "SyncManager")
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    /**
     * @deprecated NO-OP - Use OfflineFirstSyncManager.triggerImmediateSync() instead
     */
    @Deprecated("Use OfflineFirstSyncManager.triggerImmediateSync() instead")
    suspend fun forceSyncNow(): Boolean {
        MotiumApplication.logger.d(
            "SupabaseSyncManager.forceSyncNow() is deprecated - use OfflineFirstSyncManager",
            "SyncManager"
        )
        return true // Return success but don't do anything
    }

    /**
     * Effectue une synchronisation complète
     */
    private suspend fun performSync(): Boolean {
        if (isSyncing) {
            MotiumApplication.logger.d("Sync already in progress, skipping", "SyncManager")
            return false
        }

        // BATTERY OPTIMIZATION: Prevent rapid consecutive syncs (minimum 30s between syncs)
        val timeSinceLastSync = System.currentTimeMillis() - lastSuccessfulSync
        if (timeSinceLastSync < 30_000 && pendingQueue.getPendingCount() == 0) {
            MotiumApplication.logger.d(
                "⚡ Skipping sync - last sync was ${timeSinceLastSync / 1000}s ago (min 30s)",
                "SyncManager"
            )
            return true // Return true since we're not actually failing, just optimizing
        }

        isSyncing = true
        var success = false

        try {
            MotiumApplication.logger.i(
                "🔄 Starting sync - ${pendingQueue.getPendingCount()} operations pending",
                "SyncManager"
            )

            // Vérifier authentification
            if (!authRepository.isUserAuthenticated()) {
                MotiumApplication.logger.w("User not authenticated, skipping sync", "SyncManager")
                return false
            }

            // Vérifier réseau
            if (!networkManager.isConnected.value) {
                MotiumApplication.logger.w("No network connection, skipping sync", "SyncManager")
                return false
            }

            // 1. Traiter les opérations en attente avec retry
            success = processPendingOperations()

            // 2. Synchroniser les trips locaux vers Supabase (EXPORT ONLY - pas d'import)
            try {
                MotiumApplication.logger.i("Exporting local trips to Supabase...", "SyncManager")
                tripRepository.syncAllTripsToSupabase()
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Failed to export trips to Supabase: ${e.message}",
                    "SyncManager",
                    e
                )
                success = false
            }

            // 3. Synchroniser les véhicules (bidirectionnel pour mode offline)
            try {
                MotiumApplication.logger.i("Syncing vehicles with Supabase...", "SyncManager")

                // 3a. Export des véhicules locaux vers Supabase
                vehicleRepository.syncVehiclesToSupabase()

                // 3b. Import des véhicules depuis Supabase pour accès offline
                vehicleRepository.syncVehiclesFromSupabase()

                MotiumApplication.logger.i("✅ Vehicle sync completed", "SyncManager")
            } catch (e: java.util.concurrent.CancellationException) {
                // Normal cancellation (e.g., user navigated away) - don't log as error
                MotiumApplication.logger.d("Vehicle sync cancelled (user navigated away)", "SyncManager")
                throw e // Rethrow to properly propagate cancellation
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Failed to sync vehicles: ${e.message}",
                    "SyncManager",
                    e
                )
                success = false
            }

            // 4. DÉSACTIVÉ: Import de trips Supabase vers local
            // L'utilisateur veut SEULEMENT exporter les trajets locaux vers Supabase,
            // et ne PAS importer les trajets de Supabase vers le stockage local
            // Cette logique bidirectionnelle a été désactivée pour éviter les doublons
            // et garantir que seuls les trajets créés localement sont synchronisés
            /*
            try {
                MotiumApplication.logger.i("Syncing Supabase trips to local...", "SyncManager")
                tripRepository.syncTripsFromSupabase()
            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Failed to sync trips from Supabase: ${e.message}",
                    "SyncManager",
                    e
                )
                success = false
            }
            */

            if (success) {
                lastSuccessfulSync = System.currentTimeMillis()
                MotiumApplication.logger.i(
                    "✅ Sync completed successfully - ${pendingQueue.getPendingCount()} operations remaining",
                    "SyncManager"
                )
            } else {
                MotiumApplication.logger.w(
                    "⚠️ Sync completed with errors - ${pendingQueue.getPendingCount()} operations pending",
                    "SyncManager"
                )
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Sync failed: ${e.message}",
                "SyncManager",
                e
            )
            success = false
        } finally {
            isSyncing = false
        }

        return success
    }

    /**
     * Traite les opérations en attente avec backoff exponentiel
     */
    private suspend fun processPendingOperations(): Boolean {
        val operationsToRetry = pendingQueue.getOperationsReadyForRetry()

        if (operationsToRetry.isEmpty()) {
            return true
        }

        MotiumApplication.logger.i(
            "Processing ${operationsToRetry.size} pending operations",
            "SyncManager"
        )

        var allSuccess = true

        operationsToRetry.forEach { operation ->
            try {
                MotiumApplication.logger.i(
                    "Retrying ${operation.type} for ${operation.entityType} ${operation.entityId} " +
                    "(attempt ${operation.retryCount + 1})",
                    "SyncManager"
                )

                val success = when (operation.entityType) {
                    PendingSyncQueue.EntityType.TRIP -> processTripOperation(operation)
                    PendingSyncQueue.EntityType.VEHICLE -> processVehicleOperation(operation)
                    PendingSyncQueue.EntityType.USER_PROFILE -> processUserProfileOperation(operation)
                }

                if (success) {
                    pendingQueue.dequeue(operation.id)
                    MotiumApplication.logger.i(
                        "✅ Successfully synced ${operation.entityType} ${operation.entityId}",
                        "SyncManager"
                    )
                } else {
                    pendingQueue.incrementRetryCount(operation.id)
                    allSuccess = false
                    MotiumApplication.logger.w(
                        "❌ Failed to sync ${operation.entityType} ${operation.entityId} - will retry",
                        "SyncManager"
                    )
                }

            } catch (e: Exception) {
                MotiumApplication.logger.e(
                    "Error processing operation ${operation.id}: ${e.message}",
                    "SyncManager",
                    e
                )
                pendingQueue.incrementRetryCount(operation.id)
                allSuccess = false
            }
        }

        return allSuccess
    }

    /**
     * Traite une opération de type Trip
     */
    private suspend fun processTripOperation(operation: PendingSyncQueue.PendingOperation): Boolean {
        return try {
            // La synchronisation est déjà gérée par TripRepository.syncAllTripsToSupabase()
            // Cette fonction sert surtout pour les opérations DELETE
            when (operation.type) {
                PendingSyncQueue.OperationType.DELETE -> {
                    // Utiliser localUserRepository pour obtenir le bon users.id (compatible RLS)
                    val currentUser = localUserRepository.getLoggedInUser()
                    if (currentUser != null) {
                        // Supprimer le trip de Supabase
                        // Note: La suppression est déjà gérée dans TripRepository
                        true
                    } else {
                        false
                    }
                }
                else -> {
                    // CREATE et UPDATE sont gérés par syncAllTripsToSupabase()
                    true
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error processing trip operation: ${e.message}",
                "SyncManager",
                e
            )
            false
        }
    }

    /**
     * Traite une opération de type Vehicle
     */
    private suspend fun processVehicleOperation(operation: PendingSyncQueue.PendingOperation): Boolean {
        return try {
            // La synchronisation est gérée par VehicleRepository.syncVehiclesToSupabase()
            // Cette fonction sert surtout pour les opérations DELETE
            when (operation.type) {
                PendingSyncQueue.OperationType.DELETE -> {
                    // Utiliser localUserRepository pour obtenir le bon users.id (compatible RLS)
                    val currentUser = localUserRepository.getLoggedInUser()
                    if (currentUser != null) {
                        // Suppression gérée dans VehicleRepository
                        true
                    } else {
                        false
                    }
                }
                else -> {
                    // CREATE et UPDATE sont gérés par syncVehiclesToSupabase()
                    true
                }
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error processing vehicle operation: ${e.message}",
                "SyncManager",
                e
            )
            false
        }
    }

    /**
     * Traite une opération de type User Profile
     */
    private suspend fun processUserProfileOperation(operation: PendingSyncQueue.PendingOperation): Boolean {
        // TODO: Implémenter la synchronisation des profils utilisateur
        return true
    }

    /**
     * Récupère les statistiques de synchronisation
     */
    fun getSyncStats(): SyncStats {
        return SyncStats(
            pendingOperations = pendingQueue.getPendingCount(),
            lastSuccessfulSync = lastSuccessfulSync,
            isSyncing = isSyncing,
            isNetworkAvailable = networkManager.isConnected.value
        )
    }

    data class SyncStats(
        val pendingOperations: Int,
        val lastSuccessfulSync: Long,
        val isSyncing: Boolean,
        val isNetworkAvailable: Boolean
    )
}

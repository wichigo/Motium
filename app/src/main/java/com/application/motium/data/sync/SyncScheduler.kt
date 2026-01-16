package com.application.motium.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.application.motium.MotiumApplication
import java.util.concurrent.TimeUnit

/**
 * Scheduler pour la synchronisation périodique de la session Supabase
 * Configure WorkManager pour rafraîchir automatiquement la session
 *
 * IMPORTANT: WorkManager a un intervalle minimum de 15 minutes pour les tâches périodiques
 */
object SyncScheduler {

    // Intervalle de refresh en minutes (minimum 15 minutes pour WorkManager)
    private const val REFRESH_INTERVAL_MINUTES = 20L

    /**
     * Configure et démarre la synchronisation périodique de la session
     * À appeler au démarrage de l'application
     */
    fun scheduleSyncWork(context: Context) {
        try {
            MotiumApplication.logger.i("⏰ Configuration de la synchronisation périodique (toutes les $REFRESH_INTERVAL_MINUTES min)", "SyncScheduler")

            // Contraintes pour le worker
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // Nécessite une connexion réseau
                // BATTERY OPTIMIZATION: Ne pas exécuter si batterie faible
                .setRequiresBatteryNotLow(true)
                .build()

            // Créer la requête périodique
            val syncWorkRequest = PeriodicWorkRequestBuilder<SessionRefreshWorker>(
                REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Période de flex de 5 minutes
            )
                .setConstraints(constraints)
                .addTag(SessionRefreshWorker.WORK_NAME)
                .build()

            // Planifier le worker (KEEP pour ne pas remplacer si déjà planifié)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SessionRefreshWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Conserver si déjà planifié
                syncWorkRequest
            )

            MotiumApplication.logger.i("✅ Synchronisation périodique planifiée avec succès", "SyncScheduler")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de la planification de la synchronisation: ${e.message}", "SyncScheduler", e)
        }
    }

    /**
     * Annule la synchronisation périodique
     * À appeler si l'utilisateur se déconnecte
     */
    fun cancelSyncWork(context: Context) {
        try {
            MotiumApplication.logger.i("🛑 Annulation de la synchronisation périodique", "SyncScheduler")
            WorkManager.getInstance(context).cancelUniqueWork(SessionRefreshWorker.WORK_NAME)
            MotiumApplication.logger.i("✅ Synchronisation périodique annulée", "SyncScheduler")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de l'annulation de la synchronisation: ${e.message}", "SyncScheduler", e)
        }
    }

    /**
     * Vérifie le statut de la synchronisation périodique
     */
    fun getSyncWorkStatus(context: Context): String {
        return try {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SessionRefreshWorker.WORK_NAME)
                .get()

            when {
                workInfos.isEmpty() -> "❌ Non planifié"
                workInfos.any { it.state.isFinished } -> "✅ Terminé"
                workInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING } -> "🔄 En cours"
                workInfos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED } -> "⏳ En attente"
                else -> "❓ État inconnu"
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors de la vérification du statut: ${e.message}", "SyncScheduler", e)
            "❌ Erreur"
        }
    }

    /**
     * Force une exécution immédiate du worker (utile pour les tests)
     */
    fun forceSyncNow(context: Context) {
        try {
            MotiumApplication.logger.i("🚀 Force l'exécution immédiate du refresh de session", "SyncScheduler")

            val immediateWorkRequest = androidx.work.OneTimeWorkRequestBuilder<SessionRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(immediateWorkRequest)
            MotiumApplication.logger.i("✅ Refresh immédiat planifié", "SyncScheduler")
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Erreur lors du refresh immédiat: ${e.message}", "SyncScheduler", e)
        }
    }
}

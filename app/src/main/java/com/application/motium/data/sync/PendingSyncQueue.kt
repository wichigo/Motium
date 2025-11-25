package com.application.motium.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.application.motium.MotiumApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestionnaire de queue pour synchronisation offline
 * Garde trace des opérations en attente de synchronisation avec Supabase
 */
class PendingSyncQueue private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "pending_sync_queue" // Ancien nom (non chiffré)
        private const val PREFS_NAME_ENCRYPTED = "pending_sync_queue_encrypted" // Nouveau nom (chiffré)
        private const val KEY_PENDING_OPERATIONS = "pending_operations"
        private const val KEY_MIGRATION_COMPLETE = "queue_migrated_to_encrypted" // Flag de migration

        @Volatile
        private var instance: PendingSyncQueue? = null

        fun getInstance(context: Context): PendingSyncQueue {
            return instance ?: synchronized(this) {
                instance ?: PendingSyncQueue(context.applicationContext).also { instance = it }
            }
        }
    }

    @Serializable
    data class PendingOperation(
        val id: String,
        val type: OperationType,
        val entityId: String, // Trip ID, Vehicle ID, etc.
        val entityType: EntityType,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
        val lastAttempt: Long? = null,
        val data: String? = null // JSON data if needed
    )

    enum class OperationType {
        CREATE, UPDATE, DELETE
    }

    enum class EntityType {
        TRIP, VEHICLE, USER_PROFILE
    }

    private val appContext = context.applicationContext

    // SÉCURITÉ: Utiliser EncryptedSharedPreferences au lieu de SharedPreferences standard
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        MotiumApplication.logger.e(
            "❌ CRITICAL: Cannot create encrypted sync queue storage",
            "PendingSyncQueue",
            e
        )
        throw IllegalStateException(
            "Cannot initialize encrypted sync queue storage. Please reinstall the app.",
            e
        )
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // Cache en mémoire pour accès rapide (thread-safe)
    private val operationsCache = ConcurrentHashMap<String, PendingOperation>()

    init {
        migrateFromUnencryptedIfNeeded()
        loadOperationsFromDisk()
    }

    /**
     * MIGRATION: Transfert des opérations depuis SharedPreferences non chiffré vers chiffré.
     * Exécuté une seule fois au premier lancement après mise à jour.
     */
    private fun migrateFromUnencryptedIfNeeded() {
        try {
            // Vérifier si la migration a déjà été effectuée
            if (prefs.getBoolean(KEY_MIGRATION_COMPLETE, false)) {
                MotiumApplication.logger.d(
                    "Sync queue migration already complete, skipping",
                    "PendingSyncQueue"
                )
                return
            }

            // Charger les anciennes opérations depuis SharedPreferences non chiffré
            val oldPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val oldOperationsJson = oldPrefs.getString(KEY_PENDING_OPERATIONS, null)

            if (oldOperationsJson != null) {
                MotiumApplication.logger.i(
                    "🔄 Starting migration of sync queue to encrypted storage",
                    "PendingSyncQueue"
                )

                // Copier les opérations vers le stockage chiffré
                prefs.edit()
                    .putString(KEY_PENDING_OPERATIONS, oldOperationsJson)
                    .putBoolean(KEY_MIGRATION_COMPLETE, true)
                    .apply()

                // Supprimer l'ancien stockage non chiffré
                oldPrefs.edit().clear().apply()

                MotiumApplication.logger.i(
                    "✅ Successfully migrated sync queue to encrypted storage",
                    "PendingSyncQueue"
                )
            } else {
                // Pas de données à migrer, marquer comme terminé
                prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).apply()
                MotiumApplication.logger.d(
                    "No sync queue operations to migrate",
                    "PendingSyncQueue"
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "❌ Sync queue migration failed: ${e.message}",
                "PendingSyncQueue",
                e
            )
            // Ne pas marquer comme terminé en cas d'erreur - retry au prochain lancement
        }
    }

    /**
     * Charge les opérations en attente depuis le disque au démarrage
     */
    private fun loadOperationsFromDisk() {
        try {
            val operationsJson = prefs.getString(KEY_PENDING_OPERATIONS, null)
            if (operationsJson != null) {
                val operations = json.decodeFromString<List<PendingOperation>>(operationsJson)
                operations.forEach { operationsCache[it.id] = it }
                MotiumApplication.logger.i(
                    "Loaded ${operations.size} pending sync operations from disk",
                    "PendingSyncQueue"
                )
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error loading pending operations: ${e.message}",
                "PendingSyncQueue",
                e
            )
        }
    }

    /**
     * Sauvegarde les opérations en attente sur le disque
     */
    private fun saveOperationsToDisk() {
        try {
            val operations = operationsCache.values.toList()
            val operationsJson = json.encodeToString(operations)
            prefs.edit().putString(KEY_PENDING_OPERATIONS, operationsJson).apply()
        } catch (e: Exception) {
            MotiumApplication.logger.e(
                "Error saving pending operations: ${e.message}",
                "PendingSyncQueue",
                e
            )
        }
    }

    /**
     * Ajoute une opération à la queue
     */
    fun enqueue(operation: PendingOperation) {
        operationsCache[operation.id] = operation
        saveOperationsToDisk()
        MotiumApplication.logger.i(
            "Enqueued ${operation.type} operation for ${operation.entityType} ${operation.entityId}",
            "PendingSyncQueue"
        )
    }

    /**
     * Retire une opération de la queue après synchronisation réussie
     */
    fun dequeue(operationId: String) {
        operationsCache.remove(operationId)
        saveOperationsToDisk()
        MotiumApplication.logger.i(
            "Dequeued operation: $operationId",
            "PendingSyncQueue"
        )
    }

    /**
     * Met à jour le compteur de retry d'une opération
     */
    fun incrementRetryCount(operationId: String) {
        operationsCache[operationId]?.let { operation ->
            val updatedOperation = operation.copy(
                retryCount = operation.retryCount + 1,
                lastAttempt = System.currentTimeMillis()
            )
            operationsCache[operationId] = updatedOperation
            saveOperationsToDisk()
        }
    }

    /**
     * Récupère toutes les opérations en attente
     */
    fun getAllPendingOperations(): List<PendingOperation> {
        return operationsCache.values.sortedBy { it.timestamp }
    }

    /**
     * Récupère les opérations en attente par type d'entité
     */
    fun getPendingOperationsByEntity(entityType: EntityType): List<PendingOperation> {
        return operationsCache.values
            .filter { it.entityType == entityType }
            .sortedBy { it.timestamp }
    }

    /**
     * Vérifie si une opération spécifique est en attente
     */
    fun hasPendingOperation(entityId: String, operationType: OperationType): Boolean {
        return operationsCache.values.any {
            it.entityId == entityId && it.type == operationType
        }
    }

    /**
     * Récupère le nombre d'opérations en attente
     */
    fun getPendingCount(): Int {
        return operationsCache.size
    }

    /**
     * Nettoie toutes les opérations en attente (à utiliser avec précaution)
     */
    fun clearAll() {
        operationsCache.clear()
        saveOperationsToDisk()
        MotiumApplication.logger.w("Cleared all pending sync operations", "PendingSyncQueue")
    }

    /**
     * Récupère les opérations prêtes pour retry (avec backoff exponentiel)
     */
    fun getOperationsReadyForRetry(): List<PendingOperation> {
        val now = System.currentTimeMillis()
        return operationsCache.values.filter { operation ->
            if (operation.lastAttempt == null) {
                true // Jamais essayé
            } else {
                // Backoff exponentiel: 2^retryCount secondes, plafonné à 5 minutes
                val backoffMs = minOf(
                    (1 shl operation.retryCount) * 2000L, // 2s, 4s, 8s, 16s, 32s...
                    5 * 60 * 1000L // Max 5 minutes
                )
                (now - operation.lastAttempt) >= backoffMs
            }
        }.sortedBy { it.timestamp }
    }
}

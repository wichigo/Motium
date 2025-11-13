package com.application.motium.data.sync

import android.content.Context
import android.content.SharedPreferences
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
        private const val PREFS_NAME = "pending_sync_queue"
        private const val KEY_PENDING_OPERATIONS = "pending_operations"

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

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // Cache en mémoire pour accès rapide
    private val operationsCache = ConcurrentHashMap<String, PendingOperation>()

    init {
        loadOperationsFromDisk()
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

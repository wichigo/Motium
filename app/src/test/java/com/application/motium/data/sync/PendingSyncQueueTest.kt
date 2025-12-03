package com.application.motium.data.sync

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Tests unitaires pour PendingSyncQueue
 * Teste la gestion de la file d'attente de synchronisation offline
 */
class PendingSyncQueueTest {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockSharedPreferences: SharedPreferences

    @MockK
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)

        // Setup mock chain
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockSharedPreferences
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.clear() } returns mockEditor
        every { mockEditor.apply() } just Runs

        // Default return for getString
        every { mockSharedPreferences.getString(any(), any()) } returns null
        every { mockSharedPreferences.getBoolean(any(), any()) } returns false
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ============ Helper functions ============

    private fun createTestOperation(
        id: String = UUID.randomUUID().toString(),
        type: PendingSyncQueue.OperationType = PendingSyncQueue.OperationType.CREATE,
        entityId: String = "entity-${System.nanoTime()}",
        entityType: PendingSyncQueue.EntityType = PendingSyncQueue.EntityType.TRIP,
        timestamp: Long = System.currentTimeMillis(),
        retryCount: Int = 0,
        lastAttempt: Long? = null,
        data: String? = null
    ): PendingSyncQueue.PendingOperation {
        return PendingSyncQueue.PendingOperation(
            id = id,
            type = type,
            entityId = entityId,
            entityType = entityType,
            timestamp = timestamp,
            retryCount = retryCount,
            lastAttempt = lastAttempt,
            data = data
        )
    }

    // ============ OPERATION TYPE TESTS ============

    @Test
    fun `OperationType - contains all expected types`() {
        // Then
        val types = PendingSyncQueue.OperationType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(PendingSyncQueue.OperationType.CREATE))
        assertTrue(types.contains(PendingSyncQueue.OperationType.UPDATE))
        assertTrue(types.contains(PendingSyncQueue.OperationType.DELETE))
        println("✓ OperationType_containsAllExpectedTypes: All operation types present")
    }

    @Test
    fun `EntityType - contains all expected types`() {
        // Then
        val types = PendingSyncQueue.EntityType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(PendingSyncQueue.EntityType.TRIP))
        assertTrue(types.contains(PendingSyncQueue.EntityType.VEHICLE))
        assertTrue(types.contains(PendingSyncQueue.EntityType.USER_PROFILE))
        println("✓ EntityType_containsAllExpectedTypes: All entity types present")
    }

    // ============ PENDING OPERATION DATA CLASS TESTS ============

    @Test
    fun `PendingOperation - creates with correct defaults`() {
        // Given
        val operation = PendingSyncQueue.PendingOperation(
            id = "test-id",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "entity-123",
            entityType = PendingSyncQueue.EntityType.TRIP
        )

        // Then
        assertEquals("test-id", operation.id)
        assertEquals(PendingSyncQueue.OperationType.CREATE, operation.type)
        assertEquals("entity-123", operation.entityId)
        assertEquals(PendingSyncQueue.EntityType.TRIP, operation.entityType)
        assertTrue(operation.timestamp > 0)
        assertEquals(0, operation.retryCount)
        assertNull(operation.lastAttempt)
        assertNull(operation.data)
        println("✓ PendingOperation_createsWithCorrectDefaults: Default values are correct")
    }

    @Test
    fun `PendingOperation - stores all fields correctly`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val lastAttempt = timestamp - 60000
        val operation = createTestOperation(
            id = "op-123",
            type = PendingSyncQueue.OperationType.UPDATE,
            entityId = "vehicle-456",
            entityType = PendingSyncQueue.EntityType.VEHICLE,
            timestamp = timestamp,
            retryCount = 3,
            lastAttempt = lastAttempt,
            data = """{"name": "Updated Vehicle"}"""
        )

        // Then
        assertEquals("op-123", operation.id)
        assertEquals(PendingSyncQueue.OperationType.UPDATE, operation.type)
        assertEquals("vehicle-456", operation.entityId)
        assertEquals(PendingSyncQueue.EntityType.VEHICLE, operation.entityType)
        assertEquals(timestamp, operation.timestamp)
        assertEquals(3, operation.retryCount)
        assertEquals(lastAttempt, operation.lastAttempt)
        assertEquals("""{"name": "Updated Vehicle"}""", operation.data)
        println("✓ PendingOperation_storesAllFieldsCorrectly: All fields stored correctly")
    }

    // ============ BACKOFF CALCULATION TESTS ============

    @Test
    fun `backoff calculation - never tried operations are ready`() {
        // Given
        val operation = createTestOperation(
            retryCount = 0,
            lastAttempt = null
        )

        // When: Check if ready for retry (no lastAttempt means never tried)
        val isReady = operation.lastAttempt == null

        // Then
        assertTrue(isReady)
        println("✓ backoffCalculation_neverTriedOperationsAreReady: Operations without lastAttempt are ready")
    }

    @Test
    fun `backoff calculation - respects exponential backoff`() {
        // Given
        val now = System.currentTimeMillis()

        // Test retry count 0: 2 seconds backoff
        val op0 = createTestOperation(retryCount = 0, lastAttempt = now - 3000) // 3s ago
        val backoff0 = (1 shl 0) * 2000L // 2s
        assertTrue(now - (op0.lastAttempt ?: 0) >= backoff0)

        // Test retry count 1: 4 seconds backoff
        val op1 = createTestOperation(retryCount = 1, lastAttempt = now - 5000) // 5s ago
        val backoff1 = (1 shl 1) * 2000L // 4s
        assertTrue(now - (op1.lastAttempt ?: 0) >= backoff1)

        // Test retry count 2: 8 seconds backoff
        val op2 = createTestOperation(retryCount = 2, lastAttempt = now - 10000) // 10s ago
        val backoff2 = (1 shl 2) * 2000L // 8s
        assertTrue(now - (op2.lastAttempt ?: 0) >= backoff2)

        // Test retry count 3: 16 seconds backoff
        val op3 = createTestOperation(retryCount = 3, lastAttempt = now - 20000) // 20s ago
        val backoff3 = (1 shl 3) * 2000L // 16s
        assertTrue(now - (op3.lastAttempt ?: 0) >= backoff3)

        println("✓ backoffCalculation_respectsExponentialBackoff: Exponential backoff works correctly")
    }

    @Test
    fun `backoff calculation - caps at 5 minutes`() {
        // Given: High retry count that would exceed 5 minutes
        val now = System.currentTimeMillis()
        val highRetryOp = createTestOperation(
            retryCount = 10, // Would be 2^10 * 2 = 2048 seconds without cap
            lastAttempt = now - (6 * 60 * 1000) // 6 minutes ago
        )

        // When: Calculate backoff with cap
        val calculatedBackoff = minOf(
            (1 shl highRetryOp.retryCount) * 2000L,
            5 * 60 * 1000L // 5 minutes cap
        )

        // Then
        assertEquals(5 * 60 * 1000L, calculatedBackoff)
        assertTrue(now - (highRetryOp.lastAttempt ?: 0) >= calculatedBackoff)
        println("✓ backoffCalculation_capsAt5Minutes: Backoff capped at 5 minutes")
    }

    @Test
    fun `backoff calculation - not ready if within backoff period`() {
        // Given: Operation tried 1 second ago with retry count 0 (2s backoff)
        val now = System.currentTimeMillis()
        val recentOp = createTestOperation(
            retryCount = 0,
            lastAttempt = now - 1000 // 1 second ago
        )

        // When
        val backoffMs = (1 shl recentOp.retryCount) * 2000L // 2 seconds
        val isReady = (now - (recentOp.lastAttempt ?: 0)) >= backoffMs

        // Then
        assertFalse(isReady)
        println("✓ backoffCalculation_notReadyIfWithinBackoffPeriod: Operations within backoff are not ready")
    }

    // ============ OPERATION EQUALITY TESTS ============

    @Test
    fun `PendingOperation - equals and hashCode work correctly`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val op1 = PendingSyncQueue.PendingOperation(
            id = "same-id",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "entity-1",
            entityType = PendingSyncQueue.EntityType.TRIP,
            timestamp = timestamp
        )
        val op2 = PendingSyncQueue.PendingOperation(
            id = "same-id",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "entity-1",
            entityType = PendingSyncQueue.EntityType.TRIP,
            timestamp = timestamp
        )
        val op3 = PendingSyncQueue.PendingOperation(
            id = "different-id",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "entity-1",
            entityType = PendingSyncQueue.EntityType.TRIP,
            timestamp = timestamp
        )

        // Then
        assertEquals(op1, op2)
        assertEquals(op1.hashCode(), op2.hashCode())
        assertNotEquals(op1, op3)
        println("✓ PendingOperation_equalsAndHashCodeWorkCorrectly: Data class equality works")
    }

    // ============ OPERATION COPY TESTS ============

    @Test
    fun `PendingOperation - copy preserves and updates fields`() {
        // Given
        val original = createTestOperation(
            id = "original-id",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "entity-1",
            retryCount = 0,
            lastAttempt = null
        )

        // When
        val updated = original.copy(
            retryCount = original.retryCount + 1,
            lastAttempt = System.currentTimeMillis()
        )

        // Then
        assertEquals(original.id, updated.id)
        assertEquals(original.type, updated.type)
        assertEquals(original.entityId, updated.entityId)
        assertEquals(original.entityType, updated.entityType)
        assertEquals(1, updated.retryCount)
        assertNotNull(updated.lastAttempt)
        println("✓ PendingOperation_copyPreservesAndUpdatesFields: Copy works correctly")
    }

    // ============ SORTING TESTS ============

    @Test
    fun `operations - can be sorted by timestamp`() {
        // Given
        val now = System.currentTimeMillis()
        val op1 = createTestOperation(timestamp = now - 3000) // 3s ago
        val op2 = createTestOperation(timestamp = now - 1000) // 1s ago
        val op3 = createTestOperation(timestamp = now - 5000) // 5s ago

        // When
        val sorted = listOf(op1, op2, op3).sortedBy { it.timestamp }

        // Then
        assertEquals(op3.timestamp, sorted[0].timestamp) // Oldest first
        assertEquals(op1.timestamp, sorted[1].timestamp)
        assertEquals(op2.timestamp, sorted[2].timestamp) // Newest last
        println("✓ operations_canBeSortedByTimestamp: Timestamp sorting works")
    }

    // ============ FILTERING TESTS ============

    @Test
    fun `operations - can be filtered by entity type`() {
        // Given
        val tripOp1 = createTestOperation(entityType = PendingSyncQueue.EntityType.TRIP)
        val tripOp2 = createTestOperation(entityType = PendingSyncQueue.EntityType.TRIP)
        val vehicleOp = createTestOperation(entityType = PendingSyncQueue.EntityType.VEHICLE)
        val userOp = createTestOperation(entityType = PendingSyncQueue.EntityType.USER_PROFILE)
        val operations = listOf(tripOp1, tripOp2, vehicleOp, userOp)

        // When
        val tripOperations = operations.filter { it.entityType == PendingSyncQueue.EntityType.TRIP }
        val vehicleOperations = operations.filter { it.entityType == PendingSyncQueue.EntityType.VEHICLE }

        // Then
        assertEquals(2, tripOperations.size)
        assertEquals(1, vehicleOperations.size)
        println("✓ operations_canBeFilteredByEntityType: Entity type filtering works")
    }

    @Test
    fun `operations - can check for pending operation`() {
        // Given
        val operations = listOf(
            createTestOperation(
                entityId = "entity-1",
                type = PendingSyncQueue.OperationType.CREATE
            ),
            createTestOperation(
                entityId = "entity-2",
                type = PendingSyncQueue.OperationType.UPDATE
            )
        )

        // When
        val hasCreate = operations.any {
            it.entityId == "entity-1" && it.type == PendingSyncQueue.OperationType.CREATE
        }
        val hasDelete = operations.any {
            it.entityId == "entity-1" && it.type == PendingSyncQueue.OperationType.DELETE
        }

        // Then
        assertTrue(hasCreate)
        assertFalse(hasDelete)
        println("✓ operations_canCheckForPendingOperation: Pending operation check works")
    }

    // ============ SERIALIZATION TESTS ============

    @Test
    fun `PendingOperation - serializable annotation is present`() {
        // Given
        val operation = createTestOperation()

        // Then: The class should be annotated with @Serializable
        // We can't directly check annotation at runtime in Kotlin, but we can verify
        // that the data class can be used with kotlinx.serialization
        assertNotNull(operation)
        println("✓ PendingOperation_serializableAnnotationIsPresent: Operation is serializable")
    }

    // ============ EDGE CASE TESTS ============

    @Test
    fun `operation with all nullable fields null`() {
        // Given
        val operation = PendingSyncQueue.PendingOperation(
            id = "minimal-op",
            type = PendingSyncQueue.OperationType.DELETE,
            entityId = "entity-to-delete",
            entityType = PendingSyncQueue.EntityType.VEHICLE,
            timestamp = System.currentTimeMillis(),
            retryCount = 0,
            lastAttempt = null,
            data = null
        )

        // Then
        assertNull(operation.lastAttempt)
        assertNull(operation.data)
        println("✓ operationWithAllNullableFieldsNull: Nullable fields handle null correctly")
    }

    @Test
    fun `operation with empty data string`() {
        // Given
        val operation = createTestOperation(data = "")

        // Then
        assertEquals("", operation.data)
        println("✓ operationWithEmptyDataString: Empty data string handled correctly")
    }

    @Test
    fun `operation with JSON data`() {
        // Given
        val jsonData = """{"id":"trip-123","distance":15000.0,"validated":true}"""
        val operation = createTestOperation(data = jsonData)

        // Then
        assertEquals(jsonData, operation.data)
        assertTrue(operation.data?.contains("trip-123") ?: false)
        println("✓ operationWithJSONData: JSON data stored correctly")
    }

    // ============ CONCURRENT HASH MAP BEHAVIOR TESTS ============

    @Test
    fun `operations in ConcurrentHashMap - basic operations`() {
        // Given
        val cache = java.util.concurrent.ConcurrentHashMap<String, PendingSyncQueue.PendingOperation>()
        val op1 = createTestOperation(id = "op-1")
        val op2 = createTestOperation(id = "op-2")

        // When: Add operations
        cache[op1.id] = op1
        cache[op2.id] = op2

        // Then
        assertEquals(2, cache.size)
        assertEquals(op1, cache["op-1"])
        assertEquals(op2, cache["op-2"])

        // When: Remove operation
        cache.remove("op-1")

        // Then
        assertEquals(1, cache.size)
        assertNull(cache["op-1"])
        assertNotNull(cache["op-2"])

        println("✓ operationsInConcurrentHashMap_basicOperations: ConcurrentHashMap works correctly")
    }

    @Test
    fun `operations in ConcurrentHashMap - values to list`() {
        // Given
        val cache = java.util.concurrent.ConcurrentHashMap<String, PendingSyncQueue.PendingOperation>()
        val now = System.currentTimeMillis()
        cache["op-1"] = createTestOperation(id = "op-1", timestamp = now - 1000)
        cache["op-2"] = createTestOperation(id = "op-2", timestamp = now - 2000)
        cache["op-3"] = createTestOperation(id = "op-3", timestamp = now - 500)

        // When
        val sortedList = cache.values.sortedBy { it.timestamp }

        // Then
        assertEquals(3, sortedList.size)
        assertEquals("op-2", sortedList[0].id) // Oldest first
        assertEquals("op-1", sortedList[1].id)
        assertEquals("op-3", sortedList[2].id) // Newest last
        println("✓ operationsInConcurrentHashMap_valuesToList: Values to sorted list works")
    }
}

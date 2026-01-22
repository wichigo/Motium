package com.application.motium.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.PendingOperationEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for PendingOperationDao.
 *
 * These tests validate the sync queue operations, particularly:
 * - Basic CRUD operations
 * - Atomic replaceOperation() behavior with @Transaction
 * - Concurrent access scenarios that would cause race conditions without @Transaction
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PendingOperationDaoTest {

    private lateinit var database: MotiumDatabase
    private lateinit var dao: PendingOperationDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MotiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pendingOperationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== BASIC OPERATIONS ====================

    @Test
    fun `insert and retrieve operation`() = runTest {
        // Given
        val operation = createTestOperation("entity-1", "TRIP", "CREATE")

        // When
        dao.insert(operation)
        val result = dao.getAll()

        // Then
        assertEquals(1, result.size)
        assertEquals(operation.id, result[0].id)
        assertEquals("TRIP", result[0].entityType)
        assertEquals("CREATE", result[0].action)
    }

    @Test
    fun `delete operation by entity`() = runTest {
        // Given
        val operation = createTestOperation("entity-1", "TRIP", "CREATE")
        dao.insert(operation)

        // When
        dao.deleteByEntity("entity-1", "TRIP")
        val result = dao.getAll()

        // Then
        assertEquals(0, result.size)
    }

    @Test
    fun `getCount returns correct count`() = runTest {
        // Given
        dao.insert(createTestOperation("e1", "TRIP", "CREATE"))
        dao.insert(createTestOperation("e2", "TRIP", "UPDATE"))
        dao.insert(createTestOperation("e3", "VEHICLE", "DELETE"))

        // When
        val count = dao.getCount()

        // Then
        assertEquals(3, count)
    }

    // ==================== REPLACE OPERATION (ATOMIC) ====================

    @Test
    fun `replaceOperation removes existing and inserts new`() = runTest {
        // Given - existing operation for entity-1
        val oldOperation = createTestOperation("entity-1", "TRIP", "CREATE")
        dao.insert(oldOperation)

        // When - replace with new operation
        val newOperation = createTestOperation("entity-1", "TRIP", "UPDATE")
        dao.replaceOperation("entity-1", "TRIP", newOperation)

        // Then - only new operation exists
        val result = dao.getByEntity("entity-1", "TRIP")
        assertEquals(1, result.size)
        assertEquals(newOperation.id, result[0].id)
        assertEquals("UPDATE", result[0].action)
    }

    @Test
    fun `replaceOperation works when no existing operation`() = runTest {
        // Given - no existing operation

        // When
        val operation = createTestOperation("entity-new", "TRIP", "CREATE")
        dao.replaceOperation("entity-new", "TRIP", operation)

        // Then
        val result = dao.getByEntity("entity-new", "TRIP")
        assertEquals(1, result.size)
        assertEquals("CREATE", result[0].action)
    }

    @Test
    fun `replaceOperation preserves operations for other entities`() = runTest {
        // Given - operations for multiple entities
        dao.insert(createTestOperation("entity-1", "TRIP", "CREATE"))
        dao.insert(createTestOperation("entity-2", "TRIP", "CREATE"))
        dao.insert(createTestOperation("entity-3", "VEHICLE", "UPDATE"))

        // When - replace only entity-1
        val newOp = createTestOperation("entity-1", "TRIP", "DELETE")
        dao.replaceOperation("entity-1", "TRIP", newOp)

        // Then - entity-2 and entity-3 still exist
        assertEquals(3, dao.getCount())
        assertEquals(1, dao.getByEntity("entity-2", "TRIP").size)
        assertEquals(1, dao.getByEntity("entity-3", "VEHICLE").size)
    }

    // ==================== CONCURRENT ACCESS (@Transaction validation) ====================

    /**
     * This test validates that @Transaction prevents race conditions.
     *
     * Without @Transaction, concurrent calls to replaceOperation could result in:
     * - Thread A: deleteByEntity("entity-1")
     * - Thread B: deleteByEntity("entity-1") -- no-op
     * - Thread A: insert(opA)
     * - Thread B: insert(opB) -- DUPLICATE!
     *
     * With @Transaction, only ONE operation should exist after concurrent calls.
     */
    @Test
    fun `concurrent replaceOperation calls result in single operation`() = runBlocking {
        // Given - initial operation
        dao.insert(createTestOperation("concurrent-entity", "TRIP", "INITIAL"))

        // When - 10 concurrent replace operations
        val jobs = (1..10).map { i ->
            async {
                val op = createTestOperation("concurrent-entity", "TRIP", "UPDATE_$i")
                dao.replaceOperation("concurrent-entity", "TRIP", op)
            }
        }
        jobs.awaitAll()

        // Then - exactly ONE operation should exist (not 10 duplicates)
        val result = dao.getByEntity("concurrent-entity", "TRIP")
        assertEquals(
            1,
            result.size,
            "Expected exactly 1 operation after concurrent replaces, but found ${result.size}. " +
            "This indicates @Transaction is working correctly to prevent race conditions."
        )
    }

    /**
     * Stress test: Many concurrent operations on different entities.
     * Validates that @Transaction doesn't cause deadlocks.
     */
    @Test
    fun `concurrent replaceOperation on different entities succeeds`() = runBlocking {
        // When - 50 concurrent operations on 10 different entities
        val jobs = (1..50).map { i ->
            async {
                val entityId = "entity-${i % 10}"
                val op = createTestOperation(entityId, "TRIP", "OP_$i")
                dao.replaceOperation(entityId, "TRIP", op)
            }
        }
        jobs.awaitAll()

        // Then - exactly 10 entities should have operations
        val count = dao.getCount()
        assertEquals(10, count, "Expected 10 unique entities, but found $count")
    }

    // ==================== RETRY LOGIC ====================

    @Test
    fun `markRetried increments retry count and sets error`() = runTest {
        // Given
        val operation = createTestOperation("entity-1", "TRIP", "CREATE")
        dao.insert(operation)

        // When
        val timestamp = System.currentTimeMillis()
        dao.markRetried(operation.id, timestamp, "Network error")

        // Then
        val updated = dao.getByEntity("entity-1", "TRIP")[0]
        assertEquals(1, updated.retryCount)
        assertEquals(timestamp, updated.lastAttemptAt)
        assertEquals("Network error", updated.lastError)
    }

    @Test
    fun `getFailedOperations returns operations with retryCount >= 5`() = runTest {
        // Given
        val op1 = createTestOperation("e1", "TRIP", "CREATE")
        val op2 = createTestOperation("e2", "TRIP", "CREATE")
        dao.insert(op1)
        dao.insert(op2)

        // Simulate 5 retries on op1
        repeat(5) {
            dao.markRetried(op1.id, System.currentTimeMillis(), "Error $it")
        }

        // When
        val failed = dao.getFailedOperations()

        // Then
        assertEquals(1, failed.size)
        assertEquals(op1.id, failed[0].id)
        assertTrue(failed[0].retryCount >= 5)
    }

    // ==================== HELPER FUNCTIONS ====================

    private var operationCounter = 0

    private fun createTestOperation(
        entityId: String,
        entityType: String,
        action: String,
        payload: String? = null,
        priority: Int = 0
    ): PendingOperationEntity {
        return PendingOperationEntity(
            id = "op-${++operationCounter}-${System.nanoTime()}",
            entityType = entityType,
            entityId = entityId,
            action = action,
            payload = payload,
            createdAt = System.currentTimeMillis(),
            priority = priority
        )
    }
}

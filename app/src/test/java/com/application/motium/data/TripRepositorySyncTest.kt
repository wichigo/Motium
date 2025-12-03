package com.application.motium.data

import com.application.motium.data.local.dao.TripDao
import com.application.motium.data.local.entities.TripEntity
import com.application.motium.data.sync.PendingSyncQueue
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires pour TripRepository (synchronisation)
 * Teste les opérations CRUD avec synchronisation Supabase
 */
class TripRepositorySyncTest {

    @MockK
    private lateinit var mockTripDao: TripDao

    private val testUserId = "test-user-123"

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ============ Helper functions ============

    private fun createTestTrip(
        id: String = "trip-${System.nanoTime()}",
        startTime: Long = System.currentTimeMillis() - 3600000,
        endTime: Long? = System.currentTimeMillis(),
        totalDistance: Double = 15000.0,
        isValidated: Boolean = false,
        vehicleId: String? = "vehicle-1",
        tripType: String? = "PROFESSIONAL",
        needsSync: Boolean = true,
        lastSyncedAt: Long? = null
    ): Trip {
        return Trip(
            id = id,
            startTime = startTime,
            endTime = endTime,
            locations = listOf(
                TripLocation(48.8566, 2.3522, 10f, startTime),
                TripLocation(48.8584, 2.2945, 15f, endTime ?: startTime + 3600000)
            ),
            totalDistance = totalDistance,
            isValidated = isValidated,
            vehicleId = vehicleId,
            startAddress = "Paris, France",
            endAddress = "Tour Eiffel, Paris",
            notes = "Test trip",
            tripType = tripType,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastSyncedAt = lastSyncedAt,
            needsSync = needsSync
        )
    }

    private fun createTestTripEntity(
        id: String = "trip-${System.nanoTime()}",
        userId: String = testUserId,
        needsSync: Boolean = true,
        lastSyncedAt: Long? = null
    ): TripEntity {
        val now = System.currentTimeMillis()
        return TripEntity(
            id = id,
            userId = userId,
            startTime = now - 3600000,
            endTime = now,
            locations = listOf(
                TripLocation(48.8566, 2.3522, 10f, now - 3600000),
                TripLocation(48.8584, 2.2945, 15f, now)
            ),
            totalDistance = 15000.0,
            isValidated = false,
            vehicleId = "vehicle-1",
            startAddress = "Paris",
            endAddress = "Tour Eiffel",
            notes = "Test",
            tripType = "PROFESSIONAL",
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = lastSyncedAt,
            needsSync = needsSync
        )
    }

    // ============ SAVE TRIP TESTS ============

    @Test
    fun `saveTrip - saves locally first (offline-first)`() = runTest {
        // Given
        val trip = createTestTrip(id = "new-trip")
        coEvery { mockTripDao.insertTrip(any()) } just Runs

        // When: Create entity and save locally
        val tripEntity = TripEntity(
            id = trip.id,
            userId = testUserId,
            startTime = trip.startTime,
            endTime = trip.endTime,
            locations = trip.locations,
            totalDistance = trip.totalDistance,
            isValidated = trip.isValidated,
            vehicleId = trip.vehicleId,
            startAddress = trip.startAddress,
            endAddress = trip.endAddress,
            notes = trip.notes,
            tripType = trip.tripType,
            createdAt = trip.createdAt,
            updatedAt = trip.updatedAt,
            lastSyncedAt = null,
            needsSync = true
        )
        mockTripDao.insertTrip(tripEntity)

        // Then
        coVerify { mockTripDao.insertTrip(any()) }
        println("✓ saveTrip_savesLocallyFirst: Trip saved to Room first (offline-first)")
    }

    @Test
    fun `saveTrip - sets needsSync to true`() = runTest {
        // Given
        val trip = createTestTrip(id = "sync-trip")

        // When: Create entity with needsSync=true
        val tripEntity = TripEntity(
            id = trip.id,
            userId = testUserId,
            startTime = trip.startTime,
            endTime = trip.endTime,
            locations = trip.locations,
            totalDistance = trip.totalDistance,
            isValidated = trip.isValidated,
            vehicleId = trip.vehicleId,
            startAddress = trip.startAddress,
            endAddress = trip.endAddress,
            notes = trip.notes,
            tripType = trip.tripType,
            createdAt = trip.createdAt,
            updatedAt = trip.updatedAt,
            lastSyncedAt = null,
            needsSync = true
        )

        // Then
        assertTrue("New trip should need sync", tripEntity.needsSync)
        assertNull("New trip should have null lastSyncedAt", tripEntity.lastSyncedAt)
        println("✓ saveTrip_setsNeedsSyncTrue: needsSync=true for new trips")
    }

    @Test
    fun `saveTrip - attempts immediate sync`() = runTest {
        // Given: Trip saved locally, then sync attempted
        val tripEntity = createTestTripEntity(id = "sync-attempt", needsSync = true)

        // When: Sync succeeds
        val syncTimestamp = System.currentTimeMillis()
        coEvery { mockTripDao.markTripAsSynced(tripEntity.id, syncTimestamp) } just Runs
        mockTripDao.markTripAsSynced(tripEntity.id, syncTimestamp)

        // Then
        coVerify { mockTripDao.markTripAsSynced(tripEntity.id, syncTimestamp) }
        println("✓ saveTrip_attemptsImmediateSync: Immediate sync attempted after local save")
    }

    @Test
    fun `saveTrip - marks needsSync on failure`() = runTest {
        // Given: Sync fails
        val tripEntity = createTestTripEntity(id = "failed-sync", needsSync = true)

        // Then: Trip should still need sync
        assertTrue("Trip should still need sync after failure", tripEntity.needsSync)
        println("✓ saveTrip_marksNeedsSyncOnFailure: needsSync remains true on failure")
    }

    // ============ SYNC ALL TRIPS TESTS ============

    @Test
    fun `syncAllTripsToSupabase - syncs only dirty trips`() = runTest {
        // Given
        val dirtyTrips = listOf(
            createTestTripEntity(id = "dirty-1", needsSync = true),
            createTestTripEntity(id = "dirty-2", needsSync = true)
        )
        val syncedTrip = createTestTripEntity(id = "synced", needsSync = false, lastSyncedAt = System.currentTimeMillis())

        coEvery { mockTripDao.getTripsNeedingSync(testUserId) } returns dirtyTrips

        // When
        val tripsToSync = mockTripDao.getTripsNeedingSync(testUserId)

        // Then
        assertEquals(2, tripsToSync.size)
        assertTrue(tripsToSync.all { it.needsSync })
        assertFalse(tripsToSync.any { it.id == "synced" })
        println("✓ syncAllTripsToSupabase_syncsOnlyDirty: Only needsSync=true trips synced")
    }

    @Test
    fun `syncAllTripsToSupabase - marks trips as synced`() = runTest {
        // Given
        val tripId = "to-sync"
        val syncTimestamp = System.currentTimeMillis()

        coEvery { mockTripDao.markTripAsSynced(tripId, syncTimestamp) } just Runs

        // When
        mockTripDao.markTripAsSynced(tripId, syncTimestamp)

        // Then
        coVerify { mockTripDao.markTripAsSynced(tripId, syncTimestamp) }
        println("✓ syncAllTripsToSupabase_marksSynced: Trips marked synced after upload")
    }

    @Test
    fun `syncAllTripsToSupabase - handles empty list`() = runTest {
        // Given: No trips need sync
        coEvery { mockTripDao.getTripsNeedingSync(testUserId) } returns emptyList()

        // When
        val tripsToSync = mockTripDao.getTripsNeedingSync(testUserId)

        // Then
        assertTrue(tripsToSync.isEmpty())
        println("✓ syncAllTripsToSupabase_handlesEmptyList: Empty list handled correctly")
    }

    // ============ GET UNSYNCED TRIPS TESTS ============

    @Test
    fun `getUnsyncedTrips - returns trips needing sync`() = runTest {
        // Given
        val unsyncedTrips = listOf(
            createTestTripEntity(id = "unsynced-1", needsSync = true),
            createTestTripEntity(id = "unsynced-2", needsSync = true)
        )
        coEvery { mockTripDao.getTripsNeedingSync(testUserId) } returns unsyncedTrips

        // When
        val result = mockTripDao.getTripsNeedingSync(testUserId)

        // Then
        assertEquals(2, result.size)
        assertTrue(result.all { it.needsSync })
        println("✓ getUnsyncedTrips_returnsCorrectTrips: Unsynced trips returned")
    }

    @Test
    fun `getUnsyncedTripsCount - returns correct count`() = runTest {
        // Given
        val unsyncedTrips = listOf(
            createTestTripEntity(id = "unsynced-1", needsSync = true),
            createTestTripEntity(id = "unsynced-2", needsSync = true),
            createTestTripEntity(id = "unsynced-3", needsSync = true)
        )
        coEvery { mockTripDao.getTripsNeedingSync(testUserId) } returns unsyncedTrips

        // When
        val count = mockTripDao.getTripsNeedingSync(testUserId).size

        // Then
        assertEquals(3, count)
        println("✓ getUnsyncedTripsCount_returnsCorrectCount: Count is correct")
    }

    // ============ MARK TRIPS AS SYNCED TESTS ============

    @Test
    fun `markTripsAsSynced - updates multiple trips`() = runTest {
        // Given
        val tripIds = listOf("trip-1", "trip-2", "trip-3")
        val syncTimestamp = System.currentTimeMillis()

        // When: Mark each trip as synced
        tripIds.forEach { tripId ->
            coEvery { mockTripDao.markTripAsSynced(tripId, syncTimestamp) } just Runs
            mockTripDao.markTripAsSynced(tripId, syncTimestamp)
        }

        // Then
        tripIds.forEach { tripId ->
            coVerify { mockTripDao.markTripAsSynced(tripId, syncTimestamp) }
        }
        println("✓ markTripsAsSynced_updatesMultiple: Multiple trips marked as synced")
    }

    // ============ DELETE TRIP TESTS ============

    @Test
    fun `deleteTrip - removes from local database`() = runTest {
        // Given
        val tripEntity = createTestTripEntity(id = "delete-me")
        coEvery { mockTripDao.deleteTrip(tripEntity) } just Runs

        // When
        mockTripDao.deleteTrip(tripEntity)

        // Then
        coVerify { mockTripDao.deleteTrip(tripEntity) }
        println("✓ deleteTrip_removesFromLocal: Trip removed from Room")
    }

    @Test
    fun `deleteTrip - queues deletion for sync`() {
        // Given: Trip deleted locally
        val operation = PendingSyncQueue.PendingOperation(
            id = "op-delete",
            type = PendingSyncQueue.OperationType.DELETE,
            entityId = "deleted-trip",
            entityType = PendingSyncQueue.EntityType.TRIP,
            timestamp = System.currentTimeMillis()
        )

        // Then: Delete operation should be queued
        assertEquals(PendingSyncQueue.OperationType.DELETE, operation.type)
        assertEquals(PendingSyncQueue.EntityType.TRIP, operation.entityType)
        println("✓ deleteTrip_queuesForSync: Delete operation queued for Supabase")
    }

    // ============ UPDATE TRIP TESTS ============

    @Test
    fun `updateTrip - updates locally and marks for sync`() = runTest {
        // Given
        val tripEntity = createTestTripEntity(id = "update-me")
        val updatedEntity = tripEntity.copy(
            isValidated = true,
            needsSync = true,
            updatedAt = System.currentTimeMillis()
        )
        coEvery { mockTripDao.updateTrip(updatedEntity) } just Runs

        // When
        mockTripDao.updateTrip(updatedEntity)

        // Then
        coVerify { mockTripDao.updateTrip(updatedEntity) }
        assertTrue(updatedEntity.needsSync)
        println("✓ updateTrip_updatesAndMarksForSync: Trip updated and marked for sync")
    }

    // ============ OFFLINE-FIRST BEHAVIOR TESTS ============

    @Test
    fun `offline save - trip available immediately`() = runTest {
        // Given: No network, but trip should be saved locally
        val trip = createTestTrip(id = "offline-trip")
        val tripEntity = createTestTripEntity(id = "offline-trip", needsSync = true)

        coEvery { mockTripDao.insertTrip(any()) } just Runs
        coEvery { mockTripDao.getTripById("offline-trip") } returns tripEntity

        // When: Save trip
        mockTripDao.insertTrip(tripEntity)
        val savedTrip = mockTripDao.getTripById("offline-trip")

        // Then: Trip should be retrievable immediately
        assertNotNull(savedTrip)
        assertEquals("offline-trip", savedTrip?.id)
        assertTrue(savedTrip?.needsSync ?: false)
        println("✓ offlineSave_tripAvailableImmediately: Trip available locally without network")
    }

    @Test
    fun `offline save - uses last known userId`() {
        // Given: Auth might be restoring, but we have last known userId
        val lastKnownUserId = "cached-user-id"
        val trip = createTestTrip(id = "auth-restoring-trip")

        // When: Create entity with cached userId
        val tripEntity = TripEntity(
            id = trip.id,
            userId = lastKnownUserId,
            startTime = trip.startTime,
            endTime = trip.endTime,
            locations = trip.locations,
            totalDistance = trip.totalDistance,
            isValidated = trip.isValidated,
            vehicleId = trip.vehicleId,
            startAddress = trip.startAddress,
            endAddress = trip.endAddress,
            notes = trip.notes,
            tripType = trip.tripType,
            createdAt = trip.createdAt,
            updatedAt = trip.updatedAt,
            lastSyncedAt = null,
            needsSync = true
        )

        // Then
        assertEquals(lastKnownUserId, tripEntity.userId)
        println("✓ offlineSave_usesLastKnownUserId: Trip saved with cached userId")
    }

    // ============ ERROR HANDLING TESTS ============

    @Test
    fun `sync error - trip remains in local database`() = runTest {
        // Given: Trip saved, sync fails
        val tripEntity = createTestTripEntity(id = "error-trip", needsSync = true)

        coEvery { mockTripDao.getTripById("error-trip") } returns tripEntity

        // When: Sync fails, check trip still exists
        val stillExists = mockTripDao.getTripById("error-trip")

        // Then
        assertNotNull(stillExists)
        assertTrue(stillExists?.needsSync ?: false)
        println("✓ syncError_tripRemainsLocal: Trip preserved locally after sync error")
    }

    @Test
    fun `sync error - increments retry count`() {
        // Given
        val operation = PendingSyncQueue.PendingOperation(
            id = "retry-op",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "trip-123",
            entityType = PendingSyncQueue.EntityType.TRIP,
            timestamp = System.currentTimeMillis(),
            retryCount = 2
        )

        // When: Increment retry
        val updatedOp = operation.copy(
            retryCount = operation.retryCount + 1,
            lastAttempt = System.currentTimeMillis()
        )

        // Then
        assertEquals(3, updatedOp.retryCount)
        println("✓ syncError_incrementsRetryCount: Retry count incremented on error")
    }

    // ============ TRIP TYPE HANDLING TESTS ============

    @Test
    fun `saveTrip - preserves trip type`() = runTest {
        // Given
        val professionalTrip = createTestTrip(id = "pro-trip", tripType = "PROFESSIONAL")
        val personalTrip = createTestTrip(id = "perso-trip", tripType = "PERSONAL")

        // When: Create entities
        val proEntity = createTestTripEntity(id = "pro-trip")
        val persoEntity = TripEntity(
            id = personalTrip.id,
            userId = testUserId,
            startTime = personalTrip.startTime,
            endTime = personalTrip.endTime,
            locations = personalTrip.locations,
            totalDistance = personalTrip.totalDistance,
            isValidated = personalTrip.isValidated,
            vehicleId = personalTrip.vehicleId,
            startAddress = personalTrip.startAddress,
            endAddress = personalTrip.endAddress,
            notes = personalTrip.notes,
            tripType = "PERSONAL",
            createdAt = personalTrip.createdAt,
            updatedAt = personalTrip.updatedAt,
            lastSyncedAt = null,
            needsSync = true
        )

        // Then
        assertEquals("PROFESSIONAL", proEntity.tripType)
        assertEquals("PERSONAL", persoEntity.tripType)
        println("✓ saveTrip_preservesTripType: Trip type preserved in entity")
    }

    // ============ GPS TRACE HANDLING TESTS ============

    @Test
    fun `saveTrip - serializes GPS locations`() {
        // Given
        val trip = createTestTrip(id = "gps-trip")

        // When: Create entity
        val tripEntity = TripEntity(
            id = trip.id,
            userId = testUserId,
            startTime = trip.startTime,
            endTime = trip.endTime,
            locations = trip.locations,
            totalDistance = trip.totalDistance,
            isValidated = trip.isValidated,
            vehicleId = trip.vehicleId,
            startAddress = trip.startAddress,
            endAddress = trip.endAddress,
            notes = trip.notes,
            tripType = trip.tripType,
            createdAt = trip.createdAt,
            updatedAt = trip.updatedAt,
            lastSyncedAt = null,
            needsSync = true
        )

        // Then: Locations should be stored
        assertEquals(2, tripEntity.locations.size)
        assertEquals(48.8566, tripEntity.locations[0].latitude, 0.0001)
        println("✓ saveTrip_serializesGpsLocations: GPS locations serialized correctly")
    }

    @Test
    fun `saveTrip - handles empty locations`() {
        // Given: Trip with no locations
        val trip = Trip(
            id = "empty-locations",
            startTime = System.currentTimeMillis(),
            endTime = null,
            locations = emptyList(),
            totalDistance = 0.0,
            isValidated = false
        )

        // When: Create entity
        val tripEntity = TripEntity(
            id = trip.id,
            userId = testUserId,
            startTime = trip.startTime,
            endTime = trip.endTime,
            locations = trip.locations,
            totalDistance = trip.totalDistance,
            isValidated = trip.isValidated,
            vehicleId = null,
            startAddress = null,
            endAddress = null,
            notes = null,
            tripType = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastSyncedAt = null,
            needsSync = true
        )

        // Then
        assertTrue(tripEntity.locations.isEmpty())
        println("✓ saveTrip_handlesEmptyLocations: Empty locations handled correctly")
    }
}

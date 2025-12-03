package com.application.motium.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.application.motium.data.TripLocation
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.TripEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitaires pour TripDao
 * Teste les opérations CRUD et les requêtes de synchronisation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class TripDaoTest {

    private lateinit var database: MotiumDatabase
    private lateinit var tripDao: TripDao

    private val testUserId = "test-user-123"
    private val otherUserId = "other-user-456"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MotiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tripDao = database.tripDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ============ Helper functions ============

    private fun createTestTrip(
        id: String = "trip-${System.nanoTime()}",
        userId: String = testUserId,
        startTime: Long = System.currentTimeMillis(),
        endTime: Long? = System.currentTimeMillis() + 3600000,
        totalDistance: Double = 15000.0,
        isValidated: Boolean = false,
        needsSync: Boolean = true,
        lastSyncedAt: Long? = null,
        vehicleId: String? = "vehicle-1",
        tripType: String? = "PROFESSIONAL"
    ): TripEntity {
        return TripEntity(
            id = id,
            userId = userId,
            startTime = startTime,
            endTime = endTime,
            locations = listOf(
                TripLocation(48.8566, 2.3522, 10f, startTime),
                TripLocation(48.8584, 2.2945, 15f, startTime + 1800000)
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

    // ============ INSERT TESTS ============

    @Test
    fun `insertTrip - inserts correctly and can be retrieved`() = runTest {
        // Given
        val trip = createTestTrip(id = "trip-1")

        // When
        tripDao.insertTrip(trip)
        val retrieved = tripDao.getTripById("trip-1")

        // Then
        assertNotNull(retrieved)
        assertEquals("trip-1", retrieved?.id)
        assertEquals(testUserId, retrieved?.userId)
        assertEquals(15000.0, retrieved?.totalDistance ?: 0.0, 0.01)
        println("✓ insertTrip_insertsCorrectly: Trip inserted and retrieved successfully")
    }

    @Test
    fun `insertTrip - replaces on conflict with same ID`() = runTest {
        // Given
        val originalTrip = createTestTrip(id = "trip-replace", totalDistance = 10000.0)
        val updatedTrip = createTestTrip(id = "trip-replace", totalDistance = 25000.0)

        // When
        tripDao.insertTrip(originalTrip)
        tripDao.insertTrip(updatedTrip)
        val retrieved = tripDao.getTripById("trip-replace")

        // Then
        assertNotNull(retrieved)
        assertEquals(25000.0, retrieved?.totalDistance ?: 0.0, 0.01)
        println("✓ insertTrip_replacesOnConflict: Trip replaced correctly on conflict")
    }

    @Test
    fun `insertTrips - batch insert works correctly`() = runTest {
        // Given
        val trips = listOf(
            createTestTrip(id = "batch-1"),
            createTestTrip(id = "batch-2"),
            createTestTrip(id = "batch-3")
        )

        // When
        tripDao.insertTrips(trips)
        val allTrips = tripDao.getTripsForUser(testUserId)

        // Then
        assertEquals(3, allTrips.size)
        assertTrue(allTrips.any { it.id == "batch-1" })
        assertTrue(allTrips.any { it.id == "batch-2" })
        assertTrue(allTrips.any { it.id == "batch-3" })
        println("✓ insertTrips_batchInsertWorks: Batch insert successful")
    }

    // ============ UPDATE TESTS ============

    @Test
    fun `updateTrip - updates all fields correctly`() = runTest {
        // Given
        val trip = createTestTrip(id = "trip-update", totalDistance = 10000.0, isValidated = false)
        tripDao.insertTrip(trip)

        // When
        val updatedTrip = trip.copy(
            totalDistance = 20000.0,
            isValidated = true,
            notes = "Updated notes",
            updatedAt = System.currentTimeMillis()
        )
        tripDao.updateTrip(updatedTrip)
        val retrieved = tripDao.getTripById("trip-update")

        // Then
        assertNotNull(retrieved)
        assertEquals(20000.0, retrieved?.totalDistance ?: 0.0, 0.01)
        assertTrue(retrieved?.isValidated ?: false)
        assertEquals("Updated notes", retrieved?.notes)
        println("✓ updateTrip_updatesAllFields: Trip updated correctly")
    }

    // ============ DELETE TESTS ============

    @Test
    fun `deleteTrip - removes trip from database`() = runTest {
        // Given
        val trip = createTestTrip(id = "trip-delete")
        tripDao.insertTrip(trip)

        // When
        tripDao.deleteTrip(trip)
        val retrieved = tripDao.getTripById("trip-delete")

        // Then
        assertNull(retrieved)
        println("✓ deleteTrip_removesTripFromDb: Trip deleted successfully")
    }

    @Test
    fun `deleteTripById - removes correct trip`() = runTest {
        // Given
        val trip1 = createTestTrip(id = "trip-keep")
        val trip2 = createTestTrip(id = "trip-remove")
        tripDao.insertTrips(listOf(trip1, trip2))

        // When
        tripDao.deleteTripById("trip-remove")
        val kept = tripDao.getTripById("trip-keep")
        val removed = tripDao.getTripById("trip-remove")

        // Then
        assertNotNull(kept)
        assertNull(removed)
        println("✓ deleteTripById_removesCorrectTrip: Correct trip deleted")
    }

    @Test
    fun `deleteAllTripsForUser - removes only user trips`() = runTest {
        // Given
        val userTrip1 = createTestTrip(id = "user-trip-1", userId = testUserId)
        val userTrip2 = createTestTrip(id = "user-trip-2", userId = testUserId)
        val otherTrip = createTestTrip(id = "other-trip", userId = otherUserId)
        tripDao.insertTrips(listOf(userTrip1, userTrip2, otherTrip))

        // When
        tripDao.deleteAllTripsForUser(testUserId)
        val testUserTrips = tripDao.getTripsForUser(testUserId)
        val otherUserTrips = tripDao.getTripsForUser(otherUserId)

        // Then
        assertEquals(0, testUserTrips.size)
        assertEquals(1, otherUserTrips.size)
        println("✓ deleteAllTripsForUser_removesOnlyUserTrips: User isolation works")
    }

    @Test
    fun `deleteAllTrips - clears entire table`() = runTest {
        // Given
        val trip1 = createTestTrip(id = "trip-1", userId = testUserId)
        val trip2 = createTestTrip(id = "trip-2", userId = otherUserId)
        tripDao.insertTrips(listOf(trip1, trip2))

        // When
        tripDao.deleteAllTrips()
        val allTrips1 = tripDao.getTripsForUser(testUserId)
        val allTrips2 = tripDao.getTripsForUser(otherUserId)

        // Then
        assertEquals(0, allTrips1.size)
        assertEquals(0, allTrips2.size)
        println("✓ deleteAllTrips_clearsTable: All trips deleted")
    }

    // ============ QUERY TESTS ============

    @Test
    fun `getTripsForUser - returns sorted by startTime descending`() = runTest {
        // Given
        val now = System.currentTimeMillis()
        val oldTrip = createTestTrip(id = "old-trip", startTime = now - 3600000)
        val newTrip = createTestTrip(id = "new-trip", startTime = now)
        val midTrip = createTestTrip(id = "mid-trip", startTime = now - 1800000)
        tripDao.insertTrips(listOf(oldTrip, newTrip, midTrip))

        // When
        val trips = tripDao.getTripsForUser(testUserId)

        // Then
        assertEquals(3, trips.size)
        assertEquals("new-trip", trips[0].id)
        assertEquals("mid-trip", trips[1].id)
        assertEquals("old-trip", trips[2].id)
        println("✓ getTripsForUser_returnsSortedByStartTime: Trips sorted correctly")
    }

    @Test
    fun `getTripById - returns correct trip`() = runTest {
        // Given
        val trip = createTestTrip(id = "specific-trip", totalDistance = 12345.0)
        tripDao.insertTrip(trip)

        // When
        val retrieved = tripDao.getTripById("specific-trip")

        // Then
        assertNotNull(retrieved)
        assertEquals(12345.0, retrieved?.totalDistance ?: 0.0, 0.01)
        println("✓ getTripById_returnsCorrectTrip: Correct trip retrieved")
    }

    @Test
    fun `getTripById - returns null for non-existent trip`() = runTest {
        // When
        val retrieved = tripDao.getTripById("non-existent-trip")

        // Then
        assertNull(retrieved)
        println("✓ getTripById_returnsNullForNonExistent: Null returned for missing trip")
    }

    @Test
    fun `getValidatedTrips - filters correctly`() = runTest {
        // Given
        val validatedTrip1 = createTestTrip(id = "validated-1", isValidated = true)
        val validatedTrip2 = createTestTrip(id = "validated-2", isValidated = true)
        val notValidatedTrip = createTestTrip(id = "not-validated", isValidated = false)
        tripDao.insertTrips(listOf(validatedTrip1, validatedTrip2, notValidatedTrip))

        // When
        val validatedTrips = tripDao.getValidatedTrips(testUserId)

        // Then
        assertEquals(2, validatedTrips.size)
        assertTrue(validatedTrips.all { it.isValidated })
        println("✓ getValidatedTrips_filtersCorrectly: Only validated trips returned")
    }

    @Test
    fun `getTripCount - returns correct count`() = runTest {
        // Given
        tripDao.insertTrips(listOf(
            createTestTrip(id = "count-1"),
            createTestTrip(id = "count-2"),
            createTestTrip(id = "count-3")
        ))

        // When
        val count = tripDao.getTripCount(testUserId)

        // Then
        assertEquals(3, count)
        println("✓ getTripCount_returnsCorrectCount: Count is correct")
    }

    // ============ SYNC TESTS ============

    @Test
    fun `getTripsNeedingSync - returns only unsynced trips`() = runTest {
        // Given
        val syncedTrip = createTestTrip(id = "synced", needsSync = false, lastSyncedAt = System.currentTimeMillis())
        val unsyncedTrip1 = createTestTrip(id = "unsynced-1", needsSync = true)
        val unsyncedTrip2 = createTestTrip(id = "unsynced-2", needsSync = true)
        tripDao.insertTrips(listOf(syncedTrip, unsyncedTrip1, unsyncedTrip2))

        // When
        val unsyncedTrips = tripDao.getTripsNeedingSync(testUserId)

        // Then
        assertEquals(2, unsyncedTrips.size)
        assertTrue(unsyncedTrips.all { it.needsSync })
        assertFalse(unsyncedTrips.any { it.id == "synced" })
        println("✓ getTripsNeedingSync_returnsOnlyUnsynced: Filter works correctly")
    }

    @Test
    fun `markTripAsSynced - updates flags correctly`() = runTest {
        // Given
        val trip = createTestTrip(id = "to-sync", needsSync = true, lastSyncedAt = null)
        tripDao.insertTrip(trip)
        val syncTimestamp = System.currentTimeMillis()

        // When
        tripDao.markTripAsSynced("to-sync", syncTimestamp)
        val retrieved = tripDao.getTripById("to-sync")

        // Then
        assertNotNull(retrieved)
        assertFalse(retrieved?.needsSync ?: true)
        assertEquals(syncTimestamp, retrieved?.lastSyncedAt)
        println("✓ markTripAsSynced_updatesFlagsCorrectly: Sync flags updated")
    }

    @Test
    fun `markTripAsNeedingSync - sets flag correctly`() = runTest {
        // Given
        val trip = createTestTrip(id = "synced-trip", needsSync = false, lastSyncedAt = System.currentTimeMillis())
        tripDao.insertTrip(trip)

        // When
        tripDao.markTripAsNeedingSync("synced-trip")
        val retrieved = tripDao.getTripById("synced-trip")

        // Then
        assertNotNull(retrieved)
        assertTrue(retrieved?.needsSync ?: false)
        println("✓ markTripAsNeedingSync_setsFlag: needsSync flag set to true")
    }

    // ============ FLOW TESTS ============

    @Test
    fun `getTripsForUserFlow - emits updates when data changes`() = runTest {
        // Given
        val trip1 = createTestTrip(id = "flow-trip-1")
        tripDao.insertTrip(trip1)

        // When - Initial state
        val initialTrips = tripDao.getTripsForUserFlow(testUserId).first()
        assertEquals(1, initialTrips.size)

        // When - Add another trip
        val trip2 = createTestTrip(id = "flow-trip-2")
        tripDao.insertTrip(trip2)
        val updatedTrips = tripDao.getTripsForUserFlow(testUserId).first()

        // Then
        assertEquals(2, updatedTrips.size)
        println("✓ getTripsForUserFlow_emitsUpdates: Flow emits on data changes")
    }

    // ============ TYPE CONVERTER TESTS ============

    @Test
    fun `trip with locations - serializes and deserializes correctly`() = runTest {
        // Given
        val locations = listOf(
            TripLocation(48.8566, 2.3522, 10f, System.currentTimeMillis()),
            TripLocation(48.8584, 2.2945, 15f, System.currentTimeMillis() + 1000),
            TripLocation(48.8606, 2.3376, 8f, System.currentTimeMillis() + 2000)
        )
        val trip = createTestTrip(id = "locations-trip").copy(locations = locations)

        // When
        tripDao.insertTrip(trip)
        val retrieved = tripDao.getTripById("locations-trip")

        // Then
        assertNotNull(retrieved)
        assertEquals(3, retrieved?.locations?.size)
        assertEquals(48.8566, retrieved?.locations?.get(0)?.latitude ?: 0.0, 0.0001)
        assertEquals(2.3522, retrieved?.locations?.get(0)?.longitude ?: 0.0, 0.0001)
        println("✓ tripConverters_serializeLocations: Locations serialized and deserialized correctly")
    }

    @Test
    fun `trip with empty locations - handles correctly`() = runTest {
        // Given
        val trip = createTestTrip(id = "empty-locations-trip").copy(locations = emptyList())

        // When
        tripDao.insertTrip(trip)
        val retrieved = tripDao.getTripById("empty-locations-trip")

        // Then
        assertNotNull(retrieved)
        assertTrue(retrieved?.locations?.isEmpty() ?: false)
        println("✓ tripConverters_handleEmptyLocations: Empty locations handled correctly")
    }
}

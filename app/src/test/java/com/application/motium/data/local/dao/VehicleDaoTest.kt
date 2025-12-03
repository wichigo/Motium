package com.application.motium.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.VehicleEntity
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
 * Tests unitaires pour VehicleDao
 * Teste les opérations CRUD, la gestion des véhicules par défaut et la synchronisation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class VehicleDaoTest {

    private lateinit var database: MotiumDatabase
    private lateinit var vehicleDao: VehicleDao

    private val testUserId = "test-user-123"
    private val otherUserId = "other-user-456"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MotiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vehicleDao = database.vehicleDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ============ Helper functions ============

    private fun createTestVehicle(
        id: String = "vehicle-${System.nanoTime()}",
        userId: String = testUserId,
        name: String = "Test Vehicle",
        type: String = "CAR",
        licensePlate: String? = "AB-123-CD",
        power: String? = "CV_7",
        fuelType: String? = "GASOLINE",
        mileageRate: Double = 0.603,
        isDefault: Boolean = false,
        totalMileagePerso: Double = 0.0,
        totalMileagePro: Double = 0.0,
        needsSync: Boolean = true,
        lastSyncedAt: Long? = null
    ): VehicleEntity {
        val now = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
        return VehicleEntity(
            id = id,
            userId = userId,
            name = name,
            type = type,
            licensePlate = licensePlate,
            power = power,
            fuelType = fuelType,
            mileageRate = mileageRate,
            isDefault = isDefault,
            totalMileagePerso = totalMileagePerso,
            totalMileagePro = totalMileagePro,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = lastSyncedAt,
            needsSync = needsSync
        )
    }

    // ============ INSERT TESTS ============

    @Test
    fun `insertVehicle - inserts correctly and can be retrieved`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "vehicle-1", name = "Ma Voiture")

        // When
        vehicleDao.insertVehicle(vehicle)
        val retrieved = vehicleDao.getVehicleById("vehicle-1")

        // Then
        assertNotNull(retrieved)
        assertEquals("vehicle-1", retrieved?.id)
        assertEquals("Ma Voiture", retrieved?.name)
        assertEquals(testUserId, retrieved?.userId)
        println("✓ insertVehicle_insertsCorrectly: Vehicle inserted and retrieved successfully")
    }

    @Test
    fun `insertVehicle - replaces on conflict with same ID`() = runTest {
        // Given
        val originalVehicle = createTestVehicle(id = "vehicle-replace", name = "Original")
        val updatedVehicle = createTestVehicle(id = "vehicle-replace", name = "Updated")

        // When
        vehicleDao.insertVehicle(originalVehicle)
        vehicleDao.insertVehicle(updatedVehicle)
        val retrieved = vehicleDao.getVehicleById("vehicle-replace")

        // Then
        assertNotNull(retrieved)
        assertEquals("Updated", retrieved?.name)
        println("✓ insertVehicle_replacesOnConflict: Vehicle replaced correctly on conflict")
    }

    @Test
    fun `insertVehicles - batch insert works correctly`() = runTest {
        // Given
        val vehicles = listOf(
            createTestVehicle(id = "batch-1", name = "Vehicle 1"),
            createTestVehicle(id = "batch-2", name = "Vehicle 2"),
            createTestVehicle(id = "batch-3", name = "Vehicle 3")
        )

        // When
        vehicleDao.insertVehicles(vehicles)
        val allVehicles = vehicleDao.getVehiclesForUser(testUserId)

        // Then
        assertEquals(3, allVehicles.size)
        assertTrue(allVehicles.any { it.id == "batch-1" })
        assertTrue(allVehicles.any { it.id == "batch-2" })
        assertTrue(allVehicles.any { it.id == "batch-3" })
        println("✓ insertVehicles_batchInsertWorks: Batch insert successful")
    }

    // ============ UPDATE TESTS ============

    @Test
    fun `updateVehicle - updates all fields correctly`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "vehicle-update", name = "Original Name", mileageRate = 0.5)
        vehicleDao.insertVehicle(vehicle)

        // When
        val updatedVehicle = vehicle.copy(
            name = "Updated Name",
            mileageRate = 0.7,
            licensePlate = "XY-999-ZZ"
        )
        vehicleDao.updateVehicle(updatedVehicle)
        val retrieved = vehicleDao.getVehicleById("vehicle-update")

        // Then
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved?.name)
        assertEquals(0.7, retrieved?.mileageRate ?: 0.0, 0.01)
        assertEquals("XY-999-ZZ", retrieved?.licensePlate)
        println("✓ updateVehicle_updatesAllFields: Vehicle updated correctly")
    }

    @Test
    fun `updateVehicleMileage - updates both mileage values`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "mileage-vehicle", totalMileagePerso = 0.0, totalMileagePro = 0.0)
        vehicleDao.insertVehicle(vehicle)

        // When
        vehicleDao.updateVehicleMileage("mileage-vehicle", 1500.0, 3500.0)
        val retrieved = vehicleDao.getVehicleById("mileage-vehicle")

        // Then
        assertNotNull(retrieved)
        assertEquals(1500.0, retrieved?.totalMileagePerso ?: 0.0, 0.01)
        assertEquals(3500.0, retrieved?.totalMileagePro ?: 0.0, 0.01)
        println("✓ updateVehicleMileage_updatesBothValues: Mileage updated correctly")
    }

    // ============ DELETE TESTS ============

    @Test
    fun `deleteVehicle - removes vehicle from database`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "vehicle-delete")
        vehicleDao.insertVehicle(vehicle)

        // When
        vehicleDao.deleteVehicle(vehicle)
        val retrieved = vehicleDao.getVehicleById("vehicle-delete")

        // Then
        assertNull(retrieved)
        println("✓ deleteVehicle_removesFromDb: Vehicle deleted successfully")
    }

    @Test
    fun `deleteVehicleById - removes correct vehicle`() = runTest {
        // Given
        val vehicle1 = createTestVehicle(id = "vehicle-keep")
        val vehicle2 = createTestVehicle(id = "vehicle-remove")
        vehicleDao.insertVehicles(listOf(vehicle1, vehicle2))

        // When
        vehicleDao.deleteVehicleById("vehicle-remove")
        val kept = vehicleDao.getVehicleById("vehicle-keep")
        val removed = vehicleDao.getVehicleById("vehicle-remove")

        // Then
        assertNotNull(kept)
        assertNull(removed)
        println("✓ deleteVehicleById_removesCorrectVehicle: Correct vehicle deleted")
    }

    @Test
    fun `deleteAllVehiclesForUser - removes only user vehicles`() = runTest {
        // Given
        val userVehicle1 = createTestVehicle(id = "user-vehicle-1", userId = testUserId)
        val userVehicle2 = createTestVehicle(id = "user-vehicle-2", userId = testUserId)
        val otherVehicle = createTestVehicle(id = "other-vehicle", userId = otherUserId)
        vehicleDao.insertVehicles(listOf(userVehicle1, userVehicle2, otherVehicle))

        // When
        vehicleDao.deleteAllVehiclesForUser(testUserId)
        val testUserVehicles = vehicleDao.getVehiclesForUser(testUserId)
        val otherUserVehicles = vehicleDao.getVehiclesForUser(otherUserId)

        // Then
        assertEquals(0, testUserVehicles.size)
        assertEquals(1, otherUserVehicles.size)
        println("✓ deleteAllVehiclesForUser_isolatesUser: User isolation works")
    }

    @Test
    fun `deleteAllVehicles - clears entire table`() = runTest {
        // Given
        val vehicle1 = createTestVehicle(id = "vehicle-1", userId = testUserId)
        val vehicle2 = createTestVehicle(id = "vehicle-2", userId = otherUserId)
        vehicleDao.insertVehicles(listOf(vehicle1, vehicle2))

        // When
        vehicleDao.deleteAllVehicles()
        val allVehicles1 = vehicleDao.getVehiclesForUser(testUserId)
        val allVehicles2 = vehicleDao.getVehiclesForUser(otherUserId)

        // Then
        assertEquals(0, allVehicles1.size)
        assertEquals(0, allVehicles2.size)
        println("✓ deleteAllVehicles_clearsTable: All vehicles deleted")
    }

    // ============ QUERY TESTS ============

    @Test
    fun `getVehiclesForUser - orders by isDefault then name`() = runTest {
        // Given
        val vehicleC = createTestVehicle(id = "v-c", name = "C Vehicle", isDefault = false)
        val vehicleA = createTestVehicle(id = "v-a", name = "A Vehicle", isDefault = false)
        val vehicleDefault = createTestVehicle(id = "v-default", name = "Z Default", isDefault = true)
        val vehicleB = createTestVehicle(id = "v-b", name = "B Vehicle", isDefault = false)
        vehicleDao.insertVehicles(listOf(vehicleC, vehicleA, vehicleDefault, vehicleB))

        // When
        val vehicles = vehicleDao.getVehiclesForUser(testUserId)

        // Then
        assertEquals(4, vehicles.size)
        assertEquals("v-default", vehicles[0].id) // Default first
        assertEquals("v-a", vehicles[1].id) // Then alphabetical
        assertEquals("v-b", vehicles[2].id)
        assertEquals("v-c", vehicles[3].id)
        println("✓ getVehiclesForUser_ordersByDefaultThenName: Vehicles sorted correctly")
    }

    @Test
    fun `getVehicleById - returns correct vehicle`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "specific-vehicle", mileageRate = 0.789)
        vehicleDao.insertVehicle(vehicle)

        // When
        val retrieved = vehicleDao.getVehicleById("specific-vehicle")

        // Then
        assertNotNull(retrieved)
        assertEquals(0.789, retrieved?.mileageRate ?: 0.0, 0.001)
        println("✓ getVehicleById_returnsCorrectVehicle: Correct vehicle retrieved")
    }

    @Test
    fun `getVehicleById - returns null for non-existent vehicle`() = runTest {
        // When
        val retrieved = vehicleDao.getVehicleById("non-existent-vehicle")

        // Then
        assertNull(retrieved)
        println("✓ getVehicleById_returnsNullForNonExistent: Null returned for missing vehicle")
    }

    // ============ DEFAULT VEHICLE TESTS ============

    @Test
    fun `getDefaultVehicle - returns default only`() = runTest {
        // Given
        val normalVehicle = createTestVehicle(id = "normal", isDefault = false)
        val defaultVehicle = createTestVehicle(id = "default", isDefault = true)
        vehicleDao.insertVehicles(listOf(normalVehicle, defaultVehicle))

        // When
        val retrieved = vehicleDao.getDefaultVehicle(testUserId)

        // Then
        assertNotNull(retrieved)
        assertEquals("default", retrieved?.id)
        assertTrue(retrieved?.isDefault ?: false)
        println("✓ getDefaultVehicle_returnsDefaultOnly: Default vehicle returned")
    }

    @Test
    fun `getDefaultVehicle - returns null when no default`() = runTest {
        // Given
        val vehicle1 = createTestVehicle(id = "v1", isDefault = false)
        val vehicle2 = createTestVehicle(id = "v2", isDefault = false)
        vehicleDao.insertVehicles(listOf(vehicle1, vehicle2))

        // When
        val retrieved = vehicleDao.getDefaultVehicle(testUserId)

        // Then
        assertNull(retrieved)
        println("✓ getDefaultVehicle_returnsNullWhenNoDefault: Null returned when no default")
    }

    @Test
    fun `setVehicleAsDefault - sets correct vehicle as default`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "to-set-default", isDefault = false)
        vehicleDao.insertVehicle(vehicle)

        // When
        vehicleDao.setVehicleAsDefault("to-set-default")
        val retrieved = vehicleDao.getVehicleById("to-set-default")

        // Then
        assertNotNull(retrieved)
        assertTrue(retrieved?.isDefault ?: false)
        println("✓ setVehicleAsDefault_setsCorrectVehicle: Vehicle set as default")
    }

    @Test
    fun `unsetAllDefaultVehicles - clears all defaults for user`() = runTest {
        // Given
        val default1 = createTestVehicle(id = "default-1", isDefault = true)
        val default2 = createTestVehicle(id = "default-2", isDefault = true)
        vehicleDao.insertVehicles(listOf(default1, default2))

        // When
        vehicleDao.unsetAllDefaultVehicles(testUserId)
        val vehicles = vehicleDao.getVehiclesForUser(testUserId)

        // Then
        assertTrue(vehicles.none { it.isDefault })
        println("✓ unsetAllDefaultVehicles_clearsAllDefaults: All defaults cleared")
    }

    @Test
    fun `setNewDefaultVehicle - full workflow`() = runTest {
        // Given: Vehicle 1 is default, Vehicle 2 is not
        val vehicle1 = createTestVehicle(id = "old-default", isDefault = true)
        val vehicle2 = createTestVehicle(id = "new-default", isDefault = false)
        vehicleDao.insertVehicles(listOf(vehicle1, vehicle2))

        // When: Set vehicle 2 as new default
        vehicleDao.unsetAllDefaultVehicles(testUserId)
        vehicleDao.setVehicleAsDefault("new-default")

        // Then
        val oldDefault = vehicleDao.getVehicleById("old-default")
        val newDefault = vehicleDao.getVehicleById("new-default")
        val currentDefault = vehicleDao.getDefaultVehicle(testUserId)

        assertFalse(oldDefault?.isDefault ?: true)
        assertTrue(newDefault?.isDefault ?: false)
        assertEquals("new-default", currentDefault?.id)
        println("✓ setNewDefaultVehicle_fullWorkflow: Default vehicle change workflow works")
    }

    // ============ SYNC TESTS ============

    @Test
    fun `getVehiclesNeedingSync - returns only unsynced vehicles`() = runTest {
        // Given
        val syncedVehicle = createTestVehicle(id = "synced", needsSync = false, lastSyncedAt = System.currentTimeMillis())
        val unsyncedVehicle1 = createTestVehicle(id = "unsynced-1", needsSync = true)
        val unsyncedVehicle2 = createTestVehicle(id = "unsynced-2", needsSync = true)
        vehicleDao.insertVehicles(listOf(syncedVehicle, unsyncedVehicle1, unsyncedVehicle2))

        // When
        val unsyncedVehicles = vehicleDao.getVehiclesNeedingSync(testUserId)

        // Then
        assertEquals(2, unsyncedVehicles.size)
        assertTrue(unsyncedVehicles.all { it.needsSync })
        assertFalse(unsyncedVehicles.any { it.id == "synced" })
        println("✓ getVehiclesNeedingSync_filtersCorrectly: Filter works correctly")
    }

    @Test
    fun `markVehicleAsSynced - updates flags correctly`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "to-sync", needsSync = true, lastSyncedAt = null)
        vehicleDao.insertVehicle(vehicle)
        val syncTimestamp = System.currentTimeMillis()

        // When
        vehicleDao.markVehicleAsSynced("to-sync", syncTimestamp)
        val retrieved = vehicleDao.getVehicleById("to-sync")

        // Then
        assertNotNull(retrieved)
        assertFalse(retrieved?.needsSync ?: true)
        assertEquals(syncTimestamp, retrieved?.lastSyncedAt)
        println("✓ markVehicleAsSynced_updatesFlags: Sync flags updated")
    }

    @Test
    fun `markVehicleAsNeedingSync - sets flag correctly`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "synced-vehicle", needsSync = false, lastSyncedAt = System.currentTimeMillis())
        vehicleDao.insertVehicle(vehicle)

        // When
        vehicleDao.markVehicleAsNeedingSync("synced-vehicle")
        val retrieved = vehicleDao.getVehicleById("synced-vehicle")

        // Then
        assertNotNull(retrieved)
        assertTrue(retrieved?.needsSync ?: false)
        println("✓ markVehicleAsNeedingSync_setsFlag: needsSync flag set to true")
    }

    // ============ FLOW TESTS ============

    @Test
    fun `getVehiclesForUserFlow - emits updates when data changes`() = runTest {
        // Given
        val vehicle1 = createTestVehicle(id = "flow-vehicle-1")
        vehicleDao.insertVehicle(vehicle1)

        // When - Initial state
        val initialVehicles = vehicleDao.getVehiclesForUserFlow(testUserId).first()
        assertEquals(1, initialVehicles.size)

        // When - Add another vehicle
        val vehicle2 = createTestVehicle(id = "flow-vehicle-2")
        vehicleDao.insertVehicle(vehicle2)
        val updatedVehicles = vehicleDao.getVehiclesForUserFlow(testUserId).first()

        // Then
        assertEquals(2, updatedVehicles.size)
        println("✓ getVehiclesForUserFlow_emitsOnChanges: Flow emits on data changes")
    }

    // ============ ENUM CONVERSION TESTS ============

    @Test
    fun `vehicle with all enum types - stores and retrieves correctly`() = runTest {
        // Given
        val vehicle = createTestVehicle(
            id = "enum-vehicle",
            type = "MOTORCYCLE",
            power = "CV_6",
            fuelType = "DIESEL"
        )

        // When
        vehicleDao.insertVehicle(vehicle)
        val retrieved = vehicleDao.getVehicleById("enum-vehicle")

        // Then
        assertNotNull(retrieved)
        assertEquals("MOTORCYCLE", retrieved?.type)
        assertEquals("CV_6", retrieved?.power)
        assertEquals("DIESEL", retrieved?.fuelType)
        println("✓ vehicleWithEnumTypes_storesCorrectly: Enum types stored and retrieved")
    }

    @Test
    fun `vehicle with nullable fields - handles correctly`() = runTest {
        // Given
        val vehicle = createTestVehicle(
            id = "nullable-vehicle",
            licensePlate = null,
            power = null,
            fuelType = null
        )

        // When
        vehicleDao.insertVehicle(vehicle)
        val retrieved = vehicleDao.getVehicleById("nullable-vehicle")

        // Then
        assertNotNull(retrieved)
        assertNull(retrieved?.licensePlate)
        assertNull(retrieved?.power)
        assertNull(retrieved?.fuelType)
        println("✓ vehicleWithNullableFields_handlesCorrectly: Nullable fields handled")
    }
}

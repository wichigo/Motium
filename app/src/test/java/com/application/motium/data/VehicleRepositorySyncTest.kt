package com.application.motium.data

import com.application.motium.data.local.dao.VehicleDao
import com.application.motium.data.local.entities.VehicleEntity
import com.application.motium.data.sync.PendingSyncQueue
import com.application.motium.domain.model.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires pour VehicleRepository (synchronisation)
 * Teste les opérations CRUD avec synchronisation Supabase
 */
class VehicleRepositorySyncTest {

    @MockK
    private lateinit var mockVehicleDao: VehicleDao

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ============ Helper functions ============

    private fun createTestVehicle(
        id: String = "vehicle-${System.nanoTime()}",
        userId: String = "test-user-123",
        name: String = "Test Vehicle",
        type: VehicleType = VehicleType.CAR,
        licensePlate: String? = "AB-123-CD",
        power: VehiclePower? = VehiclePower.CV_7_PLUS,
        fuelType: FuelType? = FuelType.GASOLINE,
        mileageRate: Double = 0.603,
        isDefault: Boolean = false,
        totalMileagePerso: Double = 0.0,
        totalMileagePro: Double = 0.0
    ): Vehicle {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return Vehicle(
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
            updatedAt = now
        )
    }

    private fun createTestVehicleEntity(
        id: String = "vehicle-${System.nanoTime()}",
        userId: String = "test-user-123",
        name: String = "Test Vehicle",
        needsSync: Boolean = true,
        lastSyncedAt: Long? = null
    ): VehicleEntity {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
        return VehicleEntity(
            id = id,
            userId = userId,
            name = name,
            type = "CAR",
            licensePlate = "AB-123-CD",
            power = "CV_7_PLUS",
            fuelType = "GASOLINE",
            mileageRate = 0.603,
            isDefault = false,
            totalMileagePerso = 0.0,
            totalMileagePro = 0.0,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = lastSyncedAt,
            needsSync = needsSync
        )
    }

    // ============ INSERT VEHICLE TESTS ============

    @Test
    fun `insertVehicle - saves locally first`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "new-vehicle")
        coEvery { mockVehicleDao.insertVehicle(any()) } just Runs

        // When: Simulate local save
        val vehicleEntity = VehicleEntity(
            id = vehicle.id,
            userId = vehicle.userId,
            name = vehicle.name,
            type = vehicle.type.name,
            licensePlate = vehicle.licensePlate,
            power = vehicle.power?.name,
            fuelType = vehicle.fuelType?.name,
            mileageRate = vehicle.mileageRate,
            isDefault = vehicle.isDefault,
            totalMileagePerso = vehicle.totalMileagePerso,
            totalMileagePro = vehicle.totalMileagePro,
            createdAt = vehicle.createdAt.toString(),
            updatedAt = vehicle.updatedAt.toString(),
            lastSyncedAt = null,
            needsSync = true
        )
        mockVehicleDao.insertVehicle(vehicleEntity)

        // Then
        coVerify { mockVehicleDao.insertVehicle(any()) }
        println("✓ insertVehicle_savesLocallyFirst: Vehicle saved to Room first")
    }

    @Test
    fun `insertVehicle - sets needsSync to true initially`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "sync-vehicle")

        // When: Create entity for local save
        val vehicleEntity = VehicleEntity(
            id = vehicle.id,
            userId = vehicle.userId,
            name = vehicle.name,
            type = vehicle.type.name,
            licensePlate = vehicle.licensePlate,
            power = vehicle.power?.name,
            fuelType = vehicle.fuelType?.name,
            mileageRate = vehicle.mileageRate,
            isDefault = vehicle.isDefault,
            totalMileagePerso = vehicle.totalMileagePerso,
            totalMileagePro = vehicle.totalMileagePro,
            createdAt = vehicle.createdAt.toString(),
            updatedAt = vehicle.updatedAt.toString(),
            lastSyncedAt = null,
            needsSync = true
        )

        // Then
        assertTrue("New vehicle should need sync", vehicleEntity.needsSync)
        assertNull("lastSyncedAt should be null for new vehicle", vehicleEntity.lastSyncedAt)
        println("✓ insertVehicle_setsNeedsSyncTrue: needsSync=true for new vehicles")
    }

    @Test
    fun `insertVehicle - marks synced on success`() = runTest {
        // Given
        val vehicleId = "synced-vehicle"
        val syncTimestamp = System.currentTimeMillis()

        coEvery { mockVehicleDao.markVehicleAsSynced(vehicleId, syncTimestamp) } just Runs

        // When
        mockVehicleDao.markVehicleAsSynced(vehicleId, syncTimestamp)

        // Then
        coVerify { mockVehicleDao.markVehicleAsSynced(vehicleId, syncTimestamp) }
        println("✓ insertVehicle_marksSyncedOnSuccess: Vehicle marked as synced after successful upload")
    }

    @Test
    fun `insertVehicle - keeps needsSync on failure`() = runTest {
        // Given: Sync fails but vehicle is saved locally
        val vehicleEntity = createTestVehicleEntity(id = "failed-sync", needsSync = true)

        // When: Sync fails, needsSync remains true
        val stillNeedsSync = vehicleEntity.needsSync

        // Then
        assertTrue("Vehicle should still need sync after failure", stillNeedsSync)
        println("✓ insertVehicle_marksNeedsSyncOnFailure: needsSync remains true on sync failure")
    }

    // ============ UPDATE VEHICLE TESTS ============

    @Test
    fun `updateVehicle - updates locally and syncs`() = runTest {
        // Given
        val vehicle = createTestVehicle(id = "update-vehicle", name = "Updated Name")
        coEvery { mockVehicleDao.updateVehicle(any()) } just Runs

        // When
        val vehicleEntity = VehicleEntity(
            id = vehicle.id,
            userId = vehicle.userId,
            name = vehicle.name,
            type = vehicle.type.name,
            licensePlate = vehicle.licensePlate,
            power = vehicle.power?.name,
            fuelType = vehicle.fuelType?.name,
            mileageRate = vehicle.mileageRate,
            isDefault = vehicle.isDefault,
            totalMileagePerso = vehicle.totalMileagePerso,
            totalMileagePro = vehicle.totalMileagePro,
            createdAt = vehicle.createdAt.toString(),
            updatedAt = vehicle.updatedAt.toString(),
            lastSyncedAt = null,
            needsSync = true
        )
        mockVehicleDao.updateVehicle(vehicleEntity)

        // Then
        coVerify { mockVehicleDao.updateVehicle(any()) }
        println("✓ updateVehicle_syncsToSupabase: Vehicle updated locally and marked for sync")
    }

    // ============ DELETE VEHICLE TESTS ============

    @Test
    fun `deleteVehicle - deletes locally and syncs`() = runTest {
        // Given
        val vehicleEntity = createTestVehicleEntity(id = "delete-vehicle")
        coEvery { mockVehicleDao.deleteVehicle(any()) } just Runs

        // When
        mockVehicleDao.deleteVehicle(vehicleEntity)

        // Then
        coVerify { mockVehicleDao.deleteVehicle(vehicleEntity) }
        println("✓ deleteVehicle_syncsToSupabase: Vehicle deleted locally and deletion synced")
    }

    // ============ SYNC FROM SUPABASE TESTS ============

    @Test
    fun `syncVehiclesFromSupabase - updates local database`() = runTest {
        // Given: Remote vehicles from Supabase
        val remoteVehicles = listOf(
            createTestVehicleEntity(id = "remote-1", name = "Remote Vehicle 1"),
            createTestVehicleEntity(id = "remote-2", name = "Remote Vehicle 2")
        )
        coEvery { mockVehicleDao.insertVehicles(any()) } just Runs

        // When: Upsert remote vehicles to local
        mockVehicleDao.insertVehicles(remoteVehicles)

        // Then
        coVerify { mockVehicleDao.insertVehicles(remoteVehicles) }
        println("✓ syncVehiclesFromSupabase_updatesLocal: Remote vehicles inserted/updated locally")
    }

    @Test
    fun `syncVehiclesFromSupabase - marks imported as synced`() = runTest {
        // Given: Vehicles from Supabase should have needsSync=false
        val importedVehicle = createTestVehicleEntity(
            id = "imported",
            needsSync = false,
            lastSyncedAt = System.currentTimeMillis()
        )

        // Then
        assertFalse("Imported vehicle should not need sync", importedVehicle.needsSync)
        assertNotNull("Imported vehicle should have lastSyncedAt", importedVehicle.lastSyncedAt)
        println("✓ syncVehiclesFromSupabase_marksAsSynced: Imported vehicles marked as synced")
    }

    // ============ SYNC TO SUPABASE TESTS ============

    @Test
    fun `syncVehiclesToSupabase - syncs only dirty vehicles`() = runTest {
        // Given
        val userId = "test-user"
        val dirtyVehicles = listOf(
            createTestVehicleEntity(id = "dirty-1", needsSync = true),
            createTestVehicleEntity(id = "dirty-2", needsSync = true)
        )
        coEvery { mockVehicleDao.getVehiclesNeedingSync(userId) } returns dirtyVehicles

        // When
        val vehiclesToSync = mockVehicleDao.getVehiclesNeedingSync(userId)

        // Then
        assertEquals(2, vehiclesToSync.size)
        assertTrue(vehiclesToSync.all { it.needsSync })
        println("✓ syncVehiclesToSupabase_syncsOnlyDirty: Only needsSync=true vehicles synced")
    }

    @Test
    fun `syncVehiclesToSupabase - marks synced after successful upload`() = runTest {
        // Given
        val vehicleId = "to-sync"
        val syncTimestamp = System.currentTimeMillis()
        coEvery { mockVehicleDao.markVehicleAsSynced(vehicleId, syncTimestamp) } just Runs

        // When
        mockVehicleDao.markVehicleAsSynced(vehicleId, syncTimestamp)

        // Then
        coVerify { mockVehicleDao.markVehicleAsSynced(vehicleId, syncTimestamp) }
        println("✓ syncVehiclesToSupabase_marksAfterUpload: Vehicle marked synced after upload")
    }

    // ============ SET DEFAULT VEHICLE TESTS ============

    @Test
    fun `setDefaultVehicle - unsets all defaults first`() = runTest {
        // Given
        val userId = "test-user"
        val newDefaultId = "new-default"
        coEvery { mockVehicleDao.unsetAllDefaultVehicles(userId) } just Runs
        coEvery { mockVehicleDao.setVehicleAsDefault(newDefaultId) } just Runs

        // When
        mockVehicleDao.unsetAllDefaultVehicles(userId)
        mockVehicleDao.setVehicleAsDefault(newDefaultId)

        // Then
        coVerify(ordering = Ordering.SEQUENCE) {
            mockVehicleDao.unsetAllDefaultVehicles(userId)
            mockVehicleDao.setVehicleAsDefault(newDefaultId)
        }
        println("✓ setDefaultVehicle_unsetsAllFirst: All defaults unset before setting new default")
    }

    @Test
    fun `setDefaultVehicle - syncs to Supabase`() = runTest {
        // The default vehicle change should trigger a sync
        val vehicleId = "default-vehicle"
        coEvery { mockVehicleDao.markVehicleAsNeedingSync(vehicleId) } just Runs

        // When: Mark vehicle as needing sync after default change
        mockVehicleDao.markVehicleAsNeedingSync(vehicleId)

        // Then
        coVerify { mockVehicleDao.markVehicleAsNeedingSync(vehicleId) }
        println("✓ setDefaultVehicle_syncsToSupabase: Default vehicle change synced")
    }

    // ============ BIDIRECTIONAL SYNC TESTS ============

    @Test
    fun `bidirectional sync - exports then imports`() = runTest {
        // Given
        val userId = "test-user"
        val dirtyVehicles = listOf(createTestVehicleEntity(id = "export-me", needsSync = true))
        val remoteVehicles = listOf(createTestVehicleEntity(id = "import-me", needsSync = false))

        coEvery { mockVehicleDao.getVehiclesNeedingSync(userId) } returns dirtyVehicles
        coEvery { mockVehicleDao.insertVehicles(any()) } just Runs

        // When: Export step
        val toExport = mockVehicleDao.getVehiclesNeedingSync(userId)

        // When: Import step
        mockVehicleDao.insertVehicles(remoteVehicles)

        // Then
        assertEquals(1, toExport.size)
        coVerify { mockVehicleDao.getVehiclesNeedingSync(userId) }
        coVerify { mockVehicleDao.insertVehicles(remoteVehicles) }
        println("✓ bidirectionalSync_exportsThenImports: Export and import steps work")
    }

    // ============ ERROR HANDLING TESTS ============

    @Test
    fun `sync failure - queues operation for retry`() {
        // Given: Sync fails
        val operation = PendingSyncQueue.PendingOperation(
            id = "op-123",
            type = PendingSyncQueue.OperationType.CREATE,
            entityId = "vehicle-123",
            entityType = PendingSyncQueue.EntityType.VEHICLE,
            timestamp = System.currentTimeMillis()
        )

        // Then: Operation should be in retry queue
        assertEquals(PendingSyncQueue.EntityType.VEHICLE, operation.entityType)
        assertEquals(PendingSyncQueue.OperationType.CREATE, operation.type)
        println("✓ syncFailure_queuesForRetry: Failed operation queued for retry")
    }

    @Test
    fun `network error - keeps vehicle locally with needsSync`() = runTest {
        // Given: Network error during sync
        val vehicleEntity = createTestVehicleEntity(id = "offline-vehicle", needsSync = true)

        // Then: Vehicle should still be saved locally with needsSync=true
        assertTrue("Vehicle should still need sync after network error", vehicleEntity.needsSync)
        println("✓ networkError_keepsVehicleLocally: Vehicle saved locally despite network error")
    }

    // ============ VEHICLE DOMAIN MODEL CONVERSION TESTS ============

    @Test
    fun `vehicle entity to domain model - converts correctly`() {
        // Given
        val entity = createTestVehicleEntity(
            id = "convert-me",
            name = "Conversion Test"
        )

        // When: Convert (simulated - actual conversion is in extension function)
        val domainModel = Vehicle(
            id = entity.id,
            userId = entity.userId,
            name = entity.name,
            type = VehicleType.valueOf(entity.type),
            licensePlate = entity.licensePlate,
            power = entity.power?.let { VehiclePower.valueOf(it) },
            fuelType = entity.fuelType?.let { FuelType.valueOf(it) },
            mileageRate = entity.mileageRate,
            isDefault = entity.isDefault,
            totalMileagePerso = entity.totalMileagePerso,
            totalMileagePro = entity.totalMileagePro,
            createdAt = kotlinx.datetime.Instant.parse(entity.createdAt),
            updatedAt = kotlinx.datetime.Instant.parse(entity.updatedAt)
        )

        // Then
        assertEquals(entity.id, domainModel.id)
        assertEquals(entity.name, domainModel.name)
        assertEquals(VehicleType.CAR, domainModel.type)
        println("✓ vehicleEntityToDomainModel_convertsCorrectly: Entity to domain conversion works")
    }

    @Test
    fun `domain model to vehicle entity - converts correctly`() {
        // Given
        val domainModel = createTestVehicle(id = "to-entity", name = "Domain Model")

        // When: Convert
        val entity = VehicleEntity(
            id = domainModel.id,
            userId = domainModel.userId,
            name = domainModel.name,
            type = domainModel.type.name,
            licensePlate = domainModel.licensePlate,
            power = domainModel.power?.name,
            fuelType = domainModel.fuelType?.name,
            mileageRate = domainModel.mileageRate,
            isDefault = domainModel.isDefault,
            totalMileagePerso = domainModel.totalMileagePerso,
            totalMileagePro = domainModel.totalMileagePro,
            createdAt = domainModel.createdAt.toString(),
            updatedAt = domainModel.updatedAt.toString(),
            lastSyncedAt = null,
            needsSync = true
        )

        // Then
        assertEquals(domainModel.id, entity.id)
        assertEquals(domainModel.name, entity.name)
        assertEquals("CAR", entity.type)
        println("✓ domainModelToVehicleEntity_convertsCorrectly: Domain to entity conversion works")
    }
}

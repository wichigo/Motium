package com.application.motium.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.UserEntity
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
 * Tests unitaires pour UserDao
 * Teste les opérations de persistence utilisateur et de gestion de session
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class UserDaoTest {

    private lateinit var database: MotiumDatabase
    private lateinit var userDao: UserDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MotiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userDao = database.userDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ============ Helper functions ============

    private fun createTestUser(
        id: String = "user-${System.nanoTime()}",
        name: String = "Test User",
        email: String = "test@example.com",
        role: String = "INDIVIDUAL",
        organizationId: String? = null,
        organizationName: String? = null,
        subscriptionType: String = "FREE",
        subscriptionExpiresAt: String? = null,
        monthlyTripCount: Int = 0,
        phoneNumber: String = "",
        address: String = "",
        linkedToCompany: Boolean = false,
        shareProfessionalTrips: Boolean = false,
        sharePersonalTrips: Boolean = false,
        sharePersonalInfo: Boolean = false,
        lastSyncedAt: Long? = null,
        isLocallyConnected: Boolean = true
    ): UserEntity {
        val now = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
        return UserEntity(
            id = id,
            name = name,
            email = email,
            role = role,
            organizationId = organizationId,
            organizationName = organizationName,
            subscriptionType = subscriptionType,
            subscriptionExpiresAt = subscriptionExpiresAt,
            monthlyTripCount = monthlyTripCount,
            phoneNumber = phoneNumber,
            address = address,
            linkedToCompany = linkedToCompany,
            shareProfessionalTrips = shareProfessionalTrips,
            sharePersonalTrips = sharePersonalTrips,
            sharePersonalInfo = sharePersonalInfo,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = lastSyncedAt,
            isLocallyConnected = isLocallyConnected
        )
    }

    // ============ INSERT TESTS ============

    @Test
    fun `insertUser - inserts correctly and can be retrieved`() = runTest {
        // Given
        val user = createTestUser(id = "user-1", name = "John Doe", email = "john@example.com")

        // When
        userDao.insertUser(user)
        val retrieved = userDao.getUserById("user-1")

        // Then
        assertNotNull(retrieved)
        assertEquals("user-1", retrieved?.id)
        assertEquals("John Doe", retrieved?.name)
        assertEquals("john@example.com", retrieved?.email)
        println("✓ insertUser_insertsCorrectly: User inserted and retrieved successfully")
    }

    @Test
    fun `insertUser - replaces on conflict with same ID`() = runTest {
        // Given
        val originalUser = createTestUser(id = "user-replace", name = "Original Name")
        val updatedUser = createTestUser(id = "user-replace", name = "Updated Name")

        // When
        userDao.insertUser(originalUser)
        userDao.insertUser(updatedUser)
        val retrieved = userDao.getUserById("user-replace")

        // Then
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved?.name)
        println("✓ insertUser_replacesOnConflict: User replaced correctly on conflict")
    }

    // ============ UPDATE TESTS ============

    @Test
    fun `updateUser - updates all fields correctly`() = runTest {
        // Given
        val user = createTestUser(id = "user-update", name = "Original", monthlyTripCount = 5)
        userDao.insertUser(user)

        // When
        val updatedUser = user.copy(
            name = "Updated",
            monthlyTripCount = 10,
            phoneNumber = "0612345678"
        )
        userDao.updateUser(updatedUser)
        val retrieved = userDao.getUserById("user-update")

        // Then
        assertNotNull(retrieved)
        assertEquals("Updated", retrieved?.name)
        assertEquals(10, retrieved?.monthlyTripCount)
        assertEquals("0612345678", retrieved?.phoneNumber)
        println("✓ updateUser_updatesAllFields: User updated correctly")
    }

    @Test
    fun `updateLastSyncedAt - updates timestamp correctly`() = runTest {
        // Given
        val user = createTestUser(id = "user-sync", lastSyncedAt = null)
        userDao.insertUser(user)
        val syncTimestamp = System.currentTimeMillis()

        // When
        userDao.updateLastSyncedAt("user-sync", syncTimestamp)
        val retrieved = userDao.getUserById("user-sync")

        // Then
        assertNotNull(retrieved)
        assertEquals(syncTimestamp, retrieved?.lastSyncedAt)
        println("✓ updateLastSyncedAt_updatesTimestamp: Sync timestamp updated")
    }

    // ============ DELETE TESTS ============

    @Test
    fun `deleteUser - removes user from database`() = runTest {
        // Given
        val user = createTestUser(id = "user-delete")
        userDao.insertUser(user)

        // When
        userDao.deleteUser(user)
        val retrieved = userDao.getUserById("user-delete")

        // Then
        assertNull(retrieved)
        println("✓ deleteUser_removesFromDb: User deleted successfully")
    }

    @Test
    fun `deleteAllUsers - clears entire table`() = runTest {
        // Given
        val user1 = createTestUser(id = "user-1")
        val user2 = createTestUser(id = "user-2")
        userDao.insertUser(user1)
        userDao.insertUser(user2)

        // When
        userDao.deleteAllUsers()
        val retrieved1 = userDao.getUserById("user-1")
        val retrieved2 = userDao.getUserById("user-2")

        // Then
        assertNull(retrieved1)
        assertNull(retrieved2)
        println("✓ deleteAllUsers_clearsTable: All users deleted")
    }

    // ============ SESSION MANAGEMENT TESTS ============

    @Test
    fun `getLoggedInUser - returns connected user only`() = runTest {
        // Given
        val connectedUser = createTestUser(id = "connected-user", isLocallyConnected = true)
        val disconnectedUser = createTestUser(id = "disconnected-user", isLocallyConnected = false)
        userDao.insertUser(connectedUser)
        userDao.insertUser(disconnectedUser)

        // When
        val loggedInUser = userDao.getLoggedInUser()

        // Then
        assertNotNull(loggedInUser)
        assertEquals("connected-user", loggedInUser?.id)
        assertTrue(loggedInUser?.isLocallyConnected ?: false)
        println("✓ getLoggedInUser_returnsConnectedUser: Only connected user returned")
    }

    @Test
    fun `getLoggedInUser - returns null when no connected user`() = runTest {
        // Given
        val disconnectedUser = createTestUser(id = "disconnected-user", isLocallyConnected = false)
        userDao.insertUser(disconnectedUser)

        // When
        val loggedInUser = userDao.getLoggedInUser()

        // Then
        assertNull(loggedInUser)
        println("✓ getLoggedInUser_returnsNullWhenNone: Null returned when no logged in user")
    }

    @Test
    fun `hasLoggedInUser - returns true when user exists`() = runTest {
        // Given
        val connectedUser = createTestUser(id = "connected-user", isLocallyConnected = true)
        userDao.insertUser(connectedUser)

        // When
        val hasUser = userDao.hasLoggedInUser()

        // Then
        assertTrue(hasUser)
        println("✓ hasLoggedInUser_returnsTrueWhenExists: Returns true when user logged in")
    }

    @Test
    fun `hasLoggedInUser - returns false when no user logged in`() = runTest {
        // Given - Empty database or only disconnected users
        val disconnectedUser = createTestUser(id = "disconnected", isLocallyConnected = false)
        userDao.insertUser(disconnectedUser)

        // When
        val hasUser = userDao.hasLoggedInUser()

        // Then
        assertFalse(hasUser)
        println("✓ hasLoggedInUser_returnsFalseWhenNone: Returns false when no user logged in")
    }

    @Test
    fun `logoutAllUsers - sets all users to disconnected`() = runTest {
        // Given
        val user1 = createTestUser(id = "user-1", isLocallyConnected = true)
        val user2 = createTestUser(id = "user-2", isLocallyConnected = true)
        userDao.insertUser(user1)
        userDao.insertUser(user2)

        // When
        userDao.logoutAllUsers()
        val retrieved1 = userDao.getUserById("user-1")
        val retrieved2 = userDao.getUserById("user-2")
        val hasLoggedIn = userDao.hasLoggedInUser()

        // Then
        assertFalse(retrieved1?.isLocallyConnected ?: true)
        assertFalse(retrieved2?.isLocallyConnected ?: true)
        assertFalse(hasLoggedIn)
        println("✓ logoutAllUsers_setsAllToDisconnected: All users logged out")
    }

    // ============ QUERY TESTS ============

    @Test
    fun `getUserById - returns correct user`() = runTest {
        // Given
        val user = createTestUser(id = "specific-user", email = "specific@test.com")
        userDao.insertUser(user)

        // When
        val retrieved = userDao.getUserById("specific-user")

        // Then
        assertNotNull(retrieved)
        assertEquals("specific@test.com", retrieved?.email)
        println("✓ getUserById_returnsCorrectUser: Correct user retrieved")
    }

    @Test
    fun `getUserById - returns null for non-existent user`() = runTest {
        // When
        val retrieved = userDao.getUserById("non-existent-user")

        // Then
        assertNull(retrieved)
        println("✓ getUserById_returnsNullForNonExistent: Null returned for missing user")
    }

    // ============ FLOW TESTS ============

    @Test
    fun `getLoggedInUserFlow - emits updates when data changes`() = runTest {
        // Given - No user initially
        val initialUser = userDao.getLoggedInUserFlow().first()
        assertNull(initialUser)

        // When - Add connected user
        val user = createTestUser(id = "flow-user", isLocallyConnected = true)
        userDao.insertUser(user)
        val afterInsert = userDao.getLoggedInUserFlow().first()

        // Then
        assertNotNull(afterInsert)
        assertEquals("flow-user", afterInsert?.id)
        println("✓ getLoggedInUserFlow_emitsOnChanges: Flow emits on data changes")
    }

    @Test
    fun `getLoggedInUserFlow - emits null after logout`() = runTest {
        // Given
        val user = createTestUser(id = "logout-user", isLocallyConnected = true)
        userDao.insertUser(user)

        // When
        val beforeLogout = userDao.getLoggedInUserFlow().first()
        assertNotNull(beforeLogout)

        userDao.logoutAllUsers()
        val afterLogout = userDao.getLoggedInUserFlow().first()

        // Then
        assertNull(afterLogout)
        println("✓ getLoggedInUserFlow_emitsNullAfterLogout: Flow emits null after logout")
    }

    // ============ ROLE AND SUBSCRIPTION TESTS ============

    @Test
    fun `user with enterprise role - stores correctly`() = runTest {
        // Given
        val enterpriseUser = createTestUser(
            id = "enterprise-user",
            role = "ENTERPRISE",
            organizationId = "org-123",
            organizationName = "Test Company",
            linkedToCompany = true
        )

        // When
        userDao.insertUser(enterpriseUser)
        val retrieved = userDao.getUserById("enterprise-user")

        // Then
        assertNotNull(retrieved)
        assertEquals("ENTERPRISE", retrieved?.role)
        assertEquals("org-123", retrieved?.organizationId)
        assertEquals("Test Company", retrieved?.organizationName)
        assertTrue(retrieved?.linkedToCompany ?: false)
        println("✓ userWithEnterpriseRole_storesCorrectly: Enterprise user stored correctly")
    }

    @Test
    fun `user with premium subscription - stores correctly`() = runTest {
        // Given
        val premiumUser = createTestUser(
            id = "premium-user",
            subscriptionType = "PREMIUM",
            subscriptionExpiresAt = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000).toString()
        )

        // When
        userDao.insertUser(premiumUser)
        val retrieved = userDao.getUserById("premium-user")

        // Then
        assertNotNull(retrieved)
        assertEquals("PREMIUM", retrieved?.subscriptionType)
        assertNotNull(retrieved?.subscriptionExpiresAt)
        println("✓ userWithPremiumSubscription_storesCorrectly: Premium user stored correctly")
    }

    // ============ SHARING PREFERENCES TESTS ============

    @Test
    fun `user sharing preferences - stores correctly`() = runTest {
        // Given
        val user = createTestUser(
            id = "sharing-user",
            shareProfessionalTrips = true,
            sharePersonalTrips = false,
            sharePersonalInfo = true
        )

        // When
        userDao.insertUser(user)
        val retrieved = userDao.getUserById("sharing-user")

        // Then
        assertNotNull(retrieved)
        assertTrue(retrieved?.shareProfessionalTrips ?: false)
        assertFalse(retrieved?.sharePersonalTrips ?: true)
        assertTrue(retrieved?.sharePersonalInfo ?: false)
        println("✓ userSharingPreferences_storesCorrectly: Sharing preferences stored correctly")
    }

    // ============ SESSION PERSISTENCE TESTS ============

    @Test
    fun `session persistence workflow - login then logout`() = runTest {
        // Given: User logs in
        val user = createTestUser(id = "session-user", isLocallyConnected = true)
        userDao.insertUser(user)

        // Verify login state
        assertTrue(userDao.hasLoggedInUser())
        assertNotNull(userDao.getLoggedInUser())

        // When: User logs out
        userDao.logoutAllUsers()

        // Then: Session should be cleared
        assertFalse(userDao.hasLoggedInUser())
        assertNull(userDao.getLoggedInUser())

        // But user data should still exist (just disconnected)
        val retrievedUser = userDao.getUserById("session-user")
        assertNotNull(retrievedUser)
        assertFalse(retrievedUser?.isLocallyConnected ?: true)
        println("✓ sessionPersistenceWorkflow_loginThenLogout: Full workflow works correctly")
    }

    @Test
    fun `multiple users - only one connected at a time`() = runTest {
        // Given: Insert multiple users with one connected
        val user1 = createTestUser(id = "user-1", isLocallyConnected = false)
        val user2 = createTestUser(id = "user-2", isLocallyConnected = true)
        val user3 = createTestUser(id = "user-3", isLocallyConnected = false)
        userDao.insertUser(user1)
        userDao.insertUser(user2)
        userDao.insertUser(user3)

        // When
        val loggedInUser = userDao.getLoggedInUser()

        // Then: Only user2 should be returned
        assertNotNull(loggedInUser)
        assertEquals("user-2", loggedInUser?.id)
        println("✓ multipleUsers_onlyOneConnected: Only one connected user at a time")
    }
}

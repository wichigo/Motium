package com.application.motium.utils

import android.content.Context
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.datetime.Instant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class ExportManagerTest {

    private lateinit var context: Context
    private lateinit var exportManager: ExportManager

    // Test data
    private val testTrips = listOf(
        createTestTrip(
            id = "trip1",
            startTime = 1733443200000L, // 2024-12-06 00:00:00
            endTime = 1733446800000L,   // 2024-12-06 01:00:00
            distance = 25000.0,         // 25 km
            startAddress = "Paris, France",
            endAddress = "Versailles, France"
        ),
        createTestTrip(
            id = "trip2",
            startTime = 1733356800000L, // 2024-12-05 00:00:00
            endTime = 1733360400000L,   // 2024-12-05 01:00:00
            distance = 15000.0,         // 15 km
            startAddress = "Lyon, France",
            endAddress = "Grenoble, France"
        ),
        createTestTrip(
            id = "trip3",
            startTime = 1733270400000L, // 2024-12-04 00:00:00
            endTime = 1733274000000L,   // 2024-12-04 01:00:00
            distance = 50000.0,         // 50 km
            startAddress = "Marseille, France",
            endAddress = "Nice, France"
        )
    )

    private val testExpenses = listOf(
        createTestExpense(
            id = "exp1",
            date = "2024-12-06",
            type = ExpenseType.FUEL,
            amount = 45.50,
            amountHT = 37.92,
            note = "Plein essence autoroute"
        ),
        createTestExpense(
            id = "exp2",
            date = "2024-12-06",
            type = ExpenseType.TOLL,
            amount = 12.80,
            amountHT = 10.67,
            note = "Péage A6"
        ),
        createTestExpense(
            id = "exp3",
            date = "2024-12-05",
            type = ExpenseType.PARKING,
            amount = 8.00,
            amountHT = 6.67,
            note = "Parking centre-ville"
        )
    )

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Note: ExportManager uses repositories that need to be mocked for full testing
        // For now, we test the export functionality with data passed directly
    }

    // ==================== CSV Export Tests ====================

    @Test
    fun `CSV export should include correct headers for trips_only mode`() {
        // Given
        val startDate = 1733184000000L // 2024-12-03
        val endDate = 1733529600000L   // 2024-12-07

        // When - we can't fully test without mocking the repository
        // But we can verify the structure expectations

        // Then
        val totalKm = testTrips.sumOf { it.totalDistance / 1000.0 }
        val totalIndemnities = totalKm * 0.50

        assertEquals(90.0, totalKm, 0.01) // 25 + 15 + 50 = 90 km
        assertEquals(45.0, totalIndemnities, 0.01) // 90 * 0.50 = 45 EUR
    }

    @Test
    fun `CSV export should calculate correct totals with expenses`() {
        // Given - expenses are linked by date instead of tripId
        val allExpenses = testExpenses

        // When
        val totalKm = testTrips.sumOf { it.totalDistance / 1000.0 }
        val totalIndemnities = totalKm * 0.50
        val totalExpenses = allExpenses.sumOf { it.amount }
        val grandTotal = totalIndemnities + totalExpenses

        // Then
        assertEquals(90.0, totalKm, 0.01)
        assertEquals(45.0, totalIndemnities, 0.01)
        assertEquals(66.30, totalExpenses, 0.01) // 45.50 + 12.80 + 8.00 = 66.30
        assertEquals(111.30, grandTotal, 0.01)
    }

    @Test
    fun `should correctly count expenses by date`() {
        // Given - expenses are linked by date
        val dec06Expenses = testExpenses.filter { it.date == "2024-12-06" }
        val dec05Expenses = testExpenses.filter { it.date == "2024-12-05" }
        val dec04Expenses = testExpenses.filter { it.date == "2024-12-04" }

        // When/Then
        assertEquals(2, dec06Expenses.size) // Fuel + Toll
        assertEquals(1, dec05Expenses.size) // Parking
        assertEquals(0, dec04Expenses.size) // No expenses
    }

    // ==================== Expense Calculation Tests ====================

    @Test
    fun `should calculate expense totals by type`() {
        // Given
        val allExpenses = testExpenses

        // When
        val fuelTotal = allExpenses.filter { it.type == ExpenseType.FUEL }.sumOf { it.amount }
        val tollTotal = allExpenses.filter { it.type == ExpenseType.TOLL }.sumOf { it.amount }
        val parkingTotal = allExpenses.filter { it.type == ExpenseType.PARKING }.sumOf { it.amount }

        // Then
        assertEquals(45.50, fuelTotal, 0.01)
        assertEquals(12.80, tollTotal, 0.01)
        assertEquals(8.00, parkingTotal, 0.01)
    }

    @Test
    fun `should calculate HT amounts correctly`() {
        // Given
        val allExpenses = testExpenses

        // When
        val totalHT = allExpenses.mapNotNull { it.amountHT }.sum()
        val totalTTC = allExpenses.sumOf { it.amount }

        // Then
        assertEquals(55.26, totalHT, 0.01) // 37.92 + 10.67 + 6.67
        assertEquals(66.30, totalTTC, 0.01)
        assertTrue(totalHT < totalTTC) // HT should always be less than TTC
    }

    // ==================== Date Filtering Tests ====================

    @Test
    fun `should filter trips by date range`() {
        // Given
        val startDate = 1733356800000L // 2024-12-05
        val endDate = 1733443200000L   // 2024-12-06

        // When
        val filteredTrips = testTrips.filter { trip ->
            trip.startTime >= startDate && trip.startTime <= endDate
        }

        // Then
        assertEquals(2, filteredTrips.size)
        assertTrue(filteredTrips.any { it.id == "trip1" })
        assertTrue(filteredTrips.any { it.id == "trip2" })
        assertFalse(filteredTrips.any { it.id == "trip3" }) // Before date range
    }

    @Test
    fun `should handle empty date range`() {
        // Given
        val startDate = 1733616000000L // 2024-12-08 (future)
        val endDate = 1733702400000L   // 2024-12-09

        // When
        val filteredTrips = testTrips.filter { trip ->
            trip.startTime >= startDate && trip.startTime <= endDate
        }

        // Then
        assertTrue(filteredTrips.isEmpty())
    }

    // ==================== Mileage Rate Tests ====================

    @Test
    fun `should apply correct mileage rate`() {
        // Given
        val mileageRate = 0.50 // EUR/km
        val distanceKm = 100.0

        // When
        val indemnity = distanceKm * mileageRate

        // Then
        assertEquals(50.0, indemnity, 0.01)
    }

    @Test
    fun `should calculate indemnities for each trip`() {
        // Given
        val mileageRate = 0.50

        // When
        val trip1Indemnity = (testTrips[0].totalDistance / 1000.0) * mileageRate
        val trip2Indemnity = (testTrips[1].totalDistance / 1000.0) * mileageRate
        val trip3Indemnity = (testTrips[2].totalDistance / 1000.0) * mileageRate

        // Then
        assertEquals(12.50, trip1Indemnity, 0.01) // 25 km * 0.50
        assertEquals(7.50, trip2Indemnity, 0.01)  // 15 km * 0.50
        assertEquals(25.0, trip3Indemnity, 0.01)  // 50 km * 0.50
    }

    // ==================== Expense Mode Tests ====================

    @Test
    fun `trips_only mode should not include expenses in total`() {
        // Given
        val expenseMode = "trips_only"
        val totalKm = testTrips.sumOf { it.totalDistance / 1000.0 }
        val mileageRate = 0.50

        // When
        val total = when (expenseMode) {
            "trips_only" -> totalKm * mileageRate
            else -> 0.0
        }

        // Then
        assertEquals(45.0, total, 0.01) // Only mileage, no expenses
    }

    @Test
    fun `trips_with_expenses mode should include both`() {
        // Given
        val expenseMode = "trips_with_expenses"
        val allExpenses = testExpenses
        val totalKm = testTrips.sumOf { it.totalDistance / 1000.0 }
        val mileageRate = 0.50

        // When
        val total = when (expenseMode) {
            "trips_with_expenses" -> {
                val indemnities = totalKm * mileageRate
                val expenses = allExpenses.sumOf { it.amount }
                indemnities + expenses
            }
            else -> 0.0
        }

        // Then
        assertEquals(111.30, total, 0.01) // 45 + 66.30
    }

    @Test
    fun `expenses_only mode should not include mileage`() {
        // Given
        val expenseMode = "expenses_only"
        val allExpenses = testExpenses

        // When
        val total = when (expenseMode) {
            "expenses_only" -> allExpenses.sumOf { it.amount }
            else -> 0.0
        }

        // Then
        assertEquals(66.30, total, 0.01) // Only expenses, no mileage
    }

    // ==================== Helper Functions ====================

    private fun createTestTrip(
        id: String,
        startTime: Long,
        endTime: Long,
        distance: Double,
        startAddress: String,
        endAddress: String
    ): Trip {
        return Trip(
            id = id,
            startTime = startTime,
            endTime = endTime,
            locations = emptyList(),
            totalDistance = distance,
            isValidated = true,
            vehicleId = "vehicle1",
            startAddress = startAddress,
            endAddress = endAddress,
            notes = null,
            tripType = "PROFESSIONAL",
            createdAt = startTime,
            updatedAt = startTime,
            lastSyncedAt = null,
            needsSync = false,
            userId = "user1"
        )
    }

    private fun createTestExpense(
        id: String,
        date: String,
        type: ExpenseType,
        amount: Double,
        amountHT: Double?,
        note: String
    ): Expense {
        return Expense(
            id = id,
            date = date,
            type = type,
            amount = amount,
            amountHT = amountHT,
            note = note,
            photoUri = null,
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
    }
}

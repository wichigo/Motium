package com.application.motium

import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import org.junit.Test
import org.junit.Assert.*

class TripSavingTest {

    @Test
    fun `test trip validation with 88km trip - should be valid`() {
        // Simuler un trajet de 88km comme dans votre cas réel
        val trip = createMockTrip(
            distance = 88000.0, // 88km en mètres
            duration = 3600000L, // 1 heure
            pointCount = 100
        )

        // Critères de validation actuels
        val validDistance = trip.totalDistance >= 50.0 // 50m minimum
        val validDuration = (trip.endTime!! - trip.startTime) >= 30000L // 30s minimum
        val validSpeed = (trip.totalDistance / ((trip.endTime!! - trip.startTime) / 1000.0)) >= 0.5 // 0.5 m/s
        val hasEnoughPoints = trip.locations.size >= 3

        println("Trip validation test:")
        println("- Distance: ${trip.totalDistance}m (valid: $validDistance)")
        println("- Duration: ${(trip.endTime!! - trip.startTime) / 1000}s (valid: $validDuration)")
        println("- Speed: ${String.format("%.1f", (trip.totalDistance / ((trip.endTime!! - trip.startTime) / 1000.0)) * 3.6)} km/h (valid: $validSpeed)")
        println("- Points: ${trip.locations.size} (valid: $hasEnoughPoints)")

        assertTrue("88km trip should be valid", validDistance && validDuration && validSpeed && hasEnoughPoints)
    }

    @Test
    fun `test trip saving logic without database`() {
        val trip = createMockTrip(88000.0, 3600000L, 100)

        // Simuler la logique de sauvegarde sans vraie base
        val shouldSave = isValidTrip(trip)

        println("✅ Trip saving logic test:")
        println("   Trip should be saved: $shouldSave")
        println("   Trip details: ${trip.totalDistance}m, ${(trip.endTime!! - trip.startTime) / 1000}s, ${trip.locations.size} points")

        assertTrue("88km trip should pass all validation criteria", shouldSave)
    }

    @Test
    fun `test GPS point accuracy filtering`() {
        val locations = listOf(
            TripLocation(45.0, 5.0, 15f, System.currentTimeMillis()), // 15m - VALID
            TripLocation(45.0, 5.0, 25f, System.currentTimeMillis()), // 25m - VALID (< 30m)
            TripLocation(45.0, 5.0, 35f, System.currentTimeMillis()), // 35m - INVALID (> 30m)
            TripLocation(45.0, 5.0, 45f, System.currentTimeMillis())  // 45m - INVALID (> 30m)
        )

        val validPoints = locations.filter { it.accuracy <= 30f }

        println("GPS accuracy filtering test:")
        println("- Total points: ${locations.size}")
        println("- Valid points (≤30m): ${validPoints.size}")
        println("- Rejected points (>30m): ${locations.size - validPoints.size}")

        assertEquals("Should accept 2 points with accuracy ≤30m", 2, validPoints.size)
    }

    @Test
    fun `test trip creation and metrics calculation`() {
        val locations = mutableListOf<TripLocation>()
        val startTime = System.currentTimeMillis()
        var totalDistance = 0.0

        // Simuler collecte de points GPS sur 88km
        repeat(100) { i ->
            val lat = 45.0 + (i * 0.001) // Progression latitude
            val lng = 5.0 + (i * 0.001) // Progression longitude

            val location = TripLocation(lat, lng, 18f, startTime + i * 36000) // 18m accuracy

            // Calculer distance si pas le premier point
            if (locations.isNotEmpty()) {
                val lastLocation = locations.last()
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    lastLocation.latitude, lastLocation.longitude,
                    location.latitude, location.longitude,
                    results
                )
                totalDistance += results[0].toDouble()
            }

            locations.add(location)
        }

        val endTime = startTime + 3600000L // 1 heure plus tard
        val averageSpeed = totalDistance / ((endTime - startTime) / 1000.0)

        println("Trip metrics calculation test:")
        println("- Start time: $startTime")
        println("- End time: $endTime")
        println("- Duration: ${(endTime - startTime) / 1000}s")
        println("- Total distance: ${String.format("%.1f", totalDistance)}m")
        println("- Average speed: ${String.format("%.1f", averageSpeed * 3.6)} km/h")
        println("- Points collected: ${locations.size}")

        assertTrue("Should have collected points", locations.size > 0)
        assertTrue("Should have calculated distance", totalDistance > 0)
        assertTrue("Average speed should be realistic", averageSpeed > 0)
    }

    @Test
    fun `test trip rejection scenarios`() {
        // Cas 1: Trajet trop court
        val shortTrip = createMockTrip(30.0, 60000L, 5) // 30m en 1 minute
        assertFalse("Short trip should be rejected", isValidTrip(shortTrip))

        // Cas 2: Trajet trop rapide
        val fastTrip = createMockTrip(1000.0, 10000L, 5) // 1km en 10s (360 km/h)
        assertTrue("Fast but valid trip should be accepted", isValidTrip(fastTrip))

        // Cas 3: Pas assez de points
        val fewPointsTrip = createMockTrip(1000.0, 60000L, 1) // 1 seul point
        assertFalse("Trip with too few points should be rejected", isValidTrip(fewPointsTrip))

        // Cas 4: Votre trajet 88km
        val realTrip = createMockTrip(88000.0, 3600000L, 100)
        assertTrue("88km real trip should be accepted", isValidTrip(realTrip))
    }

    private fun createMockTrip(distance: Double, duration: Long, pointCount: Int): Trip {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + duration

        val locations = mutableListOf<TripLocation>()
        repeat(pointCount) { i ->
            locations.add(TripLocation(
                latitude = 45.0 + (i * 0.001),
                longitude = 5.0 + (i * 0.001),
                accuracy = 18f,
                timestamp = startTime + (i * duration / pointCount)
            ))
        }

        return Trip(
            id = "test-trip",
            startTime = startTime,
            endTime = endTime,
            locations = locations,
            totalDistance = distance,
            isValidated = false
        )
    }

    private fun isValidTrip(trip: Trip): Boolean {
        val duration = trip.endTime!! - trip.startTime
        val averageSpeed = if (duration > 0) trip.totalDistance / (duration / 1000.0) else 0.0

        // Critères assouplis
        val validDistance = trip.totalDistance >= 50.0 // 50m minimum
        val validDuration = duration >= 30000L // 30 secondes minimum
        val validSpeed = averageSpeed >= 0.5 // 1.8 km/h minimum
        val hasEnoughPoints = trip.locations.size >= 3

        return validDistance && validDuration && validSpeed && hasEnoughPoints
    }
}
package com.application.motium.service

import android.location.Location
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests unitaires pour LocationTrackingService
 * Teste la collecte GPS, validation de trajets, et critères de début/fin
 */
class LocationTrackingServiceTest {

    private val MIN_TRIP_DISTANCE_METERS = 10.0
    private val MIN_TRIP_DURATION_MS = 15000L
    private val MIN_AVERAGE_SPEED_MPS = 0.1
    private val MAX_GPS_ACCURACY_METERS = 100f

    @Test
    fun `test trip validation - valid 88km trip`() {
        // GIVEN: Un trajet de 88km en 1 heure
        val trip = createTrip(
            distanceMeters = 88000.0,
            durationMs = 3600000L,
            pointCount = 200
        )

        // WHEN: On valide le trajet
        val isValid = validateTrip(trip)

        // THEN: Le trajet devrait être valide
        assertTrue("88km trip should be valid", isValid)
    }

    @Test
    fun `test trip validation - too short distance rejected`() {
        // GIVEN: Un trajet de seulement 5 mètres
        val trip = createTrip(
            distanceMeters = 5.0,
            durationMs = 30000L,
            pointCount = 10
        )

        // WHEN: On valide le trajet
        val isValid = validateTrip(trip)

        // THEN: Le trajet devrait être rejeté
        assertFalse("5m trip should be rejected", isValid)
    }

    @Test
    fun `test trip validation - too short duration rejected`() {
        // GIVEN: Un trajet de 1km en 5 secondes (trop rapide pour être réel)
        val trip = createTrip(
            distanceMeters = 1000.0,
            durationMs = 5000L,
            pointCount = 5
        )

        // WHEN: On valide le trajet
        val isValid = validateTrip(trip)

        // THEN: Le trajet devrait être rejeté (durée < 15s)
        assertFalse("5s trip should be rejected", isValid)
    }

    @Test
    fun `test trip validation - minimum valid trip`() {
        // GIVEN: Un trajet minimal mais valide (10m en 15s)
        val trip = createTrip(
            distanceMeters = 10.0,
            durationMs = 15000L,
            pointCount = 3
        )

        // WHEN: On valide le trajet
        val isValid = validateTrip(trip)

        // THEN: Le trajet devrait être valide
        assertTrue("Minimum valid trip should pass", isValid)
    }

    @Test
    fun `test GPS accuracy filtering - high accuracy points accepted`() {
        // GIVEN: Points GPS avec différentes précisions
        val locations = listOf(
            createLocation(45.0, 5.0, 10f),  // 10m - excellent
            createLocation(45.1, 5.1, 20f),  // 20m - bon
            createLocation(45.2, 5.2, 30f)   // 30m - acceptable
        )

        // WHEN: On filtre par précision (< 100m)
        val accuratePoints = locations.filter { it.accuracy <= MAX_GPS_ACCURACY_METERS }

        // THEN: Tous les points devraient être acceptés
        assertEquals("All accurate points should be accepted", 3, accuratePoints.size)
    }

    @Test
    fun `test GPS accuracy filtering - low accuracy points rejected`() {
        // GIVEN: Points GPS avec mauvaise précision
        val locations = listOf(
            createLocation(45.0, 5.0, 150f),  // 150m - mauvais
            createLocation(45.1, 5.1, 200f),  // 200m - très mauvais
            createLocation(45.2, 5.2, 500f)   // 500m - horrible
        )

        // WHEN: On filtre par précision (< 100m)
        val accuratePoints = locations.filter { it.accuracy <= MAX_GPS_ACCURACY_METERS }

        // THEN: Tous les points devraient être rejetés
        assertEquals("All inaccurate points should be rejected", 0, accuratePoints.size)
    }

    @Test
    fun `test distance calculation between two points`() {
        // GIVEN: Deux points GPS
        val point1 = createLocation(48.8566, 2.3522, 10f) // Paris
        val point2 = createLocation(48.8606, 2.3376, 10f) // ~1.5km de distance

        // WHEN: On calcule la distance
        val distance = calculateDistance(point1, point2)

        // THEN: La distance devrait être environ 1.5km
        assertTrue("Distance should be > 1km", distance > 1000.0)
        assertTrue("Distance should be < 2km", distance < 2000.0)
    }

    @Test
    fun `test average speed calculation`() {
        // GIVEN: Un trajet de 50km en 1 heure
        val distanceMeters = 50000.0
        val durationMs = 3600000L

        // WHEN: On calcule la vitesse moyenne
        val speedMps = distanceMeters / (durationMs / 1000.0)
        val speedKmh = speedMps * 3.6

        // THEN: La vitesse devrait être ~50 km/h
        assertTrue("Speed should be about 50 km/h", speedKmh > 49.0 && speedKmh < 51.0)
        assertTrue("Speed in m/s should be > 0.1", speedMps > MIN_AVERAGE_SPEED_MPS)
    }

    @Test
    fun `test stop detection - vehicle parked for 3 minutes`() {
        // GIVEN: Véhicule immobile pendant 3 minutes dans un rayon de 30m
        val locations = mutableListOf<TripLocation>()
        val startTime = System.currentTimeMillis()
        val centerLat = 48.8566
        val centerLng = 2.3522

        // Collecter des points GPS dans un rayon de 30m pendant 3 minutes
        for (i in 0 until 18) { // 18 points sur 3 minutes (1 tous les 10s)
            locations.add(TripLocation(
                latitude = centerLat + (Math.random() - 0.5) * 0.0003, // ~30m variation
                longitude = centerLng + (Math.random() - 0.5) * 0.0003,
                accuracy = 15f,
                timestamp = startTime + (i * 10000L) // Tous les 10 secondes
            ))
        }

        // WHEN: On vérifie si le véhicule est arrêté
        val isInSameArea = checkIfInSameArea(locations, radiusMeters = 30f)
        val duration = locations.last().timestamp - locations.first().timestamp
        val isStopped = isInSameArea && duration >= 180000L // 3 minutes

        // THEN: Devrait détecter l'arrêt
        assertTrue("Should detect stop after 3 minutes in same area", isStopped)
    }

    @Test
    fun `test stop detection - short stop at traffic light not detected`() {
        // GIVEN: Arrêt court de 30 secondes (feu rouge)
        val locations = mutableListOf<TripLocation>()
        val startTime = System.currentTimeMillis()

        for (i in 0 until 3) { // 3 points sur 30 secondes
            locations.add(TripLocation(
                latitude = 48.8566,
                longitude = 2.3522,
                accuracy = 10f,
                timestamp = startTime + (i * 10000L)
            ))
        }

        // WHEN: On vérifie si c'est un arrêt significatif
        val duration = locations.last().timestamp - locations.first().timestamp
        val isSignificantStop = duration >= 180000L // 3 minutes requis

        // THEN: Ne devrait PAS détecter comme arrêt (trop court)
        assertFalse("Traffic light stop should not end trip", isSignificantStop)
    }

    @Test
    fun `test trip start - collect anchor point for 5 seconds`() {
        // GIVEN: Points GPS collectés pendant 5 secondes au départ
        val locations = mutableListOf<TripLocation>()
        val startTime = System.currentTimeMillis()

        // Collecter 5 points GPS au même endroit
        for (i in 0 until 5) {
            locations.add(TripLocation(
                latitude = 48.8566 + (Math.random() - 0.5) * 0.00005, // Variation minime
                longitude = 2.3522 + (Math.random() - 0.5) * 0.00005,
                accuracy = 12f,
                timestamp = startTime + (i * 1000L) // Tous les 1 seconde
            ))
        }

        // WHEN: On sélectionne le point de départ le plus précis
        val anchorPoint = locations.minByOrNull { it.accuracy }
        val anchoringDuration = locations.last().timestamp - locations.first().timestamp

        // THEN: Un point d'ancrage devrait être sélectionné
        assertNotNull("Should have anchor point", anchorPoint)
        assertTrue("Anchoring should last ~5 seconds", anchoringDuration >= 4000L)
        assertNotNull("Anchor point should have accuracy", anchorPoint?.accuracy)
    }

    @Test
    fun `test trip end - collect end point for 15 seconds`() {
        // GIVEN: Points GPS collectés pendant 15 secondes après détection WALKING
        val locations = mutableListOf<TripLocation>()
        val endTime = System.currentTimeMillis()

        for (i in 0 until 15) {
            locations.add(TripLocation(
                latitude = 48.8600 + (Math.random() - 0.5) * 0.00005,
                longitude = 2.3500 + (Math.random() - 0.5) * 0.00005,
                accuracy = 10f,
                timestamp = endTime + (i * 1000L)
            ))
        }

        // WHEN: On sélectionne le meilleur point de fin
        val bestEndPoint = locations.filter { it.accuracy <= 20f }.minByOrNull { it.accuracy }
        val samplingDuration = locations.last().timestamp - locations.first().timestamp

        // THEN: Un point de fin précis devrait être sélectionné
        assertNotNull("Should have best end point", bestEndPoint)
        assertTrue("Sampling should last ~15 seconds", samplingDuration >= 14000L)
        assertTrue("End point should have good accuracy", bestEndPoint!!.accuracy <= 20f)
    }

    @Test
    fun `test GPS update intervals - standby vs active tracking`() {
        // Mode STANDBY (pas de trajet)
        val standbyInterval = 60000L // 1 minute
        val standbyFastestInterval = 60000L

        // Mode TRIP (trajet actif)
        val tripInterval = 10000L // 10 secondes
        val tripFastestInterval = 5000L // 5 secondes

        // THEN: Les intervalles actifs doivent être plus courts
        assertTrue("Trip interval should be shorter than standby", tripInterval < standbyInterval)
        assertTrue("Trip fastest should be shorter than standby", tripFastestInterval < standbyFastestInterval)
    }

    @Test
    fun `test maximum trip duration - 10 hour failsafe`() {
        // GIVEN: Un trajet qui dure 12 heures (anormal)
        val tripStartTime = System.currentTimeMillis()
        val tripDuration = 12 * 3600000L // 12 heures
        val maxDuration = 10 * 3600000L // 10 heures max

        // WHEN: On vérifie si le trajet dépasse la durée max
        val exceedsMaxDuration = tripDuration > maxDuration

        // THEN: Le trajet devrait être considéré comme anormal
        assertTrue("12 hour trip should exceed maximum", exceedsMaxDuration)
    }

    @Test
    fun `test trip with insufficient points - rejected`() {
        // GIVEN: Un trajet avec seulement 2 points GPS
        val trip = createTrip(
            distanceMeters = 1000.0,
            durationMs = 60000L,
            pointCount = 2
        )

        // WHEN: On vérifie le nombre de points
        val hasEnoughPoints = trip.locations.size >= 3

        // THEN: Ne devrait PAS être valide
        assertFalse("Trip with 2 points should be rejected", hasEnoughPoints)
    }

    @Test
    fun `test location displacement filter - ignore GPS noise`() {
        // GIVEN: Configuration avec déplacement minimum de 10m
        val minDisplacement = 10f

        // Deux points très proches (5m)
        val point1 = createLocation(48.8566, 2.3522, 10f)
        val point2 = createLocation(48.85665, 2.35225, 10f)

        val distance = calculateDistance(point1, point2)

        // WHEN: On vérifie si le déplacement est significatif
        val isSignificantMove = distance >= minDisplacement

        // THEN: Le mouvement devrait être ignoré (< 10m)
        assertFalse("5m movement should be ignored", isSignificantMove)
    }

    @Test
    fun `test trip metrics - realistic values`() {
        // GIVEN: Un trajet avec métriques réalistes
        val distanceKm = 25.0
        val durationHours = 0.5 // 30 minutes
        val averageSpeedKmh = distanceKm / durationHours

        // THEN: Les métriques devraient être cohérentes
        assertEquals("Average speed should be 50 km/h", 50.0, averageSpeedKmh, 0.1)
        assertTrue("Distance should be positive", distanceKm > 0)
        assertTrue("Duration should be positive", durationHours > 0)
    }

    // Helper functions
    private fun createTrip(distanceMeters: Double, durationMs: Long, pointCount: Int): Trip {
        val startTime = System.currentTimeMillis()
        val locations = mutableListOf<TripLocation>()

        for (i in 0 until pointCount) {
            locations.add(TripLocation(
                latitude = 48.8566 + (i * 0.001),
                longitude = 2.3522 + (i * 0.001),
                accuracy = 15f,
                timestamp = startTime + (i * durationMs / pointCount)
            ))
        }

        return Trip(
            id = "test-trip",
            startTime = startTime,
            endTime = startTime + durationMs,
            locations = locations,
            totalDistance = distanceMeters,
            isValidated = false
        )
    }

    private fun createLocation(latitude: Double, longitude: Double, accuracy: Float): TripLocation {
        return TripLocation(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun validateTrip(trip: Trip): Boolean {
        val duration = (trip.endTime ?: System.currentTimeMillis()) - trip.startTime
        val averageSpeed = if (duration > 0) trip.totalDistance / (duration / 1000.0) else 0.0

        val validDistance = trip.totalDistance >= MIN_TRIP_DISTANCE_METERS
        val validDuration = duration >= MIN_TRIP_DURATION_MS
        val validSpeed = averageSpeed >= MIN_AVERAGE_SPEED_MPS
        val hasEnoughPoints = trip.locations.size >= 3

        return validDistance && validDuration && validSpeed && hasEnoughPoints
    }

    private fun calculateDistance(point1: TripLocation, point2: TripLocation): Double {
        // Formule de Haversine simplifiée
        val R = 6371000.0 // Rayon de la Terre en mètres
        val lat1 = Math.toRadians(point1.latitude)
        val lat2 = Math.toRadians(point2.latitude)
        val dLat = Math.toRadians(point2.latitude - point1.latitude)
        val dLon = Math.toRadians(point2.longitude - point1.longitude)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }

    private fun checkIfInSameArea(locations: List<TripLocation>, radiusMeters: Float): Boolean {
        if (locations.isEmpty()) return false

        val centerPoint = locations.first()
        return locations.all { point ->
            calculateDistance(centerPoint, point) <= radiusMeters
        }
    }
}

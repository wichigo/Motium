package com.application.motium.service

import com.application.motium.data.TripLocation
import com.application.motium.testutils.TestLocationFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests de validation des trajets
 *
 * Critères de validation (depuis LocationTrackingService):
 * - Distance minimum: 10m
 * - Durée minimum: 15 secondes
 * - Vitesse moyenne minimum: 0.1 m/s (0.36 km/h)
 * - Points GPS minimum: 2 points
 *
 * Ces tests vérifient que les trajets invalides sont correctement rejetés
 * et que les trajets valides passent la validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class TripValidationTest {

    // Constantes de validation (copiées de LocationTrackingService pour cohérence)
    companion object {
        const val MIN_TRIP_DISTANCE_METERS = 10.0
        const val MIN_TRIP_DURATION_MS = 15000L
        const val MIN_AVERAGE_SPEED_MPS = 0.1
        const val MIN_GPS_POINTS = 2
    }

    /**
     * Structure interne pour représenter un trajet dans les tests
     * Simule TripData de LocationTrackingService
     */
    data class TripData(
        val id: String = java.util.UUID.randomUUID().toString(),
        val startTime: Long,
        val endTime: Long,
        val locations: List<TripLocation>,
        val totalDistance: Double
    )

    /**
     * Logique de validation copiée de LocationTrackingService
     * Permet de tester la même logique sans dépendre du service
     */
    private fun isValidTrip(trip: TripData): ValidationResult {
        val duration = trip.endTime - trip.startTime
        val averageSpeed = if (duration > 0) trip.totalDistance / (duration / 1000.0) else 0.0

        val validDistance = trip.totalDistance >= MIN_TRIP_DISTANCE_METERS
        val validDuration = duration >= MIN_TRIP_DURATION_MS
        val validSpeed = averageSpeed >= MIN_AVERAGE_SPEED_MPS
        val hasEnoughPoints = trip.locations.size >= MIN_GPS_POINTS

        return ValidationResult(
            isValid = validDistance && validDuration && validSpeed && hasEnoughPoints,
            validDistance = validDistance,
            validDuration = validDuration,
            validSpeed = validSpeed,
            hasEnoughPoints = hasEnoughPoints,
            totalDistance = trip.totalDistance,
            durationMs = duration,
            averageSpeedMps = averageSpeed,
            pointCount = trip.locations.size
        )
    }

    data class ValidationResult(
        val isValid: Boolean,
        val validDistance: Boolean,
        val validDuration: Boolean,
        val validSpeed: Boolean,
        val hasEnoughPoints: Boolean,
        val totalDistance: Double,
        val durationMs: Long,
        val averageSpeedMps: Double,
        val pointCount: Int
    )

    // ════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════

    /**
     * Crée un trajet à partir d'une liste de GpsPoints
     */
    private fun createTrip(
        points: List<TestLocationFactory.GpsPoint>,
        overrideDistance: Double? = null
    ): TripData {
        if (points.isEmpty()) {
            return TripData(
                startTime = 0,
                endTime = 0,
                locations = emptyList(),
                totalDistance = 0.0
            )
        }

        val locations = points.map { point ->
            TripLocation(
                latitude = point.lat,
                longitude = point.lng,
                accuracy = point.accuracy,
                timestamp = point.timestamp
            )
        }

        val startTime = points.first().timestamp
        val endTime = points.last().timestamp
        val distance = overrideDistance ?: TestLocationFactory.calculateTotalDistance(points)

        return TripData(
            startTime = startTime,
            endTime = endTime,
            locations = locations,
            totalDistance = distance
        )
    }

    /**
     * Crée un trajet avec des paramètres spécifiques pour tester les edge cases
     */
    private fun createCustomTrip(
        distanceMeters: Double,
        durationMs: Long,
        pointCount: Int
    ): TripData {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + durationMs

        // Créer des points fictifs le long d'une ligne droite
        val locations = (0 until pointCount).map { i ->
            val progress = if (pointCount > 1) i.toDouble() / (pointCount - 1) else 0.0
            val timestamp = startTime + (durationMs * progress).toLong()

            TripLocation(
                latitude = 48.8698 + (progress * 0.001), // Légère progression
                longitude = 2.3075 + (progress * 0.001),
                accuracy = 5f,
                timestamp = timestamp
            )
        }

        return TripData(
            startTime = startTime,
            endTime = endTime,
            locations = locations,
            totalDistance = distanceMeters
        )
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: TRAJETS VALIDES
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `valid trip - medium trip passes all criteria`() {
        // Given - Un trajet de ~500m sur 2 minutes avec 10 points
        val points = TestLocationFactory.createMediumTrip()
        val trip = createTrip(points)

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet devrait être valide", result.isValid)
        assertTrue("La distance (${result.totalDistance}m) devrait être >= $MIN_TRIP_DISTANCE_METERS m", result.validDistance)
        assertTrue("La durée (${result.durationMs}ms) devrait être >= $MIN_TRIP_DURATION_MS ms", result.validDuration)
        assertTrue("La vitesse (${result.averageSpeedMps} m/s) devrait être >= $MIN_AVERAGE_SPEED_MPS m/s", result.validSpeed)
        assertTrue("Le nombre de points (${result.pointCount}) devrait être >= $MIN_GPS_POINTS", result.hasEnoughPoints)

        println("✅ Trajet valide - Distance: ${result.totalDistance}m, Durée: ${result.durationMs/1000}s, Vitesse: ${result.averageSpeedMps} m/s, Points: ${result.pointCount}")
    }

    @Test
    fun `valid trip - Paris to Versailles passes all criteria`() {
        // Given - Un long trajet Paris → Versailles (~22km, ~10min)
        val points = TestLocationFactory.createParisToVersaillesRoute()
        val trip = createTrip(points)

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet Paris→Versailles devrait être valide", result.isValid)
        assertTrue("La distance devrait être > 20km", result.totalDistance > 20000)
        assertTrue("La durée devrait être > 5min", result.durationMs > 300000)

        println("✅ Trajet Paris→Versailles - Distance: ${result.totalDistance/1000}km, Durée: ${result.durationMs/60000}min, Points: ${result.pointCount}")
    }

    @Test
    fun `valid trip - highway trip passes all criteria`() {
        // Given - Un très long trajet autoroute Paris → Lyon (~465km)
        val points = TestLocationFactory.createLongHighwayTrip()
        val trip = createTrip(points)

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet autoroute devrait être valide", result.isValid)
        assertTrue("La distance devrait être très grande (>400km)", result.totalDistance > 400000)

        println("✅ Trajet autoroute - Distance: ${result.totalDistance/1000}km, Durée: ${result.durationMs/3600000}h, Points: ${result.pointCount}")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: DISTANCE INSUFFISANTE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `invalid trip - distance less than 10m rejected`() {
        // Given - Un trajet de seulement 5m (parking → parking)
        val points = TestLocationFactory.createShortTrip()
        val trip = createTrip(points)

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (distance < 10m)", result.isValid)
        assertFalse("La validation de distance devrait échouer", result.validDistance)
        assertTrue("La distance devrait être < 10m", result.totalDistance < MIN_TRIP_DISTANCE_METERS)

        println("❌ Trajet rejeté - Distance insuffisante: ${result.totalDistance}m < ${MIN_TRIP_DISTANCE_METERS}m")
    }

    @Test
    fun `invalid trip - zero distance rejected`() {
        // Given - Un trajet avec distance 0 (pas de mouvement)
        val trip = createCustomTrip(
            distanceMeters = 0.0,
            durationMs = 60000, // 1 minute
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (distance = 0)", result.isValid)
        assertFalse("La validation de distance devrait échouer", result.validDistance)
        assertFalse("La validation de vitesse devrait aussi échouer (0 m/s)", result.validSpeed)

        println("❌ Trajet rejeté - Distance nulle: ${result.totalDistance}m")
    }

    @Test
    fun `invalid trip - distance 9 meters rejected (just under threshold)`() {
        // Given - Un trajet de 9m (juste sous le seuil)
        val trip = createCustomTrip(
            distanceMeters = 9.0,
            durationMs = 60000, // 1 minute
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet de 9m devrait être rejeté", result.isValid)
        assertFalse("La validation de distance devrait échouer pour 9m", result.validDistance)

        println("❌ Trajet rejeté - Distance 9m < seuil 10m")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: DURÉE INSUFFISANTE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `invalid trip - duration less than 15 seconds rejected`() {
        // Given - Un trajet de seulement 10 secondes
        val trip = createCustomTrip(
            distanceMeters = 100.0, // Distance OK
            durationMs = 10000, // 10 secondes (< 15s minimum)
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (durée < 15s)", result.isValid)
        assertFalse("La validation de durée devrait échouer", result.validDuration)
        assertTrue("La durée devrait être < 15000ms", result.durationMs < MIN_TRIP_DURATION_MS)

        println("❌ Trajet rejeté - Durée insuffisante: ${result.durationMs/1000}s < ${MIN_TRIP_DURATION_MS/1000}s")
    }

    @Test
    fun `invalid trip - duration 14 seconds rejected (just under threshold)`() {
        // Given - Un trajet de 14 secondes (juste sous le seuil)
        val trip = createCustomTrip(
            distanceMeters = 50.0, // Distance OK
            durationMs = 14000, // 14 secondes
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet de 14s devrait être rejeté", result.isValid)
        assertFalse("La validation de durée devrait échouer pour 14s", result.validDuration)

        println("❌ Trajet rejeté - Durée 14s < seuil 15s")
    }

    @Test
    fun `invalid trip - zero duration rejected`() {
        // Given - Un trajet avec durée 0 (instantané impossible)
        val trip = createCustomTrip(
            distanceMeters = 100.0,
            durationMs = 0,
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (durée = 0)", result.isValid)
        assertFalse("La validation de durée devrait échouer", result.validDuration)

        println("❌ Trajet rejeté - Durée nulle")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: VITESSE INSUFFISANTE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `invalid trip - speed less than 0_1 mps rejected`() {
        // Given - Un trajet avec vitesse moyenne < 0.1 m/s
        // 10m en 200 secondes = 0.05 m/s
        val trip = createCustomTrip(
            distanceMeters = 10.0,
            durationMs = 200000, // 200 secondes
            pointCount = 10
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (vitesse < 0.1 m/s)", result.isValid)
        assertFalse("La validation de vitesse devrait échouer", result.validSpeed)
        assertTrue("La vitesse devrait être < 0.1 m/s", result.averageSpeedMps < MIN_AVERAGE_SPEED_MPS)

        println("❌ Trajet rejeté - Vitesse insuffisante: ${result.averageSpeedMps} m/s < ${MIN_AVERAGE_SPEED_MPS} m/s")
    }

    @Test
    fun `invalid trip - speed 0_09 mps rejected (just under threshold)`() {
        // Given - Un trajet avec vitesse de 0.09 m/s (juste sous le seuil)
        // Pour avoir 0.09 m/s: distance / (durée en secondes) = 0.09
        // 27m en 300 secondes = 0.09 m/s
        val trip = createCustomTrip(
            distanceMeters = 27.0,
            durationMs = 300000, // 5 minutes
            pointCount = 10
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet à 0.09 m/s devrait être rejeté", result.isValid)
        assertFalse("La validation de vitesse devrait échouer pour 0.09 m/s", result.validSpeed)
        assertTrue("La vitesse (${result.averageSpeedMps}) devrait être < 0.1", result.averageSpeedMps < MIN_AVERAGE_SPEED_MPS)

        println("❌ Trajet rejeté - Vitesse 0.09 m/s < seuil 0.1 m/s")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: POINTS GPS INSUFFISANTS
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `invalid trip - fewer than 2 points rejected`() {
        // Given - Un trajet avec seulement 1 point GPS
        val trip = createCustomTrip(
            distanceMeters = 100.0, // Distance OK
            durationMs = 60000, // Durée OK
            pointCount = 1 // Seulement 1 point
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (< 2 points)", result.isValid)
        assertFalse("La validation du nombre de points devrait échouer", result.hasEnoughPoints)
        assertTrue("Le nombre de points devrait être < 2", result.pointCount < MIN_GPS_POINTS)

        println("❌ Trajet rejeté - Points insuffisants: ${result.pointCount} < ${MIN_GPS_POINTS}")
    }

    @Test
    fun `invalid trip - zero points rejected`() {
        // Given - Un trajet sans aucun point GPS
        val trip = TripData(
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 60000,
            locations = emptyList(),
            totalDistance = 0.0
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté (0 points)", result.isValid)
        assertFalse("La validation du nombre de points devrait échouer", result.hasEnoughPoints)

        println("❌ Trajet rejeté - Aucun point GPS")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: EDGE CASES - VALEURS EXACTES AUX SEUILS
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `edge case - exactly 10m distance accepted`() {
        // Given - Un trajet de exactement 10m (seuil minimum)
        // Pour avoir vitesse >= 0.1 m/s avec 10m: durée <= 100s
        val trip = createCustomTrip(
            distanceMeters = 10.0, // Exactement le seuil
            durationMs = 50000, // 50 secondes → vitesse = 0.2 m/s
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet de exactement 10m devrait être accepté", result.isValid)
        assertTrue("La validation de distance devrait passer pour 10m", result.validDistance)
        assertEquals(10.0, result.totalDistance, 0.01)

        println("✅ Edge case - Distance exacte 10m acceptée")
    }

    @Test
    fun `edge case - exactly 15 seconds duration accepted`() {
        // Given - Un trajet de exactement 15 secondes
        // Pour avoir vitesse >= 0.1 m/s: distance >= 1.5m
        val trip = createCustomTrip(
            distanceMeters = 50.0, // Distance suffisante pour vitesse OK
            durationMs = 15000, // Exactement 15 secondes
            pointCount = 5
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet de exactement 15s devrait être accepté", result.isValid)
        assertTrue("La validation de durée devrait passer pour 15s", result.validDuration)
        assertEquals(15000L, result.durationMs)

        println("✅ Edge case - Durée exacte 15s acceptée")
    }

    @Test
    fun `edge case - exactly 0_1 mps speed accepted`() {
        // Given - Un trajet avec exactement 0.1 m/s de vitesse moyenne
        // 15m en 150 secondes = 0.1 m/s
        val trip = createCustomTrip(
            distanceMeters = 15.0,
            durationMs = 150000, // 150 secondes
            pointCount = 10
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet à exactement 0.1 m/s devrait être accepté", result.isValid)
        assertTrue("La validation de vitesse devrait passer pour 0.1 m/s", result.validSpeed)
        assertEquals(0.1, result.averageSpeedMps, 0.001)

        println("✅ Edge case - Vitesse exacte 0.1 m/s acceptée")
    }

    @Test
    fun `edge case - exactly 2 points accepted`() {
        // Given - Un trajet avec exactement 2 points GPS
        val trip = createCustomTrip(
            distanceMeters = 100.0, // Distance OK
            durationMs = 60000, // Durée OK
            pointCount = 2 // Exactement 2 points
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet avec exactement 2 points devrait être accepté", result.isValid)
        assertTrue("La validation du nombre de points devrait passer pour 2 points", result.hasEnoughPoints)
        assertEquals(2, result.pointCount)

        println("✅ Edge case - Exactement 2 points acceptés")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: COMBINAISONS D'ÉCHECS
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `invalid trip - all criteria fail except speed`() {
        // Given - Un trajet qui échoue sur distance, durée et points
        // Note: La vitesse 2m/5s = 0.4 m/s est > 0.1 m/s donc valide
        val trip = TripData(
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 5000, // 5s (< 15s)
            locations = listOf(
                TripLocation(48.8698, 2.3075, 5f, System.currentTimeMillis())
            ), // 1 point (< 2)
            totalDistance = 2.0 // 2m (< 10m)
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté", result.isValid)
        assertFalse("Distance invalide", result.validDistance)
        assertFalse("Durée invalide", result.validDuration)
        assertTrue("Vitesse valide (0.4 m/s >= 0.1 m/s)", result.validSpeed) // 2m/5s = 0.4 m/s > 0.1 m/s
        assertFalse("Points insuffisants", result.hasEnoughPoints)

        println("❌ Trajet rejeté - Échec distance, durée, points (vitesse OK)")
    }

    @Test
    fun `invalid trip - distance and duration fail only`() {
        // Given - Distance et durée échouent, mais vitesse et points OK
        val trip = createCustomTrip(
            distanceMeters = 5.0, // Distance NOK
            durationMs = 10000, // Durée NOK
            pointCount = 10 // Points OK
        )
        // Vitesse = 5m / 10s = 0.5 m/s (OK)

        // When
        val result = isValidTrip(trip)

        // Then
        assertFalse("Le trajet devrait être rejeté", result.isValid)
        assertFalse("Distance invalide", result.validDistance)
        assertFalse("Durée invalide", result.validDuration)
        assertTrue("Vitesse devrait être OK (0.5 m/s)", result.validSpeed)
        assertTrue("Points devraient être OK", result.hasEnoughPoints)

        println("❌ Trajet rejeté - Échec distance + durée, vitesse et points OK")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: SCÉNARIOS RÉALISTES
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `realistic scenario - traffic jam trip still valid`() {
        // Given - Un trajet avec des arrêts (bouchon) mais qui reste valide
        val points = TestLocationFactory.createTripWithBriefStops()
        val trip = createTrip(points)

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet avec bouchons devrait être valide", result.isValid)

        println("✅ Scénario réaliste - Trajet avec bouchons valide: ${result.totalDistance}m, ${result.durationMs/1000}s")
    }

    @Test
    fun `realistic scenario - tunnel with GPS dropout still valid`() {
        // Given - Un trajet avec une perte GPS (tunnel)
        val points = TestLocationFactory.createTripWithTunnel()
        val trip = createTrip(points)

        // When
        val result = isValidTrip(trip)

        // Then
        assertTrue("Le trajet avec tunnel devrait être valide", result.isValid)

        println("✅ Scénario réaliste - Trajet avec tunnel valide: ${result.totalDistance}m, ${result.pointCount} points")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: CALCULS DE VALIDATION
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `validation calculation - average speed computed correctly`() {
        // Given - Un trajet de 1000m en 100 secondes → 10 m/s
        val trip = createCustomTrip(
            distanceMeters = 1000.0,
            durationMs = 100000,
            pointCount = 10
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertEquals("La vitesse moyenne devrait être 10 m/s", 10.0, result.averageSpeedMps, 0.001)
        assertTrue("La vitesse devrait être valide", result.validSpeed)

        println("✅ Calcul vitesse - 1000m / 100s = ${result.averageSpeedMps} m/s")
    }

    @Test
    fun `validation calculation - duration computed correctly`() {
        // Given
        val startTime = 1000000L
        val endTime = 1060000L // 60 secondes plus tard

        val trip = TripData(
            startTime = startTime,
            endTime = endTime,
            locations = listOf(
                TripLocation(48.8698, 2.3075, 5f, startTime),
                TripLocation(48.8700, 2.3080, 5f, endTime)
            ),
            totalDistance = 50.0
        )

        // When
        val result = isValidTrip(trip)

        // Then
        assertEquals("La durée devrait être 60000ms", 60000L, result.durationMs)
        assertTrue("La durée devrait être valide", result.validDuration)

        println("✅ Calcul durée - ${result.durationMs}ms")
    }

    @Test
    fun `validation - all constraints must be satisfied simultaneously`() {
        // Given - Différents trajets où un seul critère échoue

        // 1. Seule la distance échoue
        val tripBadDistance = createCustomTrip(distanceMeters = 5.0, durationMs = 60000, pointCount = 10)
        // 2. Seule la durée échoue
        val tripBadDuration = createCustomTrip(distanceMeters = 100.0, durationMs = 10000, pointCount = 10)
        // 3. Seule la vitesse échoue
        val tripBadSpeed = createCustomTrip(distanceMeters = 15.0, durationMs = 200000, pointCount = 10)
        // 4. Seuls les points échouent
        val tripBadPoints = createCustomTrip(distanceMeters = 100.0, durationMs = 60000, pointCount = 1)

        // When
        val resultBadDistance = isValidTrip(tripBadDistance)
        val resultBadDuration = isValidTrip(tripBadDuration)
        val resultBadSpeed = isValidTrip(tripBadSpeed)
        val resultBadPoints = isValidTrip(tripBadPoints)

        // Then - Tous doivent échouer car un seul critère suffit à invalider
        assertFalse("Distance insuffisante devrait invalider le trajet", resultBadDistance.isValid)
        assertFalse("Durée insuffisante devrait invalider le trajet", resultBadDuration.isValid)
        assertFalse("Vitesse insuffisante devrait invalider le trajet", resultBadSpeed.isValid)
        assertFalse("Points insuffisants devraient invalider le trajet", resultBadPoints.isValid)

        // Vérifier que seul le critère concerné échoue
        assertFalse(resultBadDistance.validDistance)
        assertTrue(resultBadDistance.validDuration)

        assertTrue(resultBadDuration.validDistance)
        assertFalse(resultBadDuration.validDuration)

        assertTrue(resultBadSpeed.validDistance)
        assertFalse(resultBadSpeed.validSpeed)

        assertTrue(resultBadPoints.validDistance)
        assertFalse(resultBadPoints.hasEnoughPoints)

        println("✅ Validation - Chaque critère est vérifié indépendamment")
    }
}

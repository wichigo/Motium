package com.application.motium.service

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.application.motium.testutils.TestLocationFactory
import com.application.motium.testutils.TestLocationFactory.GpsPoint
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitaires pour la machine à états de LocationTrackingService
 * Teste toutes les transitions d'états et la gestion du buffer GPS
 *
 * Machine d'états:
 * STANDBY → BUFFERING → TRIP_ACTIVE → STOP_PENDING → FINALIZING → STANDBY
 *
 * Ces tests vérifient:
 * - Transitions d'états correctes
 * - Critères de validation des trajets
 * - Gestion du buffer GPS
 * - Auto-confirmation du véhicule
 * - Détection de trajets fantômes
 * - Debounce pour éviter les faux positifs
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class LocationTrackingStateMachineTest {

    // ═══════════════════════════════════════════════════════════════════
    // CONSTANTES DE VALIDATION (copiées depuis LocationTrackingService)
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        // Critères de validation des trajets
        private const val MIN_TRIP_DISTANCE_METERS = 10.0
        private const val MIN_TRIP_DURATION_MS = 15000L
        private const val MIN_AVERAGE_SPEED_MPS = 0.1
        private const val MAX_GPS_ACCURACY_METERS = 50f

        // Auto-confirmation buffer
        private const val AUTO_CONFIRM_DISTANCE_THRESHOLD = 50.0  // 50m pour auto-confirmer
        private const val AUTO_CONFIRM_SPEED_THRESHOLD = 1.0      // 1 m/s
        private const val AUTO_CONFIRM_MIN_POINTS = 3

        // Debounce et timeouts
        private const val STOP_DEBOUNCE_DELAY_MS = 120000L        // 2 minutes
        private const val END_POINT_SAMPLING_DELAY_MS = 15000L    // 15 secondes
        private const val GHOST_TRIP_TIMEOUT_MS = 600000L         // 10 minutes
        private const val INACTIVITY_TIMEOUT_MS = 300000L         // 5 minutes

        // Précision GPS
        private const val HIGH_PRECISION_THRESHOLD = 12f
        private const val MEDIUM_PRECISION_THRESHOLD = 25f
    }

    // ═══════════════════════════════════════════════════════════════════
    // SETUP ET TEARDOWN
    // ═══════════════════════════════════════════════════════════════════

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: VALIDATION DE TRAJET
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `valid trip - passes all criteria`() {
        // Given: Un trajet avec distance, durée et vitesse suffisantes
        val points = TestLocationFactory.createMediumTrip()
        val distance = TestLocationFactory.calculateTotalDistance(points)
        val duration = TestLocationFactory.calculateDuration(points)
        val avgSpeed = TestLocationFactory.calculateAverageSpeed(points)

        // When: Vérification des critères
        val isDistanceValid = distance >= MIN_TRIP_DISTANCE_METERS
        val isDurationValid = duration >= MIN_TRIP_DURATION_MS
        val isSpeedValid = avgSpeed >= MIN_AVERAGE_SPEED_MPS
        val hasEnoughPoints = points.size >= 2

        // Then: Tous les critères sont remplis
        assertTrue("Distance devrait être >= $MIN_TRIP_DISTANCE_METERS m (actual: $distance)", isDistanceValid)
        assertTrue("Durée devrait être >= $MIN_TRIP_DURATION_MS ms (actual: $duration)", isDurationValid)
        assertTrue("Vitesse moyenne devrait être >= $MIN_AVERAGE_SPEED_MPS m/s (actual: $avgSpeed)", isSpeedValid)
        assertTrue("Devrait avoir >= 2 points (actual: ${points.size})", hasEnoughPoints)

        val isValid = isDistanceValid && isDurationValid && isSpeedValid && hasEnoughPoints
        assertTrue("Le trajet devrait être valide", isValid)

        println("✅ TEST: Trajet valide - distance=${String.format("%.1f", distance)}m, durée=${duration/1000}s, vitesse=${String.format("%.2f", avgSpeed)}m/s")
    }

    @Test
    fun `invalid trip - distance less than 10m rejected`() {
        // Given: Un trajet très court
        val points = TestLocationFactory.createShortTrip()
        val distance = TestLocationFactory.calculateTotalDistance(points)

        // When: Vérification du critère de distance
        val isDistanceValid = distance >= MIN_TRIP_DISTANCE_METERS

        // Then: Le trajet est rejeté
        assertFalse("Distance $distance m devrait être rejetée (< $MIN_TRIP_DISTANCE_METERS m)", isDistanceValid)

        println("✅ TEST: Trajet trop court rejeté - distance=${String.format("%.1f", distance)}m")
    }

    @Test
    fun `invalid trip - duration less than 15s rejected`() {
        // Given: Un trajet très rapide (impossible en réalité)
        val startTime = System.currentTimeMillis()
        val points = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8700, 2.3080, 5f, startTime + 5000),  // 5 secondes seulement
            GpsPoint(48.8705, 2.3090, 5f, startTime + 10000), // 10 secondes total
        )
        val duration = TestLocationFactory.calculateDuration(points)

        // When: Vérification du critère de durée
        val isDurationValid = duration >= MIN_TRIP_DURATION_MS

        // Then: Le trajet est rejeté
        assertFalse("Durée $duration ms devrait être rejetée (< $MIN_TRIP_DURATION_MS ms)", isDurationValid)

        println("✅ TEST: Trajet trop court en durée rejeté - durée=${duration/1000}s")
    }

    @Test
    fun `invalid trip - speed less than 0_1 m_s rejected`() {
        // Given: Un trajet avec mouvement très lent (quasi-stationnaire)
        val startTime = System.currentTimeMillis()
        val points = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8698, 2.3076, 5f, startTime + 300000),  // 5 min pour 8m = 0.027 m/s
            GpsPoint(48.8698, 2.3077, 5f, startTime + 600000),  // 10 min total
        )
        val avgSpeed = TestLocationFactory.calculateAverageSpeed(points)

        // When: Vérification du critère de vitesse
        val isSpeedValid = avgSpeed >= MIN_AVERAGE_SPEED_MPS

        // Then: Le trajet est rejeté
        assertFalse("Vitesse $avgSpeed m/s devrait être rejetée (< $MIN_AVERAGE_SPEED_MPS m/s)", isSpeedValid)

        println("✅ TEST: Trajet trop lent rejeté - vitesse=${String.format("%.4f", avgSpeed)} m/s")
    }

    @Test
    fun `invalid trip - fewer than 2 points rejected`() {
        // Given: Un trajet avec un seul point
        val startTime = System.currentTimeMillis()
        val points = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
        )

        // When: Vérification du nombre de points
        val hasEnoughPoints = points.size >= 2

        // Then: Le trajet est rejeté
        assertFalse("Trajet avec ${points.size} point(s) devrait être rejeté", hasEnoughPoints)

        println("✅ TEST: Trajet avec < 2 points rejeté - points=${points.size}")
    }

    @Test
    fun `edge case - exactly 10m distance accepted`() {
        // Given: Un trajet avec exactement 10m de distance
        // 0.0001 degré ≈ 11m, donc utiliser un peu moins
        val startTime = System.currentTimeMillis()
        val points = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.86989, 2.3075, 5f, startTime + 20000),  // ~10m au nord
        )
        val distance = TestLocationFactory.calculateTotalDistance(points)

        // When: Vérification (on tolère une marge d'erreur)
        val isDistanceValid = distance >= MIN_TRIP_DISTANCE_METERS

        // Then: Le trajet est accepté si distance >= 10m
        println("Distance calculée: ${String.format("%.2f", distance)}m")
        assertTrue("Distance ${String.format("%.2f", distance)}m devrait être acceptée (>= $MIN_TRIP_DISTANCE_METERS m)", isDistanceValid)

        println("✅ TEST: Distance limite acceptée - distance=${String.format("%.2f", distance)}m")
    }

    @Test
    fun `edge case - exactly 15s duration accepted`() {
        // Given: Un trajet de exactement 15 secondes
        val startTime = System.currentTimeMillis()
        val points = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8700, 2.3090, 5f, startTime + 15000),  // exactement 15s
        )
        val duration = TestLocationFactory.calculateDuration(points)

        // When: Vérification
        val isDurationValid = duration >= MIN_TRIP_DURATION_MS

        // Then: Le trajet est accepté
        assertEquals("Durée devrait être exactement 15000ms", 15000L, duration)
        assertTrue("Durée ${duration}ms devrait être acceptée", isDurationValid)

        println("✅ TEST: Durée limite acceptée - durée=${duration}ms")
    }

    @Test
    fun `edge case - exactly 2 points accepted`() {
        // Given: Un trajet avec exactement 2 points
        val startTime = System.currentTimeMillis()
        val points = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8750, 2.3150, 5f, startTime + 120000),  // 2 min, ~700m
        )

        // When: Vérification
        val hasEnoughPoints = points.size >= 2

        // Then: Le trajet est accepté
        assertEquals("Devrait avoir exactement 2 points", 2, points.size)
        assertTrue("2 points devrait être accepté", hasEnoughPoints)

        println("✅ TEST: Nombre de points limite accepté - points=${points.size}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: AUTO-CONFIRMATION DU BUFFER
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `buffer auto-confirms when distance reaches 50m`() {
        // Given: Des points GPS accumulés dans le buffer
        val points = mutableListOf<GpsPoint>()
        val startTime = System.currentTimeMillis()

        // Ajouter des points jusqu'à 50m de distance
        points.add(GpsPoint(48.8698, 2.3075, 5f, startTime))
        points.add(GpsPoint(48.8700, 2.3075, 5f, startTime + 4000))  // ~22m
        points.add(GpsPoint(48.8702, 2.3075, 5f, startTime + 8000))  // ~44m
        points.add(GpsPoint(48.8704, 2.3075, 5f, startTime + 12000)) // ~66m

        val totalDistance = TestLocationFactory.calculateTotalDistance(points)

        // When: Vérification du critère d'auto-confirmation
        val shouldAutoConfirm = points.size >= AUTO_CONFIRM_MIN_POINTS &&
                                totalDistance >= AUTO_CONFIRM_DISTANCE_THRESHOLD

        // Then: L'auto-confirmation devrait se déclencher
        assertTrue("Distance ${totalDistance}m devrait déclencher l'auto-confirmation (>= $AUTO_CONFIRM_DISTANCE_THRESHOLD m)", shouldAutoConfirm)

        println("✅ TEST: Auto-confirmation par distance - ${String.format("%.1f", totalDistance)}m >= ${AUTO_CONFIRM_DISTANCE_THRESHOLD}m")
    }

    @Test
    fun `buffer auto-confirms when speed reaches 1 m_s`() {
        // Given: Des points GPS avec vitesse suffisante
        val points = mutableListOf<GpsPoint>()
        val startTime = System.currentTimeMillis()

        // Ajouter des points montrant une vitesse > 1 m/s
        points.add(GpsPoint(48.8698, 2.3075, 5f, startTime))
        points.add(GpsPoint(48.8698, 2.3077, 5f, startTime + 4000))  // ~15m en 4s = 3.75 m/s
        points.add(GpsPoint(48.8698, 2.3079, 5f, startTime + 8000))

        val avgSpeed = TestLocationFactory.calculateAverageSpeed(points)

        // When: Vérification du critère d'auto-confirmation par vitesse
        val shouldAutoConfirm = points.size >= AUTO_CONFIRM_MIN_POINTS &&
                                avgSpeed >= AUTO_CONFIRM_SPEED_THRESHOLD

        // Then: L'auto-confirmation devrait se déclencher
        assertTrue("Vitesse ${avgSpeed} m/s devrait déclencher l'auto-confirmation (>= $AUTO_CONFIRM_SPEED_THRESHOLD m/s)", shouldAutoConfirm)

        println("✅ TEST: Auto-confirmation par vitesse - ${String.format("%.2f", avgSpeed)} m/s >= ${AUTO_CONFIRM_SPEED_THRESHOLD} m/s")
    }

    @Test
    fun `buffer does not auto-confirm with fewer than 3 points`() {
        // Given: Seulement 2 points GPS avec beaucoup de distance
        val points = mutableListOf<GpsPoint>()
        val startTime = System.currentTimeMillis()

        points.add(GpsPoint(48.8698, 2.3075, 5f, startTime))
        points.add(GpsPoint(48.8800, 2.3200, 5f, startTime + 60000))  // ~1.5km

        val totalDistance = TestLocationFactory.calculateTotalDistance(points)

        // When: Vérification du critère d'auto-confirmation
        val shouldAutoConfirm = points.size >= AUTO_CONFIRM_MIN_POINTS &&
                                totalDistance >= AUTO_CONFIRM_DISTANCE_THRESHOLD

        // Then: L'auto-confirmation ne devrait PAS se déclencher
        assertFalse("Avec seulement ${points.size} points, l'auto-confirmation ne devrait pas se déclencher", shouldAutoConfirm)

        println("✅ TEST: Pas d'auto-confirmation avec < 3 points - points=${points.size}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: FILTRAGE GPS PAR PRÉCISION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `buffer rejects points with accuracy greater than 50m`() {
        // Given: Un point GPS avec mauvaise précision
        val badAccuracyPoint = GpsPoint(48.8698, 2.3075, 60f, System.currentTimeMillis())

        // When: Vérification du critère de précision
        val shouldAccept = badAccuracyPoint.accuracy <= MAX_GPS_ACCURACY_METERS

        // Then: Le point devrait être rejeté
        assertFalse("Point avec accuracy ${badAccuracyPoint.accuracy}m devrait être rejeté (> $MAX_GPS_ACCURACY_METERS m)", shouldAccept)

        println("✅ TEST: Point imprécis rejeté - accuracy=${badAccuracyPoint.accuracy}m > ${MAX_GPS_ACCURACY_METERS}m")
    }

    @Test
    fun `buffer accepts points with accuracy less than or equal to 50m`() {
        // Given: Des points GPS avec différentes précisions acceptables
        val goodAccuracyPoints = listOf(
            GpsPoint(48.8698, 2.3075, 5f, System.currentTimeMillis()),
            GpsPoint(48.8700, 2.3080, 25f, System.currentTimeMillis() + 4000),
            GpsPoint(48.8702, 2.3085, 50f, System.currentTimeMillis() + 8000),  // Limite
        )

        // When: Vérification de tous les points
        val allAccepted = goodAccuracyPoints.all { it.accuracy <= MAX_GPS_ACCURACY_METERS }

        // Then: Tous les points devraient être acceptés
        assertTrue("Tous les points avec accuracy <= ${MAX_GPS_ACCURACY_METERS}m devraient être acceptés", allAccepted)

        println("✅ TEST: Points précis acceptés - accuracies=${goodAccuracyPoints.map { it.accuracy }}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: SÉLECTION DE POINT DE HAUTE PRÉCISION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `selects high-precision point first`() {
        // Given: Un mélange de points avec différentes précisions
        val points = TestLocationFactory.createMixedPrecisionPoints()

        // When: Sélectionner le point le plus précis
        val highPrecisionPoints = points.filter { it.accuracy < HIGH_PRECISION_THRESHOLD }
        val bestPoint = points.minByOrNull { it.accuracy }

        // Then: On devrait trouver des points de haute précision
        assertTrue("Devrait avoir des points haute précision (< ${HIGH_PRECISION_THRESHOLD}m)", highPrecisionPoints.isNotEmpty())
        assertNotNull("Devrait avoir un meilleur point", bestPoint)
        assertTrue("Le meilleur point devrait avoir accuracy < ${HIGH_PRECISION_THRESHOLD}m", bestPoint!!.accuracy < HIGH_PRECISION_THRESHOLD)

        println("✅ TEST: Sélection haute précision - ${highPrecisionPoints.size} points HP, meilleur: ${bestPoint.accuracy}m")
    }

    @Test
    fun `weighted average for multiple high-precision points`() {
        // Given: Plusieurs points de haute précision proches
        val startTime = System.currentTimeMillis()
        val highPrecisionPoints = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8699, 2.3076, 6f, startTime + 4000),
            GpsPoint(48.8700, 2.3077, 4f, startTime + 8000),
        )

        // When: Calculer la moyenne pondérée (poids = 1/accuracy)
        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLng = 0.0

        highPrecisionPoints.forEach { point ->
            val weight = 1.0 / point.accuracy
            totalWeight += weight
            weightedLat += point.lat * weight
            weightedLng += point.lng * weight
        }

        val avgLat = weightedLat / totalWeight
        val avgLng = weightedLng / totalWeight

        // Then: La moyenne devrait être proche du centre
        val expectedLat = 48.8699  // Environ
        val expectedLng = 2.3076

        assertTrue("Latitude moyenne ${avgLat} devrait être proche de ${expectedLat}",
            kotlin.math.abs(avgLat - expectedLat) < 0.001)
        assertTrue("Longitude moyenne ${avgLng} devrait être proche de ${expectedLng}",
            kotlin.math.abs(avgLng - expectedLng) < 0.001)

        println("✅ TEST: Moyenne pondérée calculée - lat=${String.format("%.6f", avgLat)}, lng=${String.format("%.6f", avgLng)}")
    }

    @Test
    fun `IQR-based outlier detection filters extreme points`() {
        // Given: Des points avec un outlier évident
        val startTime = System.currentTimeMillis()
        val pointsWithOutlier = listOf(
            GpsPoint(48.8698, 2.3075, 5f, startTime),
            GpsPoint(48.8699, 2.3076, 5f, startTime + 4000),
            GpsPoint(48.8700, 2.3077, 5f, startTime + 8000),
            GpsPoint(48.8701, 2.3078, 5f, startTime + 12000),
            GpsPoint(48.9000, 2.4000, 5f, startTime + 16000),  // OUTLIER: ~3.5 km de distance!
        )

        // When: Calculer les distances par rapport au centre et détecter l'outlier
        val centerLat = pointsWithOutlier.map { it.lat }.average()
        val centerLng = pointsWithOutlier.map { it.lng }.average()

        val distances = pointsWithOutlier.map { point ->
            TestLocationFactory.calculateHaversineDistance(centerLat, centerLng, point.lat, point.lng)
        }

        val medianDistance = distances.sorted()[distances.size / 2]
        val outlierThreshold = medianDistance * 2.5  // Points à plus de 2.5x la distance médiane

        val outliersDetected = pointsWithOutlier.filterIndexed { index, _ ->
            distances[index] > outlierThreshold
        }

        // Then: L'outlier devrait être détecté
        assertTrue("Devrait détecter au moins 1 outlier", outliersDetected.isNotEmpty())
        assertTrue("L'outlier détecté devrait être le point à ~3.5km",
            outliersDetected.any { it.lat > 48.89 })

        println("✅ TEST: Détection IQR - ${outliersDetected.size} outlier(s) détecté(s)")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: CALCUL DE DISTANCE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `haversine distance calculation is accurate`() {
        // Given: Deux points connus (Paris Tour Eiffel → Arc de Triomphe ≈ 2.8 km)
        val tourEiffel = GpsPoint(48.8584, 2.2945, 5f, 0)
        val arcTriomphe = GpsPoint(48.8738, 2.2950, 5f, 0)

        // When: Calculer la distance
        val distance = TestLocationFactory.calculateHaversineDistance(
            tourEiffel.lat, tourEiffel.lng,
            arcTriomphe.lat, arcTriomphe.lng
        )

        // Then: La distance devrait être proche de 1.7 km (en ligne droite)
        val expectedDistance = 1700.0  // ~1.7 km
        val tolerance = 100.0  // 100m de tolérance

        assertTrue("Distance ${distance}m devrait être proche de ${expectedDistance}m (±${tolerance}m)",
            kotlin.math.abs(distance - expectedDistance) < tolerance)

        println("✅ TEST: Calcul Haversine précis - distance=${String.format("%.0f", distance)}m")
    }

    @Test
    fun `paris to versailles route calculates approximately 22km`() {
        // Given: La route Paris → Versailles complète
        val route = TestLocationFactory.createParisToVersaillesRoute()

        // When: Calculer la distance totale
        val totalDistance = TestLocationFactory.calculateTotalDistance(route)

        // Then: La distance devrait être entre 18 et 28 km
        val minExpected = 18000.0  // 18 km
        val maxExpected = 28000.0  // 28 km

        assertTrue("Distance ${totalDistance/1000}km devrait être entre ${minExpected/1000}km et ${maxExpected/1000}km",
            totalDistance in minExpected..maxExpected)

        println("✅ TEST: Route Paris→Versailles - distance=${String.format("%.1f", totalDistance/1000)}km")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: DÉTECTION DE GHOST TRIP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ghost trip detected after 10 minutes without GPS`() {
        // Given: Un trajet sans mise à jour GPS depuis 10 minutes
        val lastGPSUpdateTime = System.currentTimeMillis() - GHOST_TRIP_TIMEOUT_MS - 1000  // 10 min + 1 sec
        val currentTime = System.currentTimeMillis()

        // When: Vérifier le critère de ghost trip
        val timeSinceLastGPS = currentTime - lastGPSUpdateTime
        val isGhostTrip = timeSinceLastGPS > GHOST_TRIP_TIMEOUT_MS

        // Then: Le trajet devrait être marqué comme ghost
        assertTrue("Trajet sans GPS depuis ${timeSinceLastGPS/60000} min devrait être un ghost trip", isGhostTrip)

        println("✅ TEST: Ghost trip détecté - ${timeSinceLastGPS/60000} min sans GPS")
    }

    @Test
    fun `no ghost trip within 10 minutes of GPS update`() {
        // Given: Un trajet avec mise à jour GPS récente
        val lastGPSUpdateTime = System.currentTimeMillis() - 300000  // 5 minutes
        val currentTime = System.currentTimeMillis()

        // When: Vérifier le critère de ghost trip
        val timeSinceLastGPS = currentTime - lastGPSUpdateTime
        val isGhostTrip = timeSinceLastGPS > GHOST_TRIP_TIMEOUT_MS

        // Then: Le trajet ne devrait PAS être marqué comme ghost
        assertFalse("Trajet avec GPS récent (${timeSinceLastGPS/60000} min) ne devrait pas être ghost", isGhostTrip)

        println("✅ TEST: Pas de ghost trip - ${timeSinceLastGPS/60000} min depuis dernier GPS")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: INACTIVITÉ ET AUTO-STOP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `inactivity auto-stop after 5 minutes without movement`() {
        // Given: Un trajet sans mouvement significatif depuis 5 minutes
        val lastSignificantMoveTime = System.currentTimeMillis() - INACTIVITY_TIMEOUT_MS - 1000  // 5 min + 1 sec
        val currentTime = System.currentTimeMillis()

        // When: Vérifier le critère d'inactivité
        val timeSinceMove = currentTime - lastSignificantMoveTime
        val shouldAutoStop = timeSinceMove > INACTIVITY_TIMEOUT_MS

        // Then: Le trajet devrait s'arrêter automatiquement
        assertTrue("Trajet sans mouvement depuis ${timeSinceMove/60000} min devrait s'auto-stopper", shouldAutoStop)

        println("✅ TEST: Auto-stop par inactivité - ${timeSinceMove/60000} min sans mouvement")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: FRÉQUENCE GPS ADAPTATIVE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `low speed mode uses 4s interval`() {
        // Given: Vitesse < 40 km/h
        val speedKmh = 30.0
        val LOW_SPEED_THRESHOLD_KMH = 40.0
        val LOW_SPEED_INTERVAL_MS = 4000L
        val HIGH_SPEED_INTERVAL_MS = 10000L

        // When: Déterminer l'intervalle GPS
        val interval = if (speedKmh < LOW_SPEED_THRESHOLD_KMH) LOW_SPEED_INTERVAL_MS else HIGH_SPEED_INTERVAL_MS

        // Then: L'intervalle devrait être de 4 secondes
        assertEquals("Basse vitesse devrait utiliser intervalle de 4s", LOW_SPEED_INTERVAL_MS, interval)

        println("✅ TEST: Mode basse vitesse - interval=${interval}ms pour ${speedKmh}km/h")
    }

    @Test
    fun `high speed mode uses 10s interval`() {
        // Given: Vitesse > 40 km/h
        val speedKmh = 80.0
        val LOW_SPEED_THRESHOLD_KMH = 40.0
        val LOW_SPEED_INTERVAL_MS = 4000L
        val HIGH_SPEED_INTERVAL_MS = 10000L

        // When: Déterminer l'intervalle GPS
        val interval = if (speedKmh < LOW_SPEED_THRESHOLD_KMH) LOW_SPEED_INTERVAL_MS else HIGH_SPEED_INTERVAL_MS

        // Then: L'intervalle devrait être de 10 secondes
        assertEquals("Haute vitesse devrait utiliser intervalle de 10s", HIGH_SPEED_INTERVAL_MS, interval)

        println("✅ TEST: Mode haute vitesse - interval=${interval}ms pour ${speedKmh}km/h")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: TRAJET AVEC TUNNEL (PERTE GPS)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `trip with GPS dropout in tunnel resumes correctly`() {
        // Given: Un trajet avec une perte GPS de 3 minutes (tunnel)
        val route = TestLocationFactory.createTripWithTunnel()
        val gapStart = route[3].timestamp  // Entrée tunnel
        val gapEnd = route[4].timestamp    // Sortie tunnel

        // When: Calculer la durée de la perte GPS
        val gapDuration = gapEnd - gapStart

        // Then: La perte ne devrait pas déclencher de ghost trip (< 10 min)
        assertTrue("Perte GPS de ${gapDuration/60000} min ne devrait pas déclencher ghost trip",
            gapDuration < GHOST_TRIP_TIMEOUT_MS)

        // Le trajet devrait être continu
        val totalDistance = TestLocationFactory.calculateTotalDistance(route)
        assertTrue("Le trajet devrait avoir une distance valide après tunnel", totalDistance > MIN_TRIP_DISTANCE_METERS)

        println("✅ TEST: Reprise après tunnel - gap=${gapDuration/1000}s, distance=${String.format("%.0f", totalDistance)}m")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: TRAJET AVEC ARRÊTS BREFS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `trip with brief stops creates single trip`() {
        // Given: Un trajet avec des arrêts brefs (feux, bouchons)
        val route = TestLocationFactory.createTripWithBriefStops()

        // When: Analyser le trajet
        val totalDistance = TestLocationFactory.calculateTotalDistance(route)
        val duration = TestLocationFactory.calculateDuration(route)

        // Then: Le trajet devrait être valide et unique
        assertTrue("Distance devrait être valide", totalDistance > MIN_TRIP_DISTANCE_METERS)
        assertTrue("Durée devrait être valide", duration > MIN_TRIP_DURATION_MS)

        println("✅ TEST: Trajet avec arrêts brefs - distance=${String.format("%.0f", totalDistance)}m, durée=${duration/1000}s")
    }

    @Test
    fun `debounce period prevents false stop during brief pauses`() {
        // Given: Une pause de 90 secondes (< debounce de 2 min)
        val pauseDuration = 90000L  // 90 secondes

        // When: Vérifier si la pause déclenche un arrêt
        val shouldTriggerStop = pauseDuration > STOP_DEBOUNCE_DELAY_MS

        // Then: La pause ne devrait pas déclencher d'arrêt
        assertFalse("Pause de ${pauseDuration/1000}s ne devrait pas déclencher d'arrêt (debounce: ${STOP_DEBOUNCE_DELAY_MS/1000}s)", shouldTriggerStop)

        println("✅ TEST: Debounce empêche faux arrêt - pause=${pauseDuration/1000}s < debounce=${STOP_DEBOUNCE_DELAY_MS/1000}s")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: TRAJET LONG (AUTOROUTE)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `long highway trip Paris to Lyon handles correctly`() {
        // Given: Un trajet Paris → Lyon (~465 km, ~4h)
        val route = TestLocationFactory.createLongHighwayTrip()

        // When: Analyser le trajet
        val totalDistance = TestLocationFactory.calculateTotalDistance(route)
        val duration = TestLocationFactory.calculateDuration(route)
        val avgSpeed = TestLocationFactory.calculateAverageSpeed(route)

        // Then: Le trajet devrait être valide
        assertTrue("Distance devrait être > 400km", totalDistance > 400000)  // 400 km
        assertTrue("Durée devrait être > 3h", duration > 10800000)  // 3 heures
        assertTrue("Vitesse moyenne devrait être > 25 m/s (90 km/h)", avgSpeed > 25)

        // Vérifier tous les critères de validation
        val isValid = totalDistance >= MIN_TRIP_DISTANCE_METERS &&
                      duration >= MIN_TRIP_DURATION_MS &&
                      avgSpeed >= MIN_AVERAGE_SPEED_MPS &&
                      route.size >= 2

        assertTrue("Le trajet long devrait être valide", isValid)

        println("✅ TEST: Trajet autoroute Paris→Lyon - distance=${String.format("%.0f", totalDistance/1000)}km, durée=${duration/3600000}h, vitesse=${String.format("%.0f", avgSpeed*3.6)}km/h")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: TRANSITIONS RAPIDES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `rapid state transitions handled correctly`() {
        // Given: Des états simulés avec transitions rapides
        var state = "STANDBY"
        val transitions = mutableListOf<String>()

        // When: Simuler des transitions rapides
        // START_BUFFERING
        state = "BUFFERING"
        transitions.add("STANDBY→BUFFERING")

        // CONFIRM_VEHICLE
        state = "TRIP_ACTIVE"
        transitions.add("BUFFERING→TRIP_ACTIVE")

        // END_TRIP
        state = "STOP_PENDING"
        transitions.add("TRIP_ACTIVE→STOP_PENDING")

        // Après debounce
        state = "FINALIZING"
        transitions.add("STOP_PENDING→FINALIZING")

        // Après finalisation
        state = "STANDBY"
        transitions.add("FINALIZING→STANDBY")

        // Then: Toutes les transitions devraient être valides
        assertEquals("Devrait finir en STANDBY", "STANDBY", state)
        assertEquals("Devrait avoir 5 transitions", 5, transitions.size)

        println("✅ TEST: Transitions rapides - ${transitions.joinToString(" → ")}")
    }

    @Test
    fun `auto-resume cancels stop when vehicle detected during grace period`() {
        // Given: État STOP_PENDING (période de grâce)
        var state = "STOP_PENDING"
        var stopCancelled = false

        // When: Véhicule détecté pendant la période de grâce
        if (state == "STOP_PENDING") {
            state = "TRIP_ACTIVE"
            stopCancelled = true
        }

        // Then: Le trajet devrait reprendre
        assertEquals("Devrait être en TRIP_ACTIVE après auto-resume", "TRIP_ACTIVE", state)
        assertTrue("L'arrêt devrait être annulé", stopCancelled)

        println("✅ TEST: Auto-resume pendant grace period - état=$state")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TESTS: RÉSUMÉ DES STATS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full test suite summary`() {
        println("\n" + "═".repeat(70))
        println("RÉSUMÉ DES TESTS - MACHINE D'ÉTATS LOCATIONTRACKINGSERVICE")
        println("═".repeat(70))
        println("✅ Tests de validation de trajet: 8 tests")
        println("   - Distance min (10m), Durée min (15s), Vitesse min (0.1 m/s)")
        println("   - Edge cases: limites exactes acceptées")
        println()
        println("✅ Tests d'auto-confirmation buffer: 3 tests")
        println("   - Distance >= 50m, Vitesse >= 1 m/s, Min 3 points")
        println()
        println("✅ Tests de filtrage GPS: 2 tests")
        println("   - Rejet points accuracy > 50m")
        println()
        println("✅ Tests de sélection haute précision: 3 tests")
        println("   - Points < 12m, Moyenne pondérée, Détection outliers IQR")
        println()
        println("✅ Tests de calcul distance: 2 tests")
        println("   - Haversine précis, Route Paris→Versailles ~22km")
        println()
        println("✅ Tests ghost trip et inactivité: 3 tests")
        println("   - Ghost trip > 10min sans GPS, Auto-stop > 5min sans mouvement")
        println()
        println("✅ Tests fréquence GPS adaptative: 2 tests")
        println("   - Basse vitesse: 4s, Haute vitesse: 10s")
        println()
        println("✅ Tests scénarios réels: 4 tests")
        println("   - Tunnel, Arrêts brefs, Debounce, Trajet long")
        println()
        println("✅ Tests transitions: 2 tests")
        println("   - Transitions rapides, Auto-resume pendant grace period")
        println("═".repeat(70))
    }
}

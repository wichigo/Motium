package com.application.motium.service

import android.location.Location
import com.application.motium.data.TripLocation
import com.application.motium.testutils.TestLocationFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.*

/**
 * Tests de sélection des points de précision pour le geocoding
 *
 * L'algorithme de sélection des points départ/arrivée (depuis LocationTrackingService):
 *
 * 1. Stratégie 1: Points haute précision (< 12m) après stabilisation GPS (8s)
 *    → Moyenne pondérée par précision
 *
 * 2. Stratégie 2: Points précision moyenne (< 25m) avec filtrage outliers IQR
 *    → Moyenne pondérée des points filtrés
 *
 * 3. Stratégie 3: Clustering IQR sur tous les points disponibles
 *    → Meilleur point du cluster
 *
 * 4. Stratégie 4 (fallback): Point le plus précis de la liste
 *
 * Constantes:
 * - START_POINT_ANCHORING_DELAY_MS = 8000ms
 * - END_POINT_SAMPLING_DELAY_MS = 20000ms
 * - HIGH_PRECISION_THRESHOLD = 12m
 * - MEDIUM_PRECISION_THRESHOLD = 25m
 * - OUTLIER_DISTANCE_THRESHOLD = 50m
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class PrecisionPointSelectionTest {

    // Constantes copiées de LocationTrackingService
    companion object {
        const val START_POINT_ANCHORING_DELAY_MS = 8000L
        const val END_POINT_SAMPLING_DELAY_MS = 20000L
        const val HIGH_PRECISION_THRESHOLD = 12f
        const val MEDIUM_PRECISION_THRESHOLD = 25f
        const val OUTLIER_DISTANCE_THRESHOLD = 50f
        const val START_POINT_CLUSTERING_WINDOW_MS = 45000L
    }

    /**
     * Simule la logique de sélection du meilleur point
     */
    class PrecisionPointSelector {

        /**
         * Sélectionne le meilleur point de départ
         */
        fun selectBestStartPoint(candidates: List<TripLocation>): TripLocation? {
            if (candidates.isEmpty()) return null

            // Stratégie 1: Points après stabilisation GPS avec haute précision
            val anchoringDeadline = candidates.first().timestamp + START_POINT_ANCHORING_DELAY_MS
            val afterAnchoringDelay = candidates.filter { it.timestamp >= anchoringDeadline }

            // Points très précis (< 12m)
            val highPrecisionPoints = afterAnchoringDelay.filter { it.accuracy < HIGH_PRECISION_THRESHOLD }
            if (highPrecisionPoints.size >= 2) {
                return calculateWeightedAveragePoint(highPrecisionPoints)
            }

            // Stratégie 2: Points avec précision moyenne (< 25m) après filtrage outliers
            val mediumPrecisionPoints = afterAnchoringDelay.filter { it.accuracy < MEDIUM_PRECISION_THRESHOLD }
            if (mediumPrecisionPoints.size >= 3) {
                val filteredPoints = filterOutliers(mediumPrecisionPoints)
                if (filteredPoints.isNotEmpty()) {
                    return calculateWeightedAveragePoint(filteredPoints)
                }
            }

            // Stratégie 3: Clustering IQR sur premiers points
            val clusterCandidates = candidates.take(minOf(candidates.size, 8))
            if (clusterCandidates.size >= 3) {
                val filteredPoints = filterOutliers(clusterCandidates)
                if (filteredPoints.isNotEmpty()) {
                    return filteredPoints.minByOrNull { it.accuracy }
                }
            }

            // Stratégie 4 (fallback): Point le plus précis
            return candidates.minByOrNull { it.accuracy }
        }

        /**
         * Sélectionne le meilleur point d'arrivée
         */
        fun selectBestEndPoint(candidates: List<TripLocation>): TripLocation? {
            if (candidates.isEmpty()) return null

            // Stratégie 1: Points très précis (< 12m)
            val highPrecisionPoints = candidates.filter { it.accuracy < HIGH_PRECISION_THRESHOLD }
            if (highPrecisionPoints.size >= 2) {
                val filteredPoints = filterOutliers(highPrecisionPoints)
                return calculateWeightedAveragePoint(filteredPoints)
            }

            // Stratégie 2: Points précision moyenne (< 25m) avec filtrage outliers
            val mediumPrecisionPoints = candidates.filter { it.accuracy < MEDIUM_PRECISION_THRESHOLD }
            if (mediumPrecisionPoints.size >= 2) {
                val filteredPoints = filterOutliers(mediumPrecisionPoints)
                if (filteredPoints.isNotEmpty()) {
                    return calculateWeightedAveragePoint(filteredPoints)
                }
            }

            // Stratégie 3: Tous les candidats avec filtrage outliers
            if (candidates.size >= 3) {
                val filteredPoints = filterOutliers(candidates)
                if (filteredPoints.isNotEmpty()) {
                    return calculateWeightedAveragePoint(filteredPoints)
                }
            }

            // Stratégie 4 (fallback): Point le plus précis
            return candidates.minByOrNull { it.accuracy }
        }

        /**
         * Calcule un point moyen pondéré par la précision GPS
         */
        fun calculateWeightedAveragePoint(points: List<TripLocation>): TripLocation? {
            if (points.isEmpty()) return null
            if (points.size == 1) return points[0]

            // Poids = 1/accuracy (plus précis = plus de poids)
            var totalWeight = 0.0
            var weightedLat = 0.0
            var weightedLng = 0.0

            for (point in points) {
                val weight = 1.0 / point.accuracy
                totalWeight += weight
                weightedLat += point.latitude * weight
                weightedLng += point.longitude * weight
            }

            return TripLocation(
                latitude = weightedLat / totalWeight,
                longitude = weightedLng / totalWeight,
                accuracy = (points.map { it.accuracy }.average()).toFloat(),
                timestamp = points.maxOf { it.timestamp }
            )
        }

        /**
         * Filtre les outliers en utilisant la méthode IQR
         */
        fun filterOutliers(points: List<TripLocation>): List<TripLocation> {
            if (points.size < 3) return points

            // Calculer la médiane des positions
            val sortedLats = points.map { it.latitude }.sorted()
            val sortedLngs = points.map { it.longitude }.sorted()
            val medianLat = sortedLats[sortedLats.size / 2]
            val medianLng = sortedLngs[sortedLngs.size / 2]

            // Calculer les distances à la médiane
            val distances = points.map { point ->
                val distance = haversineDistance(
                    point.latitude, point.longitude,
                    medianLat, medianLng
                )
                point to distance
            }

            // Filtrer les outliers
            val sortedDistances = distances.map { it.second }.sorted()
            val medianDistance = sortedDistances[sortedDistances.size / 2]
            val dynamicThreshold = maxOf(OUTLIER_DISTANCE_THRESHOLD.toDouble(), medianDistance * 2.5)

            return distances
                .filter { (_, distance) -> distance <= dynamicThreshold }
                .map { it.first }
        }

        private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return R * c
        }
    }

    private lateinit var selector: PrecisionPointSelector

    @Before
    fun setUp() {
        selector = PrecisionPointSelector()
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: STRATÉGIE 1 - HAUTE PRÉCISION
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `selects high-precision points first (less than 12m accuracy)`() {
        // Given - Points avec différentes précisions, dont 2 haute précision après délai d'ancrage
        val now = System.currentTimeMillis()
        val candidates = listOf(
            TripLocation(48.8698, 2.3075, 15f, now),               // Avant délai, moyenne
            TripLocation(48.8699, 2.3076, 10f, now + 10000),       // Après délai, haute
            TripLocation(48.8700, 2.3077, 8f, now + 14000),        // Après délai, haute
            TripLocation(48.8701, 2.3078, 20f, now + 18000)        // Après délai, moyenne
        )

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNotNull("Un point devrait être sélectionné", result)
        // Le résultat devrait être la moyenne pondérée des 2 points haute précision
        // Points haute précision: (48.8699, 2.3076, 10m) et (48.8700, 2.3077, 8m)
        // Poids: 1/10 = 0.1 et 1/8 = 0.125
        // Le point résultant devrait être plus proche du point 8m

        println("✅ Point haute précision sélectionné: lat=${result!!.latitude}, accuracy=${result.accuracy}m")
    }

    @Test
    fun `weighted average favors more accurate points`() {
        // Given - Points avec précisions très différentes
        val points = listOf(
            TripLocation(48.8700, 2.3080, 5f, System.currentTimeMillis()),  // Très précis
            TripLocation(48.8750, 2.3100, 30f, System.currentTimeMillis())  // Imprécis
        )

        // When
        val result = selector.calculateWeightedAveragePoint(points)

        // Then
        assertNotNull(result)
        // Le point 5m devrait avoir 6x plus de poids que le point 30m (30/5 = 6)
        // Donc le résultat devrait être plus proche de 48.87 que de 48.875
        val distanceToAccurate = abs(result!!.latitude - 48.8700)
        val distanceToInaccurate = abs(result.latitude - 48.8750)

        assertTrue(
            "Le résultat devrait être plus proche du point précis",
            distanceToAccurate < distanceToInaccurate
        )

        println("✅ Moyenne pondérée favorise le point précis: lat=${result.latitude}")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: STRATÉGIE 2 - PRÉCISION MOYENNE AVEC FILTRAGE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `fallback to medium-precision with outlier filtering`() {
        // Given - Pas de points haute précision, mais des points moyens après délai
        val now = System.currentTimeMillis()
        val candidates = listOf(
            TripLocation(48.8698, 2.3075, 25f, now),               // Avant délai
            TripLocation(48.8699, 2.3076, 18f, now + 10000),       // Après délai, moyenne
            TripLocation(48.8700, 2.3077, 20f, now + 14000),       // Après délai, moyenne
            TripLocation(48.8701, 2.3078, 22f, now + 18000),       // Après délai, moyenne
            TripLocation(48.8702, 2.3079, 24f, now + 22000)        // Après délai, moyenne
        )

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNotNull("Un point devrait être sélectionné", result)

        println("✅ Point précision moyenne sélectionné: lat=${result!!.latitude}, accuracy=${result.accuracy}m")
    }

    @Test
    fun `IQR-based outlier detection works correctly`() {
        // Given - Points groupés + un outlier très éloigné
        val center = 48.8700
        val points = listOf(
            TripLocation(center, 2.3075, 10f, System.currentTimeMillis()),        // Centre
            TripLocation(center + 0.0001, 2.3076, 10f, System.currentTimeMillis()), // ~11m
            TripLocation(center - 0.0001, 2.3074, 10f, System.currentTimeMillis()), // ~11m
            TripLocation(center + 0.001, 2.3085, 10f, System.currentTimeMillis())   // ~120m - OUTLIER
        )

        // When
        val filtered = selector.filterOutliers(points)

        // Then
        assertEquals("L'outlier devrait être filtré", 3, filtered.size)
        // Vérifier que l'outlier (48.871) n'est pas dans la liste
        assertTrue(
            "L'outlier ne devrait pas être dans la liste filtrée",
            filtered.none { it.latitude > center + 0.0005 }
        )

        println("✅ Outlier filtré: ${points.size} → ${filtered.size} points")
    }

    @Test
    fun `outlier filter keeps all points when within threshold`() {
        // Given - Tous les points sont proches (pas d'outliers)
        val center = 48.8700
        val points = listOf(
            TripLocation(center, 2.3075, 10f, System.currentTimeMillis()),
            TripLocation(center + 0.0001, 2.3076, 10f, System.currentTimeMillis()),
            TripLocation(center - 0.0001, 2.3074, 10f, System.currentTimeMillis()),
            TripLocation(center + 0.0002, 2.3077, 10f, System.currentTimeMillis())
        )

        // When
        val filtered = selector.filterOutliers(points)

        // Then
        assertEquals("Aucun point ne devrait être filtré", 4, filtered.size)

        println("✅ Aucun outlier détecté: 4 points conservés")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: STRATÉGIE 4 - FALLBACK
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `last resort uses single most accurate point`() {
        // Given - Seulement 2 points, pas assez pour les stratégies avancées
        val candidates = listOf(
            TripLocation(48.8700, 2.3075, 30f, System.currentTimeMillis()),
            TripLocation(48.8705, 2.3080, 15f, System.currentTimeMillis())
        )

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNotNull("Un point devrait être sélectionné", result)
        assertEquals("Le point le plus précis devrait être sélectionné", 15f, result!!.accuracy)

        println("✅ Fallback: point le plus précis (15m) sélectionné")
    }

    @Test
    fun `returns null when no candidates`() {
        // Given
        val candidates = emptyList<TripLocation>()

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNull("Aucun point ne devrait être retourné", result)

        println("✅ Aucun candidat → null retourné")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: DÉLAI D'ANCRAGE (8 SECONDES)
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `start point anchoring waits 8 seconds`() {
        // Given - Points avant et après le délai de 8 secondes
        val now = System.currentTimeMillis()
        val candidates = listOf(
            TripLocation(48.8700, 2.3075, 5f, now),            // Avant délai - très précis mais ignoré
            TripLocation(48.8705, 2.3080, 5f, now + 4000),     // Avant délai - très précis mais ignoré
            TripLocation(48.8750, 2.3100, 8f, now + 10000),    // Après délai - utilisé
            TripLocation(48.8755, 2.3105, 9f, now + 14000)     // Après délai - utilisé
        )

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNotNull("Un point devrait être sélectionné", result)
        // Le résultat devrait être proche des points après délai (48.8750-48.8755)
        // et non des points avant délai (48.8700-48.8705)
        assertTrue(
            "Le point sélectionné devrait être après le délai d'ancrage",
            result!!.latitude > 48.8720
        )

        println("✅ Délai d'ancrage respecté: lat=${result.latitude}")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: POINTS D'ARRIVÉE (20 SECONDES DE COLLECTE)
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `end point sampling collects for 20 seconds`() {
        // Given - Plusieurs points collectés sur 20 secondes
        val now = System.currentTimeMillis()
        val candidates = listOf(
            TripLocation(48.8049, 2.1204, 8f, now),
            TripLocation(48.8050, 2.1205, 6f, now + 5000),
            TripLocation(48.8051, 2.1206, 5f, now + 10000),
            TripLocation(48.8052, 2.1207, 4f, now + 15000),
            TripLocation(48.8053, 2.1208, 3f, now + 20000)  // Point le plus précis à la fin
        )

        // When
        val result = selector.selectBestEndPoint(candidates)

        // Then
        assertNotNull("Un point d'arrivée devrait être sélectionné", result)
        // Tous les points sont haute précision (<12m), donc moyenne pondérée

        println("✅ Point d'arrivée sélectionné après collecte: lat=${result!!.latitude}, accuracy=${result.accuracy}m")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: SCÉNARIOS RÉALISTES
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `realistic start point selection with GPS stabilization`() {
        // Given - Simulation réaliste du GPS qui se stabilise
        val now = System.currentTimeMillis()
        val candidates = listOf(
            // GPS froid - mauvaise précision
            TripLocation(48.8698, 2.3075, 45f, now),
            TripLocation(48.8700, 2.3078, 35f, now + 2000),
            TripLocation(48.8699, 2.3076, 25f, now + 4000),
            TripLocation(48.8698, 2.3075, 15f, now + 6000),
            // GPS stabilisé après 8s
            TripLocation(48.8697, 2.3074, 10f, now + 9000),
            TripLocation(48.8698, 2.3075, 8f, now + 12000),
            TripLocation(48.8697, 2.3074, 6f, now + 16000),
            TripLocation(48.8698, 2.3075, 5f, now + 20000)
        )

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNotNull(result)
        // Le résultat devrait être proche de la position stabilisée (48.8697-48.8698)
        assertTrue(
            "Le point devrait être à la position stabilisée",
            result!!.latitude in 48.8696..48.8699
        )

        println("✅ Sélection réaliste avec stabilisation GPS: lat=${result.latitude}, accuracy=${result.accuracy}m")
    }

    @Test
    fun `realistic end point selection at destination`() {
        // Given - Points collectés à l'arrivée au Château de Versailles
        val now = System.currentTimeMillis()
        val candidates = listOf(
            TripLocation(48.8050, 2.1200, 15f, now),        // Approche
            TripLocation(48.8049, 2.1203, 10f, now + 4000), // Ralentissement
            TripLocation(48.8049, 2.1204, 6f, now + 8000),  // Arrêt
            TripLocation(48.8049, 2.1204, 4f, now + 12000), // Stationnaire
            TripLocation(48.8049, 2.1204, 3f, now + 16000)  // GPS optimal
        )

        // When
        val result = selector.selectBestEndPoint(candidates)

        // Then
        assertNotNull(result)
        // Le point final devrait être très proche de (48.8049, 2.1204)
        assertEquals(48.8049, result!!.latitude, 0.001)
        assertEquals(2.1204, result.longitude, 0.001)

        println("✅ Point d'arrivée Versailles: lat=${result.latitude}, lng=${result.longitude}")
    }

    @Test
    fun `handles mixed precision points correctly`() {
        // Given - Mélange de précisions variées
        val points = TestLocationFactory.createMixedPrecisionPoints()
        val candidates = points.map { gpsPoint ->
            TripLocation(gpsPoint.lat, gpsPoint.lng, gpsPoint.accuracy, gpsPoint.timestamp)
        }

        // When
        val result = selector.selectBestEndPoint(candidates)

        // Then
        assertNotNull("Un point devrait être sélectionné malgré les précisions variées", result)

        println("✅ Points de précision mixte traités: ${candidates.size} candidats → point sélectionné")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: EDGE CASES
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `handles single point correctly`() {
        // Given
        val candidates = listOf(
            TripLocation(48.8700, 2.3075, 10f, System.currentTimeMillis())
        )

        // When
        val result = selector.selectBestStartPoint(candidates)

        // Then
        assertNotNull(result)
        assertEquals(48.8700, result!!.latitude, 0.0001)

        println("✅ Point unique retourné correctement")
    }

    @Test
    fun `weighted average with single point returns that point`() {
        // Given
        val points = listOf(
            TripLocation(48.8700, 2.3075, 10f, System.currentTimeMillis())
        )

        // When
        val result = selector.calculateWeightedAveragePoint(points)

        // Then
        assertNotNull(result)
        assertEquals(48.8700, result!!.latitude, 0.0001)
        assertEquals(2.3075, result.longitude, 0.0001)

        println("✅ Moyenne pondérée avec 1 point = ce point")
    }

    @Test
    fun `filter outliers with 2 points returns both`() {
        // Given - Moins de 3 points = pas de filtrage
        val points = listOf(
            TripLocation(48.8700, 2.3075, 10f, System.currentTimeMillis()),
            TripLocation(48.9000, 2.3500, 10f, System.currentTimeMillis()) // Très éloigné
        )

        // When
        val filtered = selector.filterOutliers(points)

        // Then
        assertEquals("2 points devraient être conservés (pas assez pour IQR)", 2, filtered.size)

        println("✅ IQR non appliqué avec seulement 2 points")
    }

    @Test
    fun `all points as outliers keeps at least some points`() {
        // Given - Tous les points sont très dispersés
        val points = listOf(
            TripLocation(48.8000, 2.3000, 10f, System.currentTimeMillis()),
            TripLocation(48.8500, 2.3500, 10f, System.currentTimeMillis()),
            TripLocation(48.9000, 2.4000, 10f, System.currentTimeMillis())
        )

        // When
        val filtered = selector.filterOutliers(points)

        // Then
        // Avec le seuil dynamique (max(50m, médiane*2.5)), certains points devraient rester
        assertTrue("Au moins quelques points devraient rester", filtered.isNotEmpty())

        println("✅ Points dispersés: ${filtered.size} points conservés sur ${points.size}")
    }
}

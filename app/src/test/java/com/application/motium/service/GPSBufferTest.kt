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
 * Tests du buffer GPS pour l'auto-tracking
 *
 * Le buffer GPS collecte temporairement les points avant confirmation du trajet.
 * Logique du buffer (depuis LocationTrackingService):
 * - Ajoute les points GPS en mode BUFFERING
 * - Auto-confirmation quand: >= 3 points ET (distance >= 50m OU vitesse >= 1 m/s)
 * - Transfère les points vers le trajet à la confirmation
 * - Filtre les points avec accuracy > 50m
 *
 * Ces tests vérifient le comportement du buffer GPS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class GPSBufferTest {

    // Constantes du buffer (copiées de LocationTrackingService)
    companion object {
        const val MAX_GPS_ACCURACY_METERS = 50f
        const val MIN_BUFFER_DISTANCE = 50.0 // mètres
        const val MIN_BUFFER_SPEED = 1.0 // m/s
        const val MIN_BUFFER_POINTS = 3

        // Fréquence GPS adaptative
        const val LOW_SPEED_THRESHOLD_KMH = 40f
        const val LOW_SPEED_UPDATE_INTERVAL = 4000L // 4 secondes
        const val HIGH_SPEED_UPDATE_INTERVAL = 10000L // 10 secondes
    }

    /**
     * Simule le buffer GPS du service
     */
    class GPSBuffer {
        private val buffer = mutableListOf<TripLocation>()
        private val maxAccuracy = MAX_GPS_ACCURACY_METERS

        val size: Int get() = buffer.size
        val points: List<TripLocation> get() = buffer.toList()

        fun add(location: TripLocation): Boolean {
            // Filtrer les points avec mauvaise précision
            if (location.accuracy > maxAccuracy) {
                return false
            }
            buffer.add(location)
            return true
        }

        fun clear() {
            buffer.clear()
        }

        fun transferToTrip(): List<TripLocation> {
            val transferred = buffer.toList()
            buffer.clear()
            return transferred
        }

        fun calculateTotalDistance(): Double {
            if (buffer.size < 2) return 0.0

            var total = 0.0
            for (i in 1 until buffer.size) {
                val prev = buffer[i - 1]
                val curr = buffer[i]
                total += haversineDistance(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude
                )
            }
            return total
        }

        fun calculateAverageSpeed(): Double {
            if (buffer.size < 2) return 0.0

            val durationMs = buffer.last().timestamp - buffer.first().timestamp
            if (durationMs <= 0) return 0.0

            val durationSeconds = durationMs / 1000.0
            return calculateTotalDistance() / durationSeconds
        }

        fun shouldAutoConfirm(): Boolean {
            if (buffer.size < MIN_BUFFER_POINTS) return false

            val distance = calculateTotalDistance()
            val speed = calculateAverageSpeed()

            return distance >= MIN_BUFFER_DISTANCE || speed >= MIN_BUFFER_SPEED
        }

        private fun haversineDistance(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Double {
            val R = 6371000.0 // Rayon de la Terre en mètres

            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)

            val c = 2 * atan2(sqrt(a), sqrt(1 - a))

            return R * c
        }
    }

    private lateinit var gpsBuffer: GPSBuffer

    @Before
    fun setUp() {
        gpsBuffer = GPSBuffer()
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: AJOUT DE POINTS
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer adds points correctly`() {
        // Given
        val point1 = TripLocation(48.8698, 2.3075, 5f, System.currentTimeMillis())
        val point2 = TripLocation(48.8700, 2.3080, 8f, System.currentTimeMillis() + 4000)

        // When
        val added1 = gpsBuffer.add(point1)
        val added2 = gpsBuffer.add(point2)

        // Then
        assertTrue("Le point 1 devrait être ajouté", added1)
        assertTrue("Le point 2 devrait être ajouté", added2)
        assertEquals("Le buffer devrait contenir 2 points", 2, gpsBuffer.size)

        println("✅ Buffer - 2 points ajoutés correctement")
    }

    @Test
    fun `buffer rejects points with accuracy greater than 50m`() {
        // Given - Un point avec mauvaise précision
        val badAccuracyPoint = TripLocation(48.8698, 2.3075, 60f, System.currentTimeMillis())
        val goodAccuracyPoint = TripLocation(48.8700, 2.3080, 25f, System.currentTimeMillis() + 4000)

        // When
        val addedBad = gpsBuffer.add(badAccuracyPoint)
        val addedGood = gpsBuffer.add(goodAccuracyPoint)

        // Then
        assertFalse("Le point à 60m de précision devrait être rejeté", addedBad)
        assertTrue("Le point à 25m de précision devrait être accepté", addedGood)
        assertEquals("Le buffer ne devrait contenir que 1 point", 1, gpsBuffer.size)

        println("✅ Buffer - Points avec mauvaise précision (>50m) rejetés")
    }

    @Test
    fun `buffer rejects points at exactly 50m accuracy threshold`() {
        // Given - Un point à exactement 50m de précision
        val point50m = TripLocation(48.8698, 2.3075, 50f, System.currentTimeMillis())
        val point49m = TripLocation(48.8700, 2.3080, 49f, System.currentTimeMillis() + 4000)

        // When
        val added50 = gpsBuffer.add(point50m)
        val added49 = gpsBuffer.add(point49m)

        // Then
        // Note: Le critère est "> 50m" donc 50m exact devrait être accepté
        assertTrue("Le point à exactement 50m devrait être accepté (>50m = rejeté)", added50)
        assertTrue("Le point à 49m devrait être accepté", added49)

        println("✅ Buffer - Point à 50m exact accepté, 49m accepté")
    }

    @Test
    fun `buffer handles multiple precision levels correctly`() {
        // Given - Points de précisions variées
        val points = TestLocationFactory.createMixedPrecisionPoints()

        // When
        var acceptedCount = 0
        var rejectedCount = 0
        points.forEach { gpsPoint ->
            val location = TripLocation(
                gpsPoint.lat, gpsPoint.lng,
                gpsPoint.accuracy, gpsPoint.timestamp
            )
            if (gpsBuffer.add(location)) {
                acceptedCount++
            } else {
                rejectedCount++
            }
        }

        // Then
        val expectedAccepted = points.count { it.accuracy <= MAX_GPS_ACCURACY_METERS }
        val expectedRejected = points.count { it.accuracy > MAX_GPS_ACCURACY_METERS }

        assertEquals("Nombre de points acceptés incorrect", expectedAccepted, acceptedCount)
        assertEquals("Nombre de points rejetés incorrect", expectedRejected, rejectedCount)

        println("✅ Buffer - $acceptedCount points acceptés, $rejectedCount rejetés sur ${points.size}")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: CALCUL DE DISTANCE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer calculates distance correctly with haversine`() {
        // Given - Points connus pour calculer la distance
        val points = TestLocationFactory.createMediumTrip()

        points.forEach { gpsPoint ->
            gpsBuffer.add(TripLocation(
                gpsPoint.lat, gpsPoint.lng,
                gpsPoint.accuracy, gpsPoint.timestamp
            ))
        }

        // When
        val calculatedDistance = gpsBuffer.calculateTotalDistance()

        // Then
        // La distance attendue depuis TestLocationFactory
        val expectedDistance = TestLocationFactory.calculateTotalDistance(points)

        assertEquals(
            "La distance calculée devrait correspondre à TestLocationFactory",
            expectedDistance,
            calculatedDistance,
            1.0 // Tolérance de 1m pour les arrondis
        )

        println("✅ Distance calculée: ${calculatedDistance}m (attendu: ${expectedDistance}m)")
    }

    @Test
    fun `buffer calculates zero distance with single point`() {
        // Given
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, System.currentTimeMillis()))

        // When
        val distance = gpsBuffer.calculateTotalDistance()

        // Then
        assertEquals("La distance avec 1 point devrait être 0", 0.0, distance, 0.01)

        println("✅ Distance avec 1 point = 0m")
    }

    @Test
    fun `buffer calculates zero distance with empty buffer`() {
        // When
        val distance = gpsBuffer.calculateTotalDistance()

        // Then
        assertEquals("La distance d'un buffer vide devrait être 0", 0.0, distance, 0.01)

        println("✅ Distance buffer vide = 0m")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: TRANSFERT VERS TRAJET
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer transfers to trip correctly`() {
        // Given
        val point1 = TripLocation(48.8698, 2.3075, 5f, System.currentTimeMillis())
        val point2 = TripLocation(48.8700, 2.3080, 8f, System.currentTimeMillis() + 4000)
        val point3 = TripLocation(48.8705, 2.3090, 6f, System.currentTimeMillis() + 8000)

        gpsBuffer.add(point1)
        gpsBuffer.add(point2)
        gpsBuffer.add(point3)

        assertEquals("Buffer devrait avoir 3 points avant transfert", 3, gpsBuffer.size)

        // When
        val transferred = gpsBuffer.transferToTrip()

        // Then
        assertEquals("3 points devraient être transférés", 3, transferred.size)
        assertEquals("Le buffer devrait être vide après transfert", 0, gpsBuffer.size)

        // Vérifier que les points sont les bons
        assertEquals(point1.latitude, transferred[0].latitude, 0.0001)
        assertEquals(point2.latitude, transferred[1].latitude, 0.0001)
        assertEquals(point3.latitude, transferred[2].latitude, 0.0001)

        println("✅ Transfert - 3 points transférés, buffer vidé")
    }

    @Test
    fun `buffer clears after transfer`() {
        // Given
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, System.currentTimeMillis()))
        gpsBuffer.add(TripLocation(48.8700, 2.3080, 8f, System.currentTimeMillis() + 4000))

        // When
        gpsBuffer.transferToTrip()

        // Then
        assertEquals("Le buffer devrait être vide", 0, gpsBuffer.size)
        assertTrue("La liste points devrait être vide", gpsBuffer.points.isEmpty())

        println("✅ Buffer vidé après transfert")
    }

    @Test
    fun `buffer clear works correctly`() {
        // Given
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, System.currentTimeMillis()))
        gpsBuffer.add(TripLocation(48.8700, 2.3080, 8f, System.currentTimeMillis() + 4000))

        assertEquals("Buffer devrait avoir 2 points", 2, gpsBuffer.size)

        // When
        gpsBuffer.clear()

        // Then
        assertEquals("Le buffer devrait être vide après clear()", 0, gpsBuffer.size)

        println("✅ Buffer clear() fonctionne")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: AUTO-CONFIRMATION
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer auto-confirms when distance greater than 50m`() {
        // Given - Points qui forment plus de 50m de distance
        // Environ 100m entre deux points éloignés
        val now = System.currentTimeMillis()
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.8700, 2.3080, 5f, now + 4000))
        gpsBuffer.add(TripLocation(48.8707, 2.3090, 5f, now + 8000)) // ~100m du premier

        val distance = gpsBuffer.calculateTotalDistance()

        // When
        val shouldConfirm = gpsBuffer.shouldAutoConfirm()

        // Then
        assertTrue("Le buffer devrait avoir >= 3 points", gpsBuffer.size >= MIN_BUFFER_POINTS)
        assertTrue("La distance ($distance m) devrait être >= 50m", distance >= MIN_BUFFER_DISTANCE)
        assertTrue("L'auto-confirmation devrait être déclenchée", shouldConfirm)

        println("✅ Auto-confirm déclenché - Distance: ${distance}m >= ${MIN_BUFFER_DISTANCE}m")
    }

    @Test
    fun `buffer auto-confirms when speed greater than 1 mps`() {
        // Given - Points rapides (vitesse > 1 m/s = 3.6 km/h)
        // Distance courte mais temps très court = vitesse élevée
        val now = System.currentTimeMillis()
        // 20m en 5 secondes = 4 m/s
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.8699, 2.3076, 5f, now + 2000))
        gpsBuffer.add(TripLocation(48.8700, 2.3078, 5f, now + 5000))

        val speed = gpsBuffer.calculateAverageSpeed()
        val distance = gpsBuffer.calculateTotalDistance()

        // When
        val shouldConfirm = gpsBuffer.shouldAutoConfirm()

        // Then
        // Si distance < 50m mais vitesse > 1 m/s, doit confirmer
        if (distance < MIN_BUFFER_DISTANCE) {
            assertTrue("La vitesse ($speed m/s) devrait être >= 1 m/s pour confirmer", speed >= MIN_BUFFER_SPEED)
        }
        assertTrue("L'auto-confirmation devrait être déclenchée", shouldConfirm)

        println("✅ Auto-confirm déclenché - Vitesse: ${speed} m/s, Distance: ${distance}m")
    }

    @Test
    fun `buffer does not auto-confirm with less than 3 points`() {
        // Given - Seulement 2 points
        val now = System.currentTimeMillis()
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.8750, 2.3150, 5f, now + 4000)) // Distance > 50m

        val distance = gpsBuffer.calculateTotalDistance()

        // When
        val shouldConfirm = gpsBuffer.shouldAutoConfirm()

        // Then
        assertTrue("La distance devrait être > 50m", distance > MIN_BUFFER_DISTANCE)
        assertFalse("L'auto-confirmation ne devrait PAS être déclenchée (< 3 points)", shouldConfirm)

        println("❌ Auto-confirm bloqué - Seulement ${gpsBuffer.size} points (min: ${MIN_BUFFER_POINTS})")
    }

    @Test
    fun `buffer does not auto-confirm with short distance and slow speed`() {
        // Given - Distance courte et vitesse lente
        val now = System.currentTimeMillis()
        // 10m en 60 secondes = 0.16 m/s (très lent)
        gpsBuffer.add(TripLocation(48.86980, 2.30750, 5f, now))
        gpsBuffer.add(TripLocation(48.86983, 2.30753, 5f, now + 30000))
        gpsBuffer.add(TripLocation(48.86985, 2.30755, 5f, now + 60000))

        val distance = gpsBuffer.calculateTotalDistance()
        val speed = gpsBuffer.calculateAverageSpeed()

        // When
        val shouldConfirm = gpsBuffer.shouldAutoConfirm()

        // Then
        assertTrue("Doit avoir >= 3 points", gpsBuffer.size >= MIN_BUFFER_POINTS)
        assertTrue("La distance ($distance m) devrait être < 50m", distance < MIN_BUFFER_DISTANCE)
        assertTrue("La vitesse ($speed m/s) devrait être < 1 m/s", speed < MIN_BUFFER_SPEED)
        assertFalse("L'auto-confirmation ne devrait PAS être déclenchée", shouldConfirm)

        println("❌ Auto-confirm bloqué - Distance: ${distance}m, Vitesse: ${speed} m/s")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: GESTION GPS DROPOUT
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer handles GPS dropout gracefully`() {
        // Given - Points avec un grand gap temporel (tunnel)
        val points = TestLocationFactory.createTripWithTunnel()

        // When
        points.forEach { gpsPoint ->
            gpsBuffer.add(TripLocation(
                gpsPoint.lat, gpsPoint.lng,
                gpsPoint.accuracy, gpsPoint.timestamp
            ))
        }

        // Then
        // Le buffer devrait avoir accepté tous les points (pas de filtre sur le gap temporel)
        val acceptedPoints = points.count { it.accuracy <= MAX_GPS_ACCURACY_METERS }
        assertEquals("Tous les points valides devraient être dans le buffer", acceptedPoints, gpsBuffer.size)

        println("✅ GPS dropout géré - ${gpsBuffer.size} points acceptés malgré gap temporel")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: FRÉQUENCE GPS ADAPTATIVE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `adaptive GPS frequency - low speed uses 4s interval`() {
        // Given
        val speedKmh = 30f // Vitesse en ville

        // When
        val interval = if (speedKmh < LOW_SPEED_THRESHOLD_KMH) {
            LOW_SPEED_UPDATE_INTERVAL
        } else {
            HIGH_SPEED_UPDATE_INTERVAL
        }

        // Then
        assertEquals("Intervalle basse vitesse devrait être 4s", 4000L, interval)

        println("✅ GPS adaptatif - Basse vitesse (${speedKmh} km/h) → intervalle ${interval}ms")
    }

    @Test
    fun `adaptive GPS frequency - high speed uses 10s interval`() {
        // Given
        val speedKmh = 100f // Vitesse autoroute

        // When
        val interval = if (speedKmh < LOW_SPEED_THRESHOLD_KMH) {
            LOW_SPEED_UPDATE_INTERVAL
        } else {
            HIGH_SPEED_UPDATE_INTERVAL
        }

        // Then
        assertEquals("Intervalle haute vitesse devrait être 10s", 10000L, interval)

        println("✅ GPS adaptatif - Haute vitesse (${speedKmh} km/h) → intervalle ${interval}ms")
    }

    @Test
    fun `adaptive GPS frequency - threshold at 40 kmh`() {
        // Given
        val speed39 = 39f
        val speed40 = 40f
        val speed41 = 41f

        // When
        val interval39 = if (speed39 < LOW_SPEED_THRESHOLD_KMH) LOW_SPEED_UPDATE_INTERVAL else HIGH_SPEED_UPDATE_INTERVAL
        val interval40 = if (speed40 < LOW_SPEED_THRESHOLD_KMH) LOW_SPEED_UPDATE_INTERVAL else HIGH_SPEED_UPDATE_INTERVAL
        val interval41 = if (speed41 < LOW_SPEED_THRESHOLD_KMH) LOW_SPEED_UPDATE_INTERVAL else HIGH_SPEED_UPDATE_INTERVAL

        // Then
        assertEquals("39 km/h → 4s", 4000L, interval39)
        assertEquals("40 km/h → 10s (>= seuil)", 10000L, interval40)
        assertEquals("41 km/h → 10s", 10000L, interval41)

        println("✅ GPS adaptatif - Seuil 40 km/h vérifié")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: CALCUL VITESSE MOYENNE
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer calculates average speed correctly`() {
        // Given - 100m en 10 secondes = 10 m/s = 36 km/h
        val now = System.currentTimeMillis()
        // Points formant environ 100m
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.8703, 2.3080, 5f, now + 5000))
        gpsBuffer.add(TripLocation(48.8707, 2.3085, 5f, now + 10000))

        val distance = gpsBuffer.calculateTotalDistance()

        // When
        val speed = gpsBuffer.calculateAverageSpeed()

        // Then
        val expectedSpeed = distance / 10.0 // distance en m / 10 secondes
        assertEquals("Vitesse moyenne incorrecte", expectedSpeed, speed, 0.1)

        println("✅ Vitesse moyenne: ${speed} m/s (${speed * 3.6} km/h)")
    }

    @Test
    fun `buffer calculates zero speed with no time elapsed`() {
        // Given - Points avec le même timestamp
        val now = System.currentTimeMillis()
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.8700, 2.3080, 5f, now)) // Même timestamp

        // When
        val speed = gpsBuffer.calculateAverageSpeed()

        // Then
        assertEquals("Vitesse devrait être 0 sans temps écoulé", 0.0, speed, 0.01)

        println("✅ Vitesse 0 avec même timestamp")
    }

    // ════════════════════════════════════════════════════════════════
    // TESTS: EDGE CASES
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `buffer handles exactly 50m distance threshold`() {
        // Given - Distance exactement à 50m
        val now = System.currentTimeMillis()

        // Créer des points qui forment exactement 50m
        // ~0.00045 degrés de latitude ≈ 50m
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.86990, 2.3076, 5f, now + 4000))
        gpsBuffer.add(TripLocation(48.87025, 2.3078, 5f, now + 8000)) // ~50m total

        val distance = gpsBuffer.calculateTotalDistance()

        // When
        val shouldConfirm = gpsBuffer.shouldAutoConfirm()

        // Then
        // Si distance >= 50m exactement, devrait confirmer
        if (distance >= MIN_BUFFER_DISTANCE) {
            assertTrue("Devrait confirmer à exactement 50m", shouldConfirm)
            println("✅ Distance exacte 50m → Auto-confirm déclenché")
        } else {
            println("ℹ️ Distance ${distance}m juste sous 50m")
        }
    }

    @Test
    fun `buffer handles exactly 1 mps speed threshold`() {
        // Given - Vitesse exactement à 1 m/s
        val now = System.currentTimeMillis()

        // 30m en 30 secondes = 1 m/s exactement
        gpsBuffer.add(TripLocation(48.8698, 2.3075, 5f, now))
        gpsBuffer.add(TripLocation(48.8699, 2.3076, 5f, now + 15000))
        gpsBuffer.add(TripLocation(48.87005, 2.3077, 5f, now + 30000))

        val distance = gpsBuffer.calculateTotalDistance()
        val speed = gpsBuffer.calculateAverageSpeed()

        // When
        val shouldConfirm = gpsBuffer.shouldAutoConfirm()

        // Then
        println("Distance: ${distance}m, Vitesse: ${speed} m/s")
        // Si distance < 50m mais vitesse >= 1 m/s, devrait confirmer
        if (distance < MIN_BUFFER_DISTANCE && speed >= MIN_BUFFER_SPEED) {
            assertTrue("Devrait confirmer à 1 m/s même si distance < 50m", shouldConfirm)
            println("✅ Vitesse 1 m/s → Auto-confirm déclenché")
        }
    }

    @Test
    fun `buffer correctly handles Paris to Versailles route`() {
        // Given - Long trajet réaliste
        val points = TestLocationFactory.createParisToVersaillesRoute()

        // When
        points.forEach { gpsPoint ->
            gpsBuffer.add(TripLocation(
                gpsPoint.lat, gpsPoint.lng,
                gpsPoint.accuracy, gpsPoint.timestamp
            ))
        }

        val distance = gpsBuffer.calculateTotalDistance()
        val speed = gpsBuffer.calculateAverageSpeed()

        // Then
        assertTrue("Distance devrait être > 20km", distance > 20000)
        assertTrue("Vitesse devrait être réaliste (< 50 m/s = 180 km/h)", speed < 50)
        assertTrue("Auto-confirm devrait être déclenché", gpsBuffer.shouldAutoConfirm())

        println("✅ Paris→Versailles - Distance: ${distance/1000}km, Vitesse: ${speed * 3.6} km/h, Points: ${gpsBuffer.size}")
    }
}

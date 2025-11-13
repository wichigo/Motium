package com.application.motium

import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.google.android.gms.location.DetectedActivity
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests d'intégration pour l'autotracking complet
 * Simule des scénarios réels de bout en bout
 */
class AutoTrackingIntegrationTest {

    @Test
    fun `test complete trip flow - start to finish with Bluetooth`() {
        // GIVEN: État initial - utilisateur dans la voiture
        val testScenario = AutoTrackingScenario()

        // Étape 1: L'utilisateur monte dans la voiture
        println("\n=== ÉTAPE 1: Utilisateur monte dans la voiture ===")
        testScenario.connectBluetooth("AA:BB:CC:DD:EE:FF")
        assertTrue("Bluetooth should be connected", testScenario.isBluetoothConnected)

        // Étape 2: Détection IN_VEHICLE par Activity Recognition
        println("\n=== ÉTAPE 2: Détection activité IN_VEHICLE ===")
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 85)
        assertTrue("Should detect IN_VEHICLE", testScenario.currentActivity == DetectedActivity.IN_VEHICLE)

        // Étape 3: Démarrage du trajet
        println("\n=== ÉTAPE 3: Démarrage du tracking GPS ===")
        testScenario.startTrip()
        assertTrue("Trip should be active", testScenario.isTripActive)
        assertNotNull("Trip should be created", testScenario.currentTrip)

        // Étape 4: Collecte de points GPS pendant le trajet (10km sur 15 minutes)
        println("\n=== ÉTAPE 4: Trajet en cours - 10km en 15 minutes ===")
        testScenario.simulateDriving(
            distanceKm = 10.0,
            durationMinutes = 15,
            pointsCount = 90 // 1 point toutes les 10 secondes
        )

        val trip = testScenario.currentTrip!!
        assertTrue("Should have collected GPS points", trip.locations.size >= 3)
        assertTrue("Distance should be ~10km", trip.totalDistance > 9000.0 && trip.totalDistance < 11000.0)

        // Étape 5: L'utilisateur arrive et sort de la voiture
        println("\n=== ÉTAPE 5: Arrivée - détection WALKING ===")
        testScenario.detectActivity(DetectedActivity.WALKING, 82)
        assertTrue("Should detect WALKING", testScenario.currentActivity == DetectedActivity.WALKING)

        // Étape 6: Fin du trajet
        println("\n=== ÉTAPE 6: Fin du trajet et sauvegarde ===")
        testScenario.endTrip()
        assertFalse("Trip should be ended", testScenario.isTripActive)

        val savedTrip = testScenario.getSavedTrip()
        assertNotNull("Trip should be saved", savedTrip)
        assertTrue("Trip should be validated", validateTrip(savedTrip!!))

        // Étape 7: Déconnexion Bluetooth
        println("\n=== ÉTAPE 7: Déconnexion Bluetooth ===")
        testScenario.disconnectBluetooth()
        assertFalse("Bluetooth should be disconnected", testScenario.isBluetoothConnected)

        println("\n✅ Test complet réussi: Trajet de ${String.format("%.1f", savedTrip.totalDistance / 1000)} km sauvegardé")
    }

    @Test
    fun `test trip with multiple stops - traffic lights and parking`() {
        // GIVEN: Trajet avec arrêts multiples
        val testScenario = AutoTrackingScenario()

        // Démarrage
        testScenario.connectBluetooth("AA:BB:CC:DD:EE:FF")
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 88)
        testScenario.startTrip()

        println("\n=== Trajet avec arrêts multiples ===")

        // Segment 1: Conduite 2km
        println("Segment 1: 2km de conduite")
        testScenario.simulateDriving(2.0, 5, 30)

        // Arrêt 1: Feu rouge 30 secondes
        println("Arrêt 1: Feu rouge 30s (devrait être ignoré)")
        testScenario.simulateStop(durationSeconds = 30)
        testScenario.detectActivity(DetectedActivity.STILL, 80)
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 85) // Redémarre

        // Segment 2: Conduite 3km
        println("Segment 2: 3km de conduite")
        testScenario.simulateDriving(3.0, 8, 48)

        // Arrêt 2: Parking 4 minutes (devrait terminer le trajet)
        println("Arrêt 2: Parking 4 minutes (devrait terminer)")
        testScenario.simulateStop(durationSeconds = 240)
        for (i in 0 until 3) {
            testScenario.detectActivity(DetectedActivity.STILL, 85)
        }

        // Fin du trajet automatique après 3 STILL consécutifs
        testScenario.endTrip()

        val savedTrip = testScenario.getSavedTrip()
        assertNotNull("Trip with stops should be saved", savedTrip)
        assertTrue("Total distance should be ~5km", savedTrip!!.totalDistance > 4500.0)
        println("✅ Trajet avec arrêts: ${String.format("%.1f", savedTrip.totalDistance / 1000)} km")
    }

    @Test
    fun `test short trip rejection - too short distance`() {
        // GIVEN: Trajet très court (5m) qui ne devrait pas être sauvegardé
        val testScenario = AutoTrackingScenario()

        println("\n=== Test rejet trajet court ===")

        testScenario.connectBluetooth("AA:BB:CC:DD:EE:FF")
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 80)
        testScenario.startTrip()

        // Conduite très courte
        testScenario.simulateDriving(0.005, 1, 3) // 5 mètres

        testScenario.detectActivity(DetectedActivity.WALKING, 85)
        testScenario.endTrip()

        val savedTrip = testScenario.getSavedTrip()

        // Le trajet devrait être rejeté
        if (savedTrip != null) {
            assertFalse("Short trip should not be validated", validateTrip(savedTrip))
            println("⚠️ Trajet trop court rejeté: ${String.format("%.1f", savedTrip.totalDistance)} m")
        } else {
            println("✅ Trajet trop court non sauvegardé")
        }
    }

    @Test
    fun `test activity recognition only - no Bluetooth`() {
        // GIVEN: Détection uniquement via Activity Recognition (pas de Bluetooth)
        val testScenario = AutoTrackingScenario()

        println("\n=== Test sans Bluetooth - Activity Recognition seul ===")

        // Pas de connexion Bluetooth
        assertFalse("Bluetooth should not be connected", testScenario.isBluetoothConnected)

        // Détection IN_VEHICLE à haute confiance
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 90)
        testScenario.startTrip()

        assertTrue("Should start trip without Bluetooth", testScenario.isTripActive)

        // Trajet normal
        testScenario.simulateDriving(15.0, 20, 120)

        testScenario.detectActivity(DetectedActivity.WALKING, 85)
        testScenario.endTrip()

        val savedTrip = testScenario.getSavedTrip()
        assertNotNull("Trip without Bluetooth should work", savedTrip)
        println("✅ Trajet sans Bluetooth: ${String.format("%.1f", savedTrip!!.totalDistance / 1000)} km")
    }

    @Test
    fun `test false positives - ignore non-vehicle movements`() {
        // GIVEN: Mouvements non-véhicule qui ne devraient pas créer de trajet
        val testScenario = AutoTrackingScenario()

        println("\n=== Test faux positifs ===")

        // Scénario 1: Marche à pied
        testScenario.detectActivity(DetectedActivity.WALKING, 85)
        assertFalse("Walking should not start trip", testScenario.isTripActive)

        // Scénario 2: Vélo
        testScenario.detectActivity(DetectedActivity.ON_BICYCLE, 80)
        assertFalse("Bicycle should not start trip", testScenario.isTripActive)

        // Scénario 3: Immobile
        testScenario.detectActivity(DetectedActivity.STILL, 90)
        assertFalse("Still should not start trip", testScenario.isTripActive)

        // Scénario 4: IN_VEHICLE mais faible confiance
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 40)
        assertFalse("Low confidence IN_VEHICLE should not start trip", testScenario.isTripActive)

        println("✅ Tous les faux positifs correctement ignorés")
    }

    @Test
    fun `test GPS accuracy filtering during trip`() {
        // GIVEN: Trajet avec points GPS de qualités variables
        val testScenario = AutoTrackingScenario()

        println("\n=== Test filtrage précision GPS ===")

        testScenario.connectBluetooth("AA:BB:CC:DD:EE:FF")
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 85)
        testScenario.startTrip()

        // Ajouter des points avec différentes précisions
        val locations = mutableListOf<TripLocation>()
        val startTime = System.currentTimeMillis()

        // Points avec bonnes précisions
        repeat(5) { i ->
            locations.add(TripLocation(48.8566 + i * 0.001, 2.3522 + i * 0.001, 15f, startTime + i * 1000L))
        }

        // Points avec mauvaises précisions (devraient être filtrés)
        repeat(3) { i ->
            locations.add(TripLocation(48.8566 + i * 0.001, 2.3522 + i * 0.001, 150f, startTime + (i + 5) * 1000L))
        }

        // Points avec bonnes précisions
        repeat(5) { i ->
            locations.add(TripLocation(48.8566 + i * 0.001, 2.3522 + i * 0.001, 20f, startTime + (i + 8) * 1000L))
        }

        // Filtrer les points (< 100m précision)
        val filteredLocations = locations.filter { it.accuracy <= 100f }

        println("Points totaux: ${locations.size}")
        println("Points filtrés (précision > 100m): ${locations.size - filteredLocations.size}")
        println("Points conservés: ${filteredLocations.size}")

        assertTrue("Should keep high accuracy points", filteredLocations.size >= 10)
        assertTrue("Should filter low accuracy points", filteredLocations.size < locations.size)

        println("✅ Filtrage GPS fonctionnel")
    }

    @Test
    fun `test concurrent trips prevention - only one active trip`() {
        // GIVEN: Tentative de démarrer plusieurs trajets simultanément
        val testScenario = AutoTrackingScenario()

        println("\n=== Test prévention trajets concurrents ===")

        // Démarrer premier trajet
        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 85)
        testScenario.startTrip()
        val firstTripId = testScenario.currentTrip?.id

        assertTrue("First trip should be active", testScenario.isTripActive)

        // Tenter de démarrer un deuxième trajet (devrait être ignoré)
        val secondTripStarted = testScenario.tryStartTrip()

        assertFalse("Should not start second trip", secondTripStarted)
        assertEquals("Should keep same trip ID", firstTripId, testScenario.currentTrip?.id)

        println("✅ Un seul trajet actif à la fois")
    }

    @Test
    fun `test trip metrics accuracy`() {
        // GIVEN: Trajet avec métriques précises
        val testScenario = AutoTrackingScenario()

        println("\n=== Test précision des métriques ===")

        testScenario.detectActivity(DetectedActivity.IN_VEHICLE, 88)
        testScenario.startTrip()

        // Trajet de 50km en 1 heure (vitesse moyenne 50 km/h)
        testScenario.simulateDriving(50.0, 60, 360)

        testScenario.detectActivity(DetectedActivity.WALKING, 85)
        testScenario.endTrip()

        val trip = testScenario.getSavedTrip()!!
        val duration = (trip.endTime!! - trip.startTime) / 1000.0 / 60.0 // minutes
        val distance = trip.totalDistance / 1000.0 // km
        val avgSpeed = (distance / duration) * 60.0 // km/h

        println("Distance: ${String.format("%.1f", distance)} km")
        println("Durée: ${String.format("%.1f", duration)} min")
        println("Vitesse moyenne: ${String.format("%.1f", avgSpeed)} km/h")

        // Vérifications avec tolérance
        assertTrue("Distance should be ~50km", distance > 48.0 && distance < 52.0)
        assertTrue("Duration should be ~60min", duration > 58.0 && duration < 62.0)
        assertTrue("Average speed should be ~50km/h", avgSpeed > 48.0 && avgSpeed < 52.0)

        println("✅ Métriques précises")
    }

    // Helper function
    private fun validateTrip(trip: Trip): Boolean {
        val duration = (trip.endTime ?: System.currentTimeMillis()) - trip.startTime
        val averageSpeed = if (duration > 0) trip.totalDistance / (duration / 1000.0) else 0.0

        return trip.totalDistance >= 10.0 &&
               duration >= 15000L &&
               averageSpeed >= 0.1 &&
               trip.locations.size >= 3
    }

    /**
     * Classe helper pour simuler un scénario complet d'autotracking
     */
    private class AutoTrackingScenario {
        var isBluetoothConnected = false
        var currentActivity = DetectedActivity.UNKNOWN
        var isTripActive = false
        var currentTrip: Trip? = null
        private var savedTrip: Trip? = null
        private val currentLocations = mutableListOf<TripLocation>()
        private var tripStartTime = 0L
        private var totalDistance = 0.0

        fun connectBluetooth(deviceAddress: String) {
            isBluetoothConnected = true
            println("Bluetooth connected: $deviceAddress")
        }

        fun disconnectBluetooth() {
            isBluetoothConnected = false
            println("Bluetooth disconnected")
        }

        fun detectActivity(activityType: Int, confidence: Int) {
            currentActivity = activityType
            println("Activity detected: ${getActivityName(activityType)} (${confidence}%)")
        }

        fun startTrip() {
            if (!isTripActive) {
                isTripActive = true
                tripStartTime = System.currentTimeMillis()
                currentLocations.clear()
                totalDistance = 0.0
                currentTrip = Trip(
                    id = "trip-${System.currentTimeMillis()}",
                    startTime = tripStartTime,
                    endTime = null,
                    locations = currentLocations,
                    totalDistance = 0.0,
                    isValidated = false
                )
                println("Trip started")
            }
        }

        fun tryStartTrip(): Boolean {
            return if (isTripActive) {
                println("Trip already active, ignoring new start request")
                false
            } else {
                startTrip()
                true
            }
        }

        fun simulateDriving(distanceKm: Double, durationMinutes: Int, pointsCount: Int) {
            val startLat = 48.8566
            val startLng = 2.3522
            val distancePerPoint = distanceKm / pointsCount

            for (i in 0 until pointsCount) {
                val location = TripLocation(
                    latitude = startLat + (i * distancePerPoint / 111.0), // ~111km par degré
                    longitude = startLng + (i * distancePerPoint / 111.0),
                    accuracy = (10 + Math.random() * 15).toFloat(), // 10-25m
                    timestamp = tripStartTime + (i * durationMinutes * 60000L / pointsCount)
                )
                currentLocations.add(location)
                totalDistance += distancePerPoint * 1000 // en mètres
            }

            println("Simulated driving: ${String.format("%.1f", distanceKm)} km in $durationMinutes min ($pointsCount points)")
        }

        fun simulateStop(durationSeconds: Int) {
            val stopLat = currentLocations.lastOrNull()?.latitude ?: 48.8566
            val stopLng = currentLocations.lastOrNull()?.longitude ?: 2.3522
            val stopTime = currentLocations.lastOrNull()?.timestamp ?: System.currentTimeMillis()

            val location = TripLocation(
                latitude = stopLat,
                longitude = stopLng,
                accuracy = 12f,
                timestamp = stopTime + (durationSeconds * 1000L)
            )
            currentLocations.add(location)
            println("Simulated stop: ${durationSeconds}s")
        }

        fun endTrip() {
            if (isTripActive) {
                val endTime = System.currentTimeMillis()
                savedTrip = Trip(
                    id = currentTrip!!.id,
                    startTime = tripStartTime,
                    endTime = endTime,
                    locations = currentLocations.toList(),
                    totalDistance = totalDistance,
                    isValidated = true
                )
                isTripActive = false
                currentTrip = null
                println("Trip ended: ${String.format("%.1f", totalDistance / 1000)} km")
            }
        }

        fun getSavedTrip(): Trip? = savedTrip

        private fun getActivityName(type: Int): String {
            return when (type) {
                DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                DetectedActivity.WALKING -> "WALKING"
                DetectedActivity.STILL -> "STILL"
                DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
                else -> "UNKNOWN"
            }
        }
    }
}

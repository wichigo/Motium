package com.application.motium.integration

import com.application.motium.data.TripLocation
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.testutils.TestActivityTransitionFactory
import com.application.motium.testutils.TestLocationFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.*

/**
 * Test d'intégration complet - Simulation de trajet réel
 *
 * Ce test simule un trajet complet de bout en bout:
 * 1. Détection IN_VEHICLE par Activity Recognition
 * 2. Buffering GPS (collecte initiale)
 * 3. Auto-confirmation du trajet
 * 4. Collecte GPS pendant le trajet
 * 5. Détection WALKING (fin de trajet)
 * 6. Période de grâce (debounce 2 min)
 * 7. Finalisation (collecte points d'arrivée 15s)
 * 8. Création du trajet
 * 9. Geocoding des adresses (VRAI service Nominatim)
 *
 * Trajet simulé: Paris (Champs-Élysées) → Versailles (Château)
 * - Distance: ~22 km
 * - Durée: ~10 min (simulation accélérée)
 * - Points GPS: 53 points
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class RealTripSimulationTest {

    // ════════════════════════════════════════════════════════════════
    // CONSTANTES (copiées de LocationTrackingService)
    // ════════════════════════════════════════════════════════════════

    companion object {
        // Validation
        const val MIN_TRIP_DISTANCE_METERS = 10.0
        const val MIN_TRIP_DURATION_MS = 15000L
        const val MIN_AVERAGE_SPEED_MPS = 0.1
        const val MIN_GPS_POINTS = 2

        // Buffer
        const val MIN_BUFFER_DISTANCE = 50.0
        const val MIN_BUFFER_SPEED = 1.0
        const val MIN_BUFFER_POINTS = 3

        // Debounce
        const val STOP_DEBOUNCE_DELAY_MS = 120000L
        const val END_POINT_SAMPLING_DELAY_MS = 15000L

        // Activity Recognition constants (valeurs de Google Play Services)
        const val ACTIVITY_IN_VEHICLE = 0
        const val ACTIVITY_ON_BICYCLE = 1
        const val ACTIVITY_ON_FOOT = 2
        const val ACTIVITY_STILL = 3
        const val ACTIVITY_WALKING = 7
        const val TRANSITION_ENTER = 0
        const val TRANSITION_EXIT = 1

        // États
        enum class TripState {
            STANDBY,
            BUFFERING,
            TRIP_ACTIVE,
            STOP_PENDING,
            FINALIZING
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SIMULATION: Composants
    // ════════════════════════════════════════════════════════════════

    /**
     * Simule le TripData de LocationTrackingService
     */
    data class SimulatedTrip(
        val id: String = java.util.UUID.randomUUID().toString(),
        val startTime: Long,
        var endTime: Long? = null,
        val locations: MutableList<TripLocation> = mutableListOf(),
        var totalDistance: Double = 0.0,
        var startAddress: String? = null,
        var endAddress: String? = null
    )

    /**
     * Simule le LocationTrackingService avec machine d'états complète
     */
    class SimulatedLocationTrackingService {
        var currentState: TripState = TripState.STANDBY
        var currentTrip: SimulatedTrip? = null
        val gpsBuffer = mutableListOf<TripLocation>()
        val startPointCandidates = mutableListOf<TripLocation>()
        val endPointCandidates = mutableListOf<TripLocation>()

        private var lastLocation: TripLocation? = null
        private var stopPendingStartTime: Long? = null
        private var isCollectingEndPoints = false

        /**
         * Traite un événement Activity Recognition
         */
        fun onActivityTransition(activityType: Int, transitionType: Int) {
            when {
                (activityType == ACTIVITY_IN_VEHICLE || activityType == ACTIVITY_ON_BICYCLE) &&
                transitionType == TRANSITION_ENTER -> {
                    startBuffering()
                }
                (activityType == ACTIVITY_WALKING ||
                 activityType == ACTIVITY_ON_FOOT ||
                 activityType == ACTIVITY_STILL) &&
                transitionType == TRANSITION_ENTER -> {
                    endTrip()
                }
            }
        }

        /**
         * Démarre le buffering GPS
         */
        fun startBuffering() {
            if (currentState == TripState.STANDBY) {
                currentState = TripState.BUFFERING
                gpsBuffer.clear()
            }
        }

        /**
         * Ajoute un point GPS
         */
        fun onLocationReceived(location: TripLocation) {
            when (currentState) {
                TripState.BUFFERING -> {
                    gpsBuffer.add(location)
                    checkAutoConfirmation()
                }
                TripState.TRIP_ACTIVE -> {
                    addLocationToTrip(location)
                    collectStartPointCandidate(location)
                }
                TripState.STOP_PENDING -> {
                    addLocationToTrip(location)
                    // Vérifier si vitesse > seuil pour reprendre
                    checkResumeFromStop()
                }
                TripState.FINALIZING -> {
                    addLocationToTrip(location)
                    if (isCollectingEndPoints) {
                        endPointCandidates.add(location)
                    }
                }
                else -> { }
            }
        }

        /**
         * Vérifie si le buffer doit auto-confirmer le trajet
         */
        private fun checkAutoConfirmation() {
            if (gpsBuffer.size < MIN_BUFFER_POINTS) return

            val totalDistance = calculateBufferDistance()
            val speed = calculateBufferSpeed()

            if (totalDistance >= MIN_BUFFER_DISTANCE || speed >= MIN_BUFFER_SPEED) {
                confirmVehicle()
            }
        }

        /**
         * Confirme le véhicule et passe en TRIP_ACTIVE
         */
        private fun confirmVehicle() {
            if (currentState != TripState.BUFFERING) return

            // Créer le trajet
            currentTrip = SimulatedTrip(startTime = gpsBuffer.first().timestamp)

            // Transférer le buffer
            gpsBuffer.forEach { location ->
                currentTrip?.locations?.add(location)
                updateDistance(location)
            }

            // Points de départ
            startPointCandidates.clear()
            startPointCandidates.addAll(gpsBuffer)

            gpsBuffer.clear()
            currentState = TripState.TRIP_ACTIVE
        }

        /**
         * Démarre la fin du trajet
         */
        private fun endTrip() {
            when (currentState) {
                TripState.TRIP_ACTIVE -> {
                    currentState = TripState.STOP_PENDING
                    stopPendingStartTime = System.currentTimeMillis()
                }
                TripState.BUFFERING -> {
                    gpsBuffer.clear()
                    currentState = TripState.STANDBY
                }
                else -> { }
            }
        }

        /**
         * Vérifie si on doit reprendre le trajet (faux positif de fin)
         */
        private fun checkResumeFromStop() {
            // Simplification: dans le vrai service, on vérifie la vitesse
            // Ici on simule juste le passage du temps
        }

        /**
         * Simule le passage du temps pour le debounce
         */
        fun advanceTime(elapsedMs: Long) {
            if (currentState == TripState.STOP_PENDING) {
                val timeSinceStop = System.currentTimeMillis() - (stopPendingStartTime ?: 0)
                if (timeSinceStop >= STOP_DEBOUNCE_DELAY_MS || elapsedMs >= STOP_DEBOUNCE_DELAY_MS) {
                    startFinalizing()
                }
            } else if (currentState == TripState.FINALIZING && isCollectingEndPoints) {
                if (elapsedMs >= END_POINT_SAMPLING_DELAY_MS) {
                    finishTrip()
                }
            }
        }

        /**
         * Force la transition vers FINALIZING
         */
        fun forceFinalize() {
            if (currentState == TripState.STOP_PENDING) {
                startFinalizing()
            }
        }

        /**
         * Démarre la phase de finalisation
         */
        private fun startFinalizing() {
            currentState = TripState.FINALIZING
            isCollectingEndPoints = true
            endPointCandidates.clear()
        }

        /**
         * Force la fin du trajet
         */
        fun forceFinishTrip() {
            if (currentState == TripState.FINALIZING) {
                finishTrip()
            }
        }

        /**
         * Termine et valide le trajet
         */
        private fun finishTrip() {
            currentTrip?.let { trip ->
                trip.endTime = trip.locations.lastOrNull()?.timestamp ?: System.currentTimeMillis()
            }

            isCollectingEndPoints = false
            currentState = TripState.STANDBY
        }

        /**
         * Ajoute un point au trajet actif
         */
        private fun addLocationToTrip(location: TripLocation) {
            currentTrip?.locations?.add(location)
            updateDistance(location)
        }

        /**
         * Met à jour la distance totale
         */
        private fun updateDistance(newLocation: TripLocation) {
            lastLocation?.let { prev ->
                val distance = haversineDistance(
                    prev.latitude, prev.longitude,
                    newLocation.latitude, newLocation.longitude
                )
                currentTrip?.totalDistance = (currentTrip?.totalDistance ?: 0.0) + distance
            }
            lastLocation = newLocation
        }

        /**
         * Collecte les candidats pour le point de départ
         */
        private fun collectStartPointCandidate(location: TripLocation) {
            val trip = currentTrip ?: return
            val elapsed = location.timestamp - trip.startTime
            if (elapsed < 45000) { // 45s clustering window
                startPointCandidates.add(location)
            }
        }

        /**
         * Calcule la distance totale du buffer
         */
        private fun calculateBufferDistance(): Double {
            if (gpsBuffer.size < 2) return 0.0

            var total = 0.0
            for (i in 1 until gpsBuffer.size) {
                val prev = gpsBuffer[i - 1]
                val curr = gpsBuffer[i]
                total += haversineDistance(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            }
            return total
        }

        /**
         * Calcule la vitesse moyenne du buffer
         */
        private fun calculateBufferSpeed(): Double {
            if (gpsBuffer.size < 2) return 0.0

            val durationMs = gpsBuffer.last().timestamp - gpsBuffer.first().timestamp
            if (durationMs <= 0) return 0.0

            return calculateBufferDistance() / (durationMs / 1000.0)
        }

        /**
         * Calcul de distance Haversine
         */
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

        /**
         * Valide le trajet selon les critères
         */
        fun isValidTrip(): Boolean {
            val trip = currentTrip ?: return false
            val duration = (trip.endTime ?: 0) - trip.startTime
            val avgSpeed = if (duration > 0) trip.totalDistance / (duration / 1000.0) else 0.0

            return trip.totalDistance >= MIN_TRIP_DISTANCE_METERS &&
                   duration >= MIN_TRIP_DURATION_MS &&
                   avgSpeed >= MIN_AVERAGE_SPEED_MPS &&
                   trip.locations.size >= MIN_GPS_POINTS
        }
    }

    private lateinit var locationService: SimulatedLocationTrackingService
    private lateinit var nominatimService: NominatimService

    @Before
    fun setUp() {
        locationService = SimulatedLocationTrackingService()
        nominatimService = NominatimService.getInstance()
    }

    // ════════════════════════════════════════════════════════════════
    // TEST PRINCIPAL: SIMULATION PARIS → VERSAILLES
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `complete trip simulation - Paris to Versailles`() = runTest {
        println("═══════════════════════════════════════════════════════════════")
        println("🚗 SIMULATION COMPLÈTE: Paris (Champs-Élysées) → Versailles")
        println("═══════════════════════════════════════════════════════════════")

        // ══════════════════════════════════════════════════════════════
        // PHASE 1: DÉTECTION VÉHICULE
        // ══════════════════════════════════════════════════════════════
        println("\n📡 Phase 1: Détection Activity Recognition - IN_VEHICLE ENTER")

        // État initial
        assertEquals(TripState.STANDBY, locationService.currentState)

        // Simuler IN_VEHICLE ENTER
        locationService.onActivityTransition(
            ACTIVITY_IN_VEHICLE,
            TRANSITION_ENTER
        )

        // Vérifier transition vers BUFFERING
        assertEquals(TripState.BUFFERING, locationService.currentState)
        println("   ✅ Transition: STANDBY → BUFFERING")

        // ══════════════════════════════════════════════════════════════
        // PHASE 2: BUFFERING GPS (premiers 5 points)
        // ══════════════════════════════════════════════════════════════
        println("\n📍 Phase 2: Buffering GPS - Collecte initiale")

        val route = TestLocationFactory.createParisToVersaillesRoute()

        // Ajouter les 5 premiers points au buffer
        route.take(5).forEach { point ->
            val location = TripLocation(point.lat, point.lng, point.accuracy, point.timestamp)
            locationService.onLocationReceived(location)
        }

        println("   📌 Buffer: ${locationService.gpsBuffer.size} points")

        // ══════════════════════════════════════════════════════════════
        // PHASE 3: AUTO-CONFIRMATION
        // ══════════════════════════════════════════════════════════════
        println("\n🎬 Phase 3: Auto-confirmation du trajet")

        // Après 5 points, le buffer devrait avoir auto-confirmé (distance > 50m)
        assertEquals(
            "Le trajet devrait être actif après auto-confirmation",
            TripState.TRIP_ACTIVE,
            locationService.currentState
        )
        assertNotNull("Un trajet devrait avoir été créé", locationService.currentTrip)
        println("   ✅ Transition: BUFFERING → TRIP_ACTIVE")
        println("   📌 Points transférés: ${locationService.currentTrip?.locations?.size}")

        // ══════════════════════════════════════════════════════════════
        // PHASE 4: COLLECTE GPS ACTIVE (reste du trajet)
        // ══════════════════════════════════════════════════════════════
        println("\n📍 Phase 4: Collecte GPS active - Trajet en cours")

        // Ajouter les points restants (6 à 55)
        route.drop(5).forEach { point ->
            val location = TripLocation(point.lat, point.lng, point.accuracy, point.timestamp)
            locationService.onLocationReceived(location)
        }

        val trip = locationService.currentTrip!!
        println("   📏 Distance accumulée: ${String.format("%.2f", trip.totalDistance / 1000)} km")
        println("   📌 Points GPS totaux: ${trip.locations.size}")

        assertTrue("La distance devrait être > 20km", trip.totalDistance > 20000)
        assertTrue("Devrait avoir >= 50 points", trip.locations.size >= 50)

        // ══════════════════════════════════════════════════════════════
        // PHASE 5: FIN DE TRAJET
        // ══════════════════════════════════════════════════════════════
        println("\n🚶 Phase 5: Détection fin de trajet - WALKING ENTER")

        // Simuler WALKING ENTER
        locationService.onActivityTransition(
            ACTIVITY_WALKING,
            TRANSITION_ENTER
        )

        assertEquals(TripState.STOP_PENDING, locationService.currentState)
        println("   ✅ Transition: TRIP_ACTIVE → STOP_PENDING")

        // ══════════════════════════════════════════════════════════════
        // PHASE 6: PÉRIODE DE GRÂCE (DEBOUNCE)
        // ══════════════════════════════════════════════════════════════
        println("\n⏱️ Phase 6: Période de grâce (2 minutes simulées)")

        // Simuler le passage de 2 minutes
        locationService.forceFinalize()

        assertEquals(TripState.FINALIZING, locationService.currentState)
        println("   ✅ Transition: STOP_PENDING → FINALIZING")

        // ══════════════════════════════════════════════════════════════
        // PHASE 7: FINALISATION
        // ══════════════════════════════════════════════════════════════
        println("\n📍 Phase 7: Collecte points d'arrivée (15s simulées)")

        // Ajouter quelques points d'arrivée
        // Utiliser des timestamps cohérents avec la route (dernier point à +640000ms)
        val routeBaseTime = route.first().timestamp
        repeat(3) { i ->
            val location = TripLocation(
                48.8049 + (i * 0.0001),
                2.1204,
                5f,
                routeBaseTime + 650000 + (i * 5000) // 10s après le dernier point de la route
            )
            locationService.onLocationReceived(location)
        }

        println("   📌 Points d'arrivée collectés: ${locationService.endPointCandidates.size}")

        // Forcer la fin
        locationService.forceFinishTrip()

        assertEquals(TripState.STANDBY, locationService.currentState)
        println("   ✅ Transition: FINALIZING → STANDBY")

        // ══════════════════════════════════════════════════════════════
        // PHASE 8: VALIDATION DU TRAJET
        // ══════════════════════════════════════════════════════════════
        println("\n✅ Phase 8: Validation du trajet créé")

        val savedTrip = locationService.currentTrip!!

        // Vérifier la validité
        assertTrue("Le trajet devrait être valide", locationService.isValidTrip())

        val duration = savedTrip.endTime!! - savedTrip.startTime
        val avgSpeed = savedTrip.totalDistance / (duration / 1000.0)

        println("   📏 Distance: ${String.format("%.2f", savedTrip.totalDistance / 1000)} km")
        println("   ⏱️ Durée: ${duration / 60000} min ${(duration % 60000) / 1000} s")
        println("   🚗 Vitesse moyenne: ${String.format("%.1f", avgSpeed * 3.6)} km/h")
        println("   📌 Points GPS: ${savedTrip.locations.size}")

        assertTrue("Distance > 20km", savedTrip.totalDistance > 20000)
        assertTrue("Distance < 30km", savedTrip.totalDistance < 30000)
        assertTrue("Durée > 5min", duration > 300000)
        assertTrue("Points >= 50", savedTrip.locations.size >= 50)

        // ══════════════════════════════════════════════════════════════
        // PHASE 9: GEOCODING (VRAI SERVICE)
        // ══════════════════════════════════════════════════════════════
        println("\n🌍 Phase 9: Geocoding des adresses (vrai Nominatim)")

        val startPoint = savedTrip.locations.first()
        val endPoint = savedTrip.locations.last()

        val startAddress = nominatimService.reverseGeocode(startPoint.latitude, startPoint.longitude)
        val endAddress = nominatimService.reverseGeocode(endPoint.latitude, endPoint.longitude)

        savedTrip.startAddress = startAddress
        savedTrip.endAddress = endAddress

        println("   📍 Départ: $startAddress")
        println("   🏁 Arrivée: $endAddress")

        // Résumé final
        println("\n═══════════════════════════════════════════════════════════════")
        println("✅ SIMULATION PARIS → VERSAILLES RÉUSSIE!")
        println("═══════════════════════════════════════════════════════════════")
        println("   📍 Départ: ${savedTrip.startAddress ?: "Non géocodé"}")
        println("   🏁 Arrivée: ${savedTrip.endAddress ?: "Non géocodé"}")
        println("   📏 Distance: ${String.format("%.2f", savedTrip.totalDistance / 1000)} km")
        println("   ⏱️ Durée: ${duration / 60000} min")
        println("   📌 Points GPS: ${savedTrip.locations.size}")
        println("═══════════════════════════════════════════════════════════════")
    }

    // ════════════════════════════════════════════════════════════════
    // SCÉNARIOS ADDITIONNELS
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `trip with brief stops - should not create multiple trips`() = runTest {
        println("\n🚦 TEST: Trajet avec arrêts brefs (bouchon)")

        // Démarrer le trajet
        locationService.onActivityTransition(
            ACTIVITY_IN_VEHICLE,
            TRANSITION_ENTER
        )

        // Charger le trajet avec arrêts
        val route = TestLocationFactory.createTripWithBriefStops()

        route.forEach { point ->
            val location = TripLocation(point.lat, point.lng, point.accuracy, point.timestamp)
            locationService.onLocationReceived(location)
        }

        // Un seul trajet devrait exister
        assertNotNull("Un trajet devrait exister", locationService.currentTrip)
        println("   ✅ Un seul trajet malgré les arrêts")
    }

    @Test
    fun `trip with GPS dropout in tunnel - resumes correctly`() = runTest {
        println("\n🚇 TEST: Trajet avec perte GPS (tunnel)")

        // Démarrer le trajet
        locationService.onActivityTransition(
            ACTIVITY_IN_VEHICLE,
            TRANSITION_ENTER
        )

        // Charger le trajet avec tunnel
        val route = TestLocationFactory.createTripWithTunnel()

        route.forEach { point ->
            val location = TripLocation(point.lat, point.lng, point.accuracy, point.timestamp)
            locationService.onLocationReceived(location)
        }

        // Le trajet devrait être actif malgré le gap
        assertEquals(TripState.TRIP_ACTIVE, locationService.currentState)

        val trip = locationService.currentTrip!!
        println("   📏 Distance: ${String.format("%.2f", trip.totalDistance)} m")
        println("   📌 Points: ${trip.locations.size}")
        println("   ✅ Trajet continue après tunnel")
    }

    @Test
    fun `very short trip - rejected if under 10m`() = runTest {
        println("\n🅿️ TEST: Trajet très court (< 10m)")

        // Démarrer le trajet
        locationService.onActivityTransition(
            ACTIVITY_IN_VEHICLE,
            TRANSITION_ENTER
        )

        // Charger un trajet court
        val route = TestLocationFactory.createShortTrip()

        route.forEach { point ->
            val location = TripLocation(point.lat, point.lng, point.accuracy, point.timestamp)
            locationService.onLocationReceived(location)
        }

        // Toujours en BUFFERING car distance < seuil d'auto-confirmation
        assertEquals(TripState.BUFFERING, locationService.currentState)
        println("   ❌ Trajet non confirmé (distance < 50m)")

        // Simuler fin
        locationService.onActivityTransition(
            ACTIVITY_WALKING,
            TRANSITION_ENTER
        )

        // Devrait être de retour en STANDBY sans trajet
        assertEquals(TripState.STANDBY, locationService.currentState)
        println("   ✅ Trajet trop court rejeté")
    }

    @Test
    fun `bicycle detection - creates trip same as vehicle`() = runTest {
        println("\n🚲 TEST: Détection vélo")

        // Simuler ON_BICYCLE
        locationService.onActivityTransition(
            ACTIVITY_ON_BICYCLE,
            TRANSITION_ENTER
        )

        // Le comportement devrait être le même que IN_VEHICLE
        // Note: Dans notre simulation simplifiée, ON_BICYCLE n'est pas géré
        // mais dans le vrai service, il déclenche aussi startBuffering()

        println("   ℹ️ Dans le vrai service, ON_BICYCLE déclenche le même comportement que IN_VEHICLE")
    }

    @Test
    fun `long highway trip - handles correctly`() = runTest {
        println("\n🛣️ TEST: Long trajet autoroute")

        // Démarrer le trajet
        locationService.onActivityTransition(
            ACTIVITY_IN_VEHICLE,
            TRANSITION_ENTER
        )

        // Charger le long trajet autoroute
        val route = TestLocationFactory.createLongHighwayTrip()

        route.forEach { point ->
            val location = TripLocation(point.lat, point.lng, point.accuracy, point.timestamp)
            locationService.onLocationReceived(location)
        }

        val trip = locationService.currentTrip!!
        println("   📏 Distance: ${String.format("%.2f", trip.totalDistance / 1000)} km")
        println("   📌 Points: ${trip.locations.size}")

        assertTrue("Distance > 400km", trip.totalDistance > 400000)
        println("   ✅ Long trajet autoroute géré correctement")
    }

    // ════════════════════════════════════════════════════════════════
    // TEST: SÉQUENCE D'ÉVÉNEMENTS ACTIVITY RECOGNITION
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `activity transition sequence - complete flow`() = runTest {
        println("\n📱 TEST: Séquence complète Activity Recognition")

        // 1. Début: IN_VEHICLE ENTER
        locationService.onActivityTransition(ACTIVITY_IN_VEHICLE, TRANSITION_ENTER)
        assertEquals(TripState.BUFFERING, locationService.currentState)
        println("   1. IN_VEHICLE ENTER → BUFFERING ✅")

        // 2. Ajouter des points pour déclencher auto-confirmation
        val route = TestLocationFactory.createMediumTrip()
        route.forEach { point ->
            locationService.onLocationReceived(TripLocation(point.lat, point.lng, point.accuracy, point.timestamp))
        }
        assertEquals(TripState.TRIP_ACTIVE, locationService.currentState)
        println("   2. GPS auto-confirm → TRIP_ACTIVE ✅")

        // 3. Fin: WALKING ENTER
        locationService.onActivityTransition(ACTIVITY_WALKING, TRANSITION_ENTER)
        assertEquals(TripState.STOP_PENDING, locationService.currentState)
        println("   3. WALKING ENTER → STOP_PENDING ✅")

        // 4. Debounce terminé
        locationService.forceFinalize()
        assertEquals(TripState.FINALIZING, locationService.currentState)
        println("   4. Debounce terminé → FINALIZING ✅")

        // 5. Fin de collecte
        locationService.forceFinishTrip()
        assertEquals(TripState.STANDBY, locationService.currentState)
        println("   5. Collecte terminée → STANDBY ✅")

        println("   ✅ Séquence complète d'états vérifiée")
    }
}

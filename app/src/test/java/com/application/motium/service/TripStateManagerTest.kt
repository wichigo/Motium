package com.application.motium.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires pour TripStateManager.
 *
 * Vérifie le comportement de la machine d'état:
 * - IDLE → MOVING (démarrage immédiat)
 * - MOVING → POSSIBLY_STOPPED (STILL détecté)
 * - POSSIBLY_STOPPED → MOVING (reprise avant 3 min)
 * - POSSIBLY_STOPPED → IDLE (arrêt confirmé après 3 min)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripStateManagerTest {

    private var tripStartedCalled = false
    private var tripEndedCalled = false
    private var lastStartedTripId: String? = null
    private var lastEndedTripId: String? = null
    private var lastState: TripStateManager.TrackingState? = null

    @Before
    fun setup() {
        // Reset callbacks
        tripStartedCalled = false
        tripEndedCalled = false
        lastStartedTripId = null
        lastEndedTripId = null
        lastState = null

        // Setup test callbacks
        TripStateManager.onTripStarted = { tripId ->
            tripStartedCalled = true
            lastStartedTripId = tripId
        }
        TripStateManager.onTripEnded = { tripId ->
            tripEndedCalled = true
            lastEndedTripId = tripId
        }
        TripStateManager.onStateChanged = { state ->
            lastState = state
        }

        // Reset state to IDLE
        TripStateManager.forceReset()
        tripStartedCalled = false
        tripEndedCalled = false
    }

    @After
    fun tearDown() {
        TripStateManager.onTripStarted = null
        TripStateManager.onTripEnded = null
        TripStateManager.onStateChanged = null
        TripStateManager.forceReset()
    }

    // ==================== TESTS DÉMARRAGE IMMÉDIAT ====================

    @Test
    fun `demarrage immediat - IDLE vers MOVING sur VehicleEnter`() {
        // Given: état IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)

        // When: détection véhicule
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)

        // Then: passage immédiat à MOVING avec tripId généré
        val state = TripStateManager.currentState
        assertTrue("Should be Moving state", state is TripStateManager.TrackingState.Moving)
        assertNotNull("Trip ID should be set", (state as TripStateManager.TrackingState.Moving).tripId)
        assertTrue("onTripStarted should be called", tripStartedCalled)
        assertEquals(state.tripId, lastStartedTripId)
    }

    @Test
    fun `demarrage immediat - IDLE vers MOVING sur BicycleEnter`() {
        // Given: état IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)

        // When: détection vélo
        TripStateManager.onEvent(TripStateManager.TrackingEvent.BicycleEnter)

        // Then: passage immédiat à MOVING
        val state = TripStateManager.currentState
        assertTrue("Should be Moving state", state is TripStateManager.TrackingState.Moving)
        assertTrue("onTripStarted should be called", tripStartedCalled)
    }

    @Test
    fun `STILL en IDLE est ignore`() {
        // Given: état IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)

        // When: détection STILL
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        // Then: reste en IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)
        assertFalse("onTripStarted should NOT be called", tripStartedCalled)
    }

    @Test
    fun `WALKING en IDLE est ignore`() {
        // Given: état IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)

        // When: détection marche
        TripStateManager.onEvent(TripStateManager.TrackingEvent.WalkingEnter)

        // Then: reste en IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)
        assertFalse("onTripStarted should NOT be called", tripStartedCalled)
    }

    // ==================== TESTS TRANSITION MOVING → POSSIBLY_STOPPED ====================

    @Test
    fun `STILL en MOVING passe en POSSIBLY_STOPPED avec meme tripId`() {
        // Given: état MOVING
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val movingState = TripStateManager.currentState as TripStateManager.TrackingState.Moving
        val originalTripId = movingState.tripId

        // When: détection STILL
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        // Then: passage à POSSIBLY_STOPPED avec MÊME tripId
        val state = TripStateManager.currentState
        assertTrue("Should be PossiblyStopped state", state is TripStateManager.TrackingState.PossiblyStopped)
        assertEquals(
            "Trip ID should be preserved",
            originalTripId,
            (state as TripStateManager.TrackingState.PossiblyStopped).tripId
        )
        assertFalse("Trip should NOT end yet", tripEndedCalled)
    }

    @Test
    fun `WALKING en MOVING passe en POSSIBLY_STOPPED`() {
        // Given: état MOVING
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val movingState = TripStateManager.currentState as TripStateManager.TrackingState.Moving
        val originalTripId = movingState.tripId

        // When: détection marche (après avoir conduit = probablement arrivé)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.WalkingEnter)

        // Then: passage à POSSIBLY_STOPPED
        val state = TripStateManager.currentState
        assertTrue("Should be PossiblyStopped state", state is TripStateManager.TrackingState.PossiblyStopped)
        assertEquals(originalTripId, (state as TripStateManager.TrackingState.PossiblyStopped).tripId)
    }

    @Test
    fun `VehicleEnter en MOVING est ignore`() {
        // Given: état MOVING
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val originalTripId = (TripStateManager.currentState as TripStateManager.TrackingState.Moving).tripId

        // Reset callback flags
        tripStartedCalled = false

        // When: encore VehicleEnter (redondant)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)

        // Then: reste en MOVING avec même tripId
        val state = TripStateManager.currentState
        assertTrue(state is TripStateManager.TrackingState.Moving)
        assertEquals(originalTripId, (state as TripStateManager.TrackingState.Moving).tripId)
        assertFalse("onTripStarted should NOT be called again", tripStartedCalled)
    }

    // ==================== TESTS FEU ROUGE / BOUCHON (REPRISE) ====================

    @Test
    fun `feu rouge ignore - VehicleEnter en POSSIBLY_STOPPED reprend MOVING avec meme tripId`() {
        // Given: état POSSIBLY_STOPPED (simulation feu rouge < 3 min)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val originalTripId = (TripStateManager.currentState as TripStateManager.TrackingState.Moving).tripId
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        val possiblyStopped = TripStateManager.currentState
        assertTrue(possiblyStopped is TripStateManager.TrackingState.PossiblyStopped)

        // When: reprise mouvement AVANT expiration timer (feu rouge terminé)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)

        // Then: retour à MOVING avec MÊME tripId (pas de nouveau trajet!)
        val state = TripStateManager.currentState
        assertTrue("Should be back to Moving", state is TripStateManager.TrackingState.Moving)
        assertEquals(
            "Trip ID should be SAME (feu rouge ignored)",
            originalTripId,
            (state as TripStateManager.TrackingState.Moving).tripId
        )
        assertFalse("Trip should NOT have ended", tripEndedCalled)
    }

    @Test
    fun `BicycleEnter en POSSIBLY_STOPPED reprend MOVING`() {
        // Given: cycliste arrêté (POSSIBLY_STOPPED)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.BicycleEnter)
        val originalTripId = (TripStateManager.currentState as TripStateManager.TrackingState.Moving).tripId
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        // When: reprend le vélo
        TripStateManager.onEvent(TripStateManager.TrackingEvent.BicycleEnter)

        // Then: retour à MOVING avec même tripId
        val state = TripStateManager.currentState
        assertTrue(state is TripStateManager.TrackingState.Moving)
        assertEquals(originalTripId, (state as TripStateManager.TrackingState.Moving).tripId)
    }

    @Test
    fun `STILL en POSSIBLY_STOPPED reste en POSSIBLY_STOPPED`() {
        // Given: état POSSIBLY_STOPPED
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val originalTripId = (TripStateManager.currentState as TripStateManager.TrackingState.Moving).tripId
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        // When: encore STILL (redondant)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        // Then: reste en POSSIBLY_STOPPED avec même tripId
        val state = TripStateManager.currentState
        assertTrue(state is TripStateManager.TrackingState.PossiblyStopped)
        assertEquals(originalTripId, (state as TripStateManager.TrackingState.PossiblyStopped).tripId)
    }

    // ==================== TESTS ARRÊT CONFIRMÉ ====================

    @Test
    fun `arret confirme - StillConfirmed termine le trajet`() {
        // Given: état POSSIBLY_STOPPED
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val tripId = (TripStateManager.currentState as TripStateManager.TrackingState.Moving).tripId
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)

        // When: timer 3 min expiré (simulation)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillConfirmed)

        // Then: retour à IDLE + trajet terminé
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)
        assertTrue("onTripEnded should be called", tripEndedCalled)
        assertEquals("Correct tripId should be passed", tripId, lastEndedTripId)
    }

    @Test
    fun `StillConfirmed en MOVING est ignore`() {
        // Given: état MOVING (pas en POSSIBLY_STOPPED)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)

        // When: StillConfirmed (ne devrait pas arriver normalement)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillConfirmed)

        // Then: reste en MOVING (événement ignoré)
        assertTrue(TripStateManager.currentState is TripStateManager.TrackingState.Moving)
        assertFalse("Trip should NOT end", tripEndedCalled)
    }

    // ==================== TESTS ENCHAÎNEMENT RAPIDE ====================

    @Test
    fun `enchainement rapide - nouveau trajet apres arret confirme`() {
        // Given: premier trajet terminé
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val firstTripId = (TripStateManager.currentState as TripStateManager.TrackingState.Moving).tripId
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillConfirmed)

        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)
        assertTrue(tripEndedCalled)
        assertEquals(firstTripId, lastEndedTripId)

        // Reset flags
        tripStartedCalled = false
        tripEndedCalled = false

        // When: nouveau départ
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)

        // Then: nouveau trajet avec NOUVEAU tripId
        val state = TripStateManager.currentState
        assertTrue(state is TripStateManager.TrackingState.Moving)
        val secondTripId = (state as TripStateManager.TrackingState.Moving).tripId
        assertNotEquals("Should be a NEW trip ID", firstTripId, secondTripId)
        assertTrue("onTripStarted should be called for new trip", tripStartedCalled)
        assertEquals(secondTripId, lastStartedTripId)
    }

    // ==================== TESTS UTILITY ====================

    @Test
    fun `isTripInProgress retourne true en MOVING`() {
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        assertTrue(TripStateManager.isTripInProgress())
    }

    @Test
    fun `isTripInProgress retourne true en POSSIBLY_STOPPED`() {
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)
        assertTrue(TripStateManager.isTripInProgress())
    }

    @Test
    fun `isTripInProgress retourne false en IDLE`() {
        assertFalse(TripStateManager.isTripInProgress())
    }

    @Test
    fun `getCurrentTripId retourne tripId en MOVING`() {
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val tripId = TripStateManager.getCurrentTripId()
        assertNotNull(tripId)
    }

    @Test
    fun `getCurrentTripId retourne tripId en POSSIBLY_STOPPED`() {
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val movingTripId = TripStateManager.getCurrentTripId()
        TripStateManager.onEvent(TripStateManager.TrackingEvent.StillEnter)
        val stoppedTripId = TripStateManager.getCurrentTripId()
        assertEquals("Trip ID should be same", movingTripId, stoppedTripId)
    }

    @Test
    fun `getCurrentTripId retourne null en IDLE`() {
        assertNull(TripStateManager.getCurrentTripId())
    }

    @Test
    fun `forceReset termine le trajet et retourne a IDLE`() {
        // Given: trajet en cours
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)
        val tripId = TripStateManager.getCurrentTripId()

        // When: force reset
        TripStateManager.forceReset()

        // Then: retour à IDLE + trajet terminé
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)
        assertTrue("onTripEnded should be called", tripEndedCalled)
        assertEquals(tripId, lastEndedTripId)
    }

    // ==================== TESTS RUNNING ====================

    @Test
    fun `RUNNING en MOVING passe en POSSIBLY_STOPPED`() {
        // Given: état MOVING
        TripStateManager.onEvent(TripStateManager.TrackingEvent.VehicleEnter)

        // When: détection course (après avoir conduit)
        TripStateManager.onEvent(TripStateManager.TrackingEvent.RunningEnter)

        // Then: passage à POSSIBLY_STOPPED (comme STILL/WALKING)
        val state = TripStateManager.currentState
        assertTrue(state is TripStateManager.TrackingState.PossiblyStopped)
    }

    @Test
    fun `RUNNING en IDLE est ignore`() {
        // When: détection course en IDLE
        TripStateManager.onEvent(TripStateManager.TrackingEvent.RunningEnter)

        // Then: reste en IDLE
        assertEquals(TripStateManager.TrackingState.Idle, TripStateManager.currentState)
    }
}

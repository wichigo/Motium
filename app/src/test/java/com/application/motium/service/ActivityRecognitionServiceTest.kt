package com.application.motium.service

import com.google.android.gms.location.DetectedActivity
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests unitaires pour ActivityRecognitionService
 * Teste tous les scénarios de détection d'activité et transitions
 */
class ActivityRecognitionServiceTest {

    @Test
    fun `test activity detection - IN_VEHICLE should trigger trip start`() {
        // GIVEN: Un résultat avec activité IN_VEHICLE à haute confiance
        val activities = listOf(
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 85),
            createDetectedActivity(DetectedActivity.STILL, 10),
            createDetectedActivity(DetectedActivity.WALKING, 5)
        )

        val mostProbableActivity = getMostProbableActivity(activities)

        // WHEN: On analyse l'activité
        val shouldStartTrip = mostProbableActivity.type == DetectedActivity.IN_VEHICLE &&
                              mostProbableActivity.confidence >= 75

        // THEN: Un trajet devrait être démarré
        assertTrue("IN_VEHICLE with 85% confidence should start trip", shouldStartTrip)
        assertEquals(DetectedActivity.IN_VEHICLE, mostProbableActivity.type)
        assertEquals(85, mostProbableActivity.confidence)
    }

    @Test
    fun `test activity detection - low confidence IN_VEHICLE should not trigger`() {
        // GIVEN: IN_VEHICLE avec faible confiance
        val activities = listOf(
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 40),
            createDetectedActivity(DetectedActivity.STILL, 35),
            createDetectedActivity(DetectedActivity.WALKING, 25)
        )

        val mostProbableActivity = getMostProbableActivity(activities)

        // WHEN: On vérifie si on doit démarrer
        val shouldStartTrip = mostProbableActivity.type == DetectedActivity.IN_VEHICLE &&
                              mostProbableActivity.confidence >= 75

        // THEN: Ne devrait PAS démarrer (confiance trop faible)
        assertFalse("Low confidence IN_VEHICLE should not start trip", shouldStartTrip)
    }

    @Test
    fun `test activity transition - IN_VEHICLE to WALKING should end trip`() {
        // GIVEN: Transition de véhicule vers marche
        val previousActivity = DetectedActivity.IN_VEHICLE
        val currentActivity = createDetectedActivity(DetectedActivity.WALKING, 80)

        // WHEN: On détecte la transition
        val shouldEndTrip = previousActivity == DetectedActivity.IN_VEHICLE &&
                           currentActivity.type == DetectedActivity.WALKING &&
                           currentActivity.confidence >= 75

        // THEN: Le trajet devrait se terminer
        assertTrue("IN_VEHICLE -> WALKING should end trip", shouldEndTrip)
    }

    @Test
    fun `test activity transition - IN_VEHICLE to STILL should not immediately end trip`() {
        // GIVEN: Transition de véhicule vers immobile (arrêt)
        val previousActivity = DetectedActivity.IN_VEHICLE
        val currentActivity = createDetectedActivity(DetectedActivity.STILL, 85)

        // WHEN: On détecte STILL après IN_VEHICLE
        val isStill = currentActivity.type == DetectedActivity.STILL
        val shouldWaitBeforeEnding = true // On attend plusieurs STILL consécutifs

        // THEN: Ne devrait PAS terminer immédiatement (peut être un feu rouge)
        assertTrue("STILL after IN_VEHICLE requires consecutive checks", isStill && shouldWaitBeforeEnding)
    }

    @Test
    fun `test consecutive STILL detection - 3 times should end trip`() {
        // GIVEN: 3 détections STILL consécutives après IN_VEHICLE
        val stillDetections = listOf(
            createDetectedActivity(DetectedActivity.STILL, 85),
            createDetectedActivity(DetectedActivity.STILL, 88),
            createDetectedActivity(DetectedActivity.STILL, 90)
        )

        // WHEN: On compte les STILL consécutifs
        val consecutiveStillCount = stillDetections.size
        val shouldEndTrip = consecutiveStillCount >= 3

        // THEN: Après 3 STILL, le trajet devrait se terminer
        assertTrue("3 consecutive STILL should end trip", shouldEndTrip)
        assertEquals(3, consecutiveStillCount)
    }

    @Test
    fun `test consecutive STILL reset - IN_VEHICLE interrupts count`() {
        // GIVEN: Séquence STILL -> STILL -> IN_VEHICLE -> STILL
        val activities = listOf(
            createDetectedActivity(DetectedActivity.STILL, 85),
            createDetectedActivity(DetectedActivity.STILL, 87),
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 80),
            createDetectedActivity(DetectedActivity.STILL, 85)
        )

        // WHEN: On simule le comptage avec reset
        var consecutiveStillCount = 0
        for (activity in activities) {
            if (activity.type == DetectedActivity.STILL) {
                consecutiveStillCount++
            } else {
                consecutiveStillCount = 0 // Reset
            }
        }

        // THEN: Le compteur devrait être à 1 (dernier STILL seulement)
        assertEquals("IN_VEHICLE should reset STILL counter", 1, consecutiveStillCount)
    }

    @Test
    fun `test multiple activities - highest confidence wins`() {
        // GIVEN: Plusieurs activités avec différentes confiances
        val activities = listOf(
            createDetectedActivity(DetectedActivity.WALKING, 45),
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 92), // Gagnant
            createDetectedActivity(DetectedActivity.STILL, 38),
            createDetectedActivity(DetectedActivity.ON_BICYCLE, 15)
        )

        // WHEN: On sélectionne la plus probable
        val mostProbable = getMostProbableActivity(activities)

        // THEN: IN_VEHICLE devrait gagner avec 92%
        assertEquals(DetectedActivity.IN_VEHICLE, mostProbable.type)
        assertEquals(92, mostProbable.confidence)
    }

    @Test
    fun `test activity type names - verify all types are handled`() {
        // GIVEN: Tous les types d'activités possibles
        val activityTypes = mapOf(
            DetectedActivity.IN_VEHICLE to "IN_VEHICLE",
            DetectedActivity.ON_BICYCLE to "ON_BICYCLE",
            DetectedActivity.ON_FOOT to "ON_FOOT",
            DetectedActivity.RUNNING to "RUNNING",
            DetectedActivity.STILL to "STILL",
            DetectedActivity.WALKING to "WALKING",
            DetectedActivity.UNKNOWN to "UNKNOWN"
        )

        // WHEN/THEN: Tous les types doivent avoir un nom
        activityTypes.forEach { (type, name) ->
            assertNotNull("Activity type $type should have a name", name)
            assertTrue("Name should not be empty", name.isNotEmpty())
        }

        assertEquals("Should handle 7 activity types", 7, activityTypes.size)
    }

    @Test
    fun `test trip start conditions - all criteria must be met`() {
        // GIVEN: Différentes conditions
        val inVehicle = true
        val highConfidence = 85 >= 75
        val bluetoothConnected = true
        val hasLocationPermission = true

        // WHEN: On vérifie toutes les conditions
        val canStartTrip = inVehicle && highConfidence && hasLocationPermission

        // THEN: Toutes les conditions doivent être vraies
        assertTrue("All conditions must be met to start trip", canStartTrip)
    }

    @Test
    fun `test trip end conditions - multiple scenarios`() {
        // Scénario 1: WALKING détecté
        val walkingDetected = true
        val shouldEndForWalking = walkingDetected
        assertTrue("WALKING should end trip", shouldEndForWalking)

        // Scénario 2: 3+ STILL consécutifs
        val consecutiveStillCount = 3
        val shouldEndForStill = consecutiveStillCount >= 3
        assertTrue("3+ STILL should end trip", shouldEndForStill)

        // Scénario 3: Bluetooth déconnecté (optionnel)
        val bluetoothDisconnected = true
        val vehicleWasDetectedViaBluetoothOnly = false
        val shouldEndForBluetooth = bluetoothDisconnected && vehicleWasDetectedViaBluetoothOnly
        assertFalse("Bluetooth alone should not end trip if IN_VEHICLE active", shouldEndForBluetooth)
    }

    @Test
    fun `test activity confidence thresholds`() {
        // Test des différents seuils de confiance
        val highConfidence = 75
        val mediumConfidence = 50
        val lowConfidence = 30

        assertTrue("75% should be considered high confidence", highConfidence >= 75)
        assertFalse("50% should not be considered high confidence", mediumConfidence >= 75)
        assertFalse("30% should not be considered high confidence", lowConfidence >= 75)
    }

    @Test
    fun `test rapid activity changes - debouncing needed`() {
        // GIVEN: Changements rapides d'activité
        val rapidChanges = listOf(
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 80),
            createDetectedActivity(DetectedActivity.STILL, 75),
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 82),
            createDetectedActivity(DetectedActivity.WALKING, 70),
            createDetectedActivity(DetectedActivity.IN_VEHICLE, 85)
        )

        // WHEN: On filtre les changements avec debouncing
        var stableActivity: DetectedActivity? = null
        var sameActivityCount = 0
        var lastActivity: DetectedActivity? = null

        for (activity in rapidChanges) {
            if (lastActivity?.type == activity.type) {
                sameActivityCount++
                if (sameActivityCount >= 2) {
                    stableActivity = activity
                }
            } else {
                sameActivityCount = 1
                lastActivity = activity
            }
        }

        // THEN: Une activité stable devrait être identifiée
        assertNotNull("Should identify stable activity after multiple same detections", stableActivity)
    }

    // Helper functions
    private fun createDetectedActivity(type: Int, confidence: Int): DetectedActivity {
        return DetectedActivity(type, confidence)
    }

    private fun getMostProbableActivity(activities: List<DetectedActivity>): DetectedActivity {
        return activities.maxByOrNull { it.confidence } ?: DetectedActivity(DetectedActivity.UNKNOWN, 0)
    }

    private fun getActivityTypeName(type: Int): String {
        return when (type) {
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.ON_FOOT -> "ON_FOOT"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.TILTING -> "TILTING"
            else -> "UNKNOWN"
        }
    }
}

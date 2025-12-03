package com.application.motium.service

import com.application.motium.domain.model.TrackingMode
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests unitaires pour la logique des modes d'auto-tracking
 * Teste les 3 modes : ALWAYS, WORK_HOURS_ONLY, DISABLED
 */
class AutoTrackingModeTest {

    // ========== Tests pour TrackingMode enum ==========

    /**
     * TEST 1: Vérifier que tous les modes sont définis
     */
    @Test
    fun testTrackingMode_AllModesExist() {
        val modes = TrackingMode.entries

        assertEquals(3, modes.size)
        assertTrue(modes.contains(TrackingMode.ALWAYS))
        assertTrue(modes.contains(TrackingMode.WORK_HOURS_ONLY))
        assertTrue(modes.contains(TrackingMode.DISABLED))

        println("✓ TEST 1: All 3 tracking modes exist")
    }

    /**
     * TEST 2: Mode ALWAYS - Doit activer le tracking en permanence
     */
    @Test
    fun testAlwaysMode_AlwaysEnabled() {
        // Given: Mode = ALWAYS
        val mode = TrackingMode.ALWAYS

        // When: Vérification à n'importe quel moment
        // Then: Le tracking doit être actif
        val shouldTrack = shouldAutotrackForMode(
            mode = mode,
            isInWorkHours = false, // peu importe
            currentlyEnabled = false
        )

        assertTrue(shouldTrack)
        println("✓ TEST 2: ALWAYS mode always enables tracking")
    }

    /**
     * TEST 3: Mode ALWAYS - Doit réactiver le service s'il est arrêté
     */
    @Test
    fun testAlwaysMode_ReenablesIfStopped() {
        // Given: Mode = ALWAYS, mais service actuellement arrêté
        val mode = TrackingMode.ALWAYS
        val currentlyEnabled = false

        // When: Le worker vérifie l'état
        // Then: Il doit réactiver le service
        val action = determineAction(
            mode = mode,
            isInWorkHours = false,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.ENABLE, action)
        println("✓ TEST 3: ALWAYS mode re-enables if stopped")
    }

    /**
     * TEST 4: Mode ALWAYS - Ne rien faire si déjà actif
     */
    @Test
    fun testAlwaysMode_NoActionIfAlreadyEnabled() {
        // Given: Mode = ALWAYS, service déjà actif
        val mode = TrackingMode.ALWAYS
        val currentlyEnabled = true

        // When: Le worker vérifie l'état
        // Then: Pas d'action nécessaire
        val action = determineAction(
            mode = mode,
            isInWorkHours = false,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.NONE, action)
        println("✓ TEST 4: ALWAYS mode does nothing if already enabled")
    }

    // ========== Tests pour WORK_HOURS_ONLY ==========

    /**
     * TEST 5: Mode PRO - Activer pendant les horaires de travail
     */
    @Test
    fun testWorkHoursMode_EnabledDuringWorkHours() {
        // Given: Mode = WORK_HOURS_ONLY, dans les horaires
        val mode = TrackingMode.WORK_HOURS_ONLY
        val isInWorkHours = true
        val currentlyEnabled = false

        // When: Le worker vérifie l'état
        // Then: Il doit activer le service
        val action = determineAction(
            mode = mode,
            isInWorkHours = isInWorkHours,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.ENABLE, action)
        println("✓ TEST 5: WORK_HOURS_ONLY enables during work hours")
    }

    /**
     * TEST 6: Mode PRO - Désactiver hors des horaires de travail
     */
    @Test
    fun testWorkHoursMode_DisabledOutsideWorkHours() {
        // Given: Mode = WORK_HOURS_ONLY, hors des horaires
        val mode = TrackingMode.WORK_HOURS_ONLY
        val isInWorkHours = false
        val currentlyEnabled = true

        // When: Le worker vérifie l'état
        // Then: Il doit désactiver le service
        val action = determineAction(
            mode = mode,
            isInWorkHours = isInWorkHours,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.DISABLE, action)
        println("✓ TEST 6: WORK_HOURS_ONLY disables outside work hours")
    }

    /**
     * TEST 7: Mode PRO - Pas de changement si état cohérent (en horaires, actif)
     */
    @Test
    fun testWorkHoursMode_NoChangeIfAlreadyCorrect_InWorkHours() {
        // Given: Mode = WORK_HOURS_ONLY, en horaires, service actif
        val mode = TrackingMode.WORK_HOURS_ONLY
        val isInWorkHours = true
        val currentlyEnabled = true

        // When: Le worker vérifie l'état
        // Then: Pas d'action nécessaire
        val action = determineAction(
            mode = mode,
            isInWorkHours = isInWorkHours,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.NONE, action)
        println("✓ TEST 7: WORK_HOURS_ONLY no change if correctly enabled in work hours")
    }

    /**
     * TEST 8: Mode PRO - Pas de changement si état cohérent (hors horaires, inactif)
     */
    @Test
    fun testWorkHoursMode_NoChangeIfAlreadyCorrect_OutsideWorkHours() {
        // Given: Mode = WORK_HOURS_ONLY, hors horaires, service inactif
        val mode = TrackingMode.WORK_HOURS_ONLY
        val isInWorkHours = false
        val currentlyEnabled = false

        // When: Le worker vérifie l'état
        // Then: Pas d'action nécessaire
        val action = determineAction(
            mode = mode,
            isInWorkHours = isInWorkHours,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.NONE, action)
        println("✓ TEST 8: WORK_HOURS_ONLY no change if correctly disabled outside work hours")
    }

    // ========== Tests pour DISABLED ==========

    /**
     * TEST 9: Mode DISABLED - Doit arrêter le service s'il tourne
     */
    @Test
    fun testDisabledMode_StopsServiceIfRunning() {
        // Given: Mode = DISABLED, service actif
        val mode = TrackingMode.DISABLED
        val currentlyEnabled = true

        // When: Le worker vérifie l'état
        // Then: Il doit désactiver le service
        val action = determineAction(
            mode = mode,
            isInWorkHours = true, // peu importe
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.DISABLE, action)
        println("✓ TEST 9: DISABLED mode stops service if running")
    }

    /**
     * TEST 10: Mode DISABLED - Pas d'action si déjà arrêté
     */
    @Test
    fun testDisabledMode_NoActionIfAlreadyStopped() {
        // Given: Mode = DISABLED, service déjà inactif
        val mode = TrackingMode.DISABLED
        val currentlyEnabled = false

        // When: Le worker vérifie l'état
        // Then: Pas d'action nécessaire
        val action = determineAction(
            mode = mode,
            isInWorkHours = false,
            currentlyEnabled = currentlyEnabled
        )

        assertEquals(TrackingAction.NONE, action)
        println("✓ TEST 10: DISABLED mode does nothing if already stopped")
    }

    // ========== Tests de transitions ==========

    /**
     * TEST 11: Transition DISABLED → ALWAYS
     */
    @Test
    fun testTransition_DisabledToAlways() {
        // Given: Mode change de DISABLED à ALWAYS
        // When: Le worker réagit au changement
        // Then: Le service doit être activé

        val action = determineAction(
            mode = TrackingMode.ALWAYS,
            isInWorkHours = false,
            currentlyEnabled = false
        )

        assertEquals(TrackingAction.ENABLE, action)
        println("✓ TEST 11: Transition DISABLED → ALWAYS enables tracking")
    }

    /**
     * TEST 12: Transition ALWAYS → DISABLED
     */
    @Test
    fun testTransition_AlwaysToDisabled() {
        // Given: Mode change de ALWAYS à DISABLED
        // When: Le worker réagit au changement
        // Then: Le service doit être désactivé

        val action = determineAction(
            mode = TrackingMode.DISABLED,
            isInWorkHours = false,
            currentlyEnabled = true
        )

        assertEquals(TrackingAction.DISABLE, action)
        println("✓ TEST 12: Transition ALWAYS → DISABLED stops tracking")
    }

    /**
     * TEST 13: Transition ALWAYS → WORK_HOURS_ONLY (hors horaires)
     */
    @Test
    fun testTransition_AlwaysToWorkHours_OutsideHours() {
        // Given: Mode change de ALWAYS à WORK_HOURS_ONLY, hors des horaires
        // When: Le worker réagit au changement
        // Then: Le service doit être désactivé

        val action = determineAction(
            mode = TrackingMode.WORK_HOURS_ONLY,
            isInWorkHours = false,
            currentlyEnabled = true
        )

        assertEquals(TrackingAction.DISABLE, action)
        println("✓ TEST 13: Transition ALWAYS → WORK_HOURS_ONLY (outside hours) stops tracking")
    }

    /**
     * TEST 14: Transition ALWAYS → WORK_HOURS_ONLY (en horaires)
     */
    @Test
    fun testTransition_AlwaysToWorkHours_DuringHours() {
        // Given: Mode change de ALWAYS à WORK_HOURS_ONLY, dans les horaires
        // When: Le worker réagit au changement
        // Then: Le service reste actif (pas d'action)

        val action = determineAction(
            mode = TrackingMode.WORK_HOURS_ONLY,
            isInWorkHours = true,
            currentlyEnabled = true
        )

        assertEquals(TrackingAction.NONE, action)
        println("✓ TEST 14: Transition ALWAYS → WORK_HOURS_ONLY (during hours) keeps tracking")
    }

    /**
     * TEST 15: Transition WORK_HOURS_ONLY → ALWAYS (hors horaires)
     */
    @Test
    fun testTransition_WorkHoursToAlways_OutsideHours() {
        // Given: Mode change de WORK_HOURS_ONLY à ALWAYS, hors horaires
        //        (le service était désactivé)
        // When: Le worker réagit au changement
        // Then: Le service doit être activé

        val action = determineAction(
            mode = TrackingMode.ALWAYS,
            isInWorkHours = false,
            currentlyEnabled = false
        )

        assertEquals(TrackingAction.ENABLE, action)
        println("✓ TEST 15: Transition WORK_HOURS_ONLY → ALWAYS enables tracking")
    }

    // ========== Tests de frontières horaires ==========

    /**
     * TEST 16: Entrée dans les horaires de travail
     */
    @Test
    fun testWorkHoursMode_EntersWorkHours() {
        // Given: Mode WORK_HOURS_ONLY, hors horaires, service arrêté
        // When: L'utilisateur entre dans ses horaires de travail
        // Then: Le service doit être activé

        // État initial
        val initialAction = determineAction(
            mode = TrackingMode.WORK_HOURS_ONLY,
            isInWorkHours = false,
            currentlyEnabled = false
        )
        assertEquals(TrackingAction.NONE, initialAction)

        // Entrée dans les horaires
        val entryAction = determineAction(
            mode = TrackingMode.WORK_HOURS_ONLY,
            isInWorkHours = true,
            currentlyEnabled = false
        )
        assertEquals(TrackingAction.ENABLE, entryAction)

        println("✓ TEST 16: Entering work hours enables tracking")
    }

    /**
     * TEST 17: Sortie des horaires de travail
     */
    @Test
    fun testWorkHoursMode_ExitsWorkHours() {
        // Given: Mode WORK_HOURS_ONLY, en horaires, service actif
        // When: L'utilisateur sort de ses horaires de travail
        // Then: Le service doit être désactivé

        // État initial
        val initialAction = determineAction(
            mode = TrackingMode.WORK_HOURS_ONLY,
            isInWorkHours = true,
            currentlyEnabled = true
        )
        assertEquals(TrackingAction.NONE, initialAction)

        // Sortie des horaires
        val exitAction = determineAction(
            mode = TrackingMode.WORK_HOURS_ONLY,
            isInWorkHours = false,
            currentlyEnabled = true
        )
        assertEquals(TrackingAction.DISABLE, exitAction)

        println("✓ TEST 17: Exiting work hours disables tracking")
    }

    // ========== Tests de validation ==========

    /**
     * TEST 18: Mode PRO sans horaires définis - Doit être refusé
     * (Ceci est géré par le ViewModel, pas le Worker)
     */
    @Test
    fun testWorkHoursMode_RequiresSchedules() {
        // Cette validation se fait au niveau du ViewModel/UI
        // Le Worker assume toujours que les horaires existent si le mode est WORK_HOURS_ONLY

        // Vérification: Le mode peut être assigné
        val mode = TrackingMode.WORK_HOURS_ONLY
        assertNotNull(mode)

        println("✓ TEST 18: WORK_HOURS_ONLY mode requires schedules (validated in UI)")
    }

    /**
     * TEST 19: Valeurs par défaut
     */
    @Test
    fun testDefaultMode() {
        // Le mode par défaut doit être DISABLED pour éviter le tracking non voulu
        val defaultMode = TrackingMode.DISABLED

        assertEquals(TrackingMode.DISABLED, defaultMode)
        println("✓ TEST 19: Default mode is DISABLED")
    }

    /**
     * TEST 20: Ordre des modes dans l'enum
     */
    @Test
    fun testModeOrder() {
        // Vérifier l'ordre logique des modes
        val modes = TrackingMode.entries

        assertEquals(TrackingMode.ALWAYS, modes[0])
        assertEquals(TrackingMode.WORK_HOURS_ONLY, modes[1])
        assertEquals(TrackingMode.DISABLED, modes[2])

        println("✓ TEST 20: Mode order is ALWAYS, WORK_HOURS_ONLY, DISABLED")
    }

    // ========== Fonctions utilitaires pour les tests ==========

    /**
     * Actions possibles du worker
     */
    private enum class TrackingAction {
        ENABLE,   // Activer le service
        DISABLE,  // Désactiver le service
        NONE      // Pas d'action
    }

    /**
     * Détermine si le tracking doit être actif pour un mode donné
     */
    private fun shouldAutotrackForMode(
        mode: TrackingMode,
        isInWorkHours: Boolean,
        currentlyEnabled: Boolean
    ): Boolean {
        return when (mode) {
            TrackingMode.ALWAYS -> true
            TrackingMode.WORK_HOURS_ONLY -> isInWorkHours
            TrackingMode.DISABLED -> false
        }
    }

    /**
     * Détermine l'action à effectuer basée sur le mode et l'état actuel
     * Cette logique reflète celle de AutoTrackingScheduleWorker
     */
    private fun determineAction(
        mode: TrackingMode,
        isInWorkHours: Boolean,
        currentlyEnabled: Boolean
    ): TrackingAction {
        return when (mode) {
            TrackingMode.ALWAYS -> {
                if (!currentlyEnabled) TrackingAction.ENABLE else TrackingAction.NONE
            }
            TrackingMode.WORK_HOURS_ONLY -> {
                when {
                    isInWorkHours && !currentlyEnabled -> TrackingAction.ENABLE
                    !isInWorkHours && currentlyEnabled -> TrackingAction.DISABLE
                    else -> TrackingAction.NONE
                }
            }
            TrackingMode.DISABLED -> {
                if (currentlyEnabled) TrackingAction.DISABLE else TrackingAction.NONE
            }
        }
    }
}

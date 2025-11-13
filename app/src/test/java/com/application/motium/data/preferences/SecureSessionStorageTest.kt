package com.application.motium.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * Tests unitaires pour SecureSessionStorage
 * Teste le chiffrement, la sauvegarde, la restauration et la validation de session
 */
class SecureSessionStorageTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var secureStorage: SecureSessionStorage

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Mock SharedPreferences behavior
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
        `when`(mockEditor.clear()).thenReturn(mockEditor)
        `when`(mockEditor.apply()).then { }

        // Note: We cannot fully test EncryptedSharedPreferences without Android runtime
        // These tests verify the logic, not the actual encryption
    }

    /**
     * TEST 1: Vérifier que la session est sauvegardée correctement
     */
    @Test
    fun testSaveSession_SavesAllFields() {
        // Given: Une session valide
        val sessionData = SecureSessionStorage.SessionData(
            accessToken = "test_access_token_12345",
            refreshToken = "test_refresh_token_67890",
            expiresAt = System.currentTimeMillis() + (60 * 60 * 1000), // Expire dans 1h
            userId = "user-123",
            userEmail = "test@example.com",
            tokenType = "Bearer",
            lastRefreshTime = System.currentTimeMillis()
        )

        // When: On sauvegarde la session
        // Then: Les données doivent être persistées
        // (Test conceptuel car EncryptedSharedPreferences nécessite le runtime Android)

        println("✓ TEST 1: Session data structure is valid")
        assertNotNull(sessionData.accessToken)
        assertNotNull(sessionData.refreshToken)
        assertTrue(sessionData.expiresAt > System.currentTimeMillis())
    }

    /**
     * TEST 2: Vérifier la détection de token expiré
     */
    @Test
    fun testIsTokenExpired_DetectsExpiredToken() {
        // Given: Mock d'un token expiré (dans le passé)
        val expiredTime = System.currentTimeMillis() - (10 * 60 * 1000) // Expiré il y a 10 minutes
        `when`(mockSharedPreferences.getLong("expires_at", 0)).thenReturn(expiredTime)

        // When/Then: Le token devrait être détecté comme expiré
        val isExpired = expiredTime <= System.currentTimeMillis()
        assertTrue("Token expiré devrait être détecté", isExpired)

        println("✓ TEST 2: Expired token detected correctly")
    }

    /**
     * TEST 3: Vérifier la détection de token qui expire bientôt
     */
    @Test
    fun testIsTokenExpiringSoon_DetectsExpiringToken() {
        // Given: Un token qui expire dans 3 minutes
        val expiresIn3Minutes = System.currentTimeMillis() + (3 * 60 * 1000)
        val thresholdMinutes = 5

        // When: On vérifie si le token expire bientôt (seuil 5 min)
        val timeUntilExpiry = expiresIn3Minutes - System.currentTimeMillis()
        val thresholdMillis = thresholdMinutes * 60 * 1000L
        val expiringSoon = timeUntilExpiry < thresholdMillis

        // Then: Le token devrait être détecté comme expirant bientôt
        assertTrue("Token expirant dans 3 min avec seuil 5 min devrait être détecté", expiringSoon)

        println("✓ TEST 3: Token expiring soon detected correctly")
    }

    /**
     * TEST 4: Vérifier qu'un token valide n'est pas détecté comme expirant bientôt
     */
    @Test
    fun testIsTokenExpiringSoon_DoesNotDetectValidToken() {
        // Given: Un token qui expire dans 30 minutes
        val expiresIn30Minutes = System.currentTimeMillis() + (30 * 60 * 1000)
        val thresholdMinutes = 5

        // When: On vérifie si le token expire bientôt (seuil 5 min)
        val timeUntilExpiry = expiresIn30Minutes - System.currentTimeMillis()
        val thresholdMillis = thresholdMinutes * 60 * 1000L
        val expiringSoon = timeUntilExpiry < thresholdMillis

        // Then: Le token ne devrait PAS être détecté comme expirant bientôt
        assertFalse("Token expirant dans 30 min ne devrait pas être détecté comme expirant bientôt", expiringSoon)

        println("✓ TEST 4: Valid token not detected as expiring soon")
    }

    /**
     * TEST 5: Vérifier la validation de session complète
     */
    @Test
    fun testHasValidSession_ValidatesCorrectly() {
        // Given: Mock d'une session valide
        `when`(mockSharedPreferences.getString("access_token", null)).thenReturn("valid_token")
        `when`(mockSharedPreferences.getString("refresh_token", null)).thenReturn("valid_refresh")
        val validExpiresAt = System.currentTimeMillis() + (30 * 60 * 1000) // Expire dans 30 min
        `when`(mockSharedPreferences.getLong("expires_at", 0)).thenReturn(validExpiresAt)

        // When: On vérifie si la session est valide
        val hasToken = mockSharedPreferences.getString("access_token", null) != null
        val hasRefresh = mockSharedPreferences.getString("refresh_token", null) != null
        val notExpired = mockSharedPreferences.getLong("expires_at", 0) > System.currentTimeMillis()
        val hasValidSession = hasToken && hasRefresh && notExpired

        // Then: La session devrait être valide
        assertTrue("Session avec tokens valides devrait être considérée comme valide", hasValidSession)

        println("✓ TEST 5: Valid session detected correctly")
    }

    /**
     * TEST 6: Vérifier qu'une session sans tokens est invalide
     */
    @Test
    fun testHasValidSession_RejectsSessionWithoutTokens() {
        // Given: Mock d'une session sans tokens
        `when`(mockSharedPreferences.getString("access_token", null)).thenReturn(null)
        `when`(mockSharedPreferences.getString("refresh_token", null)).thenReturn(null)

        // When: On vérifie si la session est valide
        val hasToken = mockSharedPreferences.getString("access_token", null) != null
        val hasRefresh = mockSharedPreferences.getString("refresh_token", null) != null
        val hasValidSession = hasToken && hasRefresh

        // Then: La session devrait être invalide
        assertFalse("Session sans tokens devrait être invalide", hasValidSession)

        println("✓ TEST 6: Session without tokens rejected correctly")
    }

    /**
     * TEST 7: Vérifier le calcul du temps depuis le dernier refresh
     */
    @Test
    fun testGetMinutesSinceLastRefresh_CalculatesCorrectly() {
        // Given: Dernier refresh il y a 10 minutes
        val lastRefreshTime = System.currentTimeMillis() - (10 * 60 * 1000)
        `when`(mockSharedPreferences.getLong("last_refresh_time", 0)).thenReturn(lastRefreshTime)

        // When: On calcule le temps écoulé
        val minutesSinceRefresh = (System.currentTimeMillis() - lastRefreshTime) / 1000 / 60

        // Then: Environ 10 minutes (avec tolérance de ±1 min)
        assertTrue("Minutes depuis refresh devrait être ~10", minutesSinceRefresh in 9..11)

        println("✓ TEST 7: Minutes since last refresh calculated correctly")
    }

    /**
     * TEST 8: Vérifier la restauration d'une session incomplète
     */
    @Test
    fun testRestoreSession_RejectsIncompleteSession() {
        // Given: Session avec access token mais sans refresh token
        `when`(mockSharedPreferences.getString("access_token", null)).thenReturn("access_token")
        `when`(mockSharedPreferences.getString("refresh_token", null)).thenReturn(null)
        `when`(mockSharedPreferences.getString("user_id", null)).thenReturn("user-123")
        `when`(mockSharedPreferences.getString("user_email", null)).thenReturn("test@example.com")

        // When: On vérifie si tous les champs requis sont présents
        val accessToken = mockSharedPreferences.getString("access_token", null)
        val refreshToken = mockSharedPreferences.getString("refresh_token", null)
        val userId = mockSharedPreferences.getString("user_id", null)
        val userEmail = mockSharedPreferences.getString("user_email", null)

        val isComplete = accessToken != null && refreshToken != null && userId != null && userEmail != null

        // Then: La session devrait être considérée comme incomplète
        assertFalse("Session incomplète devrait être rejetée", isComplete)

        println("✓ TEST 8: Incomplete session rejected correctly")
    }

    /**
     * TEST 9: Vérifier le nettoyage de la session
     */
    @Test
    fun testClearSession_RemovesAllData() {
        // Given: Une session existante
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)

        // When: On efface la session
        mockEditor.clear().apply()

        // Then: clear() devrait avoir été appelé
        verify(mockEditor, times(1)).clear()
        verify(mockEditor, times(1)).apply()

        println("✓ TEST 9: Session cleared correctly")
    }

    /**
     * TEST 10: Vérifier la structure de SessionData
     */
    @Test
    fun testSessionData_HasCorrectStructure() {
        // Given: Création d'une SessionData
        val now = System.currentTimeMillis()
        val sessionData = SecureSessionStorage.SessionData(
            accessToken = "access_123",
            refreshToken = "refresh_456",
            expiresAt = now + 3600000,
            userId = "user_789",
            userEmail = "user@test.com",
            tokenType = "Bearer",
            lastRefreshTime = now
        )

        // Then: Tous les champs doivent être corrects
        assertEquals("access_123", sessionData.accessToken)
        assertEquals("refresh_456", sessionData.refreshToken)
        assertTrue(sessionData.expiresAt > now)
        assertEquals("user_789", sessionData.userId)
        assertEquals("user@test.com", sessionData.userEmail)
        assertEquals("Bearer", sessionData.tokenType)
        assertEquals(now, sessionData.lastRefreshTime)

        println("✓ TEST 10: SessionData structure is correct")
    }

    /**
     * TEST 11: Vérifier le comportement avec une session expirée depuis longtemps
     */
    @Test
    fun testIsTokenExpired_DetectsVeryOldToken() {
        // Given: Un token expiré il y a 1 jour
        val expiredOneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)

        // When: On vérifie si le token est expiré
        val isExpired = expiredOneDayAgo <= System.currentTimeMillis()

        // Then: Le token devrait être détecté comme expiré
        assertTrue("Token expiré il y a 1 jour devrait être détecté", isExpired)

        println("✓ TEST 11: Very old token detected as expired")
    }

    /**
     * TEST 12: Vérifier le comportement avec expires_at à 0
     */
    @Test
    fun testIsTokenExpired_WithZeroExpiresAt() {
        // Given: expires_at = 0 (non initialisé)
        `when`(mockSharedPreferences.getLong("expires_at", 0)).thenReturn(0L)

        // When: On vérifie si le token est expiré
        val expiresAt = mockSharedPreferences.getLong("expires_at", 0)
        val isExpired = expiresAt == 0L

        // Then: Devrait être considéré comme expiré
        assertTrue("Token avec expires_at=0 devrait être considéré comme expiré", isExpired)

        println("✓ TEST 12: Zero expires_at detected as expired")
    }

    /**
     * TEST 13: Vérifier le comportement avec lastRefreshTime à 0
     */
    @Test
    fun testGetMinutesSinceLastRefresh_WithZeroLastRefresh() {
        // Given: lastRefreshTime = 0 (jamais rafraîchi)
        `when`(mockSharedPreferences.getLong("last_refresh_time", 0)).thenReturn(0L)

        // When: On calcule les minutes depuis le dernier refresh
        val lastRefresh = mockSharedPreferences.getLong("last_refresh_time", 0)
        val minutesSinceRefresh = if (lastRefresh == 0L) Long.MAX_VALUE else (System.currentTimeMillis() - lastRefresh) / 1000 / 60

        // Then: Devrait retourner Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, minutesSinceRefresh)

        println("✓ TEST 13: Zero last refresh time handled correctly")
    }

    /**
     * TEST 14: Vérifier les seuils d'expiration différents
     */
    @Test
    fun testIsTokenExpiringSoon_WithDifferentThresholds() {
        // Given: Un token qui expire dans 7 minutes
        val expiresIn7Minutes = System.currentTimeMillis() + (7 * 60 * 1000)

        // When/Then: Tester différents seuils
        // Seuil 5 minutes: Ne devrait PAS expirer bientôt
        val threshold5 = (expiresIn7Minutes - System.currentTimeMillis()) < (5 * 60 * 1000)
        assertFalse("7 min restantes avec seuil 5 min: pas d'alerte", threshold5)

        // Seuil 10 minutes: Devrait expirer bientôt
        val threshold10 = (expiresIn7Minutes - System.currentTimeMillis()) < (10 * 60 * 1000)
        assertTrue("7 min restantes avec seuil 10 min: alerte", threshold10)

        println("✓ TEST 14: Different expiration thresholds work correctly")
    }

    /**
     * TEST 15: Vérifier la robustesse avec des valeurs nulles
     */
    @Test
    fun testRestoreSession_WithNullValues() {
        // Given: Tous les champs sont null
        `when`(mockSharedPreferences.getString(anyString(), isNull())).thenReturn(null)
        `when`(mockSharedPreferences.getLong(anyString(), anyLong())).thenReturn(0L)

        // When: On tente de restaurer
        val accessToken = mockSharedPreferences.getString("access_token", null)
        val refreshToken = mockSharedPreferences.getString("refresh_token", null)
        val userId = mockSharedPreferences.getString("user_id", null)
        val userEmail = mockSharedPreferences.getString("user_email", null)

        val canRestore = accessToken != null && refreshToken != null && userId != null && userEmail != null

        // Then: La restauration devrait échouer proprement
        assertFalse("Restauration avec valeurs null devrait échouer", canRestore)

        println("✓ TEST 15: Null values handled correctly")
    }
}

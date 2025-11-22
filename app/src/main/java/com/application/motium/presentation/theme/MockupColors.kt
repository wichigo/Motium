package com.application.motium.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Couleurs du nouveau design Motium (Mockup 2025)
 * Utilisées pour remplacer les anciennes couleurs MotiumPrimary
 */

// Couleur principale verte
val MockupGreen = Color(0xFF10B981)

// Couleurs de texte (Light mode)
val MockupTextBlack = Color(0xFF1F2937)
val MockupTextGray = Color(0xFF6B7280)

// Couleur de fond (Light mode)
val MockupBackground = Color(0xFFF3F4F6)

// Couleurs pour Dark mode
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkTextPrimary = Color.White
val DarkTextSecondary = Color.Gray

// Couleurs neutres
val MockupCardBackground = Color.White
val MockupLightGray = Color(0xFFE5E7EB)
val MockupBorderGray = Color(0xFFD1D5DB)

// Couleurs d'état
val MockupSuccess = MockupGreen
val MockupWarning = Color(0xFFF59E0B)
val MockupError = Color(0xFFEF4444)
val MockupInfo = Color(0xFF3B82F6)

/**
 * Extension pour obtenir les couleurs adaptées au mode (light/dark)
 */
data class MockupColorScheme(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color
)

fun getMockupColors(isDarkMode: Boolean): MockupColorScheme {
    return if (isDarkMode) {
        MockupColorScheme(
            background = DarkBackground,
            surface = DarkSurface,
            primary = MockupGreen,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            border = Color(0xFF374151)
        )
    } else {
        MockupColorScheme(
            background = MockupBackground,
            surface = MockupCardBackground,
            primary = MockupGreen,
            textPrimary = MockupTextBlack,
            textSecondary = MockupTextGray,
            border = MockupBorderGray
        )
    }
}

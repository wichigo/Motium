package com.application.motium.presentation.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.application.motium.utils.ThemeManager

// Nouvelle charte graphique Motium - Couleur par défaut
private val DefaultMotiumPrimary = Color(0xFF16a34a)  // #16a34a
val MotiumPrimary: Color
    @Composable
    get() = LocalMotiumPrimary.current

// Teinte claire dynamique pour les fonds d'icônes (basée sur la couleur primaire)
val MotiumPrimaryTint: Color
    @Composable
    get() = LocalMotiumPrimary.current.copy(alpha = 0.15f)

val MotiumPrimaryLight = Color(0xFF22c55e)
val MotiumPrimaryDark = Color(0xFF15803d)

// CompositionLocal pour la couleur primaire dynamique
val LocalMotiumPrimary = compositionLocalOf { DefaultMotiumPrimary }

@Composable
fun WithCustomColor(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val themeManager = ThemeManager.getInstance(context)
    val primaryColor by themeManager.primaryColor.collectAsState()

    val customTextSelectionColors = TextSelectionColors(
        handleColor = primaryColor,
        backgroundColor = primaryColor.copy(alpha = 0.4f)
    )

    CompositionLocalProvider(
        LocalMotiumPrimary provides primaryColor,
        LocalTextSelectionColors provides customTextSelectionColors,
        content = content
    )
}

// Backgrounds
val BackgroundLight = Color(0xFFf6f7f8)  // #f6f7f8
val BackgroundDark = Color(0xFF101922)   // #101922

// Surface colors
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF1a2332)

// Text colors
val TextLight = Color(0xFF101922)
val TextDark = Color(0xFFf6f7f8)
val TextSecondaryLight = Color(0xFF64748b)
val TextSecondaryDark = Color(0xFF94a3b8)

// Status colors
val ValidatedGreen = Color(0xFF16a34a)
val PendingOrange = Color(0xFFf59e0b)
val ErrorRed = Color(0xFFef4444)

// Trip type colors
val ProfessionalBlue = Color(0xFF3b82f6)
val PersonalPurple = Color(0xFFa855f7)

// Accent colors
val PrimaryAlpha10 = Color(0x1A16a34a)  // primary/10 pour les fonds d'icônes
val PrimaryAlpha30 = Color(0x4D16a34a)  // primary/30 pour les toggles

// Legacy colors (pour compatibilité) - Using composable getters
val MotiumGreen: Color
    @Composable
    get() = MotiumPrimary
val MotiumLightGreen = MotiumPrimaryLight
val MotiumDarkGreen = MotiumPrimaryDark
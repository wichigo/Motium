package com.application.motium.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Default color for Material Theme (not customizable)
private val DefaultGreen = androidx.compose.ui.graphics.Color(0xFF16a34a)

private val DarkColorScheme = darkColorScheme(
    primary = DefaultGreen,
    primaryContainer = MotiumPrimaryDark,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onPrimaryContainer = TextDark,
    secondary = MotiumPrimaryLight,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = BackgroundDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = SurfaceDark,
    outline = TextSecondaryDark,
    error = ErrorRed,
    onError = androidx.compose.ui.graphics.Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = DefaultGreen,
    primaryContainer = MotiumPrimaryLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onPrimaryContainer = TextLight,
    secondary = MotiumPrimaryLight,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = BackgroundLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = BackgroundLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceLight,
    outline = TextSecondaryLight,
    error = ErrorRed,
    onError = androidx.compose.ui.graphics.Color.White
)

@Composable
fun MotiumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color désactivé pour utiliser la charte graphique Motium
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar transparente avec couleur de surface
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // Wrapper WithCustomColor pour propager la couleur primaire dynamique
    WithCustomColor {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
        ) {
            // Fournir LocalTextSelectionColors APRÈS MaterialTheme pour surcharger les valeurs par défaut
            val customTextSelectionColors = TextSelectionColors(
                handleColor = MotiumPrimary,
                backgroundColor = MotiumPrimary.copy(alpha = 0.4f)
            )
            CompositionLocalProvider(
                LocalTextSelectionColors provides customTextSelectionColors,
                content = content
            )
        }
    }
}
package com.application.motium.presentation.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager

@Composable
fun SplashScreen() {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val textColor = if (isDarkMode) TextDark else TextLight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Logo/App name
            Text(
                text = "Motium",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MotiumPrimary,
                fontSize = 48.sp
            )

            // Loading indicator
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MotiumPrimary,
                strokeWidth = 4.dp
            )

            // Loading text
            Text(
                text = "Chargement...",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        }
    }
}

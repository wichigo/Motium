package com.application.motium.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.presentation.theme.SurfaceLight
import com.application.motium.presentation.theme.SurfaceDark
import com.application.motium.presentation.theme.TextSecondaryLight
import com.application.motium.presentation.theme.TextSecondaryDark

data class BottomNavItem(
    val route: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
    val label: String
)

val bottomNavItems = listOf(
    BottomNavItem("home", Icons.Filled.Home, Icons.Outlined.Home, "Accueil"),
    BottomNavItem("calendar", Icons.Filled.CalendarToday, Icons.Outlined.CalendarToday, "Agenda"),
    BottomNavItem("vehicles", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar, "Véhicules"),
    BottomNavItem("export", Icons.Filled.IosShare, Icons.Outlined.IosShare, "Exports"),
    BottomNavItem("settings", Icons.Filled.Settings, Icons.Outlined.Settings, "Réglages")
)

@Composable
fun MotiumBottomNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    hasAccess: Boolean = true, // User has full access (TRIAL, PREMIUM, LIFETIME, LICENSED)
    onPremiumFeatureClick: () -> Unit = {}, // Callback when user without access clicks premium feature
    isDarkMode: Boolean = false // Support du mode sombre
) {
    // Couleurs dynamiques basées sur le mode
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val inactiveColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight
    val disabledColor = if (isDarkMode) Color(0xFF4B5563) else Color(0xFFD1D5DB)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 12.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(surfaceColor)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bottomNavItems.forEach { item ->
                    val isSelected = currentRoute == item.route
                    val isExport = item.route == "export"
                    val isEnabled = if (isExport) hasAccess else true
                    val iconColor = when {
                        !isEnabled -> disabledColor
                        isSelected -> MotiumPrimary
                        else -> inactiveColor.copy(alpha = 0.7f)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                color = if (isSelected) MotiumPrimary.copy(alpha = 0.1f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (isExport && !hasAccess) {
                                    onPremiumFeatureClick()
                                } else {
                                    onNavigate(item.route)
                                }
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.iconFilled else item.iconOutlined,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp),
                            tint = iconColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = iconColor
                        )
                    }
                }
            }
        }
    }
}

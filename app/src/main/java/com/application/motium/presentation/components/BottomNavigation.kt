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
import com.application.motium.presentation.theme.MockupGreen

data class BottomNavItem(
    val route: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
    val label: String
)

val bottomNavItems = listOf(
    BottomNavItem("home", Icons.Filled.Home, Icons.Outlined.Home, "Home"),
    BottomNavItem("calendar", Icons.Filled.CalendarToday, Icons.Outlined.CalendarToday, "Calendar"),
    BottomNavItem("vehicles", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar, "Vehicles"),
    BottomNavItem("export", Icons.Filled.IosShare, Icons.Outlined.IosShare, "Export"),
    BottomNavItem("settings", Icons.Filled.Settings, Icons.Outlined.Settings, "Settings")
)

@Composable
fun MotiumBottomNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    isPremium: Boolean = true, // Par défaut, assume premium pour compatibilité
    onPremiumFeatureClick: () -> Unit = {} // Callback quand un compte free clique sur une feature premium
) {
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
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 12.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bottomNavItems.forEach { item ->
                    val isSelected = currentRoute == item.route
                    val isExport = item.route == "export"
                    val isEnabled = if (isExport) isPremium else true
                    val iconColor = when {
                        !isEnabled -> Color(0xFFD1D5DB) // Gris clair si désactivé
                        isSelected -> MockupGreen
                        else -> Color(0xFF64748b).copy(alpha = 0.5f)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                color = if (isSelected) MockupGreen.copy(alpha = 0.1f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (isExport && !isPremium) {
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
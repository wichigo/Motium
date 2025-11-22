package com.application.motium.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.presentation.theme.MockupGreen

data class EnterpriseNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
)

val enterpriseBottomNavItems = listOf(
    EnterpriseNavItem("home", Icons.Outlined.Home, "Home"),
    EnterpriseNavItem("calendar", Icons.Outlined.CalendarToday, "Agenda"),
    EnterpriseNavItem("export", Icons.Outlined.IosShare, "Export"),
    EnterpriseNavItem("settings", Icons.Outlined.Settings, "Settings")
)

val enterpriseExpandedMenuItems = listOf(
    EnterpriseNavItem("employees_management", Icons.Filled.People, "Employés"),
    EnterpriseNavItem("employee_schedule", Icons.Filled.Schedule, "Planning"),
    EnterpriseNavItem("vehicles", Icons.Outlined.DirectionsCar, "Véhicules"),
    EnterpriseNavItem("employee_export", Icons.Filled.FileDownload, "Export"),
    EnterpriseNavItem("employee_facturation", Icons.Filled.Receipt, "Facturation")
)

@Composable
fun EnterpriseBottomNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    isPremium: Boolean = true,
    onPremiumFeatureClick: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Expanded menu items (visible when expanded)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    enterpriseExpandedMenuItems.forEach { item ->
                        EnterpriseExpandedMenuItem(
                            item = item,
                            isSelected = currentRoute == item.route,
                            onNavigate = {
                                onNavigate(item.route)
                                isExpanded = false
                            }
                        )
                    }
                }
            }

            // Bottom navigation bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Regular navigation items
                    enterpriseBottomNavItems.forEach { item ->
                        val isSelected = currentRoute == item.route
                        val isExport = item.route == "export"
                        val isEnabled = if (isExport) isPremium else true
                        val iconColor = when {
                            !isEnabled -> Color(0xFFD1D5DB)
                            isSelected -> MockupGreen
                            else -> Color(0xFF9CA3AF)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable {
                                    if (isExport && !isPremium) {
                                        onPremiumFeatureClick()
                                    } else {
                                        onNavigate(item.route)
                                        isExpanded = false
                                    }
                                }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(24.dp),
                                tint = iconColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = iconColor
                            )
                        }
                    }

                    // Expandable button (+ icon)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { isExpanded = !isExpanded }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MockupGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.Close else Icons.Filled.Add,
                                contentDescription = "Menu",
                                modifier = Modifier.size(24.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Menu",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = MockupGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnterpriseExpandedMenuItem(
    item: EnterpriseNavItem,
    isSelected: Boolean,
    onNavigate: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(280.dp)
            .height(56.dp)
            .clickable { onNavigate() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MockupGreen.copy(alpha = 0.15f) else Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 6.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) MockupGreen else Color(0xFF9CA3AF)
            )
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) MockupGreen else Color(0xFF374151)
            )
        }
    }
}

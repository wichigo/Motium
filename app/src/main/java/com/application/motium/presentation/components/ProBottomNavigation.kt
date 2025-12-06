package com.application.motium.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.presentation.theme.TextSecondaryLight
import com.application.motium.presentation.theme.TextSecondaryDark

/**
 * Item de navigation pour le menu Pro
 */
data class ProBottomNavItem(
    val route: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
    val label: String
)

/**
 * Menu de base Pro (4 items + bouton central)
 */
val proBaseNavItems = listOf(
    ProBottomNavItem("pro_home", Icons.Filled.Home, Icons.Outlined.Home, "Home"),
    ProBottomNavItem("pro_calendar", Icons.Filled.CalendarToday, Icons.Outlined.CalendarToday, "Agenda"),
    // Le bouton "+" est géré séparément (position centrale)
    ProBottomNavItem("pro_export", Icons.Filled.IosShare, Icons.Outlined.IosShare, "Export"),
    ProBottomNavItem("pro_settings", Icons.Filled.Settings, Icons.Outlined.Settings, "Réglages")
)

/**
 * Items du menu étendu Pro (affiché quand "+" est cliqué)
 */
val proExpandedMenuItems = listOf(
    ProBottomNavItem("pro_linked_accounts", Icons.Filled.People, Icons.Outlined.People, "Comptes liés"),
    ProBottomNavItem("pro_licenses", Icons.Filled.CardMembership, Icons.Outlined.CardMembership, "Licences"),
    ProBottomNavItem("pro_vehicles", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar, "Véhicules"),
    ProBottomNavItem("pro_export_advanced", Icons.Filled.FileDownload, Icons.Outlined.FileDownload, "Export Pro")
)

/**
 * Bottom Navigation pour l'interface Pro avec menu dropup
 */
@Composable
fun ProBottomNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    isPremium: Boolean = true,
    onPremiumFeatureClick: () -> Unit = {},
    isDarkMode: Boolean = false
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Animation de rotation du bouton +
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "rotation"
    )

    // Couleurs - fond blanc/noir pur, pas de teinte
    val backgroundColor = if (isDarkMode) Color(0xFF1F2937) else Color.White
    val inactiveColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight
    val disabledColor = if (isDarkMode) Color(0xFF4B5563) else Color(0xFFD1D5DB)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Menu dropup (visible quand expanded)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = tween(200)
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = tween(200)
            ) + fadeOut(animationSpec = tween(200))
        ) {
            // Container dropup - même style que la navigation principale
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(12.dp),
                        clip = false
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 8.dp)
                ) {
                    // Rangée 1: Comptes liés, Licences
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        proExpandedMenuItems.take(2).forEach { item ->
                            DropupMenuItem(
                                item = item,
                                isSelected = currentRoute == item.route,
                                inactiveColor = inactiveColor,
                                onClick = {
                                    onNavigate(item.route)
                                    isExpanded = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Rangée 2: Véhicules, Export Pro
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        proExpandedMenuItems.drop(2).forEach { item ->
                            DropupMenuItem(
                                item = item,
                                isSelected = currentRoute == item.route,
                                inactiveColor = inactiveColor,
                                onClick = {
                                    onNavigate(item.route)
                                    isExpanded = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Menu de base - identique au style MotiumBottomNavigation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(12.dp),
                    clip = false
                )
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Items avant le bouton + (Home, Calendar)
                proBaseNavItems.take(2).forEach { item ->
                    BaseNavItem(
                        item = item,
                        isSelected = currentRoute == item.route,
                        isPremium = isPremium,
                        inactiveColor = inactiveColor,
                        disabledColor = disabledColor,
                        onNavigate = onNavigate,
                        onPremiumFeatureClick = onPremiumFeatureClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Bouton + central
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MotiumPrimary)
                            .clickable { isExpanded = !isExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = if (isExpanded) "Fermer le menu" else "Ouvrir le menu Pro",
                            modifier = Modifier
                                .size(28.dp)
                                .rotate(rotationAngle),
                            tint = Color.White
                        )
                    }
                }

                // Items après le bouton + (Export, Settings)
                proBaseNavItems.drop(2).forEach { item ->
                    val isExport = item.route == "pro_export"
                    BaseNavItem(
                        item = item,
                        isSelected = currentRoute == item.route,
                        isPremium = isPremium,
                        inactiveColor = inactiveColor,
                        disabledColor = disabledColor,
                        onNavigate = onNavigate,
                        onPremiumFeatureClick = onPremiumFeatureClick,
                        isExportItem = isExport,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Item de navigation de base (menu principal)
 */
@Composable
private fun BaseNavItem(
    item: ProBottomNavItem,
    isSelected: Boolean,
    isPremium: Boolean,
    inactiveColor: Color,
    disabledColor: Color,
    onNavigate: (String) -> Unit,
    onPremiumFeatureClick: () -> Unit,
    isExportItem: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isEnabled = if (isExportItem) isPremium else true
    val iconColor = when {
        !isEnabled -> disabledColor
        isSelected -> MotiumPrimary
        else -> inactiveColor.copy(alpha = 0.7f)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(
                color = if (isSelected) MotiumPrimary.copy(alpha = 0.1f) else Color.Transparent
            )
            .clickable {
                if (isExportItem && !isPremium) {
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

/**
 * Item du menu dropup - style identique aux items de base
 */
@Composable
private fun DropupMenuItem(
    item: ProBottomNavItem,
    isSelected: Boolean,
    inactiveColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor = if (isSelected) MotiumPrimary else inactiveColor.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                color = if (isSelected) MotiumPrimary.copy(alpha = 0.1f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
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
            color = iconColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

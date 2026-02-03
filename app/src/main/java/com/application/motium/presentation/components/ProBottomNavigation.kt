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
    ProBottomNavItem("enterprise_home", Icons.Filled.Home, Icons.Outlined.Home, "Home"),
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
 * Bottom Navigation pour l'interface Pro avec menu expandable à deux rangées
 * - État normal: Une rangée avec Home, Calendar, [+], Export, Settings
 * - État expanded: Deux rangées - Pro items en haut, base items en bas avec + centré
 */
@Composable
fun ProBottomNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    hasAccess: Boolean = true,
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
        // Container unique avec hauteur animée
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(12.dp),
                    clip = false
                )
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Rangée Pro (visible uniquement quand expanded)
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(200)
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(200)
                    ) + fadeOut(animationSpec = tween(200))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .padding(horizontal = 8.dp)
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        proExpandedMenuItems.forEach { item ->
                            BaseNavItem(
                                item = item,
                                isSelected = currentRoute == item.route,
                                hasAccess = true,
                                inactiveColor = inactiveColor,
                                disabledColor = disabledColor,
                                onNavigate = { route ->
                                    onNavigate(route)
                                    isExpanded = false
                                },
                                onPremiumFeatureClick = {},
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Rangée de base (toujours visible) - 5 éléments avec + au centre
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Items avant le bouton + (Home, Calendar)
                    proBaseNavItems.take(2).forEach { item ->
                        BaseNavItem(
                            item = item,
                            isSelected = currentRoute == item.route,
                            hasAccess = hasAccess,
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
                            hasAccess = hasAccess,
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
}

/**
 * Item de navigation de base (menu principal)
 */
@Composable
private fun BaseNavItem(
    item: ProBottomNavItem,
    isSelected: Boolean,
    hasAccess: Boolean,
    inactiveColor: Color,
    disabledColor: Color,
    onNavigate: (String) -> Unit,
    onPremiumFeatureClick: () -> Unit,
    isExportItem: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isEnabled = if (isExportItem) hasAccess else true
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
                if (isExportItem && !hasAccess) {
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


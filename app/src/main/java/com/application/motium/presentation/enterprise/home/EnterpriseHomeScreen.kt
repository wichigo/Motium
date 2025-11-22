package com.application.motium.presentation.enterprise.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.EnterpriseBottomNavigation
import com.application.motium.presentation.theme.MockupGreen
import com.application.motium.utils.ThemeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterpriseHomeScreen(
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToEmployees: () -> Unit = {},
    onNavigateToSchedule: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToEmployeeExport: () -> Unit = {},
    onNavigateToFacturation: () -> Unit = {},
    onNavigateToTripDetails: (String) -> Unit = {},
    onNavigateToAddTrip: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) Color(0xFF101922) else Color(0xFFf6f7f8)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        "Bonjour, ${currentUser?.name ?: "Utilisateur"}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor,
                        fontSize = 20.sp
                    )
                    Text(
                        currentUser?.organizationName ?: "Organisation",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkMode) Color.LightGray else Color.Gray,
                        fontSize = 12.sp
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = backgroundColor
            )
        )

        // Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Bienvenue sur l'interface Entreprise",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MockupGreen,
                            fontSize = 18.sp
                        )
                        Text(
                            "Gérez vos employés, planifiez leurs trajets, et suivez les dépenses de votre organisation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDarkMode) Color.LightGray else Color.Gray
                        )
                    }
                }

                // Stats Cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        title = "Employés",
                        value = "0",
                        icon = "👥",
                        modifier = Modifier.weight(1f),
                        isDarkMode = isDarkMode
                    )
                    StatCard(
                        title = "Trajets",
                        value = "0",
                        icon = "🚗",
                        modifier = Modifier.weight(1f),
                        isDarkMode = isDarkMode
                    )
                }

                Button(
                    onClick = onNavigateToAddTrip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MockupGreen
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Ajouter un trajet",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Bottom Navigation
        EnterpriseBottomNavigation(
            currentRoute = "home",
            onNavigate = { route ->
                when (route) {
                    "calendar" -> onNavigateToCalendar()
                    "export" -> onNavigateToExport()
                    "settings" -> onNavigateToSettings()
                    "employees_management" -> onNavigateToEmployees()
                    "employee_schedule" -> onNavigateToSchedule()
                    "vehicles" -> onNavigateToVehicles()
                    "employee_export" -> onNavigateToEmployeeExport()
                    "employee_facturation" -> onNavigateToFacturation()
                }
            }
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkMode) Color(0xFF1a2332) else Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = icon,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MockupGreen
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkMode) Color.LightGray else Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

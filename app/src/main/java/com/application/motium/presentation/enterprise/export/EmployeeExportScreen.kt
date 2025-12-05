package com.application.motium.presentation.enterprise.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.utils.ThemeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeExportScreen(
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToEmployees: () -> Unit = {},
    onNavigateToSchedule: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToFacturation: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) Color(0xFF101922) else Color(0xFFf6f7f8)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1a2332) else Color.White

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    "Export des Données",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textColor
                )
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
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "Exporter les données de votre organisation",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )
                }

                item {
                    ExportCard(
                        title = "Données des Trajets",
                        description = "Exportez tous les trajets de votre organisation",
                        icon = "📊",
                        isDarkMode = isDarkMode,
                        surfaceColor = surfaceColor
                    )
                }

                item {
                    ExportCard(
                        title = "Dépenses des Employés",
                        description = "Exportez les notes de frais et dépenses associées",
                        icon = "💰",
                        isDarkMode = isDarkMode,
                        surfaceColor = surfaceColor
                    )
                }

                item {
                    ExportCard(
                        title = "Rapports Mensuels",
                        description = "Générez des rapports mensuels détaillés",
                        icon = "📈",
                        isDarkMode = isDarkMode,
                        surfaceColor = surfaceColor
                    )
                }

                item {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Télécharger les données",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Navigation
        EnterpriseBottomNavigation(
            currentRoute = "employee_export",
            onNavigate = { route ->
                when (route) {
                    "home" -> onNavigateToHome()
                    "calendar" -> onNavigateToCalendar()
                    "export" -> onNavigateToExport()
                    "settings" -> onNavigateToSettings()
                    "employees_management" -> onNavigateToEmployees()
                    "employee_schedule" -> onNavigateToSchedule()
                    "vehicles" -> onNavigateToVehicles()
                    "employee_facturation" -> onNavigateToFacturation()
                }
            }
        )
    }
}

@Composable
fun ExportCard(
    title: String,
    description: String,
    icon: String,
    isDarkMode: Boolean,
    surfaceColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = icon,
                fontSize = 32.sp
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (isDarkMode) Color.White else Color.Black
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkMode) Color.LightGray else Color.Gray
                )
            }
        }
    }
}

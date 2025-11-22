package com.application.motium.presentation.enterprise.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeScheduleScreen(
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToEmployees: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToEmployeeExport: () -> Unit = {},
    onNavigateToFacturation: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
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
                Text(
                    "Planning des Employés",
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
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Days of week
                val days = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
                val calendar = Calendar.getInstance()

                item {
                    Text(
                        "Semaine du ${SimpleDateFormat("dd MMMM", Locale.getDefault()).format(calendar.time)}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                items(7) { dayIndex ->
                    ScheduleDayCard(
                        day = days[dayIndex],
                        isDarkMode = isDarkMode,
                        textColor = textColor,
                        employeeCount = (2..5).random()
                    )
                }
            }
        }

        // Bottom Navigation
        EnterpriseBottomNavigation(
            currentRoute = "employee_schedule",
            onNavigate = { route ->
                when (route) {
                    "home" -> onNavigateToHome()
                    "calendar" -> onNavigateToCalendar()
                    "export" -> onNavigateToExport()
                    "settings" -> onNavigateToSettings()
                    "employees_management" -> onNavigateToEmployees()
                    "vehicles" -> onNavigateToVehicles()
                    "employee_export" -> onNavigateToEmployeeExport()
                    "employee_facturation" -> onNavigateToFacturation()
                }
            }
        )
    }
}

@Composable
fun ScheduleDayCard(
    day: String,
    isDarkMode: Boolean,
    textColor: Color,
    employeeCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkMode) Color(0xFF1a2332) else Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = day,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textColor,
                    fontSize = 18.sp
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            "$employeeCount employés",
                            fontSize = 12.sp
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MockupGreen,
                        labelColor = Color.White
                    )
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cliquez pour voir le détail",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkMode) Color.LightGray else Color.Gray
            )
        }
    }
}

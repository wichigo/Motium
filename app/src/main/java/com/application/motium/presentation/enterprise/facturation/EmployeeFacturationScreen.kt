package com.application.motium.presentation.enterprise.facturation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilePresent
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
fun EmployeeFacturationScreen(
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToEmployees: () -> Unit = {},
    onNavigateToSchedule: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToEmployeeExport: () -> Unit = {},
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
                    "Facturation",
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
                        "Factures et Paiements",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )
                }

                item {
                    // Summary Cards
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FacturationSummaryCard(
                            title = "Total À Payer",
                            amount = "€0,00",
                            color = Color(0xFFef4444),
                            icon = "💳",
                            modifier = Modifier.weight(1f),
                            isDarkMode = isDarkMode,
                            surfaceColor = surfaceColor
                        )
                        FacturationSummaryCard(
                            title = "Payé",
                            amount = "€0,00",
                            color = MockupGreen,
                            icon = "✅",
                            modifier = Modifier.weight(1f),
                            isDarkMode = isDarkMode,
                            surfaceColor = surfaceColor
                        )
                    }
                }

                item {
                    Text(
                        "Factures Récentes",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = textColor
                    )
                }

                items(3) { index ->
                    FacturationCard(
                        invoiceNumber = "FAC-2025-${String.format("%03d", index + 1)}",
                        date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Calendar.getInstance().time),
                        amount = "€${String.format("%.2f", (100 + index * 50).toDouble())}",
                        status = if (index == 0) "Payée" else "En attente",
                        statusColor = if (index == 0) MockupGreen else Color(0xFFF59E0B),
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
                            containerColor = MockupGreen
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilePresent,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Générer une facture",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Navigation
        EnterpriseBottomNavigation(
            currentRoute = "employee_facturation",
            onNavigate = { route ->
                when (route) {
                    "home" -> onNavigateToHome()
                    "calendar" -> onNavigateToCalendar()
                    "export" -> onNavigateToExport()
                    "settings" -> onNavigateToSettings()
                    "employees_management" -> onNavigateToEmployees()
                    "employee_schedule" -> onNavigateToSchedule()
                    "vehicles" -> onNavigateToVehicles()
                    "employee_export" -> onNavigateToEmployeeExport()
                }
            }
        )
    }
}

@Composable
fun FacturationSummaryCard(
    title: String,
    amount: String,
    color: Color,
    icon: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean,
    surfaceColor: Color
) {
    Card(
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = icon,
                    fontSize = 20.sp
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkMode) Color.LightGray else Color.Gray,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = amount,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
        }
    }
}

@Composable
fun FacturationCard(
    invoiceNumber: String,
    date: String,
    amount: String,
    status: String,
    statusColor: Color,
    isDarkMode: Boolean,
    surfaceColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = invoiceNumber,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (isDarkMode) Color.White else Color.Black
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkMode) Color.LightGray else Color.Gray
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = amount,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = statusColor
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            status,
                            fontSize = 10.sp
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = statusColor,
                        labelColor = Color.White
                    )
                )
            }
        }
    }
}

package com.application.motium.presentation.enterprise.employees

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.EnterpriseBottomNavigation
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.utils.ThemeManager
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeesManagementScreen(
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSchedule: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToEmployeeExport: () -> Unit = {},
    onNavigateToFacturation: () -> Unit = {},
    onNavigateToEmployeeDetails: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) Color(0xFF101922) else Color(0xFFf6f7f8)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1a2332) else Color.White

    var searchQuery by remember { mutableStateOf("") }

    val employees = listOf(
        Employee("1", "Ethan Carter", "ethan.carter@example.com", "Active"),
        Employee("2", "Sarah Johnson", "sarah.johnson@example.com", "Active"),
        Employee("3", "Mike Williams", "mike.williams@example.com", "Inactive"),
        Employee("4", "Emma Davis", "emma.davis@example.com", "Active"),
        Employee("5", "John Smith", "john.smith@example.com", "Active")
    )

    val filteredEmployees = employees.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.email.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    "Gestion des Employés",
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
                // Search Bar
                item {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        isDarkMode = isDarkMode
                    )
                }

                // Employees List
                item {
                    Text(
                        "Mes employés (${filteredEmployees.size})",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )
                }

                if (filteredEmployees.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Aucun employé trouvé",
                                color = if (isDarkMode) Color.LightGray else Color.Gray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(filteredEmployees.size) { index ->
                        EmployeeCard(
                            employee = filteredEmployees[index],
                            isDarkMode = isDarkMode,
                            surfaceColor = surfaceColor,
                            textColor = textColor,
                            onClick = {
                                onNavigateToEmployeeDetails(filteredEmployees[index].id)
                            }
                        )
                    }
                }

                item {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary
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
                            "Ajouter un employé",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Navigation
        EnterpriseBottomNavigation(
            currentRoute = "employees_management",
            onNavigate = { route ->
                when (route) {
                    "home" -> onNavigateToHome()
                    "calendar" -> onNavigateToCalendar()
                    "export" -> onNavigateToExport()
                    "settings" -> onNavigateToSettings()
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
fun EmployeeCard(
    employee: Employee,
    isDarkMode: Boolean,
    surfaceColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5D4C1)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = employee.name.first().toString(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            // Employee Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = employee.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textColor
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = employee.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDarkMode) Color.LightGray else Color.Gray,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    // Status Badge
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                employee.status,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (employee.status == "Active")
                                MotiumPrimary.copy(alpha = 0.2f)
                            else
                                Color(0xFFEF4444).copy(alpha = 0.2f),
                            labelColor = if (employee.status == "Active")
                                MotiumPrimary
                            else
                                Color(0xFFEF4444)
                        ),
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (employee.status == "Active") MotiumPrimary else Color(0xFFEF4444)
                                    )
                            )
                        }
                    )
                }
            }

            // Navigation Arrow
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Navigate",
                tint = MotiumPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isDarkMode: Boolean
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        placeholder = {
            Text("Chercher un employé...", fontSize = 14.sp)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MotiumPrimary,
            unfocusedContainerColor = MotiumPrimary,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
            focusedLeadingIconColor = Color.White,
            unfocusedLeadingIconColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = Color.White
        )
    )
}

data class Employee(
    val id: String,
    val name: String,
    val email: String,
    val status: String
)

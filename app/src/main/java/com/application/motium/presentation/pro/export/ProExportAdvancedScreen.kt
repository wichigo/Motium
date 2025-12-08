package com.application.motium.presentation.pro.export

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.*
import com.application.motium.presentation.components.ProBottomNavigation
import com.application.motium.utils.ThemeManager
import kotlinx.datetime.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Pro Export Advanced Screen - Multi-account export
 * Allows Pro users to export data from all linked accounts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProExportAdvancedScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val viewModel = remember { ProExportAdvancedViewModel(context) }

    val uiState by viewModel.uiState.collectAsState()
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // Calendar state
    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerMode by remember { mutableStateOf("start") }
    var selectedField by remember { mutableStateOf<String?>(null) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccessMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Export Pro",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            fontSize = 18.sp,
                            color = textColor
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Retour",
                                tint = textColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = backgroundColor
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = backgroundColor
        ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MotiumPrimary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Period section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Période",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // From/To inputs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (selectedField == "start" && showDatePicker) {
                                            showDatePicker = false
                                            selectedField = null
                                        } else {
                                            datePickerMode = "start"
                                            selectedField = "start"
                                            showDatePicker = true
                                        }
                                    }
                            ) {
                                OutlinedTextField(
                                    value = formatInstant(uiState.startDate),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Du") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledContainerColor = Color.Transparent,
                                        disabledBorderColor = if (selectedField == "start") MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledTextColor = textColor,
                                        disabledLabelColor = if (selectedField == "start") MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = false
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (selectedField == "end" && showDatePicker) {
                                            showDatePicker = false
                                            selectedField = null
                                        } else {
                                            datePickerMode = "end"
                                            selectedField = "end"
                                            showDatePicker = true
                                        }
                                    }
                            ) {
                                OutlinedTextField(
                                    value = formatInstant(uiState.endDate),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Au") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledContainerColor = Color.Transparent,
                                        disabledBorderColor = if (selectedField == "end") MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledTextColor = textColor,
                                        disabledLabelColor = if (selectedField == "end") MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = false
                                )
                            }
                        }

                        // Calendar - Animated
                        AnimatedVisibility(
                            visible = showDatePicker,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // Calendar header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = {
                                            val newMonth = currentMonth.clone() as Calendar
                                            newMonth.add(Calendar.MONTH, -1)
                                            currentMonth = newMonth
                                        }) {
                                            Icon(
                                                Icons.Default.KeyboardArrowLeft,
                                                contentDescription = "Mois précédent",
                                                tint = textSecondaryColor
                                            )
                                        }

                                        Text(
                                            SimpleDateFormat("MMMM yyyy", Locale.FRENCH).format(currentMonth.time),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = textColor
                                        )

                                        IconButton(onClick = {
                                            val newMonth = currentMonth.clone() as Calendar
                                            newMonth.add(Calendar.MONTH, 1)
                                            currentMonth = newMonth
                                        }) {
                                            Icon(
                                                Icons.Default.KeyboardArrowRight,
                                                contentDescription = "Mois suivant",
                                                tint = textSecondaryColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Days of week
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        listOf("D", "L", "M", "M", "J", "V", "S").forEach { day ->
                                            Text(
                                                day,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = textSecondaryColor,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Calendar grid
                                    ProCalendarGrid(
                                        currentMonth = currentMonth,
                                        startDate = uiState.startDate,
                                        endDate = uiState.endDate,
                                        isDarkMode = isDarkMode,
                                        textColor = textColor,
                                        onDateSelected = { selectedDay ->
                                            val calendar = currentMonth.clone() as Calendar
                                            calendar.set(Calendar.DAY_OF_MONTH, selectedDay)
                                            calendar.set(Calendar.HOUR_OF_DAY, 0)
                                            calendar.set(Calendar.MINUTE, 0)
                                            calendar.set(Calendar.SECOND, 0)
                                            calendar.set(Calendar.MILLISECOND, 0)
                                            val selectedInstant = Instant.fromEpochMilliseconds(calendar.timeInMillis)

                                            if (datePickerMode == "start") {
                                                viewModel.setStartDate(selectedInstant)
                                                if (selectedInstant > uiState.endDate) {
                                                    viewModel.setEndDate(selectedInstant)
                                                }
                                                datePickerMode = "end"
                                                selectedField = "end"
                                            } else {
                                                viewModel.setEndDate(selectedInstant)
                                                if (selectedInstant < uiState.startDate) {
                                                    viewModel.setStartDate(selectedInstant)
                                                }
                                                showDatePicker = false
                                                selectedField = null
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Accounts section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Comptes",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = textColor
                            )
                            TextButton(onClick = { viewModel.toggleSelectAll() }) {
                                Text(
                                    if (uiState.allSelected) "Désélectionner" else "Tout sélectionner",
                                    color = MotiumPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (uiState.linkedUsers.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Aucun compte lié actif",
                                        color = textSecondaryColor
                                    )
                                }
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    uiState.linkedUsers.forEachIndexed { index, user ->
                                        UserSelectionRow(
                                            user = user,
                                            isSelected = uiState.selectedUserIds.contains(user.userId),
                                            onToggle = { viewModel.toggleUserSelection(user.userId) },
                                            textColor = textColor,
                                            textSecondaryColor = textSecondaryColor
                                        )
                                        if (index < uiState.linkedUsers.size - 1) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Filters section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Filtres",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // Trip Type filter
                        run {
                            var tripTypeExpanded by remember { mutableStateOf(false) }
                            val tripTypeText = when {
                                uiState.includeProTrips && uiState.includePersoTrips -> "Tous les trajets"
                                uiState.includeProTrips -> "Trajets professionnels"
                                uiState.includePersoTrips -> "Trajets personnels"
                                else -> "Aucun trajet"
                            }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = tripTypeText,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Type de trajets") },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = {
                                        Icon(
                                            if (tripTypeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Route, contentDescription = null, tint = MotiumPrimary)
                                    },
                                    enabled = false,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable { tripTypeExpanded = !tripTypeExpanded }
                                )

                                DropdownMenu(
                                    expanded = tripTypeExpanded,
                                    onDismissRequest = { tripTypeExpanded = false },
                                    modifier = Modifier.background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Tous les trajets", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeProTrips(true)
                                            viewModel.setIncludePersoTrips(true)
                                            tripTypeExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Trajets professionnels", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeProTrips(true)
                                            viewModel.setIncludePersoTrips(false)
                                            tripTypeExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Trajets personnels", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeProTrips(false)
                                            viewModel.setIncludePersoTrips(true)
                                            tripTypeExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        }

                        // Expenses filter
                        run {
                            var expenseExpanded by remember { mutableStateOf(false) }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = if (uiState.includeExpenses) "Avec dépenses" else "Sans dépenses",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Dépenses") },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = {
                                        Icon(
                                            if (expenseExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Receipt, contentDescription = null, tint = MotiumPrimary)
                                    },
                                    enabled = false,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable { expenseExpanded = !expenseExpanded }
                                )

                                DropdownMenu(
                                    expanded = expenseExpanded,
                                    onDismissRequest = { expenseExpanded = false },
                                    modifier = Modifier.background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Avec dépenses", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeExpenses(true)
                                            expenseExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sans dépenses", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeExpenses(false)
                                            expenseExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        }
                    }
                }

                // Export buttons section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Options d'export",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        val canExport = uiState.selectedUserIds.isNotEmpty() && !uiState.isExporting

                        // CSV Export
                        Button(
                            onClick = {
                                viewModel.setExportFormat(ExportFormatOption.CSV)
                                viewModel.exportData()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canExport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MotiumPrimary,
                                disabledContainerColor = MotiumPrimary.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (uiState.isExporting && uiState.exportFormat == ExportFormatOption.CSV) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Exporter en CSV",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        // PDF Export
                        Button(
                            onClick = {
                                viewModel.setExportFormat(ExportFormatOption.PDF)
                                viewModel.exportData()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canExport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB),
                                disabledContainerColor = if (isDarkMode) Color(0xFF374151).copy(alpha = 0.5f) else Color(0xFFE5E7EB).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (uiState.isExporting && uiState.exportFormat == ExportFormatOption.PDF) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = if (isDarkMode) Color.White else Color.Black,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Exporter en PDF",
                                color = if (isDarkMode) Color.White else Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        // Excel Export
                        Button(
                            onClick = {
                                viewModel.setExportFormat(ExportFormatOption.EXCEL)
                                viewModel.exportData()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canExport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB),
                                disabledContainerColor = if (isDarkMode) Color(0xFF374151).copy(alpha = 0.5f) else Color(0xFFE5E7EB).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (uiState.isExporting && uiState.exportFormat == ExportFormatOption.EXCEL) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = if (isDarkMode) Color.White else Color.Black,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Exporter en Excel",
                                color = if (isDarkMode) Color.White else Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(120.dp)) // Space for bottom nav
                }
            }
        }
        }

        // Bottom Navigation
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            ProBottomNavigation(
                currentRoute = "pro_export_advanced",
                onNavigate = { route ->
                    when (route) {
                        "pro_home" -> onNavigateToHome()
                        "pro_calendar" -> onNavigateToCalendar()
                        "pro_export" -> onNavigateToExport()
                        "pro_settings" -> onNavigateToSettings()
                        "pro_linked_accounts" -> onNavigateToLinkedAccounts()
                        "pro_licenses" -> onNavigateToLicenses()
                        "pro_vehicles" -> onNavigateToVehicles()
                        "pro_export_advanced" -> { /* Already here */ }
                    }
                },
                isDarkMode = isDarkMode
            )
        }
    }
}

@Composable
private fun UserSelectionRow(
    user: LinkedUserDto,
    isSelected: Boolean,
    onToggle: () -> Unit,
    textColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MotiumPrimary,
                checkmarkColor = Color.White
            )
        )

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MotiumPrimary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MotiumPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
            Text(
                text = user.userEmail,
                style = MaterialTheme.typography.bodySmall,
                color = textSecondaryColor
            )
        }
    }
}

@Composable
private fun ProCalendarGrid(
    currentMonth: Calendar,
    startDate: Instant,
    endDate: Instant,
    isDarkMode: Boolean,
    textColor: Color,
    onDateSelected: (Int) -> Unit
) {
    val daysInMonth = currentMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfMonth = Calendar.getInstance().apply {
        time = currentMonth.time
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val startDayOfWeek = firstDayOfMonth.get(Calendar.DAY_OF_WEEK) - 1

    val startCal = Calendar.getInstance().apply { timeInMillis = startDate.toEpochMilliseconds() }
    val endCal = Calendar.getInstance().apply { timeInMillis = endDate.toEpochMilliseconds() }

    val selectedStartDay = if (startCal.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH) &&
        startCal.get(Calendar.YEAR) == currentMonth.get(Calendar.YEAR)) {
        startCal.get(Calendar.DAY_OF_MONTH)
    } else -1

    val selectedEndDay = if (endCal.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH) &&
        endCal.get(Calendar.YEAR) == currentMonth.get(Calendar.YEAR)) {
        endCal.get(Calendar.DAY_OF_MONTH)
    } else -1

    Column {
        var dayCounter = 1
        for (week in 0..5) {
            if (dayCounter > daysInMonth) break

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (dayOfWeek in 0..6) {
                    val dayNumber = if (week == 0 && dayOfWeek < startDayOfWeek) {
                        0
                    } else if (dayCounter <= daysInMonth) {
                        dayCounter++
                    } else {
                        0
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayNumber > 0) {
                            val isStartDay = dayNumber == selectedStartDay
                            val isEndDay = dayNumber == selectedEndDay
                            val isInRange = if (selectedStartDay > 0 && selectedEndDay > 0) {
                                dayNumber in selectedStartDay..selectedEndDay
                            } else false

                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isInRange && !isStartDay && !isEndDay) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .background(
                                                if (isDarkMode) MotiumPrimary.copy(alpha = 0.2f)
                                                else MotiumPrimary.copy(alpha = 0.15f)
                                            )
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            when {
                                                isStartDay || isEndDay -> MotiumPrimary
                                                else -> Color.Transparent
                                            },
                                            CircleShape
                                        )
                                        .clickable { onDateSelected(dayNumber) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        dayNumber.toString(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isStartDay || isEndDay) Color.White else textColor,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatInstant(instant: Instant): String {
    val date = Date(instant.toEpochMilliseconds())
    return SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(date)
}

enum class ExportFormatOption(val displayName: String) {
    CSV("CSV"),
    PDF("PDF"),
    EXCEL("Excel")
}

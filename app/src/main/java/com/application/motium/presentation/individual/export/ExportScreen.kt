package com.application.motium.presentation.individual.export

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.application.motium.domain.model.User
import com.application.motium.domain.model.hasFullAccess
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.FullScreenLoading
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.theme.*
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel(),
    // Pro-specific parameters
    isPro: Boolean = false,
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToExportAdvanced: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    // Utiliser authState de authViewModel au lieu de créer une nouvelle instance
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user

    val viewModel: ExportViewModel = viewModel { ExportViewModel(context) }
    val filters by viewModel.filters.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val vehicles by viewModel.vehicles.collectAsState()
    val filteredTrips by viewModel.filteredTrips.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerMode by remember { mutableStateOf("start") } // "start" or "end"
    var selectedField by remember { mutableStateOf<String?>(null) } // Track which field is focused
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // User access state (includes TRIAL, PREMIUM, LIFETIME, LICENSED)
    val hasAccess = currentUser?.hasFullAccess() ?: false

    // Premium dialog state
    var showPremiumDialog by remember { mutableStateOf(false) }

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    LaunchedEffect(showSnackbar) {
        if (showSnackbar) {
            snackbarHostState.showSnackbar(snackbarMessage)
            showSnackbar = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Export",
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
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = textColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = backgroundColor
                    )
                )
            },
            // Bottom navigation is now handled at app-level in MainActivity
            containerColor = backgroundColor
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            // Quick Export section
            item {
                QuickExportSection(
                    viewModel = viewModel,
                    hasAccess = hasAccess,
                    onShowPremiumDialog = { showPremiumDialog = true },
                    onSuccess = { snackbarMessage = "Export réussi!"; showSnackbar = true },
                    onError = { error -> snackbarMessage = error; showSnackbar = true }
                )
            }

            // Period section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Period",
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
                                .clip(RoundedCornerShape(16.dp))
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
                                value = viewModel.formatDate(filters.startDate),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("From") },
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
                                .clip(RoundedCornerShape(16.dp))
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
                                value = viewModel.formatDate(filters.endDate),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("To") },
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
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
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
                                            imageVector = Icons.Default.KeyboardArrowLeft,
                                            contentDescription = "Previous month",
                                            tint = textSecondaryColor
                                        )
                                    }

                                    Text(
                                        SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(currentMonth.time),
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
                                            imageVector = Icons.Default.KeyboardArrowRight,
                                            contentDescription = "Next month",
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
                                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                                        Text(
                                            day,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = textSecondaryColor,
                                            fontSize = 12.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Calendar grid
                                CalendarGrid(
                                    currentMonth = currentMonth,
                                    filters = filters,
                                    isDarkMode = isDarkMode,
                                    textColor = textColor,
                                    onDateSelected = { selectedDay ->
                                        val calendar = currentMonth.clone() as Calendar
                                        calendar.set(Calendar.DAY_OF_MONTH, selectedDay)
                                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                                        calendar.set(Calendar.MINUTE, 0)
                                        calendar.set(Calendar.SECOND, 0)
                                        calendar.set(Calendar.MILLISECOND, 0)
                                        val selectedTimestamp = calendar.timeInMillis

                                        if (datePickerMode == "start") {
                                            viewModel.setStartDate(selectedTimestamp)
                                            // Si la date de début est après la date de fin, ajuster la date de fin
                                            if (selectedTimestamp > filters.endDate) {
                                                viewModel.setEndDate(selectedTimestamp)
                                            }
                                            // Changer automatiquement au mode "end" et garder le calendrier ouvert
                                            datePickerMode = "end"
                                            selectedField = "end"
                                        } else {
                                            viewModel.setEndDate(selectedTimestamp)
                                            // Si la date de fin est avant la date de début, ajuster la date de début
                                            if (selectedTimestamp < filters.startDate) {
                                                viewModel.setStartDate(selectedTimestamp)
                                            }
                                            // Fermer le calendrier après sélection de la date de fin
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

            // Filters section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Filters",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )

                    // Vehicle filter
                    run {
                        var vehicleExpanded by remember { mutableStateOf(false) }
                        val selectedVehicle = vehicles.firstOrNull { it.id == filters.vehicleId }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedVehicle?.name ?: "All Vehicles",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Vehicle") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        if (vehicleExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    disabledLeadingIconColor = MotiumPrimary,
                                    disabledTrailingIconColor = MotiumPrimary
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        vehicleExpanded = !vehicleExpanded
                                        selectedField = if (vehicleExpanded) "vehicle" else null
                                    }
                            )

                            DropdownMenu(
                                expanded = vehicleExpanded,
                                onDismissRequest = {
                                    vehicleExpanded = false
                                    selectedField = null
                                },
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Vehicles", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setVehicleFilter(null)
                                        vehicleExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                                vehicles.forEach { vehicle ->
                                    DropdownMenuItem(
                                        text = { Text(vehicle.name, color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setVehicleFilter(vehicle.id)
                                            vehicleExpanded = false
                                            selectedField = null
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        }
                    }

                    // Trip Type filter
                    run {
                        var tripTypeExpanded by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = filters.tripType ?: "All Types",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Trip Type") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        if (tripTypeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Route,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    disabledLeadingIconColor = MotiumPrimary,
                                    disabledTrailingIconColor = MotiumPrimary
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        tripTypeExpanded = !tripTypeExpanded
                                        selectedField = if (tripTypeExpanded) "tripType" else null
                                    }
                            )

                            DropdownMenu(
                                expanded = tripTypeExpanded,
                                onDismissRequest = {
                                    tripTypeExpanded = false
                                    selectedField = null
                                },
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Types", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setTripTypeFilter(null)
                                        tripTypeExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                                DropdownMenuItem(
                                    text = { Text("Professional", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setTripTypeFilter("Professional")
                                        tripTypeExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                                DropdownMenuItem(
                                    text = { Text("Personal", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setTripTypeFilter("Personal")
                                        tripTypeExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                            }
                        }
                    }

                    // Expense Mode filter
                    run {
                        var expenseModeExpanded by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = when (filters.expenseMode) {
                                    "trips_only" -> "Trips only"
                                    "trips_with_expenses" -> "Trips with expenses"
                                    "expenses_only" -> "Expenses only"
                                    else -> "Trips only"
                                },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Export Content") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        if (expenseModeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    disabledLeadingIconColor = MotiumPrimary,
                                    disabledTrailingIconColor = MotiumPrimary
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        expenseModeExpanded = !expenseModeExpanded
                                        selectedField = if (expenseModeExpanded) "expenseMode" else null
                                    }
                            )

                            DropdownMenu(
                                expanded = expenseModeExpanded,
                                onDismissRequest = {
                                    expenseModeExpanded = false
                                    selectedField = null
                                },
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Trips only", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setExpenseModeFilter("trips_only")
                                        expenseModeExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                                DropdownMenuItem(
                                    text = { Text("Trips with expenses", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setExpenseModeFilter("trips_with_expenses")
                                        expenseModeExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                                DropdownMenuItem(
                                    text = { Text("Expenses only", color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        viewModel.setExpenseModeFilter("expenses_only")
                                        expenseModeExpanded = false
                                        selectedField = null
                                    },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                )
                            }
                        }
                    }

                    // Include Photos toggle (only visible if expenses are included)
                    AnimatedVisibility(
                        visible = filters.expenseMode != "trips_only",
                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isDarkMode) Color(0xFF1F2937) else Color(0xFFF3F4F6)
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Photo,
                                    contentDescription = null,
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        "Inclure les photos",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = textColor
                                    )
                                    Text(
                                        "Ajoute les justificatifs au PDF",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = textSecondaryColor
                                    )
                                }
                            }
                            Switch(
                                checked = filters.includePhotos,
                                onCheckedChange = { viewModel.setIncludePhotos(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MotiumPrimary,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFD1D5DB)
                                )
                            )
                        }
                    }
                }
            }

            // Summary section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Summary",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Trips card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    "Trips",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stats.totalTrips.toString(),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = textColor,
                                    fontSize = 24.sp
                                )
                            }
                        }

                        // Distance card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    "Distance",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stats.getFormattedDistance(),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = textColor,
                                    fontSize = 24.sp
                                )
                            }
                        }

                        // Indemnities card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    "Indemnities",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stats.getFormattedIndemnities(),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = textColor,
                                    fontSize = 24.sp
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
                        "Export Options",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )

                    // Premium lock info for free users
                    if (!hasAccess) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF3CD)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF856404),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    "L'export est réservé aux utilisateurs Premium",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF856404)
                                )
                            }
                        }
                    }

                    // CSV Export
                    Button(
                        onClick = {
                            if (hasAccess) {
                                viewModel.exportToCSV(
                                    onSuccess = { file ->
                                        snackbarMessage = "CSV exported successfully: ${file.name}"
                                        showSnackbar = true
                                    },
                                    onError = { error ->
                                        snackbarMessage = error
                                        showSnackbar = true
                                    }
                                )
                            } else {
                                showPremiumDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasAccess) MotiumPrimary else MotiumPrimary.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (!hasAccess) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Export as CSV",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    // PDF Export
                    Button(
                        onClick = {
                            if (hasAccess) {
                                viewModel.exportToPDF(
                                    onSuccess = { file ->
                                        snackbarMessage = "PDF exported successfully: ${file.name}"
                                        showSnackbar = true
                                    },
                                    onError = { error ->
                                        snackbarMessage = error
                                        showSnackbar = true
                                    }
                                )
                            } else {
                                showPremiumDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (!hasAccess) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isDarkMode) Color.White else Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = if (isDarkMode) Color.White else Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Export as PDF",
                            color = if (isDarkMode) Color.White else Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    // Excel Export
                    Button(
                        onClick = {
                            if (hasAccess) {
                                viewModel.exportToExcel(
                                    onSuccess = { file ->
                                        snackbarMessage = "Excel exported successfully: ${file.name}"
                                        showSnackbar = true
                                    },
                                    onError = { error ->
                                        snackbarMessage = error
                                        showSnackbar = true
                                    }
                                )
                            } else {
                                showPremiumDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (!hasAccess) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isDarkMode) Color.White else Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = if (isDarkMode) Color.White else Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Export as Excel",
                            color = if (isDarkMode) Color.White else Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
            }
        }

        if (isLoading) {
            FullScreenLoading(message = "Chargement des donnees d'export...")
        }
    }

    // Premium dialog
    if (showPremiumDialog) {
        PremiumDialog(
            onDismiss = { showPremiumDialog = false },
            onUpgrade = {
                // Navigate to settings
                onNavigateToSettings()
            },
            featureName = "l'export de données"
        )
    }
}

@Composable
private fun CalendarGrid(
    currentMonth: Calendar,
    filters: ExportFilters,
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

    // Calculer les jours sélectionnés basés sur les filtres
    val startCal = Calendar.getInstance().apply { timeInMillis = filters.startDate }
    val endCal = Calendar.getInstance().apply { timeInMillis = filters.endDate }

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
                                // Background for range
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

                                // Day circle - clickable
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isStartDay || isEndDay -> MotiumPrimary
                                                else -> Color.Transparent
                                            }
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

/**
 * Quick Export Section with period shortcuts
 * Sets the date range and enables all options, then user uses existing export buttons
 */
@Composable
private fun QuickExportSection(
    viewModel: ExportViewModel,
    hasAccess: Boolean,
    onShowPremiumDialog: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val textColor = if (isDarkMode) TextDark else TextLight

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                tint = MotiumPrimary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "Période rapide",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = textColor
            )
        }

        Text(
            "Configure la période puis exporte avec les boutons ci-dessous",
            style = MaterialTheme.typography.bodySmall,
            color = if (isDarkMode) TextSecondaryDark else TextSecondaryLight
        )

        // Quick period chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickExportChip(
                label = "1 jour",
                onClick = {
                    viewModel.applyQuickPeriod(ExportViewModel.QuickExportPeriod.TODAY)
                },
                modifier = Modifier.weight(1f)
            )
            QuickExportChip(
                label = "1 semaine",
                onClick = {
                    viewModel.applyQuickPeriod(ExportViewModel.QuickExportPeriod.THIS_WEEK)
                },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickExportChip(
                label = "1 mois",
                onClick = {
                    viewModel.applyQuickPeriod(ExportViewModel.QuickExportPeriod.THIS_MONTH)
                },
                modifier = Modifier.weight(1f)
            )
            QuickExportChip(
                label = "1 an",
                onClick = {
                    viewModel.applyQuickPeriod(ExportViewModel.QuickExportPeriod.THIS_YEAR)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QuickExportChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isDarkMode) Color(0xFF1E3A5F) else MotiumPrimary.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            label,
            color = if (isDarkMode) Color.White else MotiumPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

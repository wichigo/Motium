package com.application.motium.presentation.individual.export

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.application.motium.domain.model.isPremium
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.theme.*
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
    authViewModel: AuthViewModel = viewModel()
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

    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerMode by remember { mutableStateOf("start") } // "start" or "end"
    var selectedField by remember { mutableStateOf<String?>(null) } // Track which field is focused
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // User and premium state
    val isPremium = currentUser?.isPremium() ?: false

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
        bottomBar = {
            MotiumBottomNavigation(
                currentRoute = "export",
                isPremium = isPremium,
                onNavigate = { route ->
                    when (route) {
                        "home" -> onNavigateToHome()
                        "calendar" -> onNavigateToCalendar()
                        "vehicles" -> onNavigateToVehicles()
                        "settings" -> onNavigateToSettings()
                    }
                },
                onPremiumFeatureClick = {
                    showPremiumDialog = true
                }
            )
        },
        containerColor = backgroundColor
    ) { paddingValues ->
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "From",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selectedField == "start" && showDatePicker) {
                                            // Toggle: fermer si déjà ouvert
                                            showDatePicker = false
                                            selectedField = null
                                        } else {
                                            // Ouvrir pour ce champ
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
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledContainerColor = backgroundColor,
                                        disabledBorderColor = if (selectedField == "start") MotiumGreen else if (isDarkMode) Color(0xFF374151) else Color(0xFFD1D5DB),
                                        disabledTextColor = textColor
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = false
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "To",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selectedField == "end" && showDatePicker) {
                                            // Toggle: fermer si déjà ouvert
                                            showDatePicker = false
                                            selectedField = null
                                        } else {
                                            // Ouvrir pour ce champ
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
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledContainerColor = backgroundColor,
                                        disabledBorderColor = if (selectedField == "end") MotiumGreen else if (isDarkMode) Color(0xFF374151) else Color(0xFFD1D5DB),
                                        disabledTextColor = textColor
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = false
                                )
                            }
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
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    Column {
                        Text(
                            "Vehicle",
                            style = MaterialTheme.typography.bodySmall,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        var vehicleExpanded by remember { mutableStateOf(false) }
                        val selectedVehicle = vehicles.firstOrNull { it.id == filters.vehicleId }

                        ExposedDropdownMenuBox(
                            expanded = vehicleExpanded,
                            onExpandedChange = {
                                vehicleExpanded = !vehicleExpanded
                                selectedField = if (vehicleExpanded) "vehicle" else null
                            }
                        ) {
                            OutlinedTextField(
                                value = selectedVehicle?.name ?: "All Vehicles",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = if (selectedField == "vehicle") MotiumGreen else MotiumPrimary
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = backgroundColor,
                                    focusedContainerColor = backgroundColor,
                                    unfocusedBorderColor = if (selectedField == "vehicle") MotiumGreen else if (isDarkMode) Color(0xFF374151) else Color(0xFFD1D5DB),
                                    focusedBorderColor = MotiumGreen,
                                    unfocusedTextColor = textColor,
                                    focusedTextColor = textColor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = vehicleExpanded,
                                onDismissRequest = {
                                    vehicleExpanded = false
                                    selectedField = null
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Vehicles") },
                                    onClick = {
                                        viewModel.setVehicleFilter(null)
                                        vehicleExpanded = false
                                        selectedField = null
                                    }
                                )
                                vehicles.forEach { vehicle ->
                                    DropdownMenuItem(
                                        text = { Text(vehicle.name) },
                                        onClick = {
                                            viewModel.setVehicleFilter(vehicle.id)
                                            vehicleExpanded = false
                                            selectedField = null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Trip Type filter
                    Column {
                        Text(
                            "Trip Type",
                            style = MaterialTheme.typography.bodySmall,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        var tripTypeExpanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = tripTypeExpanded,
                            onExpandedChange = {
                                tripTypeExpanded = !tripTypeExpanded
                                selectedField = if (tripTypeExpanded) "tripType" else null
                            }
                        ) {
                            OutlinedTextField(
                                value = filters.tripType ?: "All Types",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = if (selectedField == "tripType") MotiumGreen else MotiumPrimary
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = backgroundColor,
                                    focusedContainerColor = backgroundColor,
                                    unfocusedBorderColor = if (selectedField == "tripType") MotiumGreen else if (isDarkMode) Color(0xFF374151) else Color(0xFFD1D5DB),
                                    focusedBorderColor = MotiumGreen,
                                    unfocusedTextColor = textColor,
                                    focusedTextColor = textColor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = tripTypeExpanded,
                                onDismissRequest = {
                                    tripTypeExpanded = false
                                    selectedField = null
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Types") },
                                    onClick = {
                                        viewModel.setTripTypeFilter(null)
                                        tripTypeExpanded = false
                                        selectedField = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Professional") },
                                    onClick = {
                                        viewModel.setTripTypeFilter("Professional")
                                        tripTypeExpanded = false
                                        selectedField = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Personal") },
                                    onClick = {
                                        viewModel.setTripTypeFilter("Personal")
                                        tripTypeExpanded = false
                                        selectedField = null
                                    }
                                )
                            }
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
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = surfaceColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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

                    // CSV Export
                    Button(
                        onClick = {
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
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
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
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
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
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
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

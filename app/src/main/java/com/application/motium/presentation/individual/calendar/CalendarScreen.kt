package com.application.motium.presentation.individual.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.User
import com.application.motium.domain.model.isPremium
import com.application.motium.domain.model.TimeSlot
import com.application.motium.domain.model.TrackingMode
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.calendar.WorkScheduleViewModel
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.presentation.theme.MotiumPrimaryTint
import com.application.motium.presentation.theme.ValidatedGreen
import com.application.motium.presentation.theme.PendingOrange
import com.application.motium.MotiumApplication
import com.application.motium.utils.CalendarUtils
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToTripDetails: (String) -> Unit = {},
    onNavigateToAddExpense: (String) -> Unit = {},
    onNavigateToExpenseDetails: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel(),
    initialTab: Int = 0,  // 0 = Calendar, 1 = Planning
    // Pro-specific parameters
    isPro: Boolean = false,
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToExportAdvanced: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepository = remember { TripRepository.getInstance(context) }
    val expenseRepository = remember { ExpenseRepository.getInstance(context) }
    val themeManager = remember { ThemeManager.getInstance(context) }

    // Utiliser authState de authViewModel au lieu de créer une nouvelle instance
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var expenses by remember { mutableStateOf<List<Expense>>(emptyList()) }

    // Use rememberSaveable to preserve calendar month and selected day across navigation
    var currentMonthTimestamp by rememberSaveable { mutableStateOf(Calendar.getInstance().timeInMillis) }
    val currentCalendar = remember(currentMonthTimestamp) {
        Calendar.getInstance().apply { timeInMillis = currentMonthTimestamp }
    }

    var selectedDayTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedDay = selectedDayTimestamp?.let { ts ->
        Calendar.getInstance().apply { timeInMillis = ts }
    }

    var selectedTab by remember { mutableStateOf(initialTab) } // 0 = Calendar, 1 = Planning

    // User and premium state
    val isPremium = currentUser?.isPremium() ?: false

    // Premium dialog state
    var showPremiumDialog by remember { mutableStateOf(false) }

    // Dynamic colors matching HomeScreen
    val cardColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF1F2937)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF6B7280)

    // Load trips and expenses
    LaunchedEffect(Unit) {
        trips = tripRepository.getAllTrips()
        expenses = expenseRepository.getExpensesForUser()
    }

    // Calculate calendar days with trips and expenses
    val calendarDays = remember(currentCalendar, trips, expenses) {
        generateCalendarDays(currentCalendar, trips, expenses)
    }

    // Calculate monthly stats
    val monthlyStats = remember(currentCalendar, trips) {
        val year = currentCalendar.get(Calendar.YEAR)
        val month = currentCalendar.get(Calendar.MONTH)

        val monthTrips = trips.filter { trip ->
            val tripCal = Calendar.getInstance().apply { timeInMillis = trip.startTime }
            tripCal.get(Calendar.YEAR) == year && tripCal.get(Calendar.MONTH) == month
        }

        val totalDistance = monthTrips.sumOf { it.totalDistance } / 1000.0 // Convert to km
        val totalIndemnities = monthTrips.sumOf { it.reimbursementAmount ?: 0.0 }

        Triple(totalDistance, totalIndemnities, monthTrips.size)
    }

    val today = Calendar.getInstance()

    // Calculate trips for selected day (outside LazyColumn to avoid Composable context issues)
    val selectedDayTrips = remember(selectedDay, trips) {
        selectedDay?.let { day ->
            trips.filter { trip ->
                val tripCal = Calendar.getInstance().apply { timeInMillis = trip.startTime }
                tripCal.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
                        tripCal.get(Calendar.MONTH) == day.get(Calendar.MONTH) &&
                        tripCal.get(Calendar.DAY_OF_MONTH) == day.get(Calendar.DAY_OF_MONTH)
            }
        } ?: emptyList()
    }

    // Bottom navigation is now handled at app-level in MainActivity
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Agenda",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Tab navigation (Calendar / Planning)
            item {
                TabSection(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }

            // Show either Calendar or Planning content
            if (selectedTab == 1) {
                // Planning section
                item {
                    PlanningSection(authViewModel = authViewModel)
                }
            } else {
                // Calendar section
                // Month navigation
                item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        currentMonthTimestamp = (currentCalendar.clone() as Calendar).apply {
                            add(Calendar.MONTH, -1)
                        }.timeInMillis
                    }) {
                        Icon(
                            Icons.Default.KeyboardArrowLeft,
                            contentDescription = "Previous month",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(currentCalendar.time),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    IconButton(onClick = {
                        currentMonthTimestamp = (currentCalendar.clone() as Calendar).apply {
                            add(Calendar.MONTH, 1)
                        }.timeInMillis
                    }) {
                        Icon(
                            Icons.Default.KeyboardArrowRight,
                            contentDescription = "Next month",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Days of week header
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    val daysOfWeek = listOf("S", "M", "T", "W", "T", "F", "S")
                    daysOfWeek.forEach { day ->
                        Text(
                            text = day,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Calendar grid
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(280.dp),
                    userScrollEnabled = false
                ) {
                    items(calendarDays) { day ->
                        val isTodayCheck = today.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR) &&
                                today.get(Calendar.MONTH) == currentCalendar.get(Calendar.MONTH) &&
                                today.get(Calendar.DAY_OF_MONTH) == day.number

                        val isSelectedCheck = selectedDay?.let {
                            it.get(Calendar.YEAR) == day.year &&
                                    it.get(Calendar.MONTH) == day.month &&
                                    it.get(Calendar.DAY_OF_MONTH) == day.number
                        } ?: false

                        CalendarDay(
                            day = day,
                            isToday = isTodayCheck,
                            isSelected = isSelectedCheck,
                            onDayClick = {
                                if (day.number != 0) {
                                    selectedDayTimestamp = Calendar.getInstance().apply {
                                        set(day.year, day.month, day.number)
                                    }.timeInMillis
                                }
                            }
                        )
                    }
                }
            }

            // Selected day summary and trips
            selectedDay?.let { day ->
                // Daily summary header with expense buttons
                item {
                    val dateForExpenses = remember(day) {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(day.time)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.ENGLISH).format(day.time),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        // Expense buttons (linked to day)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // View expenses button
                            IconButton(
                                onClick = { onNavigateToExpenseDetails(dateForExpenses) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Visibility,
                                    contentDescription = "View Expenses",
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Add expense button
                            IconButton(
                                onClick = { onNavigateToAddExpense(dateForExpenses) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Receipt,
                                    contentDescription = "Add Expense",
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Daily stats card - Same style as HomeScreen
                item {
                    val totalDistance = selectedDayTrips.sumOf { it.totalDistance } / 1000.0
                    val totalIndemnities = selectedDayTrips.sumOf { it.reimbursementAmount ?: 0.0 }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "Daily Summary",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                CalendarStatItem(String.format("%.1f", totalDistance), "Kilometers", MotiumPrimary, textColor, subTextColor)
                                CalendarStatItem(String.format("$%.2f", totalIndemnities), "Indemnities", MotiumPrimary, textColor, subTextColor)
                                CalendarStatItem(selectedDayTrips.size.toString(), "Trips", MotiumPrimary, textColor, subTextColor)
                            }
                        }
                    }
                }

                // List of trips for selected day - Same style as HomeScreen
                if (selectedDayTrips.isNotEmpty()) {
                    items(selectedDayTrips) { trip ->
                        CalendarTripCard(
                            trip = trip,
                            onClick = { onNavigateToTripDetails(trip.id) },
                            onToggleValidation = {
                                coroutineScope.launch {
                                    val updated = trip.copy(isValidated = !trip.isValidated)
                                    tripRepository.saveTrip(updated)
                                    trips = tripRepository.getAllTrips()
                                }
                            },
                            cardColor = cardColor,
                            textColor = textColor,
                            subTextColor = subTextColor
                        )
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No trips this day",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = subTextColor
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Month label
            item {
                Text(
                    text = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(currentCalendar.time),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            // Total distance card
            item {
                StatCard(
                    title = "Total distance",
                    value = "${monthlyStats.first.toInt()} km",
                    subtitle = "${monthlyStats.third} trips"
                )
            }

            // Total indemnities card
            item {
                StatCard(
                    title = "Total indemnities",
                    value = "${monthlyStats.second.toInt()} €",
                    subtitle = "${monthlyStats.third} trips"
                )
            }
            } // End of Calendar section
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

// StatItem matching HomeScreen style
@Composable
fun CalendarStatItem(value: String, label: String, highlightColor: Color, textColor: Color, subColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = highlightColor
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = subColor
        )
    }
}

// TripCard matching HomeScreen style exactly
@Composable
fun CalendarTripCard(
    trip: Trip,
    onClick: () -> Unit,
    onToggleValidation: () -> Unit,
    cardColor: Color,
    textColor: Color,
    subTextColor: Color
) {
    val startLocation = trip.locations.firstOrNull()
    val endLocation = trip.locations.lastOrNull()
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val timePattern = if (is24Hour) "HH:mm" else "hh:mm a"
    val startTimeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(trip.startTime))
    val endTimeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(trip.endTime ?: System.currentTimeMillis()))

    // Use cached map-matched coordinates if available, otherwise fallback to raw GPS
    val routeCoordinates = remember(trip.matchedRouteCoordinates, trip.locations) {
        trip.matchedRouteCoordinates?.let { cached ->
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<List<Double>>>(cached)
            } catch (e: Exception) {
                null
            }
        } ?: trip.locations.map { listOf(it.longitude, it.latitude) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .height(83.dp)
        ) {
            // 1. MAP
            Box(
                modifier = Modifier
                    .size(83.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE5E7EB))
            ) {
                if (startLocation != null && endLocation != null) {
                    MiniMap(
                        startLatitude = startLocation.latitude,
                        startLongitude = startLocation.longitude,
                        endLatitude = endLocation.latitude,
                        endLongitude = endLocation.longitude,
                        routeCoordinates = routeCoordinates,
                        modifier = Modifier.fillMaxSize(),
                        isCompact = true
                    )
                } else {
                    Icon(
                        Icons.Default.Map,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center),
                        tint = subTextColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. MIDDLE SECTION: Dots + Addresses
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Colonne des points et ligne de connexion
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(end = 6.dp)
                ) {
                    // Point de départ
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MotiumPrimary, CircleShape)
                    )
                    // Ligne pointillée
                    Canvas(
                        modifier = Modifier
                            .width(2.dp)
                            .weight(1f)
                    ) {
                        drawLine(
                            color = Color.LightGray,
                            start = Offset(size.width / 2, 0f),
                            end = Offset(size.width / 2, size.height),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                    // Point d'arrivée
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MotiumPrimary, CircleShape)
                    )
                }

                // Colonne des adresses
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Départ
                    Column {
                        Text(
                            text = trip.startAddress ?: "Unknown Start",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor,
                            maxLines = 1
                        )
                        Text(
                            text = startTimeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = subTextColor
                        )
                    }

                    // Arrivée
                    Column {
                        Text(
                            text = trip.endAddress ?: "Unknown End",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor,
                            maxLines = 1
                        )
                        Text(
                            text = endTimeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = subTextColor
                        )
                    }
                }
            }

            // 3. RIGHT SECTION: Badge + Price + Switch (colonne alignée au centre)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 14.dp)
            ) {
                // Badge Pro/Perso (en haut)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (trip.tripType) {
                        "PROFESSIONAL" -> MotiumPrimaryTint
                        "PERSONAL" -> Color(0xFF3B82F6).copy(alpha = 0.15f)
                        else -> Color.Gray.copy(alpha = 0.15f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = when (trip.tripType) {
                                "PROFESSIONAL" -> Icons.Default.Work
                                "PERSONAL" -> Icons.Default.Person
                                else -> Icons.Default.HelpOutline
                            },
                            contentDescription = null,
                            modifier = Modifier.size(9.dp),
                            tint = when (trip.tripType) {
                                "PROFESSIONAL" -> MotiumPrimary
                                "PERSONAL" -> Color(0xFF3B82F6)
                                else -> Color.Gray
                            }
                        )
                        Text(
                            when (trip.tripType) {
                                "PROFESSIONAL" -> "Pro"
                                "PERSONAL" -> "Perso"
                                else -> "?"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                            color = when (trip.tripType) {
                                "PROFESSIONAL" -> MotiumPrimary
                                "PERSONAL" -> Color(0xFF3B82F6)
                                else -> Color.Gray
                            }
                        )
                    }
                }

                // Prix + Switch groupés en bas
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Indemnités
                    Text(
                        text = String.format("%.2f €", trip.reimbursementAmount ?: 0.0),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MotiumPrimary
                    )

                    // Toggle Switch
                    Switch(
                        checked = trip.isValidated,
                        onCheckedChange = { onToggleValidation() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MotiumPrimary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE5E7EB),
                            uncheckedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .graphicsLayer(scaleX = 0.65f, scaleY = 0.65f)
                            .offset(y = 22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun RadioButtonOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MotiumPrimary
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// Color for expense indicator
private val ExpenseBlue = Color(0xFF3B82F6) // Tailwind blue-500

@Composable
fun CalendarDay(
    day: CalendarDayData,
    isToday: Boolean,
    isSelected: Boolean = false,
    onDayClick: () -> Unit
) {
    // Empty cell for days before the first day of the month
    if (day.number == 0) {
        Box(modifier = Modifier.size(40.dp))
        return
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable { onDayClick() }
            .background(
                color = when {
                    isToday -> MotiumPrimary
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    day.hasTrips -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    day.hasExpenses -> ExpenseBlue.copy(alpha = 0.1f) // Light blue background for expense-only days
                    else -> Color.Transparent
                },
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = day.number.toString(),
                color = when {
                    isToday -> Color.White
                    day.hasTrips -> MotiumPrimary
                    day.hasExpenses -> ExpenseBlue
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (day.hasTrips || day.hasExpenses || isToday) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )

            // Indicators row - show dots for trips and/or expenses
            if (day.hasTrips || day.hasExpenses) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Trip indicator (green/orange dot)
                    if (day.hasTrips) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = if (day.allTripsValidated) ValidatedGreen else PendingOrange,
                                    shape = CircleShape
                                )
                        )
                    }
                    // Expense indicator (blue dot)
                    if (day.hasExpenses) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(
                                    color = ExpenseBlue,
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}


@Composable
fun WeekView(onAutoTrackingSettingsClick: () -> Unit) {
    val daysOfWeek = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Auto-tracking configuration button
        Button(
            onClick = onAutoTrackingSettingsClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MotiumPrimary
            )
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Configuration Auto-tracking")
        }

        Text(
            "Horaires de travail",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Configurez vos horaires de travail pour activer l'auto-tracking automatiquement",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(8.dp))

        // Work schedule for each day
        daysOfWeek.forEach { day ->
            WorkScheduleCard(dayName = day)
        }
    }
}

@Composable
fun WorkScheduleCard(dayName: String) {
    var isEnabled by remember { mutableStateOf(dayName != "Samedi" && dayName != "Dimanche") }
    var startTime by remember { mutableStateOf("09:00") }
    var endTime by remember { mutableStateOf("17:00") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isEnabled) {
                    Text(
                        "$startTime - $endTime",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        "Désactivé",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { isEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MotiumPrimary
                )
            )
        }
    }
}

@Composable
fun TabSection(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            // Calendar tab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(0) },
                shape = RoundedCornerShape(6.dp),
                color = if (selectedTab == 0)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    Color.Transparent
            ) {
                Text(
                    text = "Agenda",
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (selectedTab == 0)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Planning tab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(1) },
                shape = RoundedCornerShape(6.dp),
                color = if (selectedTab == 1)
                    Color.White
                else
                    Color.Transparent
            ) {
                Text(
                    text = "Planning",
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (selectedTab == 1)
                        MotiumPrimary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun PlanningSection(
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val viewModel: WorkScheduleViewModel = remember { WorkScheduleViewModel(context) }

    // Get current user
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val userId = currentUser?.id

    // Load data from Supabase when user is available
    LaunchedEffect(userId) {
        userId?.let {
            viewModel.loadWorkSchedules(it)
            viewModel.loadAutoTrackingSettings(it)
        }
    }

    // Observe state from ViewModel
    val schedules by viewModel.schedules.collectAsState()
    val trackingMode by viewModel.trackingMode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Initialize schedules for each day
    val daysOfWeek = listOf(
        "Monday" to Calendar.MONDAY,
        "Tuesday" to Calendar.TUESDAY,
        "Wednesday" to Calendar.WEDNESDAY,
        "Thursday" to Calendar.THURSDAY,
        "Friday" to Calendar.FRIDAY,
        "Saturday" to Calendar.SATURDAY,
        "Sunday" to Calendar.SUNDAY
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Error message
        error?.let { errorMsg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorMsg,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Professional Hours section
        // Note: Auto-tracking mode is now controlled from the Home screen dropdown
        Text(
            text = "Professional Hours",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            )
        )

        // Day schedule cards
        daysOfWeek.forEach { (dayName, dayOfWeek) ->
            // Convert Android day to ISO day for Supabase
            val isoDay = CalendarUtils.androidDayToIsoDay(dayOfWeek)
            val daySchedules = schedules[isoDay] ?: emptyList()

            DayScheduleCard(
                dayName = dayName,
                timeSlots = daySchedules,
                onAddTimeSlot = {
                    userId?.let {
                        viewModel.addWorkSchedule(it, isoDay, TimeSlot(
                            id = java.util.UUID.randomUUID().toString(),
                            startHour = 9,
                            startMinute = 0,
                            endHour = 17,
                            endMinute = 0
                        ))
                    }
                },
                onDeleteTimeSlot = { slotId ->
                    userId?.let { viewModel.deleteWorkSchedule(slotId, it) }
                },
                onTimeSlotChanged = { updatedSlot ->
                    userId?.let { viewModel.updateWorkSchedule(it, isoDay, updatedSlot) }
                },
                isEnabled = userId != null
            )
        }
    }
}

@Composable
fun DayScheduleCard(
    dayName: String,
    timeSlots: List<TimeSlot>,
    onAddTimeSlot: () -> Unit,
    onDeleteTimeSlot: (String) -> Unit,
    onTimeSlotChanged: (TimeSlot) -> Unit,
    isEnabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )

                if (timeSlots.isEmpty()) {
                    Text(
                        text = "Not worked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    TextButton(
                        onClick = onAddTimeSlot,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MotiumPrimary
                        )
                    ) {
                        Text(
                            text = "+ Add",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            // Add button when no time slots
            if (timeSlots.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onAddTimeSlot,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MotiumPrimary
                    ),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "+ Add",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                // Time slots
                timeSlots.forEach { timeSlot ->
                    TimeSlotRow(
                        timeSlot = timeSlot,
                        onDelete = { onDeleteTimeSlot(timeSlot.id) },
                        onTimeChanged = onTimeSlotChanged
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun TimeSlotRow(
    timeSlot: TimeSlot,
    onDelete: () -> Unit,
    onTimeChanged: (TimeSlot) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)

    // Helper function to format time based on user's locale preference
    fun formatTime(hour: Int, minute: Int): String {
        return if (is24Hour) {
            String.format("%02d:%02d", hour, minute)
        } else {
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val amPm = if (hour < 12) "AM" else "PM"
            String.format("%02d:%02d %s", displayHour, minute, amPm)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clickable { showEditDialog = true }
        ) {
            // Start time display
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = formatTime(timeSlot.startHour, timeSlot.startMinute),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(text = "-", style = MaterialTheme.typography.bodyMedium)

            // End time display
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = formatTime(timeSlot.endHour, timeSlot.endMinute),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit time slot",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete time slot",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }

    if (showEditDialog) {
        TimeSlotEditDialog(
            timeSlot = timeSlot,
            onDismiss = { showEditDialog = false },
            onConfirm = { updatedSlot ->
                onTimeChanged(updatedSlot)
                showEditDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSlotEditDialog(
    timeSlot: TimeSlot,
    onDismiss: () -> Unit,
    onConfirm: (TimeSlot) -> Unit
) {
    var startHour by remember { mutableStateOf(timeSlot.startHour) }
    var startMinute by remember { mutableStateOf(timeSlot.startMinute) }
    var endHour by remember { mutableStateOf(timeSlot.endHour) }
    var endMinute by remember { mutableStateOf(timeSlot.endMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = "Edit Time Slot",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Start time
                Text(
                    text = "Start Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour
                    OutlinedTextField(
                        value = String.format("%02d", startHour),
                        onValueChange = {
                            val hour = it.toIntOrNull()
                            if (hour != null && hour in 0..23) {
                                startHour = hour
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Hour") },
                        singleLine = true
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    // Minute
                    OutlinedTextField(
                        value = String.format("%02d", startMinute),
                        onValueChange = {
                            val minute = it.toIntOrNull()
                            if (minute != null && minute in 0..59) {
                                startMinute = minute
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Minute") },
                        singleLine = true
                    )
                }

                // End time
                Text(
                    text = "End Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour
                    OutlinedTextField(
                        value = String.format("%02d", endHour),
                        onValueChange = {
                            val hour = it.toIntOrNull()
                            if (hour != null && hour in 0..23) {
                                endHour = hour
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Hour") },
                        singleLine = true
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    // Minute
                    OutlinedTextField(
                        value = String.format("%02d", endMinute),
                        onValueChange = {
                            val minute = it.toIntOrNull()
                            if (minute != null && minute in 0..59) {
                                endMinute = minute
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Minute") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updatedSlot = timeSlot.copy(
                        startHour = startHour,
                        startMinute = startMinute,
                        endHour = endHour,
                        endMinute = endMinute
                    )
                    onConfirm(updatedSlot)
                }
            ) {
                Text("Save", color = MotiumPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Data classes
data class CalendarDayData(
    val number: Int,
    val hasTrips: Boolean = false,
    val allTripsValidated: Boolean = false,
    val hasExpenses: Boolean = false,
    val year: Int = 0,
    val month: Int = 0
)

data class DaySchedule(
    val dayOfWeek: Int, // Calendar.MONDAY, etc.
    val timeSlots: List<TimeSlot> = emptyList()
)

fun generateCalendarDays(calendar: Calendar, trips: List<Trip>, expenses: List<Expense> = emptyList()): List<CalendarDayData> {
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)

    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

    // Get first day of month
    val firstDayOfMonth = Calendar.getInstance().apply {
        set(year, month, 1)
    }

    // Get day of week for first day (Sunday = 1, Monday = 2, etc.)
    val firstDayOfWeek = firstDayOfMonth.get(Calendar.DAY_OF_WEEK)

    // Calculate number of empty cells needed at the start (Sunday-based week)
    val emptyCellsCount = firstDayOfWeek - 1

    // Group trips by day
    val tripsByDay = trips.groupBy { trip ->
        val tripCal = Calendar.getInstance().apply { timeInMillis = trip.startTime }
        if (tripCal.get(Calendar.YEAR) == year && tripCal.get(Calendar.MONTH) == month) {
            tripCal.get(Calendar.DAY_OF_MONTH)
        } else {
            null
        }
    }

    // Group expenses by day (expense.date is in YYYY-MM-DD format)
    val expensesByDay = expenses.groupBy { expense ->
        try {
            val parts = expense.date.split("-")
            if (parts.size == 3) {
                val expYear = parts[0].toInt()
                val expMonth = parts[1].toInt() - 1 // Calendar months are 0-indexed
                val expDay = parts[2].toInt()
                if (expYear == year && expMonth == month) expDay else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Create empty cells for days before the first day of the month
    val emptyCells = (1..emptyCellsCount).map {
        CalendarDayData(number = 0, hasTrips = false, allTripsValidated = false, hasExpenses = false, year = year, month = month)
    }

    // Create cells for actual days of the month
    val dayCells = (1..daysInMonth).map { day ->
        val dayTrips = tripsByDay[day] ?: emptyList()
        val hasTrips = dayTrips.isNotEmpty()
        val allValidated = hasTrips && dayTrips.all { it.isValidated }
        val hasExpenses = expensesByDay[day]?.isNotEmpty() == true

        CalendarDayData(
            number = day,
            hasTrips = hasTrips,
            allTripsValidated = allValidated,
            hasExpenses = hasExpenses,
            year = year,
            month = month
        )
    }

    return emptyCells + dayCells
}
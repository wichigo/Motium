package com.application.motium.presentation.individual.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.domain.model.User
import com.application.motium.domain.model.isPremium
import com.application.motium.domain.model.TimeSlot
import com.application.motium.domain.model.TrackingMode
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.calendar.WorkScheduleViewModel
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.theme.MockupGreen
import com.application.motium.presentation.theme.ValidatedGreen
import com.application.motium.presentation.theme.PendingOrange
import com.application.motium.utils.CalendarUtils
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
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
    onNavigateToExpenseDetails: (String, List<String>) -> Unit = { _, _ -> },
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val tripRepository = remember { TripRepository.getInstance(context) }

    // Utiliser authState de authViewModel au lieu de créer une nouvelle instance
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user

    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var currentCalendar by remember { mutableStateOf(Calendar.getInstance()) }
    var selectedDay by remember { mutableStateOf<Calendar?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Calendar, 1 = Planning

    // User and premium state
    val isPremium = currentUser?.isPremium() ?: false

    // Premium dialog state
    var showPremiumDialog by remember { mutableStateOf(false) }

    // Load trips
    LaunchedEffect(Unit) {
        trips = tripRepository.getAllTrips()
    }

    // Calculate calendar days with trips
    val calendarDays = remember(currentCalendar, trips) {
        generateCalendarDays(currentCalendar, trips)
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
        val totalIndemnities = totalDistance * 0.585 // 0.585 €/km for cars

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
        },
        bottomBar = {
            MotiumBottomNavigation(
                currentRoute = "calendar",
                isPremium = isPremium,
                onNavigate = { route ->
                    when (route) {
                        "home" -> onNavigateToHome()
                        "vehicles" -> onNavigateToVehicles()
                        "export" -> onNavigateToExport()
                        "settings" -> onNavigateToSettings()
                    }
                },
                onPremiumFeatureClick = {
                    showPremiumDialog = true
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
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
                        currentCalendar = (currentCalendar.clone() as Calendar).apply {
                            add(Calendar.MONTH, -1)
                        }
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
                        currentCalendar = (currentCalendar.clone() as Calendar).apply {
                            add(Calendar.MONTH, 1)
                        }
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
                                    selectedDay = Calendar.getInstance().apply {
                                        set(day.year, day.month, day.number)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Selected day summary and trips
            selectedDay?.let { day ->
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

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
                                onClick = {
                                    val dateLabel = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.ENGLISH).format(day.time)
                                    onNavigateToExpenseDetails(dateLabel, listOf(dateForExpenses))
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Visibility,
                                    contentDescription = "View Expenses",
                                    tint = MockupGreen,
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
                                    tint = MockupGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Daily stats card
                item {
                    val totalDistance = selectedDayTrips.sumOf { it.totalDistance } / 1000.0
                    val totalIndemnities = totalDistance * 0.585
                    DailySummaryCard(
                        distance = String.format("%.1f", totalDistance),
                        tripCount = selectedDayTrips.size.toString(),
                        indemnities = String.format("%.2f", totalIndemnities)
                    )
                }

                // List of trips for selected day
                if (selectedDayTrips.isNotEmpty()) {
                    items(selectedDayTrips) { trip ->
                        TripCardClickable(
                            trip = trip,
                            onClick = { onNavigateToTripDetails(trip.id) }
                        )
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Aucun trajet ce jour",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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

@Composable
fun DailySummaryCard(
    distance: String = "0.0",
    tripCount: String = "0",
    indemnities: String = "0.00"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Résumé de la journée",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryColumn(distance, "Kilomètres")
                SummaryColumn("${indemnities}€", "Indemnités")
                SummaryColumn(tripCount, "Trajets")
            }
        }
    }
}

@Composable
fun TripCardClickable(
    trip: Trip,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trip.getFormattedStartTime(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${trip.getFormattedDistance()} • ${trip.getFormattedDuration()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = trip.getRouteDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            // Validation indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (trip.isValidated) ValidatedGreen else PendingOrange,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
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
                selectedColor = MockupGreen
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

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
                    isToday -> MockupGreen
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    day.hasTrips -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
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
                    day.hasTrips -> MockupGreen
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (day.hasTrips || isToday) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )

            // Validation marker (dot) - Green if all validated, Orange if not
            if (day.hasTrips) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            color = if (day.allTripsValidated) ValidatedGreen else PendingOrange,
                            shape = CircleShape
                        )
                )
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
fun SelectedDaySummary(day: Calendar, dayTrips: List<Trip>) {
    val totalDistance = dayTrips.sumOf { it.totalDistance }
    val totalIndemnities = totalDistance * 0.20 / 1000

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.getDefault()).format(day.time),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Résumé de la journée",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryColumn(String.format("%.1f", totalDistance / 1000), "Kilomètres")
                SummaryColumn(String.format("%.2f€", totalIndemnities), "Indemnités")
                SummaryColumn(dayTrips.size.toString(), "Trajets")
            }

            if (dayTrips.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Aucun trajet ce jour",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun NoDayDataSummary() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Aucun trajet ce jour",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun SummaryColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
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
                containerColor = MockupGreen
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
                    checkedTrackColor = MockupGreen
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
                        MockupGreen
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

        // Auto-tracking toggle card
        AutoTrackingCard(
            trackingMode = trackingMode,
            onModeChanged = { newMode ->
                userId?.let { viewModel.updateTrackingMode(it, newMode) }
            },
            isEnabled = userId != null
        )

        // Professional Hours section
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
fun AutoTrackingCard(
    trackingMode: TrackingMode,
    onModeChanged: (TrackingMode) -> Unit,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-tracking",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = when (trackingMode) {
                        TrackingMode.WORK_HOURS_ONLY -> "Automatic during professional hours"
                        TrackingMode.DISABLED -> "Manual control"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // 2-state toggle button
            IconButton(
                onClick = {
                    if (isEnabled) {
                        val nextMode = when (trackingMode) {
                            TrackingMode.DISABLED -> TrackingMode.WORK_HOURS_ONLY
                            TrackingMode.WORK_HOURS_ONLY -> TrackingMode.DISABLED
                        }
                        onModeChanged(nextMode)
                    }
                },
                enabled = isEnabled
            ) {
                Icon(
                    imageVector = when (trackingMode) {
                        TrackingMode.DISABLED -> Icons.Default.Cancel
                        TrackingMode.WORK_HOURS_ONLY -> Icons.Default.Schedule
                    },
                    contentDescription = "Toggle tracking mode",
                    tint = when (trackingMode) {
                        TrackingMode.DISABLED -> Color.Gray
                        TrackingMode.WORK_HOURS_ONLY -> MockupGreen
                    },
                    modifier = Modifier.size(32.dp)
                )
            }
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
                            contentColor = MockupGreen
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
                        contentColor = MockupGreen
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Start time display
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = if (timeSlot.startHour < 12) {
                        String.format("%02d:%02d AM",
                            if (timeSlot.startHour == 0) 12 else timeSlot.startHour,
                            timeSlot.startMinute)
                    } else {
                        String.format("%02d:%02d PM",
                            if (timeSlot.startHour == 12) 12 else timeSlot.startHour - 12,
                            timeSlot.startMinute)
                    },
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
                    text = if (timeSlot.endHour < 12) {
                        String.format("%02d:%02d AM",
                            if (timeSlot.endHour == 0) 12 else timeSlot.endHour,
                            timeSlot.endMinute)
                    } else {
                        String.format("%02d:%02d PM",
                            if (timeSlot.endHour == 12) 12 else timeSlot.endHour - 12,
                            timeSlot.endMinute)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete time slot",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

// Data classes
data class CalendarDayData(
    val number: Int,
    val hasTrips: Boolean = false,
    val allTripsValidated: Boolean = false,
    val year: Int = 0,
    val month: Int = 0
)

data class DaySchedule(
    val dayOfWeek: Int, // Calendar.MONDAY, etc.
    val timeSlots: List<TimeSlot> = emptyList()
)

fun generateCalendarDays(calendar: Calendar, trips: List<Trip>): List<CalendarDayData> {
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

    // Create empty cells for days before the first day of the month
    val emptyCells = (1..emptyCellsCount).map {
        CalendarDayData(number = 0, hasTrips = false, allTripsValidated = false, year = year, month = month)
    }

    // Create cells for actual days of the month
    val dayCells = (1..daysInMonth).map { day ->
        val dayTrips = tripsByDay[day] ?: emptyList()
        val hasTrips = dayTrips.isNotEmpty()
        val allValidated = hasTrips && dayTrips.all { it.isValidated }

        CalendarDayData(
            number = day,
            hasTrips = hasTrips,
            allTripsValidated = allValidated,
            year = year,
            month = month
        )
    }

    return emptyCells + dayCells
}
package com.application.motium.presentation.enterprise.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.data.TripRepository
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.domain.model.isPremium
import com.application.motium.domain.model.TrackingMode
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.AddressAutocomplete
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.components.EnterpriseBottomNavigationSimple
import com.application.motium.presentation.theme.*
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// MotiumPrimary est importé de com.application.motium.presentation.theme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterpriseNewHomeScreen(
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToTripDetails: (String) -> Unit = {},
    onNavigateToAddTrip: () -> Unit = {},
    onNavigateToAddExpense: (String) -> Unit = {},
    onNavigateToExpenseDetails: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepository = remember { TripRepository.getInstance(context) }
    val workScheduleRepository = remember { WorkScheduleRepository.getInstance(context) }
    val themeManager = remember { ThemeManager.getInstance(context) }

    // Utiliser authState de authViewModel
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val isPremium = currentUser?.isPremium() ?: false

    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var autoTrackingEnabled by remember { mutableStateOf(false) }
    var trackingMode by remember { mutableStateOf<TrackingMode?>(null) }
    var showAutoTrackingBlockedDialog by remember { mutableStateOf(false) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    // Pagination state
    var currentOffset by remember { mutableStateOf(0) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreTrips by remember { mutableStateOf(true) }
    var shouldLoadMore by remember { mutableStateOf(false) }

    // Statistiques du jour
    val todayTrips = remember(trips) {
        val today = Calendar.getInstance()
        trips.filter { trip ->
            val tripDate = Calendar.getInstance().apply { timeInMillis = trip.startTime }
            tripDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
            tripDate.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        }
    }

    val todayDistance = remember(todayTrips) {
        todayTrips.sumOf { it.totalDistance }
    }

    val todayIndemnities = remember(todayTrips) {
        todayTrips.sumOf { it.totalDistance * 0.20 }
    }

    // Grouper les trips par date
    val groupedTrips = remember(trips) {
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val grouped = mutableMapOf<String, List<Trip>>()
        trips.forEach { trip ->
            val tripDate = Calendar.getInstance().apply { timeInMillis = trip.startTime }
            val key = when {
                tripDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                tripDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "Today"
                tripDate.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) &&
                tripDate.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) -> "Yesterday"
                else -> SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date(trip.startTime))
            }
            grouped[key] = (grouped[key] ?: emptyList()) + trip
        }
        grouped
    }

    // Load more trips when shouldLoadMore changes
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !isLoadingMore && hasMoreTrips) {
            isLoadingMore = true
            val newTrips = tripRepository.getTripsPaginated(limit = 10, offset = currentOffset)
            if (newTrips.isNotEmpty()) {
                trips = trips + newTrips
                currentOffset += newTrips.size
                hasMoreTrips = newTrips.size == 10
            } else {
                hasMoreTrips = false
            }
            isLoadingMore = false
            shouldLoadMore = false
        }
    }

    // Charger les trips au démarrage - une seule fois
    LaunchedEffect(Unit) {
        if (trips.isEmpty()) {
            // 1. Charger d'abord depuis la BDD locale pour affichage immédiat
            // Pas besoin d'attendre l'auth, getAllTrips() utilise le dernier userId connu
            currentOffset = 0
            hasMoreTrips = true
            isLoadingMore = true

            val initialTrips = tripRepository.getTripsPaginated(limit = 10, offset = 0)
            if (initialTrips.isNotEmpty()) {
                trips = initialTrips
                currentOffset = initialTrips.size
                hasMoreTrips = initialTrips.size == 10
            } else {
                hasMoreTrips = false
            }
            isLoadingMore = false

            // 2. Attendre que l'auth soit restaurée avant de synchroniser avec Supabase
            while (authState.isLoading) {
                delay(50)
            }

            // 3. Synchroniser avec Supabase en arrière-plan (si authentifié)
            if (authState.isAuthenticated) {
                coroutineScope.launch(Dispatchers.IO) {
                    tripRepository.syncTripsFromSupabase()
                    // Recharger après synchro pour afficher les nouveaux trajets
                    val syncedTrips = tripRepository.getTripsPaginated(limit = currentOffset.coerceAtLeast(10), offset = 0)
                    trips = syncedTrips
                    currentOffset = syncedTrips.size
                    hasMoreTrips = syncedTrips.size >= 10
                }
            }

            autoTrackingEnabled = tripRepository.isAutoTrackingEnabled()

            // Load tracking mode from Supabase
            currentUser?.id?.let { userId ->
                val settings = workScheduleRepository.getAutoTrackingSettings(userId)
                trackingMode = settings?.trackingMode
            }

            // Démarrer le service si l'auto-tracking est activé
            if (autoTrackingEnabled) {
                MotiumApplication.logger.i("Auto tracking is enabled, ensuring service is running", "EnterpriseHomeScreen")
                ActivityRecognitionService.startService(context)
            }
        }
    }

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    WithCustomColor {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Trips",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            fontSize = 18.sp,
                            color = textColor
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    themeManager.toggleTheme()
                                }
                            }
                        ) {
                            Text(
                                text = if (isDarkMode) "☀️" else "🌙",
                                fontSize = 20.sp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = backgroundColor
                    )
                )
            },
            containerColor = backgroundColor
        ) { paddingValues ->
        LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Add Trip Button
                item {
                    Button(
                        onClick = onNavigateToAddTrip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp) // Fully rounded
                    ) {
                        Text(
                            "Add Trip",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            fontSize = 16.sp
                        )
                    }
                }

                // Auto Tracking Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = surfaceColor
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Auto Tracking",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor
                            )
                            Switch(
                                checked = autoTrackingEnabled,
                                onCheckedChange = {
                                    // Block toggle if auto-tracking is managed automatically
                                    if (trackingMode == TrackingMode.WORK_HOURS_ONLY) {
                                        showAutoTrackingBlockedDialog = true
                                    } else {
                                        autoTrackingEnabled = it
                                        tripRepository.setAutoTrackingEnabled(it)
                                        if (it) {
                                            ActivityRecognitionService.startService(context)
                                        } else {
                                            ActivityRecognitionService.stopService(context)
                                        }
                                        MotiumApplication.logger.i("Auto tracking: $it", "EnterpriseHomeScreen")
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MotiumPrimary,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFe2e8f0)
                                )
                            )
                        }
                    }
                }

                // Daily Summary Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = surfaceColor
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                "Daily Summary",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = textColor,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                EnterpriseHomeDailyStat(
                                    value = String.format("%.1f", todayDistance / 1000),
                                    label = "Kilometers",
                                    textSecondaryColor = textSecondaryColor
                                )
                                EnterpriseHomeDailyStat(
                                    value = String.format("$%.2f", todayIndemnities / 1000),
                                    label = "Indemnities",
                                    textSecondaryColor = textSecondaryColor
                                )
                                EnterpriseHomeDailyStat(
                                    value = todayTrips.size.toString(),
                                    label = "Trips",
                                    textSecondaryColor = textSecondaryColor
                                )
                            }
                        }
                    }
                }

                // Liste des trips groupés par date
                groupedTrips.forEach { (dateLabel, tripsForDate) ->
                    item {
                        // Convert dateLabel to YYYY-MM-DD format for expenses
                        val dateForExpenses = remember(dateLabel) {
                            val firstTrip = tripsForDate.firstOrNull()
                            firstTrip?.let {
                                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                format.format(Date(it.startTime))
                            } ?: ""
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = textColor
                            )

                            // Buttons for expenses
                            if (tripsForDate.isNotEmpty()) {
                                Row {
                                    // Button to view expense details for this day
                                    IconButton(
                                        onClick = {
                                            if (dateForExpenses.isNotEmpty()) {
                                                onNavigateToExpenseDetails(dateForExpenses)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "View Expenses",
                                            tint = MotiumPrimary
                                        )
                                    }

                                    // Button to add expense for the first trip of the day
                                    IconButton(
                                        onClick = {
                                            if (dateForExpenses.isNotEmpty()) {
                                                onNavigateToAddExpense(dateForExpenses)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Receipt,
                                            contentDescription = "Add Expense",
                                            tint = MotiumPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(
                        items = tripsForDate,
                        key = { trip -> trip.id }
                    ) { trip ->
                        EnterpriseHomeTripCard(
                            trip = trip,
                            onClick = { onNavigateToTripDetails(trip.id) },
                            onToggleValidation = {
                                coroutineScope.launch {
                                    val updatedTrip = trip.copy(isValidated = !trip.isValidated)
                                    tripRepository.saveTrip(updatedTrip)
                                    // Reload current page to reflect changes
                                    val reloadedTrips = tripRepository.getTripsPaginated(
                                        limit = currentOffset,
                                        offset = 0
                                    )
                                    trips = reloadedTrips
                                }
                            },
                            onDelete = {
                                coroutineScope.launch {
                                    tripRepository.deleteTrip(trip.id)
                                    // Reload current page to reflect deletion
                                    val reloadedTrips = tripRepository.getTripsPaginated(
                                        limit = currentOffset,
                                        offset = 0
                                    )
                                    trips = reloadedTrips
                                    // Adjust offset if needed
                                    if (reloadedTrips.size < currentOffset) {
                                        currentOffset = reloadedTrips.size
                                    }
                                }
                            },
                            surfaceColor = surfaceColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }
                }

                // Loading indicator / Load More trigger
                if (hasMoreTrips && trips.isNotEmpty()) {
                    item {
                        LaunchedEffect(Unit) {
                            if (!isLoadingMore) {
                                shouldLoadMore = true
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MotiumPrimary
                            )
                        }
                    }
                }

                // Message si aucun trip
                if (trips.isEmpty() && !isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No trips yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = textSecondaryColor
                            )
                        }
                    }
                }
            }
        }

        // Bottom navigation en overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
        ) {
            EnterpriseBottomNavigationSimple(
                currentRoute = "enterprise_home",
                onNavigate = { route ->
                    when (route) {
                        "enterprise_calendar" -> onNavigateToCalendar()
                        "enterprise_vehicles" -> onNavigateToVehicles()
                        "enterprise_export" -> onNavigateToExport()
                        "enterprise_settings" -> onNavigateToSettings()
                    }
                },
                isPremium = isPremium
            )
        }
    }
    }

    // Dialog to inform user that auto-tracking is managed automatically
    if (showAutoTrackingBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showAutoTrackingBlockedDialog = false },
            title = {
                Text(
                    text = "Automatic Mode Active",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "Auto-tracking is currently managed automatically based on your work schedule defined in the Planning page. To manually control auto-tracking, please disable the work hours mode in Planning first.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showAutoTrackingBlockedDialog = false }
                ) {
                    Text("OK", color = MotiumPrimary)
                }
            },
            containerColor = Color.White,
            tonalElevation = 24.dp
        )
    }

}

@Composable
private fun EnterpriseHomeDailyStat(
    value: String,
    label: String,
    textSecondaryColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MotiumPrimary,
            fontSize = 20.sp
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = textSecondaryColor,
            fontSize = 12.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnterpriseHomeTripCard(
    trip: Trip,
    onClick: () -> Unit,
    onToggleValidation: () -> Unit,
    onDelete: () -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == SwipeToDismissBoxValue.EndToStart || dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    // Calculer les coordonnées de départ et d'arrivée depuis les locations
    val startLocation = trip.locations.firstOrNull()
    val endLocation = trip.locations.lastOrNull()

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd, SwipeToDismissBoxValue.EndToStart -> Color(0xFFef4444)
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Supprimer",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Mini-map à gauche (96x96dp)
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(Color(0xFFe5e7eb), RoundedCornerShape(8.dp))
                    ) {
                        if (startLocation != null && endLocation != null) {
                            MiniMap(
                                startLatitude = startLocation.latitude,
                                startLongitude = startLocation.longitude,
                                endLatitude = endLocation.latitude,
                                endLongitude = endLocation.longitude,
                                routeCoordinates = trip.locations.map {
                                    listOf(it.longitude, it.latitude)
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFFe5e7eb), RoundedCornerShape(8.dp))
                            )
                        } else {
                            // Placeholder si pas de coordonnées
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // Colonne avec origine et destination
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 60.dp), // Espace pour le prix et le toggle
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Point de départ
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(8.dp)
                                    .background(MotiumPrimary, shape = RoundedCornerShape(4.dp))
                            )
                            Column {
                                Text(
                                    trip.startAddress ?: "Unknown",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = textColor,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Text(
                                    SimpleDateFormat("hh:mm a", Locale.US).format(Date(trip.startTime)).uppercase(Locale.US),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Ligne pointillée
                        Box(
                            modifier = Modifier
                                .padding(start = 3.dp)
                                .width(1.dp)
                                .height(12.dp)
                                .background(textSecondaryColor.copy(alpha = 0.3f))
                        )

                        // Point d'arrivée
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(8.dp)
                                    .background(MotiumPrimary, shape = RoundedCornerShape(4.dp))
                            )
                            Column {
                                Text(
                                    trip.endAddress ?: "Unknown",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = textColor,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Text(
                                    SimpleDateFormat("hh:mm a", Locale.US).format(
                                        Date(trip.endTime ?: System.currentTimeMillis())
                                    ).uppercase(Locale.US),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Prix en haut à droite
                Text(
                    String.format("$%.2f", trip.totalDistance * 0.20 / 1000),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MotiumPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                )

                // Toggle en bas à droite
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 12.dp, end = 12.dp)
                ) {
                    Switch(
                        checked = trip.isValidated,
                        onCheckedChange = { onToggleValidation() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MotiumPrimary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFe2e8f0)
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

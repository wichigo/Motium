package com.application.motium.presentation.individual.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.domain.model.isPremium
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.utils.ThemeManager
import com.application.motium.presentation.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// Définition des couleurs spécifiques à la maquette
val MockupGreen = Color(0xFF10B981)
val MockupTextBlack = Color(0xFF1F2937)
val MockupTextGray = Color(0xFF6B7280)
val MockupBackground = Color(0xFFF3F4F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewHomeScreen(
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
    val themeManager = remember { ThemeManager.getInstance(context) }

    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user

    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var autoTrackingEnabled by remember { mutableStateOf(false) }
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

    val todayDistance = remember(todayTrips) { todayTrips.sumOf { it.totalDistance } }
    val todayIndemnities = remember(todayTrips) { todayTrips.sumOf { it.totalDistance * 0.20 } }

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

    // Charger les données avec pagination - une seule fois au démarrage
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
                kotlinx.coroutines.delay(50)
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
            if (autoTrackingEnabled) {
                MotiumApplication.logger.i("Auto tracking is enabled, ensuring service is running", "HomeScreen")
                ActivityRecognitionService.startService(context)
            }
        }
    }

    // Couleurs dynamiques
    val backgroundColor = if (isDarkMode) Color(0xFF121212) else MockupBackground
    val cardColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else MockupTextBlack
    val subTextColor = if (isDarkMode) Color.Gray else MockupTextGray

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Trips",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                    },
                    actions = {
                        IconButton(onClick = { coroutineScope.launch { themeManager.toggleTheme() } }) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Theme",
                                tint = subTextColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = backgroundColor)
                )
            },
            containerColor = backgroundColor
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. GROS BOUTON VERT "ADD TRIP"
                item {
                    Button(
                        onClick = onNavigateToAddTrip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MockupGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(28.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Text(
                            "Add Trip",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            fontSize = 18.sp
                        )
                    }
                }

                // 2. AUTO TRACKING SWITCH
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Auto Tracking",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = textColor
                            )
                            Switch(
                                checked = autoTrackingEnabled,
                                onCheckedChange = {
                                    autoTrackingEnabled = it
                                    tripRepository.setAutoTrackingEnabled(it)
                                    if (it) ActivityRecognitionService.startService(context)
                                    else ActivityRecognitionService.stopService(context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MockupGreen,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFE5E7EB)
                                )
                            )
                        }
                    }
                }

                // 3. DAILY SUMMARY
                item {
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
                                StatItem(String.format("%.1f", todayDistance / 1000), "Kilometers", MockupGreen, textColor, subTextColor)
                                StatItem(String.format("$%.2f", todayIndemnities / 1000), "Indemnities", MockupGreen, textColor, subTextColor)
                                StatItem(todayTrips.size.toString(), "Trips", MockupGreen, textColor, subTextColor)
                            }
                        }
                    }
                }

                // 4. TOUS LES TRIPS GROUPÉS PAR DATE
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
                                .padding(start = 4.dp, top = 8.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                modifier = Modifier.weight(1f)
                            )

                            // Expense buttons (NOUVEAU: lié à la journée)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // View expenses button
                                IconButton(
                                    onClick = {
                                        if (dateForExpenses.isNotEmpty()) {
                                            onNavigateToExpenseDetails(dateForExpenses)
                                        }
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
                                    onClick = {
                                        if (dateForExpenses.isNotEmpty()) {
                                            onNavigateToAddExpense(dateForExpenses)
                                        }
                                    },
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

                    items(
                        items = tripsForDate,
                        key = { trip -> trip.id }
                    ) { trip ->
                        NewHomeTripCard(
                            trip = trip,
                            onClick = { onNavigateToTripDetails(trip.id) },
                            onToggleValidation = {
                                coroutineScope.launch {
                                    val updated = trip.copy(isValidated = !trip.isValidated)
                                    tripRepository.saveTrip(updated)
                                    // Reload current page to reflect changes
                                    val reloadedTrips = tripRepository.getTripsPaginated(
                                        limit = currentOffset,
                                        offset = 0
                                    )
                                    trips = reloadedTrips
                                }
                            },
                            cardColor = cardColor,
                            textColor = textColor,
                            subTextColor = subTextColor
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
                                color = MockupGreen
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
                                color = subTextColor
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            MotiumBottomNavigation(
                currentRoute = "home",
                onNavigate = { route ->
                    when (route) {
                        "calendar" -> onNavigateToCalendar()
                        "vehicles" -> onNavigateToVehicles()
                        "export" -> onNavigateToExport()
                        "settings" -> onNavigateToSettings()
                    }
                }
            )
        }
    }
}

@Composable
fun StatItem(value: String, label: String, highlightColor: Color, textColor: Color, subColor: Color) {
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

@Composable
fun NewHomeTripCard(
    trip: Trip,
    onClick: () -> Unit,
    onToggleValidation: () -> Unit,
    cardColor: Color,
    textColor: Color,
    subTextColor: Color
) {
    val startLocation = trip.locations.firstOrNull()
    val endLocation = trip.locations.lastOrNull()
    val startTimeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date(trip.startTime))
    val endTimeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date(trip.endTime ?: System.currentTimeMillis()))

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
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 1. MAP
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE5E7EB))
            ) {
                if (startLocation != null && endLocation != null) {
                    MiniMap(
                        startLatitude = startLocation.latitude,
                        startLongitude = startLocation.longitude,
                        endLatitude = endLocation.latitude,
                        endLongitude = endLocation.longitude,
                        routeCoordinates = trip.locations.map { listOf(it.longitude, it.latitude) },
                        modifier = Modifier.fillMaxSize()
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

            // 2. DETAILS COLUMN
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // --- Ligne Départ ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(8.dp)
                                .background(MockupGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = trip.startAddress ?: "Unknown Start",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                maxLines = 1
                            )
                            Text(
                                text = startTimeStr,
                                style = MaterialTheme.typography.bodySmall,
                                color = subTextColor
                            )
                        }
                    }
                    Text(
                        text = String.format("$%.2f", trip.totalDistance * 0.20 / 1000),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MockupGreen
                    )
                }

                // --- Connecteur (Pointillés) ---
                Row(modifier = Modifier.height(24.dp)) {
                    Box(modifier = Modifier.width(4.dp))
                    Canvas(modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                    ) {
                        drawLine(
                            color = Color.LightGray,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                // --- Ligne Arrivée ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(8.dp)
                                .background(MockupGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = trip.endAddress ?: "Unknown End",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                maxLines = 1
                            )
                            Text(
                                text = endTimeStr,
                                style = MaterialTheme.typography.bodySmall,
                                color = subTextColor
                            )
                        }
                    }
                    // Toggle Switch ajusté
                    Box(modifier = Modifier.height(24.dp)) {
                         Switch(
                            checked = trip.isValidated,
                            onCheckedChange = { onToggleValidation() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MockupGreen,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFE5E7EB),
                                uncheckedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }
            }
        }
    }
}

// Extension corrigée
fun Modifier.scale(scale: Float): Modifier = this.graphicsLayer(
    scaleX = scale,
    scaleY = scale
)

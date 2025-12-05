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
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
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
import com.application.motium.data.VehicleRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.domain.model.isPremium
import com.application.motium.domain.model.TrackingMode
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.TrackingModeDropdown
import com.application.motium.data.sync.AutoTrackingScheduleWorker
import com.application.motium.domain.model.AutoTrackingSettings
import kotlinx.datetime.Instant
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.utils.ThemeManager
import com.application.motium.presentation.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// Couleurs additionnelles (MotiumPrimary remplacé par MotiumPrimary du thème)
val MockupTextBlack = Color(0xFF1F2937)
val MockupTextGray = Color(0xFF6B7280)
val MockupBackground = Color(0xFFF3F4F6)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun NewHomeScreen(
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToCalendarPlanning: () -> Unit = {},
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
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }
    val workScheduleRepository = remember { WorkScheduleRepository.getInstance(context) }
    val themeManager = remember { ThemeManager.getInstance(context) }

    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user

    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var autoTrackingEnabled by remember { mutableStateOf(false) }
    // Initialiser avec la valeur du cache local pour éviter le "flash" DISABLED → mode réel
    var trackingMode by remember { mutableStateOf(tripRepository.getTrackingMode()) }
    var hasWorkSchedules by remember { mutableStateOf(false) }
    var showNoSchedulesDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
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

                // Charger le mode de tracking et les horaires depuis Supabase
                currentUser?.let { user ->
                    coroutineScope.launch(Dispatchers.IO) {
                        val settings = workScheduleRepository.getAutoTrackingSettings(user.id)
                        val supabaseMode = settings?.trackingMode ?: TrackingMode.DISABLED
                        // Mettre à jour le cache local avec la valeur Supabase (source de vérité)
                        tripRepository.setTrackingMode(supabaseMode)
                        trackingMode = supabaseMode

                        // Vérifier si l'utilisateur a des horaires définis
                        val schedules = workScheduleRepository.getWorkSchedules(user.id)
                        hasWorkSchedules = schedules.isNotEmpty()
                    }
                }
            }

            autoTrackingEnabled = tripRepository.isAutoTrackingEnabled()
            if (autoTrackingEnabled) {
                MotiumApplication.logger.i("Auto tracking is enabled, ensuring service is running", "HomeScreen")
                ActivityRecognitionService.startService(context)
            }
        }
    }

    // Couleurs dynamiques (utilise les couleurs du thème)
    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val subTextColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

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
            // Pull-to-refresh state
            val pullRefreshState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = {
                    coroutineScope.launch(Dispatchers.IO) {
                        isRefreshing = true
                        try {
                            // Récupérer le userId depuis authState (plus fiable que authRepository)
                            val userId = currentUser?.id

                            // 1. Sync trips depuis Supabase
                            tripRepository.syncTripsFromSupabase(userId)

                            // 2. Sync véhicules (pour les kilométrages à jour)
                            vehicleRepository.syncVehiclesFromSupabase()

                            // 3. Sync horaires de travail
                            currentUser?.let { user ->
                                val settings = workScheduleRepository.getAutoTrackingSettings(user.id)
                                val supabaseMode = settings?.trackingMode ?: TrackingMode.DISABLED
                                tripRepository.setTrackingMode(supabaseMode)
                                trackingMode = supabaseMode
                                val schedules = workScheduleRepository.getWorkSchedules(user.id)
                                hasWorkSchedules = schedules.isNotEmpty()
                            }

                            // 4. Recharger les trips locaux
                            currentOffset = 0
                            val refreshedTrips = tripRepository.getTripsPaginated(limit = 10, offset = 0)
                            trips = refreshedTrips
                            currentOffset = refreshedTrips.size
                            hasMoreTrips = refreshedTrips.size == 10

                            MotiumApplication.logger.i("Pull-to-refresh completed: ${trips.size} trips loaded", "HomeScreen")
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Refresh failed: ${e.message}", "HomeScreen", e)
                        } finally {
                            isRefreshing = false
                        }
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .pullRefresh(pullRefreshState)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
                            containerColor = MotiumPrimary,
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

                // 2. AUTO TRACKING DROPDOWN
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            TrackingModeDropdown(
                                selectedMode = trackingMode,
                                onModeSelected = { newMode ->
                                    // Si Pro sélectionné sans horaires définis, rediriger vers Planning
                                    if (newMode == TrackingMode.WORK_HOURS_ONLY && !hasWorkSchedules) {
                                        showNoSchedulesDialog = true
                                        return@TrackingModeDropdown
                                    }

                                    // Mettre à jour le mode de tracking
                                    currentUser?.let { user ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val settings = AutoTrackingSettings(
                                                id = java.util.UUID.randomUUID().toString(),
                                                userId = user.id,
                                                trackingMode = newMode,
                                                minTripDistanceMeters = 100,
                                                minTripDurationSeconds = 60,
                                                createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                                                updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                                            )
                                            workScheduleRepository.saveAutoTrackingSettings(settings)
                                            // Mettre à jour le cache local ET l'état UI
                                            tripRepository.setTrackingMode(newMode)
                                            trackingMode = newMode

                                            // Gérer le démarrage/arrêt des services selon le mode
                                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                when (newMode) {
                                                    TrackingMode.ALWAYS -> {
                                                        tripRepository.setAutoTrackingEnabled(true)
                                                        autoTrackingEnabled = true
                                                        ActivityRecognitionService.startService(context)
                                                        AutoTrackingScheduleWorker.cancel(context)
                                                        MotiumApplication.logger.i("ALWAYS mode: Auto-tracking permanently enabled", "HomeScreen")
                                                    }
                                                    TrackingMode.WORK_HOURS_ONLY -> {
                                                        AutoTrackingScheduleWorker.schedule(context)
                                                        AutoTrackingScheduleWorker.runNow(context)
                                                        MotiumApplication.logger.i("PRO mode: Auto-tracking managed by work schedule", "HomeScreen")
                                                    }
                                                    TrackingMode.DISABLED -> {
                                                        tripRepository.setAutoTrackingEnabled(false)
                                                        autoTrackingEnabled = false
                                                        ActivityRecognitionService.stopService(context)
                                                        AutoTrackingScheduleWorker.cancel(context)
                                                        MotiumApplication.logger.i("DISABLED mode: Auto-tracking stopped", "HomeScreen")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = currentUser != null
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
                                StatItem(String.format("%.1f", todayDistance / 1000), "Kilometers", MotiumPrimary, textColor, subTextColor)
                                StatItem(String.format("$%.2f", todayIndemnities / 1000), "Indemnities", MotiumPrimary, textColor, subTextColor)
                                StatItem(todayTrips.size.toString(), "Trips", MotiumPrimary, textColor, subTextColor)
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
                                        tint = MotiumPrimary,
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
                                        tint = MotiumPrimary,
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
                                color = subTextColor
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
                }

                // Pull-to-refresh indicator
                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    contentColor = MotiumPrimary
                )
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
                },
                isDarkMode = isDarkMode
            )
        }
    }

    // Dialog pour rediriger vers Planning quand Pro est sélectionné sans horaires
    if (showNoSchedulesDialog) {
        AlertDialog(
            onDismissRequest = { showNoSchedulesDialog = false },
            title = {
                Text(
                    text = "Aucun horaire défini",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "Pour utiliser le mode Professionnel, vous devez d'abord définir vos horaires de travail dans la section Planning. Voulez-vous configurer vos horaires maintenant ?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNoSchedulesDialog = false
                        onNavigateToCalendarPlanning()
                    }
                ) {
                    Text("Configurer", color = MotiumPrimary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNoSchedulesDialog = false }
                ) {
                    Text("Annuler", color = Color.Gray)
                }
            },
            containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White,
            tonalElevation = 24.dp
        )
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
                        text = String.format("%.2f €", trip.totalDistance * 0.20 / 1000),
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
                            .scale(0.65f)
                            .offset(y = 22.dp)
                    )
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

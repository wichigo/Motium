package com.application.motium.presentation.individual.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.domain.model.TrackingMode
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.components.TrackingModeDropdown
import com.application.motium.presentation.components.FullScreenLoading
import com.application.motium.data.sync.AutoTrackingScheduleWorker
import com.application.motium.domain.model.AutoTrackingSettings
import kotlinx.datetime.Instant
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.worker.ActivityRecognitionHealthWorker
import com.application.motium.utils.ThemeManager
import com.application.motium.presentation.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.presentation.components.SyncStatusIndicator

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
    authViewModel: AuthViewModel = viewModel(),
    // Pro-specific parameters
    isPro: Boolean = false,
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToExportAdvanced: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepository = remember { TripRepository.getInstance(context) }
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }
    val workScheduleRepository = remember { WorkScheduleRepository.getInstance(context) }
    val themeManager = remember { ThemeManager.getInstance(context) }
    val syncManager = remember { OfflineFirstSyncManager.getInstance(context) }

    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user

    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var autoTrackingEnabled by remember { mutableStateOf(false) }
    // Initialiser avec la valeur du cache local pour éviter le "flash" DISABLED → mode réel
    var trackingMode by remember { mutableStateOf(tripRepository.getTrackingMode()) }
    var hasWorkSchedules by remember { mutableStateOf(false) }
    var showNoSchedulesDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isInitialLoading by remember { mutableStateOf(false) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    // Pagination state
    var currentOffset by remember { mutableStateOf(0) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreTrips by remember { mutableStateOf(true) }
    var shouldLoadMore by remember { mutableStateOf(false) }

    // Swipe-to-delete state
    var tripToDelete by remember { mutableStateOf<Trip?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Track if we've synced with Supabase this session (survives recomposition)
    var hasSyncedWithSupabase by rememberSaveable { mutableStateOf(false) }

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
    val todayIndemnities = remember(todayTrips) { todayTrips.sumOf { it.reimbursementAmount ?: 0.0 } }

    // Grouper les trips par date
    val groupedTrips = remember(trips) {
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val grouped = mutableMapOf<String, List<Trip>>()
        trips.forEach { trip ->
            val tripDate = Calendar.getInstance().apply { timeInMillis = trip.startTime }
            val key = when {
                tripDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                tripDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "Aujourd'hui"
                tripDate.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) &&
                tripDate.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) -> "Hier"
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
            isInitialLoading = true
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
            // Utiliser authState.user au lieu de currentUser (capture plus récente après while loop)
            val freshUser = authState.user
            if (authState.isAuthenticated && freshUser != null && !hasSyncedWithSupabase) {
                hasSyncedWithSupabase = true
                MotiumApplication.logger.i("🔄 Initial sync triggered for user ${freshUser.id}", "HomeScreen")

                // BATTERY OPTIMIZATION (2026-01): Use unified sync_changes() via WorkManager
                // instead of legacy tripRepository.syncTripsFromSupabase() which bypasses atomic sync
                syncManager.triggerImmediateSync()

                // Charger le mode de tracking et les horaires depuis Supabase
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        MotiumApplication.logger.i("🔄 Loading auto-tracking settings from Supabase for user ${freshUser.id}", "HomeScreen")
                        val settings = workScheduleRepository.getAutoTrackingSettings(freshUser.id)
                        val supabaseMode = settings?.trackingMode ?: TrackingMode.DISABLED
                        // Mettre à jour le cache local avec la valeur Supabase (source de vérité)
                        tripRepository.setTrackingMode(supabaseMode)
                        trackingMode = supabaseMode
                        MotiumApplication.logger.i("✅ Auto-tracking mode loaded: $supabaseMode", "HomeScreen")

                        // Vérifier si l'utilisateur a des horaires définis
                        val schedules = workScheduleRepository.getWorkSchedules(freshUser.id)
                        hasWorkSchedules = schedules.isNotEmpty()
                    } catch (e: CancellationException) {
                        // Normal when user navigates away - don't log as error
                        MotiumApplication.logger.d("Work schedule loading cancelled (user left screen)", "HomeScreen")
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("Failed to load work schedules: ${e.message}", "HomeScreen")
                    }
                }
            }

            autoTrackingEnabled = tripRepository.isAutoTrackingEnabled()
            if (autoTrackingEnabled) {
                MotiumApplication.logger.i("Auto tracking is enabled, ensuring service is running", "HomeScreen")
                ActivityRecognitionService.startService(context)
            }
            isInitialLoading = false
        }
    }

    // Re-sync when auth state changes to authenticated (handles delayed session restoration)
    // Uses hasSyncedWithSupabase flag (rememberSaveable) to avoid re-syncing on navigation back
    LaunchedEffect(authState.isAuthenticated, hasSyncedWithSupabase) {
        val user = authState.user
        if (authState.isAuthenticated && user != null && !hasSyncedWithSupabase) {
            MotiumApplication.logger.i("🔄 Auth state changed to authenticated, syncing from Supabase", "HomeScreen")
            hasSyncedWithSupabase = true
            // BATTERY OPTIMIZATION (2026-01): Use unified sync_changes() via WorkManager
            // instead of legacy tripRepository.syncTripsFromSupabase() which bypasses atomic sync
            syncManager.triggerImmediateSync()

            // Charger le mode de tracking et les horaires depuis Supabase
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    MotiumApplication.logger.i("🔄 Loading auto-tracking settings from Supabase for user ${user.id}", "HomeScreen")
                    val settings = workScheduleRepository.getAutoTrackingSettings(user.id)
                    val supabaseMode = settings?.trackingMode ?: TrackingMode.DISABLED
                    // Mettre à jour le cache local avec la valeur Supabase (source de vérité)
                    tripRepository.setTrackingMode(supabaseMode)
                    trackingMode = supabaseMode
                    MotiumApplication.logger.i("✅ Auto-tracking mode loaded: $supabaseMode", "HomeScreen")

                    // Vérifier si l'utilisateur a des horaires définis
                    val schedules = workScheduleRepository.getWorkSchedules(user.id)
                    hasWorkSchedules = schedules.isNotEmpty()
                } catch (e: CancellationException) {
                    // Normal when user navigates away - don't log as error
                    MotiumApplication.logger.d("Work schedule loading cancelled (user left screen)", "HomeScreen")
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Failed to load work schedules: ${e.message}", "HomeScreen")
                }
            }
        }
    }

    // Background map-matching: Calculate and cache route coordinates for trips without cache
    // Use a set to track which trips are being processed to avoid re-triggering
    val nominatimService = remember { NominatimService.getInstance() }
    var processedTripIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(trips.map { it.id }.toSet()) {
        if (trips.isEmpty()) return@LaunchedEffect

        // Find trips that need map-matching (no cached coordinates, have enough GPS points, not already processed)
        // OSRM requires at least 3 points for map-matching
        val tripsNeedingMapMatch = trips.filter { trip ->
            trip.matchedRouteCoordinates.isNullOrBlank() &&
            trip.locations.size >= 3 &&
            trip.id !in processedTripIds
        }

        if (tripsNeedingMapMatch.isNotEmpty()) {
            // Mark these trips as being processed to avoid re-triggering
            processedTripIds = processedTripIds + tripsNeedingMapMatch.map { it.id }.toSet()

            // Process trips in background, one at a time to avoid overloading OSRM
            coroutineScope.launch(Dispatchers.IO) {
                for (trip in tripsNeedingMapMatch) {
                    try {
                        val gpsPoints = trip.locations.map { loc ->
                            Pair(loc.latitude, loc.longitude)
                        }
                        val matched = nominatimService.matchRoute(gpsPoints)

                        if (matched != null && matched.isNotEmpty()) {
                            // Serialize to JSON
                            val jsonCoords = matched.joinToString(",", "[", "]") { coord ->
                                "[${coord[0]},${coord[1]}]"
                            }

                            // Save to database
                            val updatedTrip = trip.copy(matchedRouteCoordinates = jsonCoords)
                            tripRepository.saveTrip(updatedTrip)

                            // Update local state to trigger recomposition
                            trips = trips.map { t ->
                                if (t.id == trip.id) updatedTrip else t
                            }
                        }

                        // Small delay between requests to be nice to OSRM servers
                        kotlinx.coroutines.delay(500)
                    } catch (_: Exception) {
                        // Silently ignore - failure cache in NominatimService prevents retries
                    }
                }
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
                            "Trajets",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                    },
                    actions = {
                        // Sync status indicator
                        SyncStatusIndicator(
                            isOnline = syncManager.isOnline,
                            pendingOperationsCount = syncManager.pendingOperationsCount,
                            needsRelogin = authState.needsRelogin,
                            onSyncClick = { syncManager.triggerImmediateSync() },
                            onReloginClick = {
                                // Force sign out to trigger re-authentication
                                authViewModel.signOut()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        // Theme toggle
                        IconButton(onClick = { coroutineScope.launch { themeManager.toggleTheme() } }) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Thème",
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
                            // BATTERY OPTIMIZATION (2026-01): Use unified sync_changes() via WorkManager
                            // instead of legacy sync methods which bypass atomic sync and waste battery
                            syncManager.triggerImmediateSync()

                            // Sync horaires de travail (settings are loaded locally, not synced via sync_changes)
                            currentUser?.let { user ->
                                val settings = workScheduleRepository.getAutoTrackingSettings(user.id)
                                val supabaseMode = settings?.trackingMode ?: TrackingMode.DISABLED
                                tripRepository.setTrackingMode(supabaseMode)
                                trackingMode = supabaseMode
                                val schedules = workScheduleRepository.getWorkSchedules(user.id)
                                hasWorkSchedules = schedules.isNotEmpty()
                            }

                            // Recharger les trips depuis Room (la sync mettra à jour Room en background)
                            currentOffset = 0
                            val refreshedTrips = tripRepository.getTripsPaginated(limit = 10, offset = 0)
                            trips = refreshedTrips
                            currentOffset = refreshedTrips.size
                            hasMoreTrips = refreshedTrips.size == 10

                            MotiumApplication.logger.i("Pull-to-refresh completed: ${trips.size} trips loaded", "HomeScreen")
                        } catch (e: CancellationException) {
                            // Normal when user navigates away during refresh - don't log as error
                            MotiumApplication.logger.d("Refresh cancelled (user left screen)", "HomeScreen")
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
                // 1. GROS BOUTON VERT "AJOUTER UN TRAJET"
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
                            "Ajouter un trajet",
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
                                            val existingSettings = workScheduleRepository.getAutoTrackingSettings(user.id)
                                            val settings = AutoTrackingSettings(
                                                id = existingSettings?.id ?: java.util.UUID.randomUUID().toString(),
                                                userId = user.id,
                                                trackingMode = newMode,
                                                minTripDistanceMeters = existingSettings?.minTripDistanceMeters ?: 100,
                                                minTripDurationSeconds = existingSettings?.minTripDurationSeconds ?: 60,
                                                createdAt = existingSettings?.createdAt
                                                    ?: Instant.fromEpochMilliseconds(System.currentTimeMillis()),
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
                                                        ActivityRecognitionHealthWorker.schedule(context)
                                                        MotiumApplication.logger.i("ALWAYS mode: Auto-tracking permanently enabled", "HomeScreen")
                                                    }
                                                    TrackingMode.WORK_HOURS_ONLY -> {
                                                        AutoTrackingScheduleWorker.schedule(context)
                                                        AutoTrackingScheduleWorker.runNow(context)
                                                        ActivityRecognitionHealthWorker.schedule(context)
                                                        MotiumApplication.logger.i("PRO mode: Auto-tracking managed by work schedule", "HomeScreen")
                                                    }
                                                    TrackingMode.DISABLED -> {
                                                        tripRepository.setAutoTrackingEnabled(false)
                                                        autoTrackingEnabled = false
                                                        ActivityRecognitionService.stopService(context)
                                                        AutoTrackingScheduleWorker.cancel(context)
                                                        ActivityRecognitionHealthWorker.cancel(context)
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
                                "Résumé quotidien",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatItem(String.format("%.1f", todayDistance / 1000), "Kilomètres", MotiumPrimary, textColor, subTextColor)
                                StatItem(String.format("%.2f€", todayIndemnities), "Indemnités", MotiumPrimary, textColor, subTextColor)
                                StatItem(todayTrips.size.toString(), "Trajets", MotiumPrimary, textColor, subTextColor)
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
                                        contentDescription = "Voir les dépenses",
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
                                        contentDescription = "Ajouter une dépense",
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
                        SwipeToDeleteTripCard(
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
                            onDeleteRequest = {
                                tripToDelete = trip
                                showDeleteConfirmDialog = true
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
                                "Aucun trajet pour le moment",
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

        // Bottom navigation is now handled at app-level in MainActivity
        if (isInitialLoading && trips.isEmpty()) {
            FullScreenLoading(message = "Chargement des trajets...")
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
            tonalElevation = 0.dp
        )
    }

    // Dialog de confirmation de suppression du trajet
    if (showDeleteConfirmDialog && tripToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                tripToDelete = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Supprimer ce trajet ?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column {
                    Text(
                        text = "Cette action est irréversible. Le trajet sera définitivement supprimé.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    tripToDelete?.let { trip ->
                        Text(
                            text = "${trip.startAddress ?: "Départ"} → ${trip.endAddress ?: "Arrivée"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = subTextColor
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        tripToDelete?.let { trip ->
                            coroutineScope.launch {
                                tripRepository.deleteTrip(trip.id)
                                // Remove from local list immediately for smooth UX
                                trips = trips.filter { it.id != trip.id }
                                showDeleteConfirmDialog = false
                                tripToDelete = null
                            }
                        }
                    }
                ) {
                    Text("Supprimer", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        tripToDelete = null
                    }
                ) {
                    Text("Annuler", color = Color.Gray)
                }
            },
            containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White,
            tonalElevation = 0.dp
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

/**
 * Wrapper composable that adds swipe-to-delete functionality to NewHomeTripCard.
 * Swipe right past the threshold and release to trigger delete confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteTripCard(
    trip: Trip,
    onClick: () -> Unit,
    onToggleValidation: () -> Unit,
    onDeleteRequest: () -> Unit,
    cardColor: Color,
    textColor: Color,
    subTextColor: Color
) {
    val hapticFeedback = LocalHapticFeedback.current
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // User swiped past threshold and released - trigger delete
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeleteRequest()
                    false // Return to normal position while dialog shows
                }
                else -> false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.3f }
    )

    // Track swipe progress for haptic feedback during swipe
    val currentProgress = dismissState.progress
    LaunchedEffect(currentProgress) {
        if (currentProgress > 0.3f && !hasTriggeredHaptic) {
            hasTriggeredHaptic = true
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (currentProgress < 0.2f && hasTriggeredHaptic) {
            hasTriggeredHaptic = false
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Delete background (revealed when swiping right)
            val color by animateColorAsState(
                when {
                    currentProgress > 0.3f -> Color(0xFFDC2626) // Full red past threshold
                    currentProgress > 0.1f -> Color(0xFFDC2626).copy(alpha = 0.6f)
                    else -> Color(0xFFDC2626).copy(alpha = 0.3f)
                },
                label = "delete_bg_color"
            )
            val iconScale by animateFloatAsState(
                when {
                    currentProgress > 0.3f -> 1.3f
                    currentProgress > 0.1f -> 1f + (currentProgress * 0.5f)
                    else -> 1f
                },
                label = "delete_icon_scale"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Supprimer",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .scale(iconScale)
                )
            }
        },
        content = {
            NewHomeTripCard(
                trip = trip,
                onClick = onClick,
                onToggleValidation = onToggleValidation,
                cardColor = cardColor,
                textColor = textColor,
                subTextColor = subTextColor
            )
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                        isCompact = true  // Small 83dp map on Home screen
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
                            text = trip.startAddress ?: "Départ inconnu",
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
                            text = trip.endAddress ?: "Arrivée inconnue",
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
                            uncheckedTrackColor = subTextColor.copy(alpha = 0.3f),
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


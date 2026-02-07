package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.TripRemoteDataSource
import com.application.motium.domain.model.Trip as DomainTrip
import com.application.motium.domain.model.TripType  // Used in conversion function
import com.application.motium.presentation.individual.home.NewHomeTripCard
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Convert domain.model.Trip to data.Trip for display with NewHomeTripCard
 */
private fun DomainTrip.toDataTrip(): Trip {
    return Trip(
        id = id,
        startTime = startTime.toEpochMilliseconds(),
        endTime = endTime?.toEpochMilliseconds(),
        locations = tracePoints?.map { point ->
            TripLocation(
                latitude = point.latitude,
                longitude = point.longitude,
                accuracy = point.accuracy ?: 0f,
                timestamp = point.timestamp.toEpochMilliseconds()
            )
        } ?: listOf(
            TripLocation(startLatitude, startLongitude, 0f, startTime.toEpochMilliseconds()),
            TripLocation(endLatitude ?: startLatitude, endLongitude ?: startLongitude, 0f, endTime?.toEpochMilliseconds() ?: startTime.toEpochMilliseconds())
        ),
        totalDistance = distanceKm * 1000, // Convert km to meters
        isValidated = isValidated,
        vehicleId = vehicleId,
        startAddress = startAddress,
        endAddress = endAddress,
        tripType = when (type) {
            TripType.PROFESSIONAL -> "PROFESSIONAL"
            TripType.PERSONAL -> "PERSONAL"
        },
        reimbursementAmount = reimbursementAmount,
        isWorkHomeTrip = isWorkHomeTrip,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        userId = userId
    )
}

/**
 * Screen displaying validated trips for a linked user
 * Reuses the NewHomeTripCard component for consistent display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedUserTripsScreen(
    userId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToTripDetails: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val themeManager = remember { ThemeManager.getInstance(context) }
    val linkedAccountRemoteDataSource = remember { LinkedAccountRemoteDataSource.getInstance(context) }
    val tripRemoteDataSource = remember { TripRemoteDataSource.getInstance(context) }

    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // Pagination constants
    val pageSize = 15

    // State - use data.Trip for compatibility with NewHomeTripCard
    var user by remember { mutableStateOf<LinkedUserDto?>(null) }
    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreTrips by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Lazy list state for infinite scroll detection
    val listState = rememberLazyListState()

    // Function to load more trips
    suspend fun loadMoreTrips() {
        if (isLoadingMore || !hasMoreTrips) return
        isLoadingMore = true

        try {
            val result = tripRemoteDataSource.getTripsWithPagination(
                userId = userId,
                limit = pageSize,
                offset = trips.size,
                validatedOnly = true
            )
            result.fold(
                onSuccess = { paginatedResult ->
                    trips = trips + paginatedResult.trips.map { it.toDataTrip() }
                    hasMoreTrips = paginatedResult.hasMore
                },
                onFailure = { /* Silently fail for pagination */ }
            )
        } finally {
            isLoadingMore = false
        }
    }

    // Detect when user scrolls near the end
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= totalItems - 5 // Load more when 5 items from the end
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && !isLoading && !isLoadingMore && hasMoreTrips) {
                loadMoreTrips()
            }
        }
    }

    // Load user and initial trips
    LaunchedEffect(userId) {
        isLoading = true
        try {
            // Load user info
            val userResult = linkedAccountRemoteDataSource.getLinkedUserById(userId)
            userResult.fold(
                onSuccess = { linkedUser ->
                    user = linkedUser
                },
                onFailure = { /* Continue without user name */ }
            )

            // Load first page of validated trips
            val tripsResult = tripRemoteDataSource.getTripsWithPagination(
                userId = userId,
                limit = pageSize,
                offset = 0,
                validatedOnly = true
            )
            tripsResult.fold(
                onSuccess = { paginatedResult ->
                    trips = paginatedResult.trips.map { it.toDataTrip() }
                    hasMoreTrips = paginatedResult.hasMore
                },
                onFailure = { e ->
                    error = e.message
                }
            )
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    // Group trips by date (data.Trip uses Long for startTime)
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
                else -> SimpleDateFormat("EEEE dd MMMM", Locale.FRENCH).format(Date(trip.startTime))
            }
            grouped[key] = (grouped[key] ?: emptyList()) + trip
        }
        grouped
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        user?.displayName ?: "Trajets",
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
        containerColor = backgroundColor
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MotiumPrimary)
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Error,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = ErrorRed
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Erreur: $error",
                            color = textSecondaryColor
                        )
                    }
                }
            }
            trips.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Route,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = textSecondaryColor
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Aucun trajet validé",
                            style = MaterialTheme.typography.titleMedium,
                            color = textSecondaryColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Ce collaborateur n'a pas encore de trajets validés.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondaryColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Summary stats
                    item {
                        TripsSummaryCard(
                            trips = trips,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }

                    // Grouped trips
                    groupedTrips.forEach { (dateLabel, tripsForDate) ->
                        item {
                            Text(
                                dateLabel,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }

                        items(
                            items = tripsForDate,
                            key = { trip -> trip.id }
                        ) { trip ->
                            // Reuse NewHomeTripCard - same component as Home page
                            NewHomeTripCard(
                                trip = trip,
                                onClick = { onNavigateToTripDetails(trip.id) },
                                onToggleValidation = { /* Read-only for Pro view */ },
                                cardColor = cardColor,
                                textColor = textColor,
                                subTextColor = textSecondaryColor
                            )
                        }
                    }

                    // Loading indicator when loading more trips
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MotiumPrimary,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TripsSummaryCard(
    trips: List<Trip>,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    // data.Trip uses totalDistance in meters, convert to km for display
    val totalDistance = trips.sumOf { it.totalDistance } / 1000.0
    val totalIndemnities = trips.sumOf { it.reimbursementAmount ?: 0.0 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Résumé des trajets validés",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryStatItem(
                    value = String.format("%.1f", totalDistance),
                    label = "Kilomètres",
                    color = MotiumPrimary,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
                SummaryStatItem(
                    value = String.format("%.2f €", totalIndemnities),
                    label = "Indemnités",
                    color = MotiumPrimary,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
                SummaryStatItem(
                    value = trips.size.toString(),
                    label = "Trajets",
                    color = MotiumPrimary,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }
        }
    }
}

@Composable
private fun SummaryStatItem(
    value: String,
    label: String,
    color: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = textSecondaryColor
        )
    }
}


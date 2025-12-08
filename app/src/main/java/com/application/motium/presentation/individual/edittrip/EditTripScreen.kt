package com.application.motium.presentation.individual.edittrip

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.data.TripRepository
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.domain.model.Vehicle
import com.application.motium.presentation.components.AddressAutocomplete
import com.application.motium.presentation.theme.MotiumPrimary
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.*

data class ExpenseItem(
    val id: String = UUID.randomUUID().toString(),
    val type: ExpenseType = ExpenseType.FUEL,
    val amount: String = "",
    val note: String = "",
    val photoUri: Uri? = null,
    val isExisting: Boolean = false // Track if this is an existing expense or new one
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTripScreen(
    tripId: String,
    onNavigateBack: () -> Unit,
    onTripUpdated: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val nominatimService = remember { NominatimService.getInstance() }
    val tripRepository = remember { TripRepository.getInstance(context) }
    val expenseRepository = remember { ExpenseRepository.getInstance(context) }
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }  // Room cache
    val authRepository = remember { SupabaseAuthRepository.getInstance(context) }
    val localUserRepository = remember { LocalUserRepository.getInstance(context) }

    // Auth state
    val authState by authRepository.authState.collectAsState(initial = com.application.motium.domain.model.AuthState())
    val currentUser = authState.user

    // Loading state
    var isLoading by remember { mutableStateOf(true) }
    var isCalculatingRoute by remember { mutableStateOf(false) }

    // Track if trip has real GPS trace (from automatic tracking)
    var hasRealGpsTrace by remember { mutableStateOf(false) }
    var originalStartCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var originalEndCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var originalDistanceKm by remember { mutableStateOf(0.0) }
    var startGapDistanceKm by remember { mutableStateOf(0.0) }
    var endGapDistanceKm by remember { mutableStateOf(0.0) }
    // Original GPS trace (immutable) - used to rebuild route with gaps
    var originalRouteCoordinates by remember { mutableStateOf<List<List<Double>>?>(null) }

    // Trip fields
    var originalTrip by remember { mutableStateOf<Trip?>(null) }
    var startLocation by remember { mutableStateOf("") }
    var endLocation by remember { mutableStateOf("") }
    var startCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var endCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var routeCoordinates by remember { mutableStateOf<List<List<Double>>?>(null) }
    var distance by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(Date()) }
    var selectedTime by remember { mutableStateOf("") }
    var endTime by remember { mutableStateOf("") }
    var isProfessional by remember { mutableStateOf(true) }
    var isWorkHomeTrip by remember { mutableStateOf(false) }
    var considerFullDistance by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var selectedVehicleId by remember { mutableStateOf<String?>(null) }
    var availableVehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Supabase repository for restoring GPS trace if needed
    val supabaseTripRepository = remember { com.application.motium.data.supabase.SupabaseTripRepository.getInstance(context) }
    val secureSessionStorage = remember { com.application.motium.data.preferences.SecureSessionStorage(context) }

    // Load vehicles when currentUser becomes available (same logic as Add Trip)
    LaunchedEffect(currentUser?.id) {
        val userId = currentUser?.id
        if (!userId.isNullOrEmpty()) {
            try {
                MotiumApplication.logger.i("🚗 Loading vehicles for user: ${currentUser.email}", "EditTripScreen")
                val vehicles = vehicleRepository.getAllVehiclesForUser(userId)
                availableVehicles = vehicles
                if (vehicles.isNotEmpty()) {
                    MotiumApplication.logger.i("✅ Loaded ${vehicles.size} vehicles: ${vehicles.map { it.name }}", "EditTripScreen")
                } else {
                    MotiumApplication.logger.w("⚠️ No vehicles found for user", "EditTripScreen")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to load vehicles: ${e.message}", "EditTripScreen", e)
            }
        } else {
            MotiumApplication.logger.w("⚠️ No userId available yet (user=$currentUser)", "EditTripScreen")
        }
    }

    // Load trip and expenses
    // Include currentUser?.id as dependency to retry when auth becomes available
    LaunchedEffect(tripId, currentUser?.id) {
        coroutineScope.launch {
            // Load trip from local Room database
            val allTrips = tripRepository.getAllTrips()
            var trip = allTrips.firstOrNull { it.id == tripId }

            if (trip != null) {
                // Check if local trip has suspiciously few GPS points (might be corrupted)
                // Try to restore full GPS trace from Supabase if local has <= 5 points
                val localPointsCount = trip.locations.size
                if (localPointsCount <= 5) {
                    // Try to get userId from auth state, fallback to secure storage
                    val userId = currentUser?.id ?: secureSessionStorage.restoreSession()?.userId
                    MotiumApplication.logger.d("EditTripScreen: Checking GPS restoration - localPoints=$localPointsCount, userId=$userId", "EditTripScreen")
                    if (!userId.isNullOrEmpty()) {
                        MotiumApplication.logger.i("⚠️ Local trip has only $localPointsCount points, trying to restore from Supabase...", "EditTripScreen")
                        try {
                            val supabaseResult = supabaseTripRepository.getTripById(tripId, userId)
                            if (supabaseResult.isSuccess) {
                                val supabaseTrip = supabaseResult.getOrNull()
                                val supabasePointsCount = supabaseTrip?.tracePoints?.size ?: 0
                                if (supabasePointsCount > localPointsCount) {
                                    // Supabase has more points - convert to data Trip and use it
                                    MotiumApplication.logger.i("✅ Restored GPS trace from Supabase: $localPointsCount → $supabasePointsCount points, distance: ${supabaseTrip!!.distanceKm}km", "EditTripScreen")
                                    val restoredLocations = supabaseTrip.tracePoints!!.map { point ->
                                        TripLocation(
                                            latitude = point.latitude,
                                            longitude = point.longitude,
                                            accuracy = point.accuracy ?: 10.0f,
                                            timestamp = point.timestamp.toEpochMilliseconds()
                                        )
                                    }
                                    // Restore both locations AND distance from Supabase
                                    val restoredDistanceMeters = supabaseTrip.distanceKm * 1000
                                    trip = trip.copy(
                                        locations = restoredLocations,
                                        totalDistance = restoredDistanceMeters
                                    )
                                    MotiumApplication.logger.i("✅ Restored trip: ${restoredLocations.size} points, ${restoredDistanceMeters}m", "EditTripScreen")
                                } else {
                                    MotiumApplication.logger.i("Supabase has same or fewer points ($supabasePointsCount), keeping local", "EditTripScreen")
                                }
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Failed to restore from Supabase: ${e.message}", "EditTripScreen", e)
                        }
                    }
                }

                originalTrip = trip

                // Pre-fill trip data
                startLocation = trip.startAddress ?: ""
                endLocation = trip.endAddress ?: ""

                val firstLocation = trip.locations.firstOrNull()
                val lastLocation = trip.locations.lastOrNull()

                startCoordinates = firstLocation?.let { it.latitude to it.longitude }
                endCoordinates = lastLocation?.let { it.latitude to it.longitude }

                // Save original coordinates to detect significant changes
                originalStartCoordinates = startCoordinates
                originalEndCoordinates = endCoordinates

                // Load existing route coordinates from trip locations
                // Consider any trip with 2+ points as having a real GPS trace
                if (trip.locations.size >= 2) {
                    // Convert trip locations to route coordinates format [lon, lat]
                    val loadedRoute = trip.locations.map { location ->
                        listOf(location.longitude, location.latitude)
                    }
                    routeCoordinates = loadedRoute
                    originalRouteCoordinates = loadedRoute // Save immutable copy for gap calculations
                    hasRealGpsTrace = true
                    MotiumApplication.logger.i("✅ Loaded ${trip.locations.size} GPS trace points for editing", "EditTripScreen")
                } else {
                    hasRealGpsTrace = false
                    MotiumApplication.logger.i("ℹ️ Trip has only ${trip.locations.size} points (incomplete trip)", "EditTripScreen")
                }

                originalDistanceKm = trip.totalDistance / 1000.0
                distance = String.format("%.1f", originalDistanceKm)
                MotiumApplication.logger.i("📏 Loaded trip distance: ${trip.totalDistance}m (${distance}km)", "EditTripScreen")
                val durationMin = ((trip.endTime ?: trip.startTime) - trip.startTime) / 1000 / 60
                duration = durationMin.toString()

                selectedDate = Date(trip.startTime)
                selectedTime = timeFormat.format(Date(trip.startTime))
                endTime = trip.endTime?.let { timeFormat.format(Date(it)) } ?: ""

                notes = trip.notes ?: ""
                selectedVehicleId = trip.vehicleId
                isProfessional = trip.tripType == "PROFESSIONAL"
                isWorkHomeTrip = trip.isWorkHomeTrip

                // Load user setting for considerFullDistance
                try {
                    val user = localUserRepository.getLoggedInUser()
                    considerFullDistance = user?.considerFullDistance ?: false
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Could not load user settings: ${e.message}", "EditTripScreen")
                }
            } else {
                MotiumApplication.logger.e("Trip not found: $tripId", "EditTripScreen")
                Toast.makeText(context, "Trip not found", Toast.LENGTH_SHORT).show()
                onNavigateBack()
            }

            isLoading = false
        }
    }

    // Auto-recalculate route if distance seems wrong (for old trips with default 5km)
    // IMPORTANT: Skip if trip has real GPS trace - we don't want to overwrite it!
    LaunchedEffect(startCoordinates, endCoordinates, originalTrip?.totalDistance, hasRealGpsTrace) {
        val trip = originalTrip
        if (!isLoading && trip != null && startCoordinates != null && endCoordinates != null) {
            // NEVER recalculate if we have a real GPS trace - preserve it!
            if (hasRealGpsTrace) {
                MotiumApplication.logger.i("ℹ️ Trip has real GPS trace (${trip.locations.size} points), skipping auto-recalculation", "EditTripScreen")
                return@LaunchedEffect
            }

            val tripDistance = trip.totalDistance
            // If distance is suspiciously small (< 10km) but we have coordinates, recalculate
            if (tripDistance < 10000) {
                MotiumApplication.logger.i("⚠️ Distance seems incorrect (${tripDistance}m < 10km), recalculating route...", "EditTripScreen")
                isCalculatingRoute = true
                try {
                    val route = nominatimService.getRoute(
                        startCoordinates!!.first,
                        startCoordinates!!.second,
                        endCoordinates!!.first,
                        endCoordinates!!.second
                    )

                    if (route != null) {
                        routeCoordinates = route.coordinates
                        distance = String.format("%.1f", route.distance / 1000)
                        duration = String.format("%.0f", route.duration / 60)
                        MotiumApplication.logger.i("✅ Route recalculated: ${distance}km", "EditTripScreen")
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Route recalculation error: ${e.message}", "EditTripScreen", e)
                } finally {
                    isCalculatingRoute = false
                }
            }
        }
    }

    // Calculate distance between two GPS points in meters
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    // Rebuild route from original GPS trace with current gaps (start/end points)
    fun rebuildRouteWithGaps() {
        originalRouteCoordinates?.let { original ->
            var rebuilt = original.toList()

            // Prepend new start point if gap > 10m
            if (startGapDistanceKm > 0.01 && startCoordinates != null) {
                val newStartPoint = listOf(startCoordinates!!.second, startCoordinates!!.first) // [lon, lat]
                rebuilt = listOf(newStartPoint) + rebuilt
            }

            // Append new end point if gap > 10m
            if (endGapDistanceKm > 0.01 && endCoordinates != null) {
                val newEndPoint = listOf(endCoordinates!!.second, endCoordinates!!.first) // [lon, lat]
                rebuilt = rebuilt + listOf(newEndPoint)
            }

            routeCoordinates = rebuilt
            MotiumApplication.logger.i("📍 Route reconstruite: ${original.size} original + gaps = ${rebuilt.size} points", "EditTripScreen")
        }
    }

    // Update total distance = original + start gap + end gap
    fun updateTotalDistance() {
        val totalKm = originalDistanceKm + startGapDistanceKm + endGapDistanceKm
        distance = String.format("%.1f", totalKm)
        MotiumApplication.logger.i("📏 Distance mise à jour: ${originalDistanceKm}km (original) + ${startGapDistanceKm}km (départ) + ${endGapDistanceKm}km (arrivée) = ${totalKm}km", "EditTripScreen")
    }

    // Calculate gap from new departure to first GPS point and update route
    fun updateStartGap(newStart: Pair<Double, Double>) {
        if (hasRealGpsTrace && originalStartCoordinates != null) {
            val gapMeters = distanceBetween(
                newStart.first, newStart.second,
                originalStartCoordinates!!.first, originalStartCoordinates!!.second
            )
            startGapDistanceKm = gapMeters / 1000.0
            MotiumApplication.logger.i("📍 Gap départ: nouvelle adresse -> 1er point GPS = ${String.format("%.2f", startGapDistanceKm)}km", "EditTripScreen")

            // Rebuild entire route from original with all current gaps
            rebuildRouteWithGaps()
            updateTotalDistance()
        }
    }

    // Calculate gap from last GPS point to new arrival and update route
    fun updateEndGap(newEnd: Pair<Double, Double>) {
        if (hasRealGpsTrace && originalEndCoordinates != null) {
            val gapMeters = distanceBetween(
                originalEndCoordinates!!.first, originalEndCoordinates!!.second,
                newEnd.first, newEnd.second
            )
            endGapDistanceKm = gapMeters / 1000.0
            MotiumApplication.logger.i("📍 Gap arrivée: dernier point GPS -> nouvelle adresse = ${String.format("%.2f", endGapDistanceKm)}km", "EditTripScreen")

            // Rebuild entire route from original with all current gaps
            rebuildRouteWithGaps()
            updateTotalDistance()
        }
    }

    // Calculate route for manual trips (no GPS trace)
    fun calculateRoute() {
        if (!hasRealGpsTrace && startCoordinates != null && endCoordinates != null) {
            MotiumApplication.logger.i("🗺️ Calculating route for manual trip", "EditTripScreen")
            isCalculatingRoute = true
            coroutineScope.launch {
                try {
                    val route = nominatimService.getRoute(
                        startCoordinates!!.first,
                        startCoordinates!!.second,
                        endCoordinates!!.first,
                        endCoordinates!!.second
                    )

                    if (route != null) {
                        routeCoordinates = route.coordinates
                        originalDistanceKm = route.distance / 1000.0
                        distance = String.format("%.1f", originalDistanceKm)
                        duration = String.format("%.0f", route.duration / 60)

                        // Calculate end time
                        val startTimeDate = timeFormat.parse(selectedTime) ?: Date()
                        val endTimeDate = Date(startTimeDate.time + route.duration.toLong() * 1000)
                        endTime = timeFormat.format(endTimeDate)

                        MotiumApplication.logger.i("✅ Route calculated: ${distance}km, ${duration}min", "EditTripScreen")
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Route error: ${e.message}", "EditTripScreen", e)
                } finally {
                    isCalculatingRoute = false
                }
            }
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Edit Trip",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                MotiumApplication.logger.d("Save button clicked", "EditTripScreen")

                                if (startLocation.isBlank()) {
                                    Toast.makeText(context, "Please select a departure location", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }

                                if (endLocation.isBlank()) {
                                    Toast.makeText(context, "Please select an arrival location", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }

                                coroutineScope.launch {
                                    try {
                                        // Fix locale issue: replace comma with period before parsing
                                        val distanceStr = distance.replace(',', '.')
                                        val distanceKm = distanceStr.toDoubleOrNull() ?: 5.0
                                        MotiumApplication.logger.i("💾 Saving trip with distance: ${distance} (normalized: ${distanceStr}) -> ${distanceKm}km (${distanceKm * 1000}m)", "EditTripScreen")
                                        val durationMin = duration.toIntOrNull() ?: 15

                                        val timeArray = selectedTime.split(":")
                                        val hour = timeArray.getOrNull(0)?.toIntOrNull() ?: 12
                                        val minute = timeArray.getOrNull(1)?.toIntOrNull() ?: 0

                                        val calendar = Calendar.getInstance().apply {
                                            time = selectedDate
                                            set(Calendar.HOUR_OF_DAY, hour)
                                            set(Calendar.MINUTE, minute)
                                            set(Calendar.SECOND, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }

                                        val startTimeMs = calendar.timeInMillis
                                        val endTimeMs = startTimeMs + (durationMin * 60 * 1000)

                                        // Use route coordinates if available, otherwise fallback to start/end only
                                        val routePoints = routeCoordinates
                                        MotiumApplication.logger.i("💾 SAVE: routeCoordinates has ${routePoints?.size ?: 0} points, hasRealGpsTrace=$hasRealGpsTrace", "EditTripScreen")
                                        val locations = if (!routePoints.isNullOrEmpty()) {
                                            // Use route coordinates with interpolated timestamps
                                            val timeStep = (endTimeMs - startTimeMs) / routePoints.size.toDouble()
                                            routePoints.mapIndexed { index, coord ->
                                                TripLocation(
                                                    latitude = coord.getOrNull(1) ?: 0.0,
                                                    longitude = coord.getOrNull(0) ?: 0.0,
                                                    accuracy = 10.0f,
                                                    timestamp = (startTimeMs + (index * timeStep)).toLong()
                                                )
                                            }
                                        } else {
                                            // Fallback to 2-point route (straight line)
                                            listOf(
                                                TripLocation(
                                                    latitude = startCoordinates?.first ?: 0.0,
                                                    longitude = startCoordinates?.second ?: 0.0,
                                                    accuracy = 10.0f,
                                                    timestamp = startTimeMs
                                                ),
                                                TripLocation(
                                                    latitude = endCoordinates?.first ?: 0.0,
                                                    longitude = endCoordinates?.second ?: 0.0,
                                                    accuracy = 10.0f,
                                                    timestamp = endTimeMs
                                                )
                                            )
                                        }

                                        // Update trip
                                        val updatedTrip = originalTrip!!.copy(
                                            startTime = startTimeMs,
                                            endTime = endTimeMs,
                                            locations = locations,
                                            totalDistance = distanceKm * 1000,
                                            startAddress = startLocation,
                                            endAddress = endLocation,
                                            notes = notes.ifBlank { null },
                                            vehicleId = selectedVehicleId,
                                            tripType = if (isProfessional) "PROFESSIONAL" else "PERSONAL",
                                            isWorkHomeTrip = if (isProfessional) false else isWorkHomeTrip // Only for personal trips
                                        )

                                        MotiumApplication.logger.i("✅ Updated trip object: totalDistance=${updatedTrip.totalDistance}m, locations=${updatedTrip.locations.size} points", "EditTripScreen")
                                        tripRepository.saveTrip(updatedTrip)
                                        MotiumApplication.logger.i("✅ Trip saved to repository", "EditTripScreen")

                                        MotiumApplication.logger.i("Trip updated: $tripId", "EditTripScreen")
                                        Toast.makeText(context, "Trip updated successfully", Toast.LENGTH_SHORT).show()
                                        onTripUpdated()
                                    } catch (e: Exception) {
                                        MotiumApplication.logger.e("Error updating trip: ${e.message}", "EditTripScreen", e)
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Save"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Date fields
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DateTimeField(
                            label = "Departure Date",
                            value = "${dateFormat.format(selectedDate)} ${selectedTime}",
                            icon = Icons.Default.CalendarToday,
                            modifier = Modifier.weight(1f)
                        )
                        DateTimeField(
                            label = "End Date",
                            value = "${dateFormat.format(selectedDate)} ${endTime}",
                            icon = Icons.Default.CalendarToday,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Location fields
                item {
                    LocationField(
                        label = "Departure Location",
                        value = startLocation,
                        iconColor = MotiumPrimary,
                        onValueChange = { startLocation = it },
                        onAddressSelected = { result ->
                            MotiumApplication.logger.i("📍 Departure selected: ${result.display_name}", "EditTripScreen")
                            val newCoords = result.lat.toDouble() to result.lon.toDouble()
                            startCoordinates = newCoords
                            startLocation = result.display_name

                            if (hasRealGpsTrace) {
                                // Cumulative mode: calculate gap from new address to first GPS point
                                updateStartGap(newCoords)
                            } else if (endCoordinates != null) {
                                // Manual trip: recalculate full route
                                calculateRoute()
                            }
                        }
                    )
                }

                item {
                    LocationField(
                        label = "Arrival Location",
                        value = endLocation,
                        iconColor = Color.Red,
                        onValueChange = { endLocation = it },
                        onAddressSelected = { result ->
                            MotiumApplication.logger.i("📍 Arrival selected: ${result.display_name}", "EditTripScreen")
                            val newCoords = result.lat.toDouble() to result.lon.toDouble()
                            endCoordinates = newCoords
                            endLocation = result.display_name

                            if (hasRealGpsTrace) {
                                // Cumulative mode: calculate gap from last GPS point to new address
                                updateEndGap(newCoords)
                            } else if (startCoordinates != null) {
                                // Manual trip: recalculate full route
                                calculateRoute()
                            }
                        }
                    )
                }

                // Professional Trip toggle
                item {
                    ProfessionalTripToggle(
                        isProfessional = isProfessional,
                        onToggle = {
                            isProfessional = it
                            // Reset work-home when switching to professional
                            if (it) isWorkHomeTrip = false
                        }
                    )
                }

                // Work-Home Trip toggle (only for personal trips)
                if (!isProfessional) {
                    item {
                        WorkHomeTripToggle(
                            isWorkHomeTrip = isWorkHomeTrip,
                            onToggle = { isWorkHomeTrip = it },
                            distanceKm = distance.replace(',', '.').toDoubleOrNull() ?: 0.0,
                            considerFullDistance = considerFullDistance
                        )
                    }
                }

                // Vehicle Selection
                item {
                    VehicleSelector(
                        selectedVehicleId = selectedVehicleId,
                        availableVehicles = availableVehicles,
                        onVehicleSelected = { vehicleId ->
                            selectedVehicleId = vehicleId
                        }
                    )
                }

                // Distance and Duration
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReadOnlyField(
                            label = "Distance",
                            value = if (distance.isNotBlank()) "$distance km" else "",
                            icon = Icons.Default.Route,
                            modifier = Modifier.weight(1f)
                        )
                        ReadOnlyField(
                            label = "Duration",
                            value = if (duration.isNotBlank()) "$duration min" else "",
                            icon = Icons.Default.Schedule,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Notes section
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        placeholder = { Text("Add any notes for this trip...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            focusedLabelColor = MotiumPrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun DateTimeField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = MotiumPrimary
            )
        },
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            focusedLabelColor = MotiumPrimary
        )
    )
}

@Composable
fun ReadOnlyField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = MotiumPrimary
            )
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            focusedLabelColor = MotiumPrimary
        )
    )
}

@Composable
fun LocationField(
    label: String,
    value: String,
    iconColor: Color,
    onValueChange: (String) -> Unit,
    onAddressSelected: (com.application.motium.data.geocoding.NominatimResult) -> Unit
) {
    AddressAutocomplete(
        label = label,
        value = value,
        onValueChange = onValueChange,
        onAddressSelected = onAddressSelected,
        placeholder = "Enter address",
        leadingIcon = {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = iconColor
            )
        }
    )
}

@Composable
fun ProfessionalTripToggle(
    isProfessional: Boolean,
    onToggle: (Boolean) -> Unit
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
            Text(
                "Professional Trip",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                )
            )
            Switch(
                checked = isProfessional,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MotiumPrimary
                )
            )
        }
    }
}

/**
 * Toggle for marking a personal trip as work-home commute.
 * Shows a warning when distance exceeds 40km and considerFullDistance is disabled.
 */
@Composable
fun WorkHomeTripToggle(
    isWorkHomeTrip: Boolean,
    onToggle: (Boolean) -> Unit,
    distanceKm: Double,
    considerFullDistance: Boolean
) {
    val showDistanceWarning = isWorkHomeTrip && distanceKm > 40.0 && !considerFullDistance

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Trajet travail-maison",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        "Ouvre droit aux indemnités kilométriques",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = isWorkHomeTrip,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MotiumPrimary
                    )
                )
            }

            // Warning when distance exceeds 40km
            if (showDistanceWarning) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0) // Light orange
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Seuls 40 km seront retenus pour le calcul des indemnités (case \"Prendre en compte toute la distance\" non cochée dans les paramètres).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseItemRow(
    expense: ExpenseItem,
    onExpenseChange: (ExpenseItem) -> Unit,
    onRemove: () -> Unit
) {
    val expenseTypes = listOf(
        ExpenseType.FUEL to "Fuel",
        ExpenseType.HOTEL to "Hotel",
        ExpenseType.TOLL to "Tolls",
        ExpenseType.PARKING to "Parking",
        ExpenseType.RESTAURANT to "Restaurant",
        ExpenseType.MEAL_OUT to "Meals (out)",
        ExpenseType.OTHER to "Other"
    )

    var expanded by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            onExpenseChange(expense.copy(photoUri = it))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Type and Amount row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Type dropdown
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = expenseTypes.find { it.first == expense.type }?.second ?: "Fuel",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = {
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MotiumPrimary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(16.dp),
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            disabledLeadingIconColor = MotiumPrimary,
                            disabledTrailingIconColor = MotiumPrimary
                        )
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { expanded = !expanded }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        expenseTypes.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    onExpenseChange(expense.copy(type = type))
                                    expanded = false
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }

                // Amount field
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Amount",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = expense.amount,
                        onValueChange = { onExpenseChange(expense.copy(amount = it)) },
                        leadingIcon = { Text("€", fontWeight = FontWeight.Bold) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("0.00") },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            // Note field
            Column {
                Text(
                    text = "Note (Optional)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = expense.note,
                    onValueChange = { onExpenseChange(expense.copy(note = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., Gas station receipt") },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
            }

            // Photo and Remove buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (expense.photoUri != null) "Photo added" else "Add photo",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red
                    )
                ) {
                    Text("Remove", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun VehicleSelector(
    selectedVehicleId: String?,
    availableVehicles: List<Vehicle>,
    onVehicleSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    if (availableVehicles.isEmpty()) {
        OutlinedTextField(
            value = "No vehicles available",
            onValueChange = {},
            label = { Text("Vehicle") },
            enabled = false,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        )
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = availableVehicles.find { it.id == selectedVehicleId }?.name ?: "Select vehicle",
            onValueChange = {},
            label = { Text("Vehicle") },
            trailingIcon = {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
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
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            enabled = false,
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                disabledLeadingIconColor = MotiumPrimary,
                disabledTrailingIconColor = MotiumPrimary
            )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = !expanded }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            // Option to select no vehicle
            DropdownMenuItem(
                text = {
                    Text(
                        "No vehicle",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    onVehicleSelected(null)
                    expanded = false
                },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            )

            HorizontalDivider()

            // Vehicle options
            availableVehicles.forEach { vehicle ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                vehicle.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${vehicle.type.displayName}${vehicle.licensePlate?.let { " • $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onVehicleSelected(vehicle.id)
                        expanded = false
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                )
            }
        }
    }
}

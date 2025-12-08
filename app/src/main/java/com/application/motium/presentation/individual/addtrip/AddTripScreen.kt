package com.application.motium.presentation.individual.addtrip

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
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.domain.model.AuthState
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.domain.model.Vehicle
import com.application.motium.presentation.components.AddressAutocomplete
import com.application.motium.presentation.components.MiniMap
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
    val photoUri: Uri? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTripScreen(
    onNavigateBack: () -> Unit,
    onTripSaved: (Trip) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val nominatimService = remember { NominatimService.getInstance() }
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }  // Room cache
    val authRepository = remember { SupabaseAuthRepository.getInstance(context) }
    val localUserRepository = remember { LocalUserRepository.getInstance(context) }

    val authState by authRepository.authState.collectAsState(initial = AuthState())
    val currentUser = authState.user

    // Trip fields
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
    var isCalculatingRoute by remember { mutableStateOf(false) }

    // Vehicle fields
    var availableVehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    var selectedVehicleId by remember { mutableStateOf<String?>(null) }

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Initialize time
    LaunchedEffect(Unit) {
        val now = Date()
        selectedTime = timeFormat.format(now)
        endTime = timeFormat.format(Date(now.time + (23 * 60 * 1000))) // +23 min

        // Load user setting for considerFullDistance
        try {
            val user = localUserRepository.getLoggedInUser()
            considerFullDistance = user?.considerFullDistance ?: false
        } catch (e: Exception) {
            MotiumApplication.logger.w("Could not load user settings: ${e.message}", "AddTripScreen")
        }
    }

    // Load vehicles when currentUser becomes available
    LaunchedEffect(currentUser?.id) {
        val userId = currentUser?.id
        if (!userId.isNullOrEmpty()) {
            try {
                MotiumApplication.logger.i("🚗 Loading vehicles for user: ${currentUser.email}", "AddTripScreen")
                val vehicles = vehicleRepository.getAllVehiclesForUser(userId)
                availableVehicles = vehicles
                // Select first vehicle by default
                if (vehicles.isNotEmpty()) {
                    selectedVehicleId = vehicles.first().id
                    MotiumApplication.logger.i("✅ Loaded ${vehicles.size} vehicles, selected: ${vehicles.first().name}", "AddTripScreen")
                } else {
                    MotiumApplication.logger.w("⚠️ No vehicles found for user", "AddTripScreen")
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Failed to load vehicles: ${e.message}", "AddTripScreen", e)
            }
        } else {
            MotiumApplication.logger.w("⚠️ No userId available yet (user=$currentUser)", "AddTripScreen")
        }
    }

    // Calculate route
    fun calculateRoute() {
        if (startCoordinates != null && endCoordinates != null) {
            MotiumApplication.logger.i("🗺️ Calculating route from ${startCoordinates} to ${endCoordinates}", "AddTripScreen")
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
                        distance = String.format("%.1f", route.distance / 1000)
                        duration = String.format("%.0f", route.duration / 60)

                        MotiumApplication.logger.i("✅ Route calculated: ${route.coordinates.size} points, ${route.distance/1000}km, ${route.duration/60}min", "AddTripScreen")

                        // Calculate end time
                        val startTimeDate = timeFormat.parse(selectedTime) ?: Date()
                        val endTimeDate = Date(startTimeDate.time + route.duration.toLong() * 1000)
                        endTime = timeFormat.format(endTimeDate)
                    } else {
                        MotiumApplication.logger.w("⚠️ Route calculation returned null", "AddTripScreen")
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Route error: ${e.message}", "AddTripScreen", e)
                } finally {
                    isCalculatingRoute = false
                }
            }
        } else {
            MotiumApplication.logger.w("⚠️ Cannot calculate route: start=$startCoordinates, end=$endCoordinates", "AddTripScreen")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Add Trip",
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
                            MotiumApplication.logger.d("Save button clicked", "AddTripScreen")
                            MotiumApplication.logger.d("Start location: '$startLocation'", "AddTripScreen")
                            MotiumApplication.logger.d("End location: '$endLocation'", "AddTripScreen")

                            if (startLocation.isBlank()) {
                                Toast.makeText(context, "Please select a departure location", Toast.LENGTH_SHORT).show()
                                MotiumApplication.logger.w("Start location is blank", "AddTripScreen")
                                return@IconButton
                            }

                            if (endLocation.isBlank()) {
                                Toast.makeText(context, "Please select an arrival location", Toast.LENGTH_SHORT).show()
                                MotiumApplication.logger.w("End location is blank", "AddTripScreen")
                                return@IconButton
                            }

                            try {
                                MotiumApplication.logger.d("Creating trip...", "AddTripScreen")

                                // Create trip
                                // Fix locale issue: replace comma with period before parsing
                                val distanceStr = distance.replace(',', '.')
                                val distanceKm = distanceStr.toDoubleOrNull() ?: 5.0
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

                                // Build locations list from route coordinates if available
                                val routePoints = routeCoordinates
                                MotiumApplication.logger.i("📍 Creating trip locations: routePoints=${routePoints?.size} points", "AddTripScreen")

                                val locations = if (!routePoints.isNullOrEmpty()) {
                                    MotiumApplication.logger.i("✅ Using route coordinates (${routePoints.size} points)", "AddTripScreen")
                                    // Use route coordinates to create intermediate points
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
                                    MotiumApplication.logger.w("⚠️ No route coordinates, using simple 2-point route", "AddTripScreen")
                                    // Fallback to simple 2-point route
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

                                val tripId = UUID.randomUUID().toString()
                                val trip = Trip(
                                    id = tripId,
                                    startTime = startTimeMs,
                                    endTime = endTimeMs,
                                    locations = locations,
                                    totalDistance = distanceKm * 1000,
                                    isValidated = false,
                                    vehicleId = selectedVehicleId,
                                    startAddress = startLocation,
                                    endAddress = endLocation,
                                    notes = notes.ifBlank { null },
                                    tripType = if (isProfessional) "PROFESSIONAL" else "PERSONAL",
                                    isWorkHomeTrip = if (isProfessional) false else isWorkHomeTrip // Only for personal trips
                                )

                                MotiumApplication.logger.d("Trip created: ${trip.id}", "AddTripScreen")
                                Toast.makeText(context, "Trip saved successfully", Toast.LENGTH_SHORT).show()
                                onTripSaved(trip)
                            } catch (e: Exception) {
                                MotiumApplication.logger.e("Error creating trip: ${e.message}", "AddTripScreen", e)
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
                        MotiumApplication.logger.i("📍 Departure selected: ${result.display_name}", "AddTripScreen")
                        MotiumApplication.logger.i("📍 Departure coords: lat=${result.lat}, lon=${result.lon}", "AddTripScreen")
                        startCoordinates = result.lat.toDouble() to result.lon.toDouble()
                        startLocation = result.display_name
                        MotiumApplication.logger.i("📍 endCoordinates=$endCoordinates - will calculate route: ${endCoordinates != null}", "AddTripScreen")
                        if (endCoordinates != null) calculateRoute()
                    }
                )
            }

            item {
                LocationField(
                    label = "Arrival Location",
                    value = endLocation,
                    iconColor = Color(0xFFEF4444),
                    onValueChange = { endLocation = it },
                    onAddressSelected = { result ->
                        MotiumApplication.logger.i("📍 Arrival selected: ${result.display_name}", "AddTripScreen")
                        MotiumApplication.logger.i("📍 Arrival coords: lat=${result.lat}, lon=${result.lon}", "AddTripScreen")
                        endCoordinates = result.lat.toDouble() to result.lon.toDouble()
                        endLocation = result.display_name
                        MotiumApplication.logger.i("📍 startCoordinates=$startCoordinates - will calculate route: ${startCoordinates != null}", "AddTripScreen")
                        if (startCoordinates != null) calculateRoute()
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

            // Vehicle selection
            item {
                VehicleSelectionField(
                    availableVehicles = availableVehicles,
                    selectedVehicleId = selectedVehicleId,
                    onVehicleSelected = { selectedVehicleId = it }
                )
            }

            // Notes section
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = {
                        if (notes.isEmpty()) {
                            Text("Add any notes for this trip...")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Notes,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = MotiumPrimary
                    )
                )
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
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLabelColor = MotiumPrimary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary
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
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLabelColor = MotiumPrimary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedTextColor = MaterialTheme.colorScheme.onSurface
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
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Professional Trip",
                style = MaterialTheme.typography.bodyLarge.copy(
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
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Trajet travail-maison",
                        style = MaterialTheme.typography.bodyLarge.copy(
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
fun VehicleSelectionField(
    availableVehicles: List<Vehicle>,
    selectedVehicleId: String?,
    onVehicleSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    if (availableVehicles.isEmpty()) {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Vehicle") },
            placeholder = { Text("No vehicles available") },
            leadingIcon = {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = MotiumPrimary.copy(alpha = 0.5f)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = availableVehicles.find { it.id == selectedVehicleId }?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Vehicle") },
                placeholder = {
                    if (selectedVehicleId == null) {
                        Text("Select a vehicle")
                    }
                },
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = false,
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                availableVehicles.forEach { vehicle ->
                    DropdownMenuItem(
                        text = { Text(vehicle.name, color = MaterialTheme.colorScheme.onSurface) },
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
                        leadingIcon = {
                            Text(
                                "$",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        },
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

            // Remove button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFEF4444)
                    )
                ) {
                    Text("Remove", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

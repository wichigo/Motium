package com.application.motium.presentation.enterprise.tripdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripRepository
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.domain.model.Vehicle
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.theme.*
import com.application.motium.data.geocoding.NominatimService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterpriseTripDetailsScreen(
    tripId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToEdit: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepository = remember { TripRepository.getInstance(context) }
    val expenseRepository = remember { ExpenseRepository.getInstance(context) }
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }  // Room cache

    // Utiliser authState de authViewModel
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user

    var trip by remember { mutableStateOf<Trip?>(null) }
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var expenses by remember { mutableStateOf<List<Expense>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }

    // Map matching: tracé "snap to road" pour affichage précis
    var matchedRouteCoordinates by remember { mutableStateOf<List<List<Double>>?>(null) }
    var isMapMatching by remember { mutableStateOf(false) }
    val nominatimService = remember { NominatimService.getInstance() }

    // Charger le trip et les expenses au démarrage
    LaunchedEffect(tripId) {
        coroutineScope.launch {
            val allTrips = tripRepository.getAllTrips()
            trip = allTrips.firstOrNull { it.id == tripId }

            // Load vehicle if trip has a vehicleId
            trip?.vehicleId?.let { vehicleId ->
                try {
                    val loadedVehicle = vehicleRepository.getVehicleById(vehicleId)
                    if (loadedVehicle != null) {
                        vehicle = loadedVehicle
                        MotiumApplication.logger.i("Loaded vehicle: ${loadedVehicle.name}", "TripDetailsScreen")
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.w("Error loading vehicle: ${e.message}", "TripDetailsScreen")
                }
            }

            // Load expenses for this trip from local cache (offline-first)
            try {
                val expenseList = expenseRepository.getExpensesForTrip(tripId)
                expenses = expenseList
                MotiumApplication.logger.i("Loaded ${expenseList.size} expenses for trip $tripId", "TripDetailsScreen")
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to load expenses: ${e.message}", "TripDetailsScreen", e)
            }

            // Map Matching: "Snap to Road" pour un tracé qui suit les vraies routes
            trip?.let { currentTrip ->
                if (currentTrip.locations.size >= 2) {
                    isMapMatching = true
                    try {
                        val gpsPoints = currentTrip.locations.map { loc ->
                            Pair(loc.latitude, loc.longitude)
                        }
                        val matched = nominatimService.matchRoute(gpsPoints)
                        if (matched != null && matched.isNotEmpty()) {
                            matchedRouteCoordinates = matched
                            MotiumApplication.logger.i(
                                "✅ Map matching: ${currentTrip.locations.size} GPS → ${matched.size} road points",
                                "EnterpriseTripDetailsScreen"
                            )
                        } else {
                            MotiumApplication.logger.w(
                                "Map matching returned null, using raw GPS",
                                "EnterpriseTripDetailsScreen"
                            )
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.w(
                            "Map matching error: ${e.message}, using raw GPS",
                            "EnterpriseTripDetailsScreen"
                        )
                    } finally {
                        isMapMatching = false
                    }
                }
            }

            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Trip Details",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Retour",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToEdit(tripId) }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Modifier",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = ErrorRed
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (trip == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Trip not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val currentTrip = trip!!
            val duration = (currentTrip.endTime ?: System.currentTimeMillis()) - currentTrip.startTime
            val minutes = duration / 1000 / 60

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                // Minimap en haut
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentTrip.locations.isNotEmpty()) {
                            val firstLocation = currentTrip.locations.first()
                            val lastLocation = currentTrip.locations.last()

                            // Utiliser les coordonnées "map matched" si disponibles,
                            // sinon fallback vers les points GPS bruts
                            val routeCoordinates = matchedRouteCoordinates
                                ?: currentTrip.locations.map { location ->
                                    listOf(location.longitude, location.latitude)
                                }

                            MiniMap(
                                startLatitude = firstLocation.latitude,
                                startLongitude = firstLocation.longitude,
                                endLatitude = lastLocation.latitude,
                                endLongitude = lastLocation.longitude,
                                routeCoordinates = routeCoordinates,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                "No route data",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Trip Summary Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Trip Summary",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            fontSize = 18.sp
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Date et heure
                        DetailRow(
                            label = "Date",
                            value = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
                                .format(Date(currentTrip.startTime))
                        )

                        DetailRow(
                            label = "Time",
                            value = "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTrip.startTime))} - " +
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTrip.endTime ?: System.currentTimeMillis()))
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Adresses
                        DetailRow(
                            label = "Start",
                            value = currentTrip.startAddress ?: "Unknown location"
                        )

                        DetailRow(
                            label = "End",
                            value = currentTrip.endAddress ?: "Unknown location"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Vehicle
                        if (vehicle != null) {
                            DetailRow(
                                label = "Vehicle",
                                value = "${vehicle?.name} (${vehicle?.type?.displayName})"
                            )
                        } else if (currentTrip.vehicleId != null) {
                            DetailRow(
                                label = "Vehicle",
                                value = "Loading..."
                            )
                        } else {
                            DetailRow(
                                label = "Vehicle",
                                value = "Not assigned"
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Distance et durée
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            StatItem(
                                label = "Distance",
                                value = currentTrip.getFormattedDistance()
                            )
                            StatItem(
                                label = "Duration",
                                value = "$minutes min"
                            )
                            StatItem(
                                label = "Avg Speed",
                                value = String.format("%.0f km/h",
                                    if (duration > 0) (currentTrip.totalDistance / (duration / 1000.0)) * 3.6 else 0.0
                                )
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Indemnités
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Indemnities",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                            )
                            Text(
                                "€${String.format("%.2f", currentTrip.totalDistance * 0.20 / 1000)}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 20.sp
                            )
                        }

                        // Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Status",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                            )

                            val statusColor = if (currentTrip.isValidated) ValidatedGreen else PendingOrange

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = statusColor.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    if (currentTrip.isValidated) "Validated" else "Pending",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Expense Notes Section
                if (expenses.isNotEmpty()) {
                    ExpenseNotesSection(
                        expenses = expenses,
                        onPhotoClick = { photoUri ->
                            selectedPhotoUri = photoUri
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Bouton Validate/Unvalidate
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val updatedTrip = currentTrip.copy(isValidated = !currentTrip.isValidated)
                            tripRepository.saveTrip(updatedTrip)
                            trip = updatedTrip
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentTrip.isValidated)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (currentTrip.isValidated) "Mark as Pending" else "Validate Trip",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Dialog de confirmation de suppression
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Trip") },
            text = { Text("Are you sure you want to delete this trip? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            trip?.let {
                                tripRepository.deleteTrip(it.id)
                                onNavigateBack()
                            }
                        }
                    }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Photo viewer dialog
    selectedPhotoUri?.let { photoUri ->
        AlertDialog(
            onDismissRequest = { selectedPhotoUri = null },
            title = { Text("Receipt Photo") },
            text = {
                Text(
                    "Photo URI: $photoUri",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedPhotoUri = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 18.sp
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
fun ExpenseNotesSection(
    expenses: List<Expense>,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Expense Notes",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                fontSize = 16.sp
            )

            expenses.forEach { expense ->
                ExpenseItem(
                    expense = expense,
                    onPhotoClick = onPhotoClick
                )
            }
        }
    }
}

@Composable
fun ExpenseItem(
    expense: Expense,
    onPhotoClick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = getExpenseIcon(expense.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = expense.getExpenseTypeLabel(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    fontSize = 14.sp
                )
                if (expense.note.isNotBlank()) {
                    Text(
                        text = expense.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            expense.photoUri?.let { photoUri ->
                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPhotoClick(photoUri) },
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Receipt",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Text(
                text = expense.getFormattedAmount(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                fontSize = 14.sp
            )
        }
    }
}

fun getExpenseIcon(type: ExpenseType): ImageVector {
    return when (type) {
        ExpenseType.FUEL -> Icons.Default.LocalGasStation
        ExpenseType.TOLL -> Icons.Default.Toll
        ExpenseType.RESTAURANT, ExpenseType.MEAL_OUT -> Icons.Default.Restaurant
        ExpenseType.PARKING -> Icons.Default.LocalParking
        ExpenseType.HOTEL -> Icons.Default.Hotel
        ExpenseType.OTHER -> Icons.Default.Receipt
    }
}

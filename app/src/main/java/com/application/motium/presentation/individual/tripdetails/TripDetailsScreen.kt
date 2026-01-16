package com.application.motium.presentation.individual.tripdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
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
import com.application.motium.data.TripLocation
import com.application.motium.data.TripRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.data.ExpenseRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.domain.model.Vehicle
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import com.application.motium.utils.MileageAllowanceCalculator
import com.application.motium.data.geocoding.NominatimAddress
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.domain.model.TripType
import com.application.motium.domain.model.VehicleType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailsScreen(
    tripId: String,
    linkedUserId: String? = null, // Optional: ID of linked user when viewing their trips as Pro owner
    onNavigateBack: () -> Unit = {},
    onNavigateToEdit: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepository = remember { TripRepository.getInstance(context) }
    val expenseRepository = remember { ExpenseRepository.getInstance(context) }
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }  // Room cache, not Supabase
    val themeManager = remember { ThemeManager.getInstance(context) }

    // Utiliser authState de authViewModel
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    var trip by remember { mutableStateOf<Trip?>(null) }
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }
    var showTripTypeDialog by remember { mutableStateOf(false) }

    // Map matching: tracé "snap to road" pour affichage précis
    var matchedRouteCoordinates by remember { mutableStateOf<List<List<Double>>?>(null) }
    var isMapMatching by remember { mutableStateOf(false) }
    val nominatimService = remember { NominatimService.getInstance() }
    val tripRemoteDataSource = remember { com.application.motium.data.supabase.TripRemoteDataSource.getInstance(context) }
    val secureSessionStorage = remember { com.application.motium.data.preferences.SecureSessionStorage(context) }

    // OFFLINE-FIRST: Charger le trip depuis Room Database
    // Fallback vers Supabase uniquement pour:
    // 1. Trajets de linked users (Pro viewing linked user's trip)
    // 2. Restauration de GPS trace corrompue en local
    LaunchedEffect(tripId, currentUser?.id, linkedUserId) {
        coroutineScope.launch {
            var loadedTrip: Trip? = null

            // STRATEGY 1: If linkedUserId is provided, fetch directly from Supabase (Pro viewing linked user's trip)
            if (!linkedUserId.isNullOrEmpty()) {
                MotiumApplication.logger.i("📥 Fetching linked user trip from Supabase: tripId=$tripId, linkedUserId=$linkedUserId", "TripDetailsScreen")
                try {
                    val supabaseResult = tripRemoteDataSource.getTripByIdForLinkedUser(tripId, linkedUserId)
                    if (supabaseResult.isSuccess) {
                        val supabaseTrip = supabaseResult.getOrNull()
                        if (supabaseTrip != null) {
                            // Convert domain Trip to data Trip
                            val traceLocations = supabaseTrip.tracePoints?.map { point ->
                                TripLocation(
                                    latitude = point.latitude,
                                    longitude = point.longitude,
                                    accuracy = point.accuracy ?: 10.0f,
                                    timestamp = point.timestamp.toEpochMilliseconds()
                                )
                            } ?: listOf(
                                TripLocation(supabaseTrip.startLatitude, supabaseTrip.startLongitude, 10f, supabaseTrip.startTime.toEpochMilliseconds()),
                                TripLocation(supabaseTrip.endLatitude ?: supabaseTrip.startLatitude, supabaseTrip.endLongitude ?: supabaseTrip.startLongitude, 10f, supabaseTrip.endTime?.toEpochMilliseconds() ?: supabaseTrip.startTime.toEpochMilliseconds())
                            )

                            loadedTrip = Trip(
                                id = supabaseTrip.id,
                                startTime = supabaseTrip.startTime.toEpochMilliseconds(),
                                endTime = supabaseTrip.endTime?.toEpochMilliseconds(),
                                locations = traceLocations,
                                totalDistance = supabaseTrip.distanceKm * 1000,
                                isValidated = supabaseTrip.isValidated,
                                vehicleId = supabaseTrip.vehicleId,
                                startAddress = supabaseTrip.startAddress,
                                endAddress = supabaseTrip.endAddress,
                                tripType = supabaseTrip.type.name,
                                reimbursementAmount = supabaseTrip.reimbursementAmount,
                                isWorkHomeTrip = supabaseTrip.isWorkHomeTrip,
                                createdAt = supabaseTrip.createdAt.toEpochMilliseconds(),
                                updatedAt = supabaseTrip.updatedAt.toEpochMilliseconds(),
                                userId = supabaseTrip.userId
                            )
                            MotiumApplication.logger.i("✅ Loaded linked user trip from Supabase: ${traceLocations.size} GPS points", "TripDetailsScreen")
                        }
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("❌ Failed to fetch linked user trip: ${e.message}", "TripDetailsScreen", e)
                }
            } else {
                // STRATEGY 2 (OFFLINE-FIRST): Load from local Room database first
                MotiumApplication.logger.i("📂 Loading trip from Room Database: tripId=$tripId", "TripDetailsScreen")
                loadedTrip = tripRepository.getTripById(tripId)

                // FALLBACK: Check if local trip has suspiciously few GPS points - try to restore from Supabase
                // This handles cases where local GPS trace was corrupted during sync
                if (loadedTrip != null && loadedTrip.locations.size <= 5) {
                    // Try to get userId from auth state, fallback to secure storage
                    val userId = currentUser?.id ?: secureSessionStorage.restoreSession()?.userId
                    MotiumApplication.logger.d("🔍 Checking GPS trace integrity - localPoints=${loadedTrip.locations.size}, userId=$userId", "TripDetailsScreen")
                    if (!userId.isNullOrEmpty()) {
                        MotiumApplication.logger.i("⚠️ Local trip has only ${loadedTrip.locations.size} points, trying to restore from Supabase (FALLBACK)...", "TripDetailsScreen")
                        try {
                            val supabaseResult = tripRemoteDataSource.getTripById(tripId, userId)
                            if (supabaseResult.isSuccess) {
                                val supabaseTrip = supabaseResult.getOrNull()
                                val supabasePointsCount = supabaseTrip?.tracePoints?.size ?: 0
                                if (supabasePointsCount > loadedTrip.locations.size) {
                                    MotiumApplication.logger.i("✅ GPS trace restored from Supabase: ${loadedTrip.locations.size} → $supabasePointsCount points, distance: ${supabaseTrip!!.distanceKm}km", "TripDetailsScreen")
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
                                    loadedTrip = loadedTrip.copy(
                                        locations = restoredLocations,
                                        totalDistance = restoredDistanceMeters
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("❌ Failed to restore GPS trace (will use local): ${e.message}", "TripDetailsScreen", e)
                        }
                    }
                }
            }

            trip = loadedTrip

            // Load vehicle if trip has a vehicleId (from Room cache)
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

            // Map Matching: "Snap to Road" pour un tracé qui suit les vraies routes
            trip?.let { currentTrip ->
                // Check if we already have cached map-matched coordinates
                if (!currentTrip.matchedRouteCoordinates.isNullOrBlank()) {
                    try {
                        val cached = kotlinx.serialization.json.Json.decodeFromString<List<List<Double>>>(currentTrip.matchedRouteCoordinates)
                        if (cached.isNotEmpty()) {
                            matchedRouteCoordinates = cached
                            MotiumApplication.logger.d(
                                "📍 Using cached map-matched route: ${cached.size} points",
                                "TripDetailsScreen"
                            )
                            isLoading = false
                            return@launch
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("Failed to parse cached coordinates, recalculating", "TripDetailsScreen")
                    }
                }

                // Calculate map-matching if not cached
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
                                "TripDetailsScreen"
                            )

                            // Cache the map-matched coordinates for future use (Home screen mini-maps)
                            try {
                                // Simple JSON serialization: [[lon,lat],[lon,lat],...]
                                val jsonCoords = matched.joinToString(",", "[", "]") { coord ->
                                    "[${coord[0]},${coord[1]}]"
                                }
                                val updatedTrip = currentTrip.copy(matchedRouteCoordinates = jsonCoords)
                                tripRepository.saveTrip(updatedTrip)
                                trip = updatedTrip
                                MotiumApplication.logger.d(
                                    "💾 Cached map-matched coordinates (${jsonCoords.length} chars)",
                                    "TripDetailsScreen"
                                )
                            } catch (e: Exception) {
                                MotiumApplication.logger.w("Failed to cache map-matched coords: ${e.message}", "TripDetailsScreen")
                            }
                        } else {
                            MotiumApplication.logger.w(
                                "Map matching returned null, using raw GPS",
                                "TripDetailsScreen"
                            )
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.w(
                            "Map matching error: ${e.message}, using raw GPS",
                            "TripDetailsScreen"
                        )
                    } finally {
                        isMapMatching = false
                    }
                }
            }

            isLoading = false
        }
    }

    // Couleurs dynamiques
    val backgroundColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF3F4F6)
    val cardColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF1F2937)
    val subTextColor = if (isDarkMode) Color.Gray else Color(0xFF6B7280)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Détails du trajet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                actions = {
                    IconButton(onClick = { onNavigateToEdit(tripId) }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Modifier",
                            tint = MotiumPrimary
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = Color(0xFFEF4444)
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
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MotiumPrimary)
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
                    .background(backgroundColor)
                    .verticalScroll(rememberScrollState())
            ) {
                // Minimap en haut
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(220.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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

                            // Indicateur de chargement du map matching
                            if (isMapMatching) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp),
                                    strokeWidth = 2.dp,
                                    color = MotiumPrimary
                                )
                            }
                        } else {
                            Text(
                                "No route data",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Trip Summary Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardColor
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header avec titre et badge type
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Résumé du trajet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                fontSize = 20.sp,
                                color = textColor
                            )

                            // Badge Type de trajet (Pro/Perso)
                            Surface(
                                modifier = Modifier.clickable { showTripTypeDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                color = when (currentTrip.tripType) {
                                    "PROFESSIONAL" -> MotiumPrimary.copy(alpha = 0.15f)
                                    "PERSONAL" -> Color(0xFF3B82F6).copy(alpha = 0.15f)
                                    else -> Color.Gray.copy(alpha = 0.15f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = when (currentTrip.tripType) {
                                            "PROFESSIONAL" -> Icons.Default.Work
                                            "PERSONAL" -> Icons.Default.Person
                                            else -> Icons.Default.QuestionMark
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = when (currentTrip.tripType) {
                                            "PROFESSIONAL" -> MotiumPrimary
                                            "PERSONAL" -> Color(0xFF3B82F6)
                                            else -> Color.Gray
                                        }
                                    )
                                    Text(
                                        when (currentTrip.tripType) {
                                            "PROFESSIONAL" -> "Pro"
                                            "PERSONAL" -> "Perso"
                                            else -> "Non défini"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        fontSize = 13.sp,
                                        color = when (currentTrip.tripType) {
                                            "PROFESSIONAL" -> MotiumPrimary
                                            "PERSONAL" -> Color(0xFF3B82F6)
                                            else -> Color.Gray
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = subTextColor.copy(alpha = 0.2f))

                        // Date et heure
                        DetailRow(
                            label = "Date",
                            value = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
                                .format(Date(currentTrip.startTime)),
                            textColor = textColor,
                            subTextColor = subTextColor
                        )

                        DetailRow(
                            label = "Heure",
                            value = "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTrip.startTime))} - " +
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTrip.endTime ?: System.currentTimeMillis())),
                            textColor = textColor,
                            subTextColor = subTextColor
                        )

                        HorizontalDivider(color = subTextColor.copy(alpha = 0.2f))

                        // Adresses (reformatées en format court)
                        DetailRow(
                            label = "Départ",
                            value = NominatimAddress.simplifyLegacyAddress(currentTrip.startAddress),
                            textColor = textColor,
                            subTextColor = subTextColor
                        )

                        DetailRow(
                            label = "Arrivée",
                            value = NominatimAddress.simplifyLegacyAddress(currentTrip.endAddress),
                            textColor = textColor,
                            subTextColor = subTextColor
                        )

                        HorizontalDivider(color = subTextColor.copy(alpha = 0.2f))

                        // Vehicle
                        if (vehicle != null) {
                            DetailRow(
                                label = "Véhicule",
                                value = "${vehicle?.name} (${vehicle?.type?.displayName})",
                                textColor = textColor,
                                subTextColor = subTextColor
                            )
                        } else if (currentTrip.vehicleId != null) {
                            DetailRow(
                                label = "Véhicule",
                                value = "Chargement...",
                                textColor = textColor,
                                subTextColor = subTextColor
                            )
                        } else {
                            DetailRow(
                                label = "Véhicule",
                                value = "Non assigné",
                                textColor = textColor,
                                subTextColor = subTextColor
                            )
                        }

                        HorizontalDivider(color = subTextColor.copy(alpha = 0.2f))

                        // Distance et durée
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            StatItem(
                                label = "Distance",
                                value = currentTrip.getFormattedDistance(),
                                subTextColor = subTextColor
                            )
                            StatItem(
                                label = "Durée",
                                value = "$minutes min",
                                subTextColor = subTextColor
                            )
                            StatItem(
                                label = "Vitesse moy.",
                                value = String.format("%.0f km/h",
                                    if (duration > 0) (currentTrip.totalDistance / (duration / 1000.0)) * 3.6 else 0.0
                                ),
                                subTextColor = subTextColor
                            )
                        }

                        HorizontalDivider(color = subTextColor.copy(alpha = 0.2f))

                        // Indemnités kilométriques - Utilise la valeur pré-calculée
                        val mileageCost = currentTrip.reimbursementAmount ?: 0.0

                        // Info sur la tranche actuelle (pour affichage)
                        val tripType = when (currentTrip.tripType) {
                            "PROFESSIONAL" -> TripType.PROFESSIONAL
                            "PERSONAL" -> TripType.PERSONAL
                            else -> TripType.PERSONAL
                        }
                        val previousAnnualKm = when (tripType) {
                            TripType.PROFESSIONAL -> vehicle?.totalMileagePro ?: 0.0
                            TripType.PERSONAL -> vehicle?.totalMileagePerso ?: 0.0
                        }
                        val bracketInfo = vehicle?.let {
                            MileageAllowanceCalculator.getCurrentBracketInfo(it.type, it.power, previousAnnualKm)
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "Indemnités kilométriques",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = textColor
                                    )
                                    if (bracketInfo != null && vehicle != null) {
                                        val isElectric = vehicle!!.fuelType == com.application.motium.domain.model.FuelType.ELECTRIC
                                        Text(
                                            buildString {
                                                append(vehicle!!.power?.displayName ?: "5 CV")
                                                append(" • ")
                                                append(bracketInfo.bracketName)
                                                if (isElectric) append(" • +20% électrique")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isElectric) MotiumPrimary else subTextColor,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Text(
                                    "€${String.format("%.2f", mileageCost)}",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MotiumPrimary,
                                    fontSize = 22.sp
                                )
                            }

                            // Afficher le taux effectif (avec majoration électrique si applicable)
                            if (bracketInfo != null && vehicle != null) {
                                val isElectric = vehicle!!.fuelType == com.application.motium.domain.model.FuelType.ELECTRIC
                                val effectiveRate = if (isElectric) bracketInfo.currentRate * 1.20 else bracketInfo.currentRate
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (isElectric) "Taux majoré (+20%)" else "Taux appliqué",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subTextColor
                                    )
                                    Text(
                                        "${String.format("%.3f", effectiveRate)} €/km",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = if (isElectric) MotiumPrimary else textColor
                                    )
                                }
                            }
                        }

                        // Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Statut",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = textColor
                            )

                            val statusColor = if (currentTrip.isValidated) MotiumPrimary else Color(0xFFF59E0B)

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = statusColor.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (currentTrip.isValidated) Icons.Default.CheckCircle else Icons.Default.AccessTime,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = statusColor
                                    )
                                    Text(
                                        if (currentTrip.isValidated) "Validé" else "En attente",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        fontSize = 13.sp,
                                        color = statusColor
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // MODIFIÉ: Section dépenses retirée (maintenant liées aux journées)

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
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentTrip.isValidated)
                            subTextColor.copy(alpha = 0.2f)
                        else
                            MotiumPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (currentTrip.isValidated) Icons.AutoMirrored.Filled.Undo else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (currentTrip.isValidated) subTextColor else Color.White
                        )
                        Text(
                            if (currentTrip.isValidated) "Marquer en attente" else "Valider le trajet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (currentTrip.isValidated) subTextColor else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Dialog pour changer le type de trajet
    if (showTripTypeDialog) {
        AlertDialog(
            onDismissRequest = { showTripTypeDialog = false },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    "Type de trajet",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Sélectionnez le type de trajet :",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Option Professionnel
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                coroutineScope.launch {
                                    trip?.let { currentTrip ->
                                        val updatedTrip = currentTrip.copy(tripType = "PROFESSIONAL")
                                        tripRepository.saveTrip(updatedTrip)
                                        trip = updatedTrip
                                    }
                                    showTripTypeDialog = false
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MotiumPrimary.copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp,
                            if (trip?.tripType == "PROFESSIONAL") MotiumPrimary else Color.Transparent
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Work,
                                contentDescription = null,
                                tint = MotiumPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    "Professionnel",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MotiumPrimary
                                )
                                Text(
                                    "Trajet dans le cadre du travail",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Option Personnel
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                coroutineScope.launch {
                                    trip?.let { currentTrip ->
                                        val updatedTrip = currentTrip.copy(tripType = "PERSONAL")
                                        tripRepository.saveTrip(updatedTrip)
                                        trip = updatedTrip
                                    }
                                    showTripTypeDialog = false
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF3B82F6).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp,
                            if (trip?.tripType == "PERSONAL") Color(0xFF3B82F6) else Color.Transparent
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    "Personnel",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFF3B82F6)
                                )
                                Text(
                                    "Trajet personnel ou privé",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTripTypeDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Dialog de confirmation de suppression
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    "Supprimer le trajet",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    "Êtes-vous sûr de vouloir supprimer ce trajet ? Cette action est irréversible.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
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
                    Text("Supprimer", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Photo viewer dialog
    selectedPhotoUri?.let { photoUri ->
        AlertDialog(
            onDismissRequest = { selectedPhotoUri = null },
            containerColor = Color.White,
            tonalElevation = 0.dp,
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
fun DetailRow(label: String, value: String, textColor: Color = Color.Unspecified, subTextColor: Color = Color.Gray) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = subTextColor,
            fontSize = 13.sp
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = textColor,
            fontSize = 16.sp
        )
    }
}

@Composable
fun StatItem(label: String, value: String, subTextColor: Color = Color.Gray) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MotiumPrimary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = subTextColor
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
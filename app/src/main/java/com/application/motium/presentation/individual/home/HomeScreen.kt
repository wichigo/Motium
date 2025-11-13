package com.application.motium.presentation.individual.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
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
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.TripLocation
import com.application.motium.data.TripRepository
import com.application.motium.data.geocoding.NominatimResult
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.AddressAutocomplete
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.components.TripLimitReachedDialog
import com.application.motium.presentation.theme.MotiumGreen
import com.application.motium.presentation.theme.ValidatedGreen
import com.application.motium.presentation.theme.PendingOrange
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.domain.model.*
import com.application.motium.data.supabase.SupabaseAuthRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.datetime.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepository = remember { TripRepository.getInstance(context) }
    val authRepository = remember { SupabaseAuthRepository.getInstance(context) }

    // État persistant du switch
    var autoTrackingEnabled by remember { mutableStateOf(tripRepository.isAutoTrackingEnabled()) }

    // États pour les données
    var recentTrips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var todayStats by remember { mutableStateOf("0.0" to "0") }

    // État utilisateur et premium
    var currentUser by remember { mutableStateOf<User?>(null) }
    val isPremium = currentUser?.isPremium() ?: false

    // États pour les dialogues
    var showAddTripDialog by remember { mutableStateOf(false) }
    var showPremiumDialog by remember { mutableStateOf(false) }
    var showTripLimitDialog by remember { mutableStateOf(false) }

    // État pour la synchronisation
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    // Charger les données au démarrage
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            // Charger l'utilisateur actuel
            currentUser = authRepository.getCurrentUser()

            // Charger les trajets
            recentTrips = tripRepository.getRecentTrips(10)
            val stats = tripRepository.getTripStats()
            todayStats = stats.getTodayDistanceKm() to stats.todayTrips.toString()
        }
    }

    // Démarrer le service au démarrage si l'auto-tracking est activé
    // NE PAS appeler setAutoTrackingEnabled ici pour éviter de désactiver l'auto-tracking sur recomposition
    LaunchedEffect(Unit) {
        if (autoTrackingEnabled) {
            MotiumApplication.logger.i("Auto-tracking enabled, starting service on screen load", "HomeScreen")
            ActivityRecognitionService.startService(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Bonjour, Individual",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "5",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                actions = {
                    // Bouton de synchronisation debug
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isSyncing = true
                                syncMessage = null
                                try {
                                    MotiumApplication.logger.i("🔄 Synchronisation manuelle démarrée", "HomeScreen")
                                    tripRepository.syncAllTripsToSupabase()
                                    syncMessage = "✅ Synchronisation réussie"
                                    MotiumApplication.logger.i("✅ Synchronisation manuelle terminée", "HomeScreen")
                                } catch (e: Exception) {
                                    syncMessage = "❌ Erreur: ${e.message}"
                                    MotiumApplication.logger.e("❌ Erreur synchronisation: ${e.message}", "HomeScreen", e)
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Synchroniser les trips",
                                tint = MotiumGreen
                            )
                        }
                    }

                    Text(
                        text = "Suivi\nauto",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = autoTrackingEnabled,
                        onCheckedChange = {
                            autoTrackingEnabled = it
                            // IMPORTANT: Sauvegarder l'état ET démarrer/arrêter le service
                            tripRepository.setAutoTrackingEnabled(it)
                            if (it) {
                                MotiumApplication.logger.i("Auto tracking enabled by user", "HomeScreen")
                                ActivityRecognitionService.startService(context)
                            } else {
                                MotiumApplication.logger.i("Auto tracking disabled by user", "HomeScreen")
                                ActivityRecognitionService.stopService(context)
                            }
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            MotiumBottomNavigation(
                currentRoute = "home",
                isPremium = isPremium,
                onNavigate = { route ->
                    when (route) {
                        "calendar" -> onNavigateToCalendar()
                        "vehicles" -> onNavigateToVehicles()
                        "export" -> onNavigateToExport()
                        "settings" -> onNavigateToSettings()
                    }
                },
                onPremiumFeatureClick = {
                    showPremiumDialog = true
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // Vérifier si l'utilisateur peut ajouter un trajet
                    if (currentUser?.canSaveTrip() == false) {
                        showTripLimitDialog = true
                    } else {
                        showAddTripDialog = true
                    }
                },
                containerColor = MotiumGreen,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ajouter un trajet")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Date header
            item {
                Text(
                    text = "03 juin 2025",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // Daily summary
            item {
                DailySummaryCard(
                    distance = todayStats.first,
                    tripCount = todayStats.second
                )
            }

            // Trip list
            if (recentTrips.isNotEmpty()) {
                items(recentTrips.take(5)) { trip ->
                    RealTripCard(trip = trip) {
                        // Rafraîchir les données quand un trajet est supprimé
                        coroutineScope.launch {
                            tripRepository.deleteTrip(trip.id)
                            recentTrips = tripRepository.getRecentTrips(10)
                            val stats = tripRepository.getTripStats()
                            todayStats = stats.getTodayDistanceKm() to stats.todayTrips.toString()
                        }
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aucun trajet enregistré\nActivez le suivi automatique",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Calendar section header
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "31 mai 2025",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // Previous day summary
            item {
                PreviousDaySummaryCard()
            }

            // Previous trips
            items(previousTrips) { trip ->
                TripCard(trip = trip)
            }
        }
    }

    // Dialogue d'ajout de trajet
    if (showAddTripDialog) {
        AddTripDialog(
            onDismiss = { showAddTripDialog = false },
            onTripAdded = { trip ->
                coroutineScope.launch {
                    try {
                        // Sauvegarder le trajet
                        tripRepository.saveTrip(trip)

                        // Incrémenter le compteur de trajets mensuels pour les comptes free
                        currentUser?.let { user ->
                            if (!user.isPremium()) {
                                val newCount = user.monthlyTripCount + 1
                                authRepository.updateMonthlyTripCount(user.id, newCount)
                                // Recharger l'utilisateur
                                currentUser = authRepository.getCurrentUser()
                            }
                        }

                        // Rafraîchir les données
                        recentTrips = tripRepository.getRecentTrips(10)
                        val stats = tripRepository.getTripStats()
                        todayStats = stats.getTodayDistanceKm() to stats.todayTrips.toString()

                        MotiumApplication.logger.i("Manual trip added successfully: ${trip.id}", "HomeScreen")
                        showAddTripDialog = false
                    } catch (e: Exception) {
                        MotiumApplication.logger.e("Error adding manual trip: ${e.message}", "HomeScreen", e)
                    }
                }
            }
        )
    }

    // Dialog pour fonctionnalité premium
    if (showPremiumDialog) {
        PremiumDialog(
            onDismiss = { showPremiumDialog = false },
            onUpgrade = {
                // TODO: Naviger vers l'écran d'abonnement
                onNavigateToSettings()
            },
            featureName = "l'export de données"
        )
    }

    // Dialog pour limite de trajets atteinte
    if (showTripLimitDialog) {
        currentUser?.let { user ->
            TripLimitReachedDialog(
                onDismiss = { showTripLimitDialog = false },
                onUpgrade = {
                    // TODO: Naviguer vers l'écran d'abonnement
                    onNavigateToSettings()
                },
                remainingTrips = user.getRemainingTrips() ?: 0,
                tripLimit = user.subscription.type.tripLimit ?: 10
            )
        }
    }
}

@Composable
fun DailySummaryCard(
    distance: String = "0.0",
    tripCount: String = "0"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
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
                SummaryItem(distance, "Kilomètres")
                SummaryItem("0.00€", "Indemnités") // TODO: Calculer les indemnités
                SummaryItem(tripCount, "Trajets")
            }
        }
    }
}

@Composable
fun PreviousDaySummaryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
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
                SummaryItem("220.9", "Kilomètres")
                SummaryItem("66.27€", "Indemnités")
                SummaryItem("4", "Trajets")
            }
        }
    }
}

@Composable
fun SummaryItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
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
fun TripCard(trip: SampleTrip) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
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
                    text = trip.time,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${trip.distance} • ${trip.duration}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = trip.route,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (trip.isValidated) ValidatedGreen else PendingOrange,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { /* TODO: Delete trip */ }) {
                    Text("🗑️", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun RealTripCard(trip: Trip, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
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
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (trip.isValidated) ValidatedGreen else PendingOrange,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Text("🗑️", fontSize = 16.sp)
                }
            }
        }
    }
}

// Sample data
data class SampleTrip(
    val time: String,
    val distance: String,
    val duration: String,
    val route: String,
    val isValidated: Boolean
)

val sampleTrips = listOf(
    SampleTrip(
        time = "00:00",
        distance = "100.3 km",
        duration = "78 min",
        route = "Lyon, Métropole de Lyon, Rhône, ... Chambéry, Savoie, Auvergne-Rhô...",
        isValidated = true
    ),
    SampleTrip(
        time = "00:00",
        distance = "775.8 km",
        duration = "497 min",
        route = "Marseille, Bouches-du-Rhône, Pro... Paris, Île-de-France, France métro...",
        isValidated = true
    )
)

val previousTrips = listOf(
    SampleTrip(
        time = "00:00",
        distance = "63.17 km",
        duration = "71 min",
        route = "Start Location 9ce8\nEnd Location 5da2",
        isValidated = false
    ),
    SampleTrip(
        time = "00:00",
        distance = "35.97 km",
        duration = "96 min",
        route = "Start Location 7f35\nEnd Location aa43",
        isValidated = true
    )
)

@Composable
fun AddTripDialog(
    onDismiss: () -> Unit,
    onTripAdded: (Trip) -> Unit
) {
    var startLocation by remember { mutableStateOf("") }
    var endLocation by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(Date()) }
    var selectedTime by remember { mutableStateOf("") }

    // Coordonnées des adresses sélectionnées
    var startCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var endCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var routeCoordinates by remember { mutableStateOf<List<List<Double>>?>(null) }
    var isCalculatingRoute by remember { mutableStateOf(false) }


    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val nominatimService = remember { NominatimService.getInstance() }
    val scope = rememberCoroutineScope()

    // Fonction pour calculer l'itinéraire
    fun calculateRoute() {
        if (startCoordinates != null && endCoordinates != null) {
            println("DEBUG: Calculating route manually")
            android.util.Log.d("DEBUG_ROUTE", "Calculating route manually")

            isCalculatingRoute = true
            scope.launch {
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

                        println("DEBUG: Route success - ${route.distance/1000}km, ${route.duration/60}min")
                        android.util.Log.d("DEBUG_ROUTE", "Route success - ${route.distance/1000}km, ${route.duration/60}min")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Route error: ${e.message}")
                    android.util.Log.e("DEBUG_ROUTE", "Route error", e)
                } finally {
                    isCalculatingRoute = false
                }
            }
        }
    }

    // Initialiser l'heure actuelle
    LaunchedEffect(Unit) {
        selectedTime = timeFormat.format(Date())
    }


    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "Ajouter un trajet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Lieu de départ avec autocomplétion
                item {
                    AddressAutocomplete(
                        label = "Lieu de départ",
                        value = startLocation,
                        onValueChange = { startLocation = it },
                        onAddressSelected = { result ->
                            val lat = result.lat.toDouble()
                            val lon = result.lon.toDouble()
                            startCoordinates = lat to lon
                            startLocation = result.display_name
                            println("DEBUG: Start coordinates set: $lat, $lon")
                            android.util.Log.d("DEBUG_ROUTE", "Start coordinates set: $lat, $lon")

                            // Déclencher le calcul si on a déjà l'adresse de fin
                            if (endCoordinates != null) {
                                calculateRoute()
                            }
                        }
                    )
                }

                // Lieu d'arrivée avec autocomplétion
                item {
                    AddressAutocomplete(
                        label = "Lieu d'arrivée",
                        value = endLocation,
                        onValueChange = { endLocation = it },
                        onAddressSelected = { result ->
                            val lat = result.lat.toDouble()
                            val lon = result.lon.toDouble()
                            endCoordinates = lat to lon
                            endLocation = result.display_name
                            println("DEBUG: End coordinates set: $lat, $lon")
                            android.util.Log.d("DEBUG_ROUTE", "End coordinates set: $lat, $lon")

                            // Déclencher le calcul si on a déjà l'adresse de départ
                            if (startCoordinates != null) {
                                calculateRoute()
                            }
                        }
                    )
                }

                // Mini-carte avec itinéraire
                item {
                    MiniMap(
                        startLatitude = startCoordinates?.first,
                        startLongitude = startCoordinates?.second,
                        endLatitude = endCoordinates?.first,
                        endLongitude = endCoordinates?.second,
                        routeCoordinates = routeCoordinates
                    )
                }

                // Indicateur de calcul d'itinéraire
                if (isCalculatingRoute) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Calcul de l'itinéraire...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }


                // Distance et durée
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Distance (auto-calculée)
                        OutlinedTextField(
                            value = distance,
                            onValueChange = { distance = it },
                            label = { Text("Distance (km)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            placeholder = { Text("Auto-calculé") }
                        )

                        // Durée (auto-calculée)
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it },
                            label = { Text("Durée (min)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            placeholder = { Text("Auto-calculé") }
                        )
                    }
                }

                // Date et heure
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Date
                        OutlinedTextField(
                            value = dateFormat.format(selectedDate),
                            onValueChange = { },
                            label = { Text("Date") },
                            modifier = Modifier.weight(1f),
                            readOnly = true,
                            trailingIcon = {
                                TextButton(onClick = { /* TODO: Date picker */ }) {
                                    Text("📅")
                                }
                            }
                        )

                        // Heure
                        OutlinedTextField(
                            value = selectedTime,
                            onValueChange = { selectedTime = it },
                            label = { Text("Heure") },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("14:30") }
                        )
                    }
                }

                // Boutons d'action
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Annuler")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (startLocation.isNotBlank() && endLocation.isNotBlank()) {

                                    try {
                                        // Utiliser des valeurs par défaut si distance/durée ne sont pas remplies
                                        val distanceKm = if (distance.isNotBlank()) {
                                            distance.replace(",", ".").toDouble()
                                        } else {
                                            5.0 // Valeur par défaut: 5 km
                                        }

                                        val durationMin = if (duration.isNotBlank()) {
                                            duration.toInt()
                                        } else {
                                            15 // Valeur par défaut: 15 minutes
                                        }

                                        // Calculer l'heure de début et de fin
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

                                        val startTime = calendar.timeInMillis
                                        val endTime = startTime + (durationMin * 60 * 1000)

                                        MotiumApplication.logger.i("Creating trip with addresses: start='$startLocation', end='$endLocation'", "AddTripDialog")

                                        // Create TripLocation objects for start and end
                                        val startTripLocation = TripLocation(
                                            latitude = startCoordinates?.first ?: 45.7640,
                                            longitude = startCoordinates?.second ?: 4.8357,
                                            accuracy = 10.0f,
                                            timestamp = startTime
                                        )

                                        val endTripLocation = TripLocation(
                                            latitude = endCoordinates?.first ?: 45.7640,
                                            longitude = endCoordinates?.second ?: 4.8357,
                                            accuracy = 10.0f,
                                            timestamp = endTime
                                        )

                                        val trip = Trip(
                                            id = UUID.randomUUID().toString(),
                                            startTime = startTime,
                                            endTime = endTime,
                                            locations = listOf(startTripLocation, endTripLocation),
                                            totalDistance = distanceKm * 1000, // Convert km to meters
                                            isValidated = false,
                                            vehicleId = null,
                                            startAddress = startLocation,
                                            endAddress = endLocation
                                        )

                                        onTripAdded(trip)

                                    } catch (e: Exception) {
                                        MotiumApplication.logger.e("Error creating manual trip: ${e.message}", "AddTripDialog", e)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MotiumGreen,
                                contentColor = Color.White
                            ),
                            enabled = startLocation.isNotBlank() && endLocation.isNotBlank()
                        ) {
                            Text("Ajouter")
                        }
                    }
                }
            }
        }
    }
}
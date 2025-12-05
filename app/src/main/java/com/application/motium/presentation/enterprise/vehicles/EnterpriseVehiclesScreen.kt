package com.application.motium.presentation.enterprise.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.domain.model.User
import com.application.motium.domain.model.Vehicle
import com.application.motium.domain.model.VehiclePower
import com.application.motium.domain.model.VehicleType
import com.application.motium.domain.model.isPremium
import com.application.motium.presentation.components.EnterpriseBottomNavigationSimple
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.presentation.theme.MotiumPrimaryTint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterpriseVehiclesScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    authViewModel: com.application.motium.presentation.auth.AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val viewModel: EnterpriseVehicleViewModel = viewModel { EnterpriseVehicleViewModel(context) }

    // Utiliser authState de authViewModel au lieu de créer une nouvelle instance
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val currentUserId = currentUser?.id

    val uiState by viewModel.uiState.collectAsState()
    val vehicles by viewModel.vehicles.collectAsState()

    var selectedVehicleId by remember { mutableStateOf<String?>(null) }
    var showAddVehicleScreen by remember { mutableStateOf(false) }

    // User and premium state
    val isPremium = currentUser?.isPremium() ?: false

    // Premium dialog state
    var showPremiumDialog by remember { mutableStateOf(false) }

    // Snackbar state for errors
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Show error in Snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = "❌ $error",
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    // Show success message in Snackbar
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = "✅ $message",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            viewModel.clearSuccessMessage()
        }
    }

    // Load vehicles when user ID changes
    LaunchedEffect(currentUserId) {
        // DEBUG: Log user information
        com.application.motium.MotiumApplication.logger.i(
            "VehiclesScreen - Current user ID: ${currentUserId ?: "NULL"}, Email: ${currentUser?.email ?: "NULL"}",
            "VehiclesScreen"
        )

        if (currentUserId != null) {
            com.application.motium.MotiumApplication.logger.i(
                "VehiclesScreen - User detected, ViewModel will load vehicles.",
                "VehiclesScreen"
            )
            // The ViewModel now loads vehicles automatically when the user is authenticated.
            // A manual call here can be used for force-refreshing when entering the screen.
            viewModel.loadVehicles()
        } else {
            com.application.motium.MotiumApplication.logger.e(
                "VehiclesScreen - No user ID found! User might not be authenticated",
                "VehiclesScreen",
                null
            )
        }
    }

    // DEBUG: Log when vehicles list changes
    LaunchedEffect(vehicles) {
        com.application.motium.MotiumApplication.logger.i(
            "VehiclesScreen - Vehicles list updated: ${vehicles.size} vehicles loaded",
            "VehiclesScreen"
        )
        vehicles.forEachIndexed { index, vehicle ->
            com.application.motium.MotiumApplication.logger.d(
                "  Vehicle $index: ${vehicle.name} (${vehicle.type.displayName}) - ID: ${vehicle.id}, Default: ${vehicle.isDefault}",
                "VehiclesScreen"
            )
        }
    }

    // DEBUG: Log UI state changes
    LaunchedEffect(uiState) {
        com.application.motium.MotiumApplication.logger.d(
            "VehiclesScreen - UI State: Loading=${uiState.isLoading}, Error=${uiState.error ?: "none"}, Success=${uiState.successMessage ?: "none"}",
            "VehiclesScreen"
        )
    }

    // Show add vehicle screen
    if (showAddVehicleScreen) {
        EnterpriseAddVehicleScreen(
            onNavigateBack = { showAddVehicleScreen = false },
            onAddVehicle = { name, type, licensePlate, power, fuelType, mileageRate, isDefault ->
                viewModel.addVehicle(name, type, licensePlate, power, fuelType, mileageRate, isDefault)
            }
        )
        return
    }

    // Show vehicle details if a vehicle is selected
    if (selectedVehicleId != null) {
        EnterpriseVehicleDetailsScreen(
            vehicleId = selectedVehicleId!!,
            onNavigateBack = { selectedVehicleId = null },
            onNavigateToHome = onNavigateToHome,
            onNavigateToCalendar = onNavigateToCalendar,
            onNavigateToExport = onNavigateToExport,
            onNavigateToSettings = onNavigateToSettings
        )
        return
    }

    Scaffold(
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Vehicles",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                actions = {
                    // Refresh button
                    androidx.compose.material3.IconButton(
                        onClick = {
                            com.application.motium.MotiumApplication.logger.i(
                                "VehiclesScreen - Manual refresh triggered by user",
                                "VehiclesScreen"
                            )
                            viewModel.loadVehicles()
                        }
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                            contentDescription = "Refresh vehicles",
                            tint = MotiumPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            EnterpriseBottomNavigationSimple(
                currentRoute = "enterprise_vehicles",
                isPremium = isPremium,
                onNavigate = { route ->
                    when (route) {
                        "enterprise_home" -> onNavigateToHome()
                        "enterprise_calendar" -> onNavigateToCalendar()
                        "enterprise_export" -> onNavigateToExport()
                        "enterprise_settings" -> onNavigateToSettings()
                    }
                },
                onPremiumFeatureClick = {
                    showPremiumDialog = true
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddVehicleScreen = true },
                containerColor = MotiumPrimary,
                contentColor = Color.White,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Vehicle",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Add Vehicle",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.End
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // DEBUG: Afficher le user_id actuel
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3CD)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "🐛 DEBUG INFO",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF856404)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "User ID: ${currentUserId ?: "NULL"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF856404),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Text(
                                text = "Email: ${currentUser?.email ?: "NULL"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF856404)
                            )
                            Text(
                                text = "Vehicles loaded: ${vehicles.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF856404)
                            )
                            Text(
                                text = "Premium: ${if (isPremium) "✅ Yes" else "❌ No"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF856404)
                            )

                            // Afficher un avertissement si non connecté
                            if (currentUserId == null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF856404).copy(alpha = 0.3f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "⚠️ NOT AUTHENTICATED",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFDC3545)
                                )
                                Text(
                                    text = "Your session has expired. Please sign in again.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF856404)
                                )
                            }
                        }
                    }
                }

                // Message si non connecté
                if (currentUserId == null && !uiState.isLoading) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF8D7DA)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "🔐",
                                    style = MaterialTheme.typography.displayMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Session Expired",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF721C24)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Your authentication session has expired. Please sign in again to access your vehicles.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF721C24),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        // Naviguer vers Settings pour se déconnecter puis reconnecter
                                        onNavigateToSettings()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFDC3545)
                                    )
                                ) {
                                    Text("Go to Settings", color = Color.White)
                                }
                            }
                        }
                    }
                }

                if (vehicles.isEmpty() && !uiState.isLoading && currentUserId != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "🚗",
                                    style = MaterialTheme.typography.displayMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No vehicles yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Add your first vehicle to get started",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                items(vehicles) { vehicle ->
                    ModernVehicleCard(
                        vehicle = vehicle,
                        onCardClick = { selectedVehicleId = vehicle.id },
                        onSetDefault = {
                            viewModel.setDefaultVehicle(vehicle.id)
                        }
                    )
                }
            }

            // Loading indicator
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MotiumPrimary)
                }
            }
        }
    }

    // Premium dialog
    if (showPremiumDialog) {
        PremiumDialog(
            onDismiss = { showPremiumDialog = false },
            onUpgrade = {
                // Navigate to settings
                onNavigateToSettings()
            },
            featureName = "l'export de données"
        )
    }
}

@Composable
fun ModernVehicleCard(
    vehicle: Vehicle,
    onCardClick: () -> Unit = {},
    onSetDefault: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vehicle icon with background
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotiumPrimaryTint),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (vehicle.type) {
                            VehicleType.CAR -> "🚗"
                            VehicleType.MOTORCYCLE -> "🏍️"
                            VehicleType.SCOOTER -> "🛵"
                            VehicleType.BIKE -> "🚲"
                        },
                        fontSize = 28.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Vehicle info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = vehicle.type.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = vehicle.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    if (vehicle.isDefault) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MotiumPrimary)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Default",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Chevron right
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = "Details",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }

        }
    }
}

fun calculateCurrentMileageRate(vehicle: Vehicle): String {
    val proMileage = vehicle.totalMileagePro

    // Pour les vélos, taux unique
    if (vehicle.type == VehicleType.BIKE) {
        return "0.25"
    }

    // Pour les motos et scooters
    if (vehicle.type == VehicleType.MOTORCYCLE || vehicle.type == VehicleType.SCOOTER) {
        // Supposons moto > 125cc pour simplifier
        return "0.395"
    }

    // Pour les voitures, utiliser le barème officiel avec tranches
    val power = vehicle.power ?: return "0.585" // Valeur par défaut

    return when {
        proMileage <= 5000 -> {
            // Tranche 0-5000 km
            when (power) {
                VehiclePower.CV_3 -> "0.537"
                VehiclePower.CV_4 -> "0.603"
                VehiclePower.CV_5 -> "0.631"
                VehiclePower.CV_6 -> "0.661"
                VehiclePower.CV_7_PLUS -> "0.685"
            }
        }
        proMileage <= 20000 -> {
            // Tranche 5001-20000 km
            when (power) {
                VehiclePower.CV_3 -> "0.291"
                VehiclePower.CV_4 -> "0.337"
                VehiclePower.CV_5 -> "0.356"
                VehiclePower.CV_6 -> "0.375"
                VehiclePower.CV_7_PLUS -> "0.394"
            }
        }
        else -> {
            // Tranche > 20000 km
            when (power) {
                VehiclePower.CV_3 -> "0.213"
                VehiclePower.CV_4 -> "0.245"
                VehiclePower.CV_5 -> "0.260"
                VehiclePower.CV_6 -> "0.273"
                VehiclePower.CV_7_PLUS -> "0.286"
            }
        }
    }
}

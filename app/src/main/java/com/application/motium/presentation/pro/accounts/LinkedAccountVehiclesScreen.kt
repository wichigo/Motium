package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
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
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.VehicleRemoteDataSource
import com.application.motium.domain.model.Vehicle
import com.application.motium.domain.model.VehicleType
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Screen displaying vehicles of a linked user (read-only)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedAccountVehiclesScreen(
    accountId: String,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val themeManager = remember { ThemeManager.getInstance(context) }
    val vehicleRemoteDataSource = remember { VehicleRemoteDataSource.getInstance(context) }
    val linkedAccountRemoteDataSource = remember { LinkedAccountRemoteDataSource.getInstance(context) }

    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // State
    var user by remember { mutableStateOf<LinkedUserDto?>(null) }
    var vehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load user info and vehicles
    LaunchedEffect(accountId) {
        isLoading = true
        try {
            // Load user info
            val userResult = linkedAccountRemoteDataSource.getLinkedUserById(accountId)
            userResult.fold(
                onSuccess = { linkedUser -> user = linkedUser },
                onFailure = { /* Continue anyway */ }
            )

            // Load vehicles
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val loadedVehicles = vehicleRemoteDataSource.getAllVehiclesForUser(accountId)
                    vehicles = loadedVehicles
                    MotiumApplication.logger.i("Loaded ${loadedVehicles.size} vehicles for user $accountId", "LinkedAccountVehicles")
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Failed to load vehicles: ${e.message}", "LinkedAccountVehicles", e)
                    error = e.message
                } finally {
                    isLoading = false
                }
            }
        } catch (e: Exception) {
            error = e.message
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Vehicules",
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        user?.let {
                            Text(
                                it.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondaryColor
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
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
                        Text(
                            text = "Erreur",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error ?: "Erreur inconnue",
                            color = textSecondaryColor
                        )
                    }
                }
            }
            vehicles.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "🚗",
                            style = MaterialTheme.typography.displayMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Aucun véhicule",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ce collaborateur n'a pas encore enregistre de vehicule",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondaryColor,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 24.dp,
                        bottom = 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Summary card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardColor)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatColumn(
                                    value = vehicles.size.toString(),
                                    label = "Vehicules",
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                                StatColumn(
                                    value = vehicles.count { it.type == VehicleType.CAR }.toString(),
                                    label = "Voitures",
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                                StatColumn(
                                    value = vehicles.count { it.type == VehicleType.MOTORCYCLE || it.type == VehicleType.SCOOTER }.toString(),
                                    label = "2 roues",
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                            }
                        }
                    }

                    // Vehicle cards
                    items(vehicles) { vehicle ->
                        LinkedAccountVehicleCard(
                            vehicle = vehicle,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MotiumPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = textSecondaryColor
        )
    }
}

@Composable
private fun LinkedAccountVehicleCard(
    vehicle: Vehicle,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
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
                        .clip(RoundedCornerShape(12.dp))
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vehicle.type.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        fontSize = 18.sp,
                        color = textColor
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = vehicle.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = textSecondaryColor
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
                                text = "Par defaut",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Vehicle details
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VehicleDetailItem(
                    label = "Puissance",
                    value = vehicle.power?.cv ?: "N/A",
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
                VehicleDetailItem(
                    label = "Carburant",
                    value = vehicle.fuelType?.displayName ?: "N/A",
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
                VehicleDetailItem(
                    label = "Taux/km",
                    value = String.format("%.3f €", vehicle.mileageRate),
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            // License plate if available
            vehicle.licensePlate?.let { plate ->
                if (plate.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(textSecondaryColor.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = textSecondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = plate,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleDetailItem(
    label: String,
    value: String,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = textSecondaryColor
        )
    }
}



package com.application.motium.presentation.individual.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
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
import com.application.motium.domain.model.Vehicle
import com.application.motium.domain.model.VehiclePower
import com.application.motium.domain.model.VehicleType
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.utils.ThemeManager

// A separate composable for the details, to keep VehiclesScreen cleaner
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailsScreen(
    vehicleId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: VehicleViewModel = viewModel { VehicleViewModel(context) }
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()
    val vehicles by viewModel.vehicles.collectAsState()

    val vehicle = remember(vehicles, vehicleId) {
        vehicles.firstOrNull { it.id == vehicleId }
    }

    // State for showing edit screen
    var showEditScreen by remember { mutableStateOf(false) }

    // Show edit screen if requested
    if (showEditScreen) {
        EditVehicleScreen(
            vehicleId = vehicleId,
            onNavigateBack = { showEditScreen = false },
            onVehicleUpdated = {
                // Reload vehicles after update
                viewModel.loadVehicles()
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Détails du véhicule",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditScreen = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Modifier",
                            tint = MotiumPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            MotiumBottomNavigation(
                currentRoute = "vehicles",
                onNavigate = { route ->
                    when (route) {
                        "home" -> onNavigateToHome()
                        "calendar" -> onNavigateToCalendar()
                        "export" -> onNavigateToExport()
                        "settings" -> onNavigateToSettings()
                    }
                },
                isDarkMode = isDarkMode
            )
        }
    ) { paddingValues ->
        if (vehicle == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                // To prevent flicker while vehicle is loading after a change
                CircularProgressIndicator(color = MotiumPrimary)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Vehicle Icon
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MotiumPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (vehicle.type) {
                            VehicleType.CAR -> "🚗"
                            VehicleType.MOTORCYCLE -> "🏍️"
                            VehicleType.SCOOTER -> "🛵"
                            VehicleType.BIKE -> "🚲"
                        },
                        fontSize = 60.sp
                    )
                }

                // Vehicle Name and Type
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = vehicle.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = vehicle.type.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    if (vehicle.isDefault) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MotiumPrimary.copy(alpha = 0.2f),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = "⭐ Par défaut",
                                color = MotiumPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Vehicle Information Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Informations du véhicule",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        VehicleInfoRow("Type", vehicle.type.displayName)
                        VehicleInfoRow("Plaque d'immatriculation", vehicle.licensePlate ?: "N/D")
                        VehicleInfoRow("Puissance fiscale", vehicle.power?.displayName ?: "N/D")
                        VehicleInfoRow("Type de carburant", vehicle.fuelType?.displayName ?: "N/D")
                    }
                }

                // Mileage Rates Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Kilométrage annuel et barèmes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Professional Section
                        val proRate = calculateCurrentMileageRate(vehicle, isProfessional = true)
                        VehicleInfoRow(
                            "Kilométrage Pro",
                            "${"%.1f".format(vehicle.totalMileagePro)} km"
                        )
                        VehicleInfoRow(
                            "Taux actuel Pro",
                            "$proRate €/km"
                        )
                        Text(
                            text = "Taux basé sur le barème fiscal 2025 selon le kilométrage annuel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Work-Home Trip Section (for fiscal allowance)
                        val workHomeRate = calculateWorkHomeMileageRate(vehicle)
                        VehicleInfoRow(
                            "Dist. travail-maison",
                            "${"%.1f".format(vehicle.totalMileageWorkHome)} km"
                        )
                        VehicleInfoRow(
                            "Taux travail-maison",
                            "$workHomeRate €/km"
                        )
                        Text(
                            text = "Trajets personnels domicile-travail. Distance plafonnée à 40 km par trajet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Personal Section (non-reimbursable)
                        VehicleInfoRow(
                            "Kilométrage Perso",
                            "${"%.1f".format(vehicle.totalMileagePerso)} km"
                        )
                        Text(
                            text = "Trajets personnels hors travail-maison (non remboursables).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )

                    }
                }

                // Delete Button
                Button(
                    onClick = {
                        viewModel.deleteVehicle(vehicle)
                        onNavigateBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFCDD2),
                        contentColor = Color(0xFFD32F2F)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "🗑️ Supprimer le véhicule",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun VehicleInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// Calcul du taux actuel basé sur le kilométrage travail-maison - Barème 2025
private fun calculateWorkHomeMileageRate(vehicle: Vehicle): String {
    val mileageKm = vehicle.totalMileageWorkHome

    // Pour les vélos, taux fixe
    if (vehicle.type == VehicleType.BIKE) {
        return "0.25"
    }

    // Pour les motos et scooters
    if (vehicle.type == VehicleType.MOTORCYCLE || vehicle.type == VehicleType.SCOOTER) {
        return "0.395"
    }

    // Pour les voitures, utiliser le barème officiel 2025
    val power = vehicle.power ?: return "N/D"

    // Barème kilométrique 2025 - valeurs directement en km
    return when {
        mileageKm <= 5000 -> {
            when (power) {
                VehiclePower.CV_3 -> "0.529"
                VehiclePower.CV_4 -> "0.606"
                VehiclePower.CV_5 -> "0.636"
                VehiclePower.CV_6 -> "0.665"
                VehiclePower.CV_7_PLUS -> "0.697"
            }
        }
        mileageKm <= 20000 -> {
            when (power) {
                VehiclePower.CV_3 -> "0.316"
                VehiclePower.CV_4 -> "0.340"
                VehiclePower.CV_5 -> "0.357"
                VehiclePower.CV_6 -> "0.374"
                VehiclePower.CV_7_PLUS -> "0.394"
            }
        }
        else -> { // > 20000 km
            when (power) {
                VehiclePower.CV_3 -> "0.370"
                VehiclePower.CV_4 -> "0.407"
                VehiclePower.CV_5 -> "0.427"
                VehiclePower.CV_6 -> "0.447"
                VehiclePower.CV_7_PLUS -> "0.470"
            }
        }
    }
}

// Calcul du taux actuel basé sur le kilométrage (pro ou perso) - Barème 2025
private fun calculateCurrentMileageRate(vehicle: Vehicle, isProfessional: Boolean): String {
    val mileageKm = if (isProfessional) vehicle.totalMileagePro else vehicle.totalMileagePerso

    // Pour les vélos, taux fixe
    if (vehicle.type == VehicleType.BIKE) {
        return "0.25"
    }

    // Pour les motos et scooters
    if (vehicle.type == VehicleType.MOTORCYCLE || vehicle.type == VehicleType.SCOOTER) {
        return "0.395"
    }

    // Pour les voitures, utiliser le barème officiel 2025
    val power = vehicle.power ?: return "N/D"

    // Barème kilométrique 2025 - valeurs directement en km (pas de conversion)
    return when {
        mileageKm <= 5000 -> {
            when (power) {
                VehiclePower.CV_3 -> "0.529"
                VehiclePower.CV_4 -> "0.606"
                VehiclePower.CV_5 -> "0.636"
                VehiclePower.CV_6 -> "0.665"
                VehiclePower.CV_7_PLUS -> "0.697"
            }
        }
        mileageKm <= 20000 -> {
            when (power) {
                VehiclePower.CV_3 -> "0.316"
                VehiclePower.CV_4 -> "0.340"
                VehiclePower.CV_5 -> "0.357"
                VehiclePower.CV_6 -> "0.374"
                VehiclePower.CV_7_PLUS -> "0.394"
            }
        }
        else -> { // > 20000 km
            when (power) {
                VehiclePower.CV_3 -> "0.370"
                VehiclePower.CV_4 -> "0.407"
                VehiclePower.CV_5 -> "0.427"
                VehiclePower.CV_6 -> "0.447"
                VehiclePower.CV_7_PLUS -> "0.470"
            }
        }
    }
}

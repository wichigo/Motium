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
import com.application.motium.presentation.theme.MotiumGreen

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
    val vehicles by viewModel.vehicles.collectAsState()

    val vehicle = remember(vehicles, vehicleId) {
        vehicles.firstOrNull { it.id == vehicleId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Vehicle Details",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Edit vehicle */ }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit"
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
                }
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
                CircularProgressIndicator(color = MotiumGreen)
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
                        .background(MotiumGreen.copy(alpha = 0.2f)),
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
                            color = MotiumGreen.copy(alpha = 0.2f),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = "⭐ Default",
                                color = MotiumGreen,
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
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Vehicle Information",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        VehicleInfoRow("Type", vehicle.type.displayName)
                        VehicleInfoRow("License Plate", vehicle.licensePlate ?: "N/A")
                        VehicleInfoRow("Fiscal Power", vehicle.power?.displayName ?: "N/A")
                        VehicleInfoRow("Fuel Type", vehicle.fuelType?.displayName ?: "N/A")
                    }
                }

                // Mileage Rates Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Annual Mileage & Rates",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Professional Section
                        val proRate = calculateCurrentMileageRate(vehicle, isProfessional = true)
                        VehicleInfoRow(
                            "Professional Mileage",
                            "${"%.1f".format(vehicle.totalMileagePro / 1000)} km"
                        )
                        VehicleInfoRow(
                            "Current Pro Rate",
                            "$proRate €/km"
                        )
                        Text(
                            text = "Based on the official scale for the current annual mileage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Personal Section
                        val persoRate = calculateCurrentMileageRate(vehicle, isProfessional = false)
                        VehicleInfoRow(
                            "Personal Mileage",
                            "${"%.1f".format(vehicle.totalMileagePerso / 1000)} km"
                        )
                        VehicleInfoRow(
                            "Current Personal Rate",
                            "$persoRate €/km"
                        )
                        Text(
                            text = "Personal mileage rate is indicative.",
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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "🗑️ Delete Vehicle",
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

// Recalculate the rate based on the correct mileage (pro or perso)
private fun calculateCurrentMileageRate(vehicle: Vehicle, isProfessional: Boolean): String {
    // For personal trips, you might have a fixed rate or different logic.
    // Here, we'll assume it's the same scale for demonstration, but it could be different.
    val mileage = if (isProfessional) vehicle.totalMileagePro else vehicle.totalMileagePerso

    // For bikes, the rate is fixed
    if (vehicle.type == VehicleType.BIKE) {
        return "0.25"
    }

    // For motorcycles and scooters
    if (vehicle.type == VehicleType.MOTORCYCLE || vehicle.type == VehicleType.SCOOTER) {
        // A more detailed implementation could check engine size
        return "0.395"
    }

    // For cars, use the official tiered rates
    val power = vehicle.power ?: return "N/A" // Default if power is not set

    val mileageKm = mileage / 1000 // Convert meters to km

    return when {
        mileageKm <= 5000 -> {
            when (power) {
                VehiclePower.CV_3 -> "0.537"
                VehiclePower.CV_4 -> "0.603"
                VehiclePower.CV_5 -> "0.631"
                VehiclePower.CV_6 -> "0.661"
                VehiclePower.CV_7_PLUS -> "0.685"
            }
        }
        mileageKm <= 20000 -> {
            when (power) {
                VehiclePower.CV_3 -> "0.291"
                VehiclePower.CV_4 -> "0.337"
                VehiclePower.CV_5 -> "0.356"
                VehiclePower.CV_6 -> "0.375"
                VehiclePower.CV_7_PLUS -> "0.394"
            }
        }
        else -> { // Over 20000 km
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
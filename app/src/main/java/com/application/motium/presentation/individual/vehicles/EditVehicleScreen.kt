package com.application.motium.presentation.individual.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.domain.model.*
import com.application.motium.presentation.theme.MotiumGreen
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.utils.FrenchMileageCalculator
import kotlinx.datetime.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditVehicleScreen(
    vehicleId: String,
    onNavigateBack: () -> Unit,
    onVehicleUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: VehicleViewModel = viewModel { VehicleViewModel(context) }
    val vehicles by viewModel.vehicles.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // Find the vehicle to edit
    val vehicle = remember(vehicles, vehicleId) {
        vehicles.firstOrNull { it.id == vehicleId }
    }

    // Form state - initialized from vehicle
    var name by remember(vehicle) { mutableStateOf(vehicle?.name ?: "") }
    var licensePlate by remember(vehicle) { mutableStateOf(vehicle?.licensePlate ?: "") }
    var selectedType by remember(vehicle) { mutableStateOf(vehicle?.type ?: VehicleType.CAR) }
    var selectedPower by remember(vehicle) { mutableStateOf(vehicle?.power) }
    var selectedFuelType by remember(vehicle) { mutableStateOf(vehicle?.fuelType) }
    var isDefault by remember(vehicle) { mutableStateOf(vehicle?.isDefault ?: false) }

    // Track if default was changed
    val originalIsDefault = remember(vehicle) { vehicle?.isDefault ?: false }

    // Calcul automatique du taux kilométrique
    val calculatedMileageRate = remember(selectedType, selectedPower, selectedFuelType, vehicle) {
        FrenchMileageCalculator.calculateMileageRate(
            selectedType,
            selectedPower,
            selectedFuelType,
            vehicle?.totalMileagePro ?: 0.0
        )
    }

    var showTypeDropdown by remember { mutableStateOf(false) }
    var showPowerDropdown by remember { mutableStateOf(false) }
    var showFuelTypeDropdown by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val nameFocusRequester = remember { FocusRequester() }
    val plateFocusRequester = remember { FocusRequester() }

    // Handle success - navigate back
    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) {
            onVehicleUpdated()
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Modifier le véhicule",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
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
                    IconButton(
                        onClick = {
                            if (vehicle != null && name.isNotBlank()) {
                                val updatedVehicle = vehicle.copy(
                                    name = name,
                                    type = selectedType,
                                    licensePlate = licensePlate.takeIf { it.isNotBlank() },
                                    power = selectedPower,
                                    fuelType = selectedFuelType,
                                    mileageRate = calculatedMileageRate,
                                    isDefault = isDefault,
                                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                                )
                                viewModel.updateVehicle(updatedVehicle)
                            }
                        },
                        enabled = name.isNotBlank() && !uiState.isLoading
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Enregistrer"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        if (vehicle == null) {
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MotiumGreen)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Default vehicle section - at top for visibility
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDefault) MotiumGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (isDefault) MotiumGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Véhicule par défaut",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDefault) MotiumGreen else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isDefault) "Utilisé pour les trajets auto-suivis" else "Définir comme véhicule par défaut pour l'auto-suivi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Switch(
                            checked = isDefault,
                            onCheckedChange = { isDefault = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MotiumGreen,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }

                if (isDefault && !originalIsDefault) {
                    Text(
                        text = "Le précédent véhicule par défaut sera retiré automatiquement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    placeholder = { Text("ex. Ma super voiture") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { plateFocusRequester.requestFocus() }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        focusedLabelColor = MotiumPrimary,
                        cursorColor = MotiumPrimary
                    )
                )

                // Vehicle type dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedType.displayName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = {
                            Icon(
                                if (showTypeDropdown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MotiumPrimary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
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
                            .clickable { showTypeDropdown = !showTypeDropdown }
                    )

                    DropdownMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false },
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        VehicleType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        type.displayName,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    selectedType = type
                                    showTypeDropdown = false
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }

                // License plate field
                OutlinedTextField(
                    value = licensePlate,
                    onValueChange = { licensePlate = it },
                    label = { Text("Plaque d'immatriculation") },
                    placeholder = { Text("ex. AB-123-CD") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(plateFocusRequester),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        focusedLabelColor = MotiumPrimary,
                        cursorColor = MotiumPrimary
                    )
                )

                // Power dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedPower?.displayName ?: "Sélectionner la puissance",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Puissance (en CV)") },
                        trailingIcon = {
                            Icon(
                                if (showPowerDropdown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MotiumPrimary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
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
                            .clickable { showPowerDropdown = !showPowerDropdown }
                    )

                    DropdownMenu(
                        expanded = showPowerDropdown,
                        onDismissRequest = { showPowerDropdown = false },
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Aucun",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                selectedPower = null
                                showPowerDropdown = false
                            },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        )
                        VehiclePower.values().forEach { power ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        power.displayName,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    selectedPower = power
                                    showPowerDropdown = false
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }

                // Fuel type dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedFuelType?.displayName ?: "Sélectionner le type de carburant",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Type de carburant") },
                        trailingIcon = {
                            Icon(
                                if (showFuelTypeDropdown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MotiumPrimary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
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
                            .clickable { showFuelTypeDropdown = !showFuelTypeDropdown }
                    )

                    DropdownMenu(
                        expanded = showFuelTypeDropdown,
                        onDismissRequest = { showFuelTypeDropdown = false },
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Aucun",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                selectedFuelType = null
                                showFuelTypeDropdown = false
                            },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        )
                        FuelType.values().forEach { fuel ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        fuel.displayName,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    selectedFuelType = fuel
                                    showFuelTypeDropdown = false
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }

                // Calculated mileage rate display
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MotiumPrimary.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Barème kilométrique (2025)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = String.format("%.3f \u20ac/km", calculatedMileageRate),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MotiumPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save button
                Button(
                    onClick = {
                        // Update the vehicle - updateVehicle() handles default logic automatically
                        val updatedVehicle = vehicle.copy(
                            name = name,
                            type = selectedType,
                            licensePlate = licensePlate.takeIf { it.isNotBlank() },
                            power = selectedPower,
                            fuelType = selectedFuelType,
                            mileageRate = calculatedMileageRate,
                            isDefault = isDefault,
                            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                        )
                        viewModel.updateVehicle(updatedVehicle)
                    },
                    enabled = name.isNotBlank() && !uiState.isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "Enregistrer les modifications",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}



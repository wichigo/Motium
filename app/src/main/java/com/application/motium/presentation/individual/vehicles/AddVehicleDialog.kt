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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.application.motium.domain.model.*
import com.application.motium.presentation.theme.MotiumGreen
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.utils.FrenchMileageCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVehicleScreen(
    onNavigateBack: () -> Unit,
    onAddVehicle: (
        name: String,
        type: VehicleType,
        licensePlate: String?,
        power: VehiclePower?,
        fuelType: FuelType?,
        mileageRate: Double,
        isDefault: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var licensePlate by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(VehicleType.CAR) }
    var selectedPower by remember { mutableStateOf<VehiclePower?>(null) }
    var selectedFuelType by remember { mutableStateOf<FuelType?>(null) }
    var isDefault by remember { mutableStateOf(false) }

    // Calcul automatique du taux kilométrique avec 0 km (véhicule neuf)
    val calculatedMileageRate = remember(selectedType, selectedPower, selectedFuelType) {
        FrenchMileageCalculator.calculateMileageRate(
            selectedType,
            selectedPower,
            selectedFuelType,
            0.0 // Véhicule commence avec 0 km
        )
    }

    var showTypeDropdown by remember { mutableStateOf(false) }
    var showPowerDropdown by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val nameFocusRequester = remember { FocusRequester() }
    val plateFocusRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Add Vehicle",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. My awesome car") },
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
                shape = RoundedCornerShape(8.dp)
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
                            text = { Text(type.displayName, color = MaterialTheme.colorScheme.onSurface) },
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
                label = { Text("License Plate") },
                placeholder = { Text("e.g. AB-123-CD") },
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
                shape = RoundedCornerShape(8.dp)
            )

            // Power dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedPower?.displayName ?: "Select power",
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Power (in hp)") },
                    placeholder = { Text("e.g. 150") },
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
                        text = { Text("None", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            selectedPower = null
                            showPowerDropdown = false
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    )
                    VehiclePower.values().forEach { power ->
                        DropdownMenuItem(
                            text = { Text(power.displayName, color = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                selectedPower = power
                                showPowerDropdown = false
                            },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        )
                    }
                }
            }

            // Fuel type radio buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Fuel Type",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FuelType.values().forEach { fuel ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedFuelType == fuel,
                            onClick = { selectedFuelType = fuel },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MotiumGreen
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = fuel.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Default vehicle checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isDefault,
                    onCheckedChange = { isDefault = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MotiumGreen
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Set as default vehicle",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Save button
            Button(
                onClick = {
                    onAddVehicle(
                        name,
                        selectedType,
                        licensePlate.takeIf { it.isNotBlank() },
                        selectedPower,
                        selectedFuelType,
                        calculatedMileageRate,
                        isDefault
                    )
                    onNavigateBack()
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumGreen
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Save Vehicle",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
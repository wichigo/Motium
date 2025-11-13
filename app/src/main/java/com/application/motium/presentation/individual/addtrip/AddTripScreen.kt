package com.application.motium.presentation.individual.addtrip

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.presentation.components.AddressAutocomplete
import com.application.motium.presentation.components.MiniMap
import com.application.motium.presentation.theme.MotiumGreen
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
    onTripSaved: (Trip, List<Expense>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val nominatimService = remember { NominatimService.getInstance() }

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
    var notes by remember { mutableStateOf("") }
    var isCalculatingRoute by remember { mutableStateOf(false) }

    // Expense fields
    var expenses by remember { mutableStateOf(listOf<ExpenseItem>()) }

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Initialize time
    LaunchedEffect(Unit) {
        val now = Date()
        selectedTime = timeFormat.format(now)
        endTime = timeFormat.format(Date(now.time + (23 * 60 * 1000))) // +23 min
    }

    // Calculate route
    fun calculateRoute() {
        if (startCoordinates != null && endCoordinates != null) {
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

                        // Calculate end time
                        val startTimeDate = timeFormat.parse(selectedTime) ?: Date()
                        val endTimeDate = Date(startTimeDate.time + route.duration.toLong() * 1000)
                        endTime = timeFormat.format(endTimeDate)
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Route error: ${e.message}", "AddTripScreen", e)
                } finally {
                    isCalculatingRoute = false
                }
            }
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
                                val distanceKm = distance.toDoubleOrNull() ?: 5.0
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

                                val locations = listOf(
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

                                val tripId = UUID.randomUUID().toString()
                                val trip = Trip(
                                    id = tripId,
                                    startTime = startTimeMs,
                                    endTime = endTimeMs,
                                    locations = locations,
                                    totalDistance = distanceKm * 1000,
                                    isValidated = false,
                                    vehicleId = null,
                                    startAddress = startLocation,
                                    endAddress = endLocation,
                                    notes = notes.ifBlank { null }
                                )

                                // Convert ExpenseItem to Expense
                                val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                                val expensesToSave = expenses.mapNotNull { expenseItem ->
                                    // Only save expenses with amount
                                    if (expenseItem.amount.isNotBlank()) {
                                        try {
                                            Expense(
                                                id = expenseItem.id,
                                                tripId = tripId,
                                                type = expenseItem.type,
                                                amount = expenseItem.amount.toDouble(),
                                                note = expenseItem.note,
                                                photoUri = expenseItem.photoUri?.toString(),
                                                createdAt = now,
                                                updatedAt = now
                                            )
                                        } catch (e: NumberFormatException) {
                                            MotiumApplication.logger.w("Invalid expense amount: ${expenseItem.amount}", "AddTripScreen")
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                }

                                MotiumApplication.logger.d("Trip created: ${trip.id} with ${expensesToSave.size} expenses", "AddTripScreen")
                                Toast.makeText(context, "Trip saved successfully", Toast.LENGTH_SHORT).show()
                                onTripSaved(trip, expensesToSave)
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
                AddressAutocomplete(
                    label = "Departure Location",
                    value = startLocation,
                    onValueChange = { startLocation = it },
                    onAddressSelected = { result ->
                        startCoordinates = result.lat.toDouble() to result.lon.toDouble()
                        startLocation = result.display_name
                        if (endCoordinates != null) calculateRoute()
                    }
                )
            }

            item {
                AddressAutocomplete(
                    label = "Arrival Location",
                    value = endLocation,
                    onValueChange = { endLocation = it },
                    onAddressSelected = { result ->
                        endCoordinates = result.lat.toDouble() to result.lon.toDouble()
                        endLocation = result.display_name
                        if (startCoordinates != null) calculateRoute()
                    }
                )
            }

            // Professional Trip toggle
            item {
                ProfessionalTripToggle(
                    isProfessional = isProfessional,
                    onToggle = { isProfessional = it }
                )
            }

            // Distance and Duration
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReadOnlyField(
                        label = "Distance",
                        value = if (distance.isNotBlank()) "$distance mi" else "",
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

            // Notes section
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Add any notes for this trip...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
            }

            // Expense Notes section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Expense Notes",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Button(
                        onClick = {
                            expenses = expenses + ExpenseItem()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumGreen
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            // Expense items
            itemsIndexed(expenses) { index, expense ->
                ExpenseItemCard(
                    expense = expense,
                    onExpenseChange = { updatedExpense ->
                        expenses = expenses.toMutableList().apply {
                            set(index, updatedExpense)
                        }
                    },
                    onRemove = {
                        expenses = expenses.toMutableList().apply {
                            removeAt(index)
                        }
                    }
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
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        readOnly = true,
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        },
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium
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
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        readOnly = true,
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        },
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        enabled = false
    )
}

@Composable
fun ProfessionalTripToggle(
    isProfessional: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Professional Trip",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                )
            )
            Switch(
                checked = isProfessional,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MotiumGreen
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseItemCard(
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

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            onExpenseChange(expense.copy(photoUri = it))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Type dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = expenseTypes.find { it.first == expense.type }?.second ?: "Fuel",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = {
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        expenseTypes.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onExpenseChange(expense.copy(type = type))
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Amount field
                OutlinedTextField(
                    value = expense.amount,
                    onValueChange = { onExpenseChange(expense.copy(amount = it)) },
                    label = { Text("Amount") },
                    leadingIcon = { Text("$", fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("0.00") }
                )
            }

            // Note field
            OutlinedTextField(
                value = expense.note,
                onValueChange = { onExpenseChange(expense.copy(note = it)) },
                label = { Text("Note (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g., Gas station receipt") }
            )

            // Photo button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") }
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (expense.photoUri != null) "Photo added" else "Add photo")
                }

                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red
                    )
                ) {
                    Text("Remove")
                }
            }
        }
    }
}

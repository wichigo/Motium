package com.application.motium.presentation.individual.expense

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseExpenseRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.presentation.theme.MotiumGreen
import com.application.motium.service.ReceiptAnalysisService
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    tripId: String,
    onNavigateBack: () -> Unit,
    onExpenseSaved: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val expenseRepository = remember { SupabaseExpenseRepository.getInstance(context) }
    val tripRepository = remember { TripRepository.getInstance(context) }
    val receiptAnalysisService = remember { ReceiptAnalysisService.getInstance(context) }

    // Expense fields
    var selectedType by remember { mutableStateOf(ExpenseType.FUEL) }
    var amountTTC by remember { mutableStateOf("") }
    var amountHT by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var tripName by remember { mutableStateOf("") }
    var isAnalyzingReceipt by remember { mutableStateOf(false) }
    var isAutoFilled by remember { mutableStateOf(false) }

    // Load trip name
    LaunchedEffect(tripId) {
        val trips = tripRepository.getAllTrips()
        val trip = trips.firstOrNull { it.id == tripId }
        tripName = trip?.let { "${it.startAddress ?: "Unknown"} → ${it.endAddress ?: "Unknown"}" } ?: "Trip"
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        photoUri = uri

        // Automatically analyze receipt when photo is selected
        if (uri != null) {
            isAnalyzingReceipt = true
            coroutineScope.launch {
                receiptAnalysisService.analyzeReceipt(uri).onSuccess { result ->
                    // Auto-fill amounts if found
                    result.amountTTC?.let { ttc ->
                        amountTTC = String.format("%.2f", ttc)
                        isAutoFilled = true
                    }
                    result.amountHT?.let { ht ->
                        amountHT = String.format("%.2f", ht)
                        isAutoFilled = true
                    }

                    if (result.amountTTC != null || result.amountHT != null) {
                        Toast.makeText(
                            context,
                            "Amounts detected automatically ✓",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Could not detect amounts, please enter manually",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.onFailure { error ->
                    MotiumApplication.logger.e("Receipt analysis failed: ${error.message}", "AddExpenseScreen", error)
                    Toast.makeText(
                        context,
                        "Analysis failed, please enter amounts manually",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isAnalyzingReceipt = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Add Expense",
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
                            if (amountTTC.isBlank()) {
                                Toast.makeText(context, "Please enter TTC amount", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }

                            try {
                                val amountTTCValue = amountTTC.replace(',', '.').toDouble()
                                val amountHTValue = amountHT.takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()

                                val expense = Expense(
                                    id = UUID.randomUUID().toString(),
                                    tripId = tripId,
                                    type = selectedType,
                                    amount = amountTTCValue,
                                    amountHT = amountHTValue,
                                    note = note,
                                    photoUri = photoUri?.toString(),
                                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                                )

                                coroutineScope.launch {
                                    try {
                                        expenseRepository.saveExpenses(listOf(expense))
                                        MotiumApplication.logger.i("Expense saved: ${expense.id}", "AddExpenseScreen")
                                        Toast.makeText(context, "Expense saved successfully", Toast.LENGTH_SHORT).show()
                                        onExpenseSaved()
                                    } catch (e: Exception) {
                                        MotiumApplication.logger.e("Failed to save expense: ${e.message}", "AddExpenseScreen", e)
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Invalid amount format", Toast.LENGTH_SHORT).show()
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
            // Trip info
            item {
                ReadOnlyField(
                    label = "Trip",
                    value = tripName,
                    icon = Icons.Default.Route
                )
            }

            // Expense Type
            item {
                ExpenseTypeField(
                    selectedType = selectedType,
                    onTypeSelected = { selectedType = it }
                )
            }

            // Amount HT
            item {
                AmountField(
                    label = "Amount HT (Optional)",
                    value = amountHT,
                    onValueChange = {
                        amountHT = it
                        isAutoFilled = false
                    },
                    isMandatory = false,
                    isAutoDetected = isAutoFilled && amountHT.isNotBlank()
                )
            }

            // Amount TTC
            item {
                AmountField(
                    label = "Amount TTC",
                    value = amountTTC,
                    onValueChange = {
                        amountTTC = it
                        isAutoFilled = false
                    },
                    isMandatory = true,
                    isAutoDetected = isAutoFilled && amountTTC.isNotBlank()
                )
            }

            // Note
            item {
                Column {
                    Text(
                        text = "Note",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("Add a note for this expense...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            // Photo
            item {
                Column {
                    Text(
                        text = "Receipt Photo",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isAnalyzingReceipt
                    ) {
                        if (isAnalyzingReceipt) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Analyzing receipt...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (photoUri != null) "Photo added ✓" else "Add photo (auto-detect amounts)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReadOnlyField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            leadingIcon = {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                disabledBorderColor = Color.Transparent,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            ),
            enabled = false
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseTypeField(
    selectedType: ExpenseType,
    onTypeSelected: (ExpenseType) -> Unit
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

    Column {
        Text(
            text = "Expense Type",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = expenseTypes.find { it.first == selectedType }?.second ?: "Fuel",
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Receipt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                expenseTypes.forEach { (type, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onTypeSelected(type)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AmountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isMandatory: Boolean,
    isAutoDetected: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (isAutoDetected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Auto-detected",
                        tint = MotiumGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (isMandatory) {
                Text(
                    text = "Required",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            leadingIcon = {
                Text(
                    "€",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("0.00") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = if (isAutoDetected)
                    MotiumGreen.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedBorderColor = if (isAutoDetected)
                    MotiumGreen
                else
                    MaterialTheme.colorScheme.primary
            )
        )
    }
}

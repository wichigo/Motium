package com.application.motium.presentation.individual.expense

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.application.motium.MotiumApplication
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.TripRepository
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.service.ReceiptAnalysisService
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    date: String,  // MODIFIÉ: date au format YYYY-MM-DD au lieu de tripId
    onNavigateBack: () -> Unit,
    onExpenseSaved: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val expenseRepository = remember { ExpenseRepository.getInstance(context) }
    val receiptAnalysisService = remember { ReceiptAnalysisService.getInstance(context) }
    val storageService = remember { com.application.motium.service.SupabaseStorageService.getInstance(context) }

    // Expense fields
    var selectedType by remember { mutableStateOf(ExpenseType.FUEL) }
    var amountTTC by remember { mutableStateOf("") }
    var amountHT by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }  // URL finale (Supabase)
    var localPreviewUri by remember { mutableStateOf<Uri?>(null) }  // Preview locale immédiate
    var isAnalyzingReceipt by remember { mutableStateOf(false) }
    var isAutoFilled by remember { mutableStateOf(false) }

    // Camera capture state
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Format date for display
    val formattedDate = remember(date) {
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
            val parsedDate = inputFormat.parse(date)
            parsedDate?.let { outputFormat.format(it) } ?: date
        } catch (e: Exception) {
            date
        }
    }

    // Helper function to create temp file URI for camera
    fun createTempPhotoUri(): Uri {
        val photoFile = File.createTempFile("receipt_", ".jpg", context.cacheDir)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }

    // Shared function to analyze and upload photo
    fun analyzeAndUploadPhoto(uri: Uri) {
        // Afficher immédiatement la preview locale
        localPreviewUri = uri
        isAnalyzingReceipt = true

        coroutineScope.launch {
            // First, analyze the receipt for amounts
            receiptAnalysisService.analyzeReceipt(uri).onSuccess { result ->
                result.amountTTC?.let { ttc ->
                    amountTTC = String.format("%.2f", ttc)
                    isAutoFilled = true
                    MotiumApplication.logger.i("Auto-filled TTC: $ttc", "AddExpenseScreen")
                }
                result.amountHT?.let { ht ->
                    amountHT = String.format("%.2f", ht)
                    isAutoFilled = true
                    MotiumApplication.logger.i("Auto-filled HT: $ht", "AddExpenseScreen")
                }

                val detected = mutableListOf<String>()
                if (result.amountTTC != null) detected.add("TTC")
                if (result.amountHT != null) detected.add("HT")

                if (detected.isNotEmpty()) {
                    Toast.makeText(context, "${detected.joinToString(" & ")} detected automatically", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No amounts detected, enter manually", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                MotiumApplication.logger.e("Receipt analysis failed: ${error.message}", "AddExpenseScreen", error)
                Toast.makeText(context, "OCR failed, enter amounts manually", Toast.LENGTH_SHORT).show()
            }

            // Then upload the photo to Supabase Storage
            storageService.uploadReceiptPhoto(uri).onSuccess { publicUrl ->
                photoUri = Uri.parse(publicUrl)
                MotiumApplication.logger.i("Receipt photo uploaded: $publicUrl", "AddExpenseScreen")
            }.onFailure { error ->
                MotiumApplication.logger.e("Failed to upload receipt photo: ${error.message}", "AddExpenseScreen", error)
                Toast.makeText(context, "Photo upload failed: ${error.message}", Toast.LENGTH_LONG).show()
                // Garder la preview locale même si l'upload échoue
            }

            isAnalyzingReceipt = false
        }
    }

    // Gallery picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { analyzeAndUploadPhoto(it) }
    }

    // Camera capture launcher
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            analyzeAndUploadPhoto(tempCameraUri!!)
        }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, launch camera
            tempCameraUri = createTempPhotoUri()
            takePictureLauncher.launch(tempCameraUri!!)
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Function to launch camera with permission check
    fun launchCamera() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                tempCameraUri = createTempPhotoUri()
                takePictureLauncher.launch(tempCameraUri!!)
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
                                    date = date,
                                    type = selectedType,
                                    amount = amountTTCValue,
                                    amountHT = amountHTValue,
                                    note = note,
                                    photoUri = photoUri?.toString(),
                                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                                )

                                coroutineScope.launch {
                                    val result = expenseRepository.saveExpense(expense)
                                    if (result.isSuccess) {
                                        MotiumApplication.logger.i("Expense saved: ${expense.id}", "AddExpenseScreen")
                                        Toast.makeText(context, "Expense saved successfully", Toast.LENGTH_SHORT).show()
                                        onExpenseSaved()
                                    } else {
                                        val error = result.exceptionOrNull()
                                        MotiumApplication.logger.e("Failed to save expense: ${error?.message}", "AddExpenseScreen", error)
                                        Toast.makeText(context, "Error: ${error?.message}", Toast.LENGTH_LONG).show()
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
            // Date info
            item {
                ReadOnlyField(
                    label = "Date",
                    value = formattedDate,
                    icon = Icons.Default.CalendarToday
                )
            }

            // Photo Section (moved here - below date, above expense type)
            item {
                PhotoSection(
                    photoUri = localPreviewUri,  // Utiliser la preview locale pour l'affichage immédiat
                    isUploaded = photoUri != null,  // Indique si l'upload est terminé
                    isAnalyzing = isAnalyzingReceipt,
                    onCameraClick = { launchCamera() },
                    onGalleryClick = { imagePickerLauncher.launch("image/*") }
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
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    placeholder = { Text("Add a note for this expense...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        focusedLabelColor = MotiumPrimary,
                        cursorColor = MotiumPrimary
                    )
                )
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
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = MotiumPrimary
            )
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            focusedLabelColor = MotiumPrimary
        )
    )
}

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

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = expenseTypes.find { it.first == selectedType }?.second ?: "Fuel",
            onValueChange = {},
            readOnly = true,
            label = { Text("Expense Type") },
            trailingIcon = {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MotiumPrimary
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Receipt,
                    contentDescription = null,
                    tint = MotiumPrimary
                )
            },
            modifier = Modifier.fillMaxWidth(),
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
                .clickable { expanded = !expanded }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            expenseTypes.forEach { (type, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                )
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (isAutoDetected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Auto-detected",
                        tint = MotiumPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        },
        leadingIcon = {
            Text(
                "€",
                fontWeight = FontWeight.Bold,
                color = MotiumPrimary
            )
        },
        trailingIcon = if (isMandatory) {
            {
                Text(
                    "Required",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("0.00") },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = if (isAutoDetected)
                MotiumPrimary.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary,
            unfocusedLabelColor = if (isAutoDetected)
                MotiumPrimary
            else
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            focusedLabelColor = MotiumPrimary,
            cursorColor = MotiumPrimary
        )
    )
}

@Composable
fun PhotoSection(
    photoUri: Uri?,
    isUploaded: Boolean = false,
    isAnalyzing: Boolean,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    Icons.Default.Receipt,
                    contentDescription = null,
                    tint = MotiumPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Receipt Photo",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Loading state
            if (isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MotiumPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Analyzing receipt...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                // Two buttons: Camera and Gallery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Camera button
                    OutlinedButton(
                        onClick = onCameraClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MotiumPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MotiumPrimary.copy(alpha = 0.5f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MotiumPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Camera",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Take photo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Gallery button
                    OutlinedButton(
                        onClick = onGalleryClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MotiumPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MotiumPrimary.copy(alpha = 0.5f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MotiumPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Gallery",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Choose photo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // Preview thumbnail if photo is added
                photoUri?.let { uri ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUploaded) MotiumPrimary.copy(alpha = 0.1f) else Color(0xFFFFF3CD)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail
                            AsyncImage(
                                model = uri,
                                contentDescription = "Receipt preview",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isUploaded) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MotiumPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Photo saved",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MotiumPrimary
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFFD97706)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Uploading...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFD97706)
                                        )
                                    }
                                }
                                Text(
                                    if (isUploaded) "Receipt photo uploaded to cloud" else "Saving photo to cloud...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                // Helper text when no photo
                if (photoUri == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Amounts will be auto-detected from receipt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

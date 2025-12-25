package com.application.motium.presentation.individual.expense

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.application.motium.MotiumApplication
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.local.MotiumDatabase
import com.application.motium.data.local.entities.PendingFileUploadEntity
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.ExpenseType
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.service.ReceiptAnalysisService
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditExpenseScreen(
    expenseId: String,
    onNavigateBack: () -> Unit,
    onExpenseSaved: () -> Unit,
    onExpenseDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val expenseRepository = remember { ExpenseRepository.getInstance(context) }
    val receiptAnalysisService = remember { ReceiptAnalysisService.getInstance(context) }
    val storageService = remember { com.application.motium.service.SupabaseStorageService.getInstance(context) }
    val database = remember { MotiumDatabase.getInstance(context) }
    val pendingFileUploadDao = remember { database.pendingFileUploadDao() }

    // Loading state
    var isLoading by remember { mutableStateOf(true) }
    var originalExpense by remember { mutableStateOf<Expense?>(null) }

    // Expense fields
    var selectedType by remember { mutableStateOf(ExpenseType.FUEL) }
    var amountTTC by remember { mutableStateOf("") }
    var amountHT by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expenseDate by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var localPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var isAnalyzingReceipt by remember { mutableStateOf(false) }
    var isAutoFilled by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Camera capture state
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Load expense data
    LaunchedEffect(expenseId) {
        coroutineScope.launch {
            try {
                val expense = expenseRepository.getExpenseById(expenseId)
                if (expense != null) {
                    originalExpense = expense
                    selectedType = expense.type
                    amountTTC = String.format("%.2f", expense.amount)
                    amountHT = expense.amountHT?.let { String.format("%.2f", it) } ?: ""
                    note = expense.note
                    expenseDate = expense.date
                    expense.photoUri?.let {
                        photoUri = Uri.parse(it)
                        localPreviewUri = Uri.parse(it)
                    }
                    MotiumApplication.logger.i("Loaded expense: ${expense.id}", "EditExpenseScreen")
                } else {
                    MotiumApplication.logger.e("Expense not found: $expenseId", "EditExpenseScreen")
                    Toast.makeText(context, "Expense not found", Toast.LENGTH_SHORT).show()
                    onNavigateBack()
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Failed to load expense: ${e.message}", "EditExpenseScreen", e)
                Toast.makeText(context, "Error loading expense", Toast.LENGTH_SHORT).show()
                onNavigateBack()
            }
            isLoading = false
        }
    }

    // Format date for display
    val formattedDate = remember(expenseDate) {
        if (expenseDate.isBlank()) return@remember ""
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
            val parsedDate = inputFormat.parse(expenseDate)
            parsedDate?.let { outputFormat.format(it) } ?: expenseDate
        } catch (e: Exception) {
            expenseDate
        }
    }

    // Helper function to create temp file URI for camera
    fun createTempPhotoUri(): Uri {
        val photoFile = File.createTempFile("receipt_", ".jpg", context.cacheDir)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }

    // Helper function to copy file to app's internal storage for persistence
    suspend fun copyToInternalStorage(sourceUri: Uri): Uri? {
        return try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null

            // Create permanent file in app's files directory
            val fileName = "receipt_${System.currentTimeMillis()}.jpg"
            val destFile = File(context.filesDir, fileName)

            FileOutputStream(destFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            inputStream.close()

            Uri.fromFile(destFile)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to copy file to internal storage: ${e.message}", "EditExpenseScreen", e)
            null
        }
    }

    // Shared function to analyze and upload photo (offline-first)
    fun analyzeAndUploadPhoto(uri: Uri) {
        localPreviewUri = uri
        isAnalyzingReceipt = true

        coroutineScope.launch {
            // Step 1: Analyze receipt with OCR
            receiptAnalysisService.analyzeReceipt(uri).onSuccess { result ->
                result.amountTTC?.let { ttc ->
                    amountTTC = String.format("%.2f", ttc)
                    isAutoFilled = true
                }
                result.amountHT?.let { ht ->
                    amountHT = String.format("%.2f", ht)
                    isAutoFilled = true
                }

                val detected = mutableListOf<String>()
                if (result.amountTTC != null) detected.add("TTC")
                if (result.amountHT != null) detected.add("HT")

                if (detected.isNotEmpty()) {
                    Toast.makeText(context, "${detected.joinToString(" & ")} detected automatically", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                MotiumApplication.logger.e("Receipt analysis failed: ${error.message}", "EditExpenseScreen", error)
            }

            // Step 2: Copy file to internal storage for persistence
            val persistentUri = copyToInternalStorage(uri)
            if (persistentUri == null) {
                MotiumApplication.logger.e("Failed to persist receipt photo locally", "EditExpenseScreen")
                Toast.makeText(context, "Failed to save photo locally", Toast.LENGTH_SHORT).show()
                isAnalyzingReceipt = false
                return@launch
            }

            // Step 3: Set photoUri to local URI immediately (offline-first)
            photoUri = persistentUri
            MotiumApplication.logger.i("Receipt photo saved locally: ${persistentUri.path}", "EditExpenseScreen")

            // Step 4: Create pending upload record (will be processed by DeltaSyncWorker)
            val pendingUpload = PendingFileUploadEntity(
                id = UUID.randomUUID().toString(),
                expenseId = expenseId,
                localUri = persistentUri.toString(),
                status = PendingFileUploadEntity.STATUS_PENDING,
                createdAt = System.currentTimeMillis()
            )
            pendingFileUploadDao.insert(pendingUpload)
            MotiumApplication.logger.i("Queued receipt photo for upload: ${pendingUpload.id}", "EditExpenseScreen")

            // Step 5: Try immediate upload in background (non-blocking)
            // If this fails, DeltaSyncWorker will retry later
            storageService.uploadReceiptPhoto(persistentUri).onSuccess { publicUrl ->
                // Upload succeeded immediately - update pending record and photoUri
                pendingFileUploadDao.markCompleted(pendingUpload.id, publicUrl)
                photoUri = Uri.parse(publicUrl)
                MotiumApplication.logger.i("Receipt photo uploaded immediately: $publicUrl", "EditExpenseScreen")
            }.onFailure { error ->
                // Upload failed - will be retried by DeltaSyncWorker
                MotiumApplication.logger.w(
                    "Immediate upload failed (will retry later): ${error.message}",
                    "EditExpenseScreen"
                )
                // Keep local URI in photoUri - expense can still be saved offline
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
            tempCameraUri = createTempPhotoUri()
            takePictureLauncher.launch(tempCameraUri!!)
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

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

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = { Text("Delete Expense") },
            text = { Text("Are you sure you want to delete this expense? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            val result = expenseRepository.deleteExpense(expenseId)
                            if (result.isSuccess) {
                                Toast.makeText(context, "Expense deleted", Toast.LENGTH_SHORT).show()
                                onExpenseDeleted()
                            } else {
                                Toast.makeText(context, "Error deleting expense", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Edit Expense",
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
                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.Red
                        )
                    }
                    // Save button
                    IconButton(
                        onClick = {
                            if (amountTTC.isBlank()) {
                                Toast.makeText(context, "Please enter TTC amount", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }

                            try {
                                val amountTTCValue = amountTTC.replace(',', '.').toDouble()
                                val amountHTValue = amountHT.takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()

                                val updatedExpense = originalExpense?.copy(
                                    type = selectedType,
                                    amount = amountTTCValue,
                                    amountHT = amountHTValue,
                                    note = note,
                                    photoUri = photoUri?.toString(),
                                    updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                                )

                                if (updatedExpense != null) {
                                    coroutineScope.launch {
                                        val result = expenseRepository.saveExpense(updatedExpense)
                                        if (result.isSuccess) {
                                            MotiumApplication.logger.i("Expense updated: ${updatedExpense.id}", "EditExpenseScreen")
                                            Toast.makeText(context, "Expense updated successfully", Toast.LENGTH_SHORT).show()
                                            onExpenseSaved()
                                        } else {
                                            val error = result.exceptionOrNull()
                                            MotiumApplication.logger.e("Failed to update expense: ${error?.message}", "EditExpenseScreen", error)
                                            Toast.makeText(context, "Error: ${error?.message}", Toast.LENGTH_LONG).show()
                                        }
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
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MotiumPrimary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Date info (read-only)
                item {
                    OutlinedTextField(
                        value = formattedDate,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = MotiumPrimary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        )
                    )
                }

                // Photo Section
                item {
                    EditPhotoSection(
                        photoUri = localPreviewUri,
                        isUploaded = photoUri != null && localPreviewUri == photoUri,
                        isAnalyzing = isAnalyzingReceipt,
                        onCameraClick = { launchCamera() },
                        onGalleryClick = { imagePickerLauncher.launch("image/*") }
                    )
                }

                // Expense Type
                item {
                    EditExpenseTypeField(
                        selectedType = selectedType,
                        onTypeSelected = { selectedType = it }
                    )
                }

                // Amount HT
                item {
                    EditAmountField(
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
                    EditAmountField(
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
                        placeholder = {
                            if (note.isEmpty()) {
                                Text("Add a note for this expense...")
                            }
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Notes,
                                contentDescription = null,
                                tint = MotiumPrimary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun EditExpenseTypeField(
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
            shape = RoundedCornerShape(12.dp),
            enabled = false,
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    shape = RoundedCornerShape(12.dp)
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
private fun EditAmountField(
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
                "\u20AC",
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
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = if (isAutoDetected)
                MotiumPrimary.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MotiumPrimary,
            unfocusedLabelColor = if (isAutoDetected)
                MotiumPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLabelColor = MotiumPrimary
        )
    )
}

@Composable
private fun EditPhotoSection(
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                        }
                    }

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
                        }
                    }
                }

                photoUri?.let { uri ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MotiumPrimary.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MotiumPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Photo attached",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MotiumPrimary
                                    )
                                }
                                Text(
                                    "Tap camera or gallery to replace",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

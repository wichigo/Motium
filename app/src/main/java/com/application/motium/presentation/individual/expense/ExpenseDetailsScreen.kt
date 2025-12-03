package com.application.motium.presentation.individual.expense

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository
import com.application.motium.data.supabase.SupabaseExpenseRepository
import com.application.motium.domain.model.Expense
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// Colors matching HomeScreen
private val MockupGreen = Color(0xFF10B981)
private val MockupTextBlack = Color(0xFF1F2937)
private val MockupTextGray = Color(0xFF6B7280)
private val MockupBackground = Color(0xFFF3F4F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDetailsScreen(
    date: String,  // MODIFIÉ: date au format YYYY-MM-DD au lieu de dateLabel + tripIds
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val expenseRepository = remember { SupabaseExpenseRepository.getInstance(context) }
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    // Dynamic colors matching HomeScreen
    val cardColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else MockupTextBlack
    val subTextColor = if (isDarkMode) Color.Gray else MockupTextGray
    val backgroundColor = if (isDarkMode) Color(0xFF121212) else MockupBackground

    var expenses by remember { mutableStateOf<List<Expense>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalTTC by remember { mutableStateOf(0.0) }
    var totalHT by remember { mutableStateOf(0.0) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }

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

    // Photo viewer dialog
    selectedPhotoUri?.let { photoUri ->
        Dialog(onDismissRequest = { selectedPhotoUri = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column {
                    // Header with close button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Receipt Photo",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        IconButton(onClick = { selectedPhotoUri = null }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Photo
                    AsyncImage(
                        model = Uri.parse(photoUri),
                        contentDescription = "Receipt photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 500.dp)
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    // Load all expenses for this day (MODIFIÉ: utilise getExpensesForDay)
    LaunchedEffect(date) {
        coroutineScope.launch {
            expenseRepository.getExpensesForDay(date).onSuccess { expenseList ->
                expenses = expenseList

                // Calculate totals
                totalTTC = expenses.sumOf { it.amount }
                totalHT = expenses.mapNotNull { it.amountHT }.sum()

                MotiumApplication.logger.i("Loaded ${expenseList.size} expenses for $date", "ExpenseDetailsScreen")
            }.onFailure { error ->
                MotiumApplication.logger.e("Failed to load expenses for date $date: ${error.message}", "ExpenseDetailsScreen", error)
            }

            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Expenses",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            fontSize = 18.sp
                        )
                        Text(
                            formattedDate,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
                CircularProgressIndicator()
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
                // Summary card - HomeScreen style
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "Daily Summary",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Expenses count
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${expenses.size}",
                                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MockupGreen
                                    )
                                    Text(
                                        "Expenses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subTextColor
                                    )
                                }
                                // Total TTC
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        String.format("%.2f €", totalTTC),
                                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MockupGreen
                                    )
                                    Text(
                                        "Total TTC",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subTextColor
                                    )
                                }
                                // Total HT
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        String.format("%.2f €", totalHT),
                                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MockupGreen
                                    )
                                    Text(
                                        "Total HT",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subTextColor
                                    )
                                }
                            }
                        }
                    }
                }

                // Expense list
                if (expenses.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Receipt,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = subTextColor.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "No expenses for this day",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = subTextColor
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(expenses) { expense ->
                        ExpenseCard(
                            expense = expense,
                            onPhotoClick = { photoUri ->
                                selectedPhotoUri = photoUri
                            },
                            cardColor = cardColor,
                            textColor = textColor,
                            subTextColor = subTextColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpenseCard(
    expense: Expense,
    onPhotoClick: (String) -> Unit = {},
    cardColor: Color = Color.White,
    textColor: Color = MockupTextBlack,
    subTextColor: Color = MockupTextGray
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon with colored background (similar to MiniMap in trips)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MockupGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (expense.type.name) {
                        "FUEL" -> Icons.Default.LocalGasStation
                        "PARKING" -> Icons.Default.LocalParking
                        "TOLL" -> Icons.Default.Toll
                        "MAINTENANCE" -> Icons.Default.Build
                        "INSURANCE" -> Icons.Default.Security
                        "OTHER" -> Icons.Default.MoreHoriz
                        else -> Icons.Default.Receipt
                    },
                    contentDescription = null,
                    tint = MockupGreen,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Header row with type and badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        expense.getExpenseTypeLabel(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )

                    // Type badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MockupGreen.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Receipt,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MockupGreen
                            )
                            Text(
                                "Expense",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                fontSize = 11.sp,
                                color = MockupGreen
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Note if available
                if (expense.note.isNotBlank()) {
                    Text(
                        expense.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Amount row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // HT amount if available
                    expense.amountHT?.let { ht ->
                        Column {
                            Text(
                                "HT",
                                style = MaterialTheme.typography.bodySmall,
                                color = subTextColor
                            )
                            Text(
                                expense.getFormattedAmountHT() ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        }
                    }

                    // TTC amount (main)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            expense.getFormattedAmount(),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MockupGreen
                        )
                        if (expense.photoUri != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                modifier = Modifier.clickable { onPhotoClick(expense.photoUri!!) },
                                shape = RoundedCornerShape(8.dp),
                                color = MockupGreen.copy(alpha = 0.1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CameraAlt,
                                        contentDescription = "View photo",
                                        tint = MockupGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        "Photo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MockupGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

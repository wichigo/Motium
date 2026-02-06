package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.application.motium.data.supabase.ExpenseRemoteDataSource
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.domain.model.Expense
import com.application.motium.presentation.individual.expense.ExpenseCard
import com.application.motium.presentation.theme.BackgroundDark
import com.application.motium.presentation.theme.BackgroundLight
import com.application.motium.presentation.theme.ErrorRed
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.presentation.theme.SurfaceDark
import com.application.motium.presentation.theme.SurfaceLight
import com.application.motium.presentation.theme.TextDark
import com.application.motium.presentation.theme.TextLight
import com.application.motium.presentation.theme.TextSecondaryDark
import com.application.motium.presentation.theme.TextSecondaryLight
import com.application.motium.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun formatExpenseDateLabel(date: String): String {
    return try {
        val apiFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFormatter = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
        val today = apiFormatter.format(Date())
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayText = apiFormatter.format(yesterday.time)

        when (date) {
            today -> "Aujourd'hui"
            yesterdayText -> "Hier"
            else -> {
                val parsed = apiFormatter.parse(date)
                if (parsed != null) labelFormatter.format(parsed) else date
            }
        }
    } catch (_: Exception) {
        date
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedUserExpensesScreen(
    userId: String,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val linkedAccountRemoteDataSource = remember { LinkedAccountRemoteDataSource.getInstance(context) }
    val expenseRemoteDataSource = remember { ExpenseRemoteDataSource.getInstance(context) }

    val isDarkMode by themeManager.isDarkMode.collectAsState()
    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    var user by remember { mutableStateOf<LinkedUserDto?>(null) }
    var expenses by remember { mutableStateOf<List<Expense>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userId) {
        isLoading = true
        try {
            linkedAccountRemoteDataSource.getLinkedUserById(userId).fold(
                onSuccess = { linkedUser -> user = linkedUser },
                onFailure = { /* Keep fallback title */ }
            )

            expenseRemoteDataSource.getAllExpenses(userId).fold(
                onSuccess = { loadedExpenses ->
                    expenses = loadedExpenses.sortedWith(
                        compareByDescending<Expense> { it.date }
                            .thenByDescending { it.createdAt.toEpochMilliseconds() }
                    )
                },
                onFailure = { e ->
                    error = e.message
                }
            )
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    val groupedExpenses = remember(expenses) { expenses.groupBy { it.date } }
    val totalAmount = remember(expenses) { expenses.sumOf { it.amount } }
    val withReceiptCount = remember(expenses) { expenses.count { !it.photoUri.isNullOrBlank() } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        user?.displayName ?: "Depenses",
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = textColor
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = backgroundColor)
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
                        Icon(
                            Icons.Outlined.Error,
                            contentDescription = null,
                            tint = ErrorRed
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Erreur: $error", color = textSecondaryColor)
                    }
                }
            }
            expenses.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Receipt,
                            contentDescription = null,
                            tint = textSecondaryColor
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Aucune depense",
                            style = MaterialTheme.typography.titleMedium,
                            color = textSecondaryColor
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                ExpenseStat(
                                    value = expenses.size.toString(),
                                    label = "Depenses",
                                    textSecondaryColor = textSecondaryColor
                                )
                                ExpenseStat(
                                    value = String.format("%.2f EUR", totalAmount),
                                    label = "Total",
                                    textSecondaryColor = textSecondaryColor
                                )
                                ExpenseStat(
                                    value = withReceiptCount.toString(),
                                    label = "Justificatifs",
                                    textSecondaryColor = textSecondaryColor
                                )
                            }
                        }
                    }

                    groupedExpenses.forEach { (date, dayExpenses) ->
                        item {
                            Text(
                                text = formatExpenseDateLabel(date),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }

                        items(dayExpenses, key = { it.id }) { expense ->
                            ExpenseCard(
                                expense = expense,
                                onClick = {},
                                cardColor = cardColor,
                                textColor = textColor,
                                subTextColor = textSecondaryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseStat(
    value: String,
    label: String,
    textSecondaryColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
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

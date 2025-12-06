package com.application.motium.presentation.pro.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseStatus
import com.application.motium.domain.model.LicensesSummary
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager

/**
 * Screen for managing licenses (Pro feature)
 * Displays license summary and list with Stripe integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val viewModel = remember { LicensesViewModel(context) }

    val uiState by viewModel.uiState.collectAsState()
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccessMessage()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Licences",
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Retour",
                            tint = textColor
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = backgroundColor
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showPurchaseDialog() },
                containerColor = MotiumPrimary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter une licence")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor
    ) { paddingValues ->
        if (uiState.isLoading) {
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
                // Summary card
                item {
                    LicensesSummaryCard(
                        summary = uiState.summary,
                        cardColor = cardColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )
                }

                // Pricing info
                item {
                    PricingInfoCard(
                        cardColor = cardColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )
                }

                // Licenses list
                if (uiState.licenses.isNotEmpty()) {
                    item {
                        Text(
                            "Licences actives",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                    }

                    items(uiState.licenses) { license ->
                        LicenseCard(
                            license = license,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            onCancel = { viewModel.cancelLicense(license.id) }
                        )
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = cardColor),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Outlined.CardMembership,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = textSecondaryColor
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Aucune licence",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = textSecondaryColor
                                )
                                Text(
                                    "Achetez des licences pour vos collaborateurs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textSecondaryColor
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
                }
            }
        }
    }

    // Purchase dialog
    if (uiState.showPurchaseDialog) {
        PurchaseLicenseDialog(
            onDismiss = { viewModel.hidePurchaseDialog() },
            onPurchase = { quantity -> viewModel.purchaseLicenses(quantity) },
            isLoading = uiState.isPurchasing
        )
    }
}

@Composable
private fun LicensesSummaryCard(
    summary: LicensesSummary,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                "Résumé",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    value = summary.activeLicenses.toString(),
                    label = "Actives",
                    color = ValidatedGreen,
                    textSecondaryColor = textSecondaryColor
                )
                StatItem(
                    value = summary.pendingLicenses.toString(),
                    label = "En attente",
                    color = PendingOrange,
                    textSecondaryColor = textSecondaryColor
                )
                StatItem(
                    value = summary.expiredLicenses.toString(),
                    label = "Expirées",
                    color = ErrorRed,
                    textSecondaryColor = textSecondaryColor
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // Monthly cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Coût mensuel HT",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )
                Text(
                    String.format("%.2f €", summary.monthlyTotalHT),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "TVA 20%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )
                Text(
                    String.format("%.2f €", summary.monthlyVAT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Total TTC",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(
                    String.format("%.2f €", summary.monthlyTotalTTC),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MotiumPrimary
                )
            }
        }
    }
}

@Composable
private fun PricingInfoCard(
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MotiumPrimary.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MotiumPrimary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Tarif: 5,00 € HT / mois / utilisateur",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    "Soit 6,00 € TTC avec TVA 20%",
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    color: Color,
    textSecondaryColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = textSecondaryColor
        )
    }
}

@Composable
private fun LicenseCard(
    license: License,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when (license.status) {
                            LicenseStatus.ACTIVE -> ValidatedGreen.copy(alpha = 0.1f)
                            LicenseStatus.PENDING -> PendingOrange.copy(alpha = 0.1f)
                            else -> ErrorRed.copy(alpha = 0.1f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (license.status) {
                        LicenseStatus.ACTIVE -> Icons.Default.CheckCircle
                        LicenseStatus.PENDING -> Icons.Default.Schedule
                        else -> Icons.Default.Cancel
                    },
                    contentDescription = null,
                    tint = when (license.status) {
                        LicenseStatus.ACTIVE -> ValidatedGreen
                        LicenseStatus.PENDING -> PendingOrange
                        else -> ErrorRed
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Licence #${license.id.take(8)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    text = when (license.status) {
                        LicenseStatus.ACTIVE -> "Active"
                        LicenseStatus.PENDING -> "En attente"
                        LicenseStatus.EXPIRED -> "Expirée"
                        LicenseStatus.CANCELLED -> "Annulée"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor
                )
                license.daysRemaining?.let { days ->
                    if (days <= 7) {
                        Text(
                            text = "Expire dans $days jours",
                            style = MaterialTheme.typography.labelSmall,
                            color = PendingOrange
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format("%.2f €", license.priceMonthlyTTC),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MotiumPrimary
                )
                Text(
                    text = "/mois",
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondaryColor
                )
            }

            if (license.status == LicenseStatus.ACTIVE) {
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = textSecondaryColor
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Annuler", color = ErrorRed) },
                            onClick = {
                                showMenu = false
                                onCancel()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Cancel,
                                    contentDescription = null,
                                    tint = ErrorRed
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

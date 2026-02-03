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
import androidx.compose.material.icons.outlined.CalendarMonth
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
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseEffectiveStatus
import com.application.motium.domain.model.LicenseStatus
import com.application.motium.domain.model.LicensesSummary
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.DeferredPaymentConfig
import com.application.motium.presentation.components.StripeDeferredPaymentSheet
import com.application.motium.presentation.components.StripePaymentSheet
import com.application.motium.presentation.components.createLicenseButtonLabel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen for managing licenses (Pro feature)
 * Displays license summary and list with Stripe integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToExportAdvanced: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val viewModel = remember { LicensesViewModel(context) }

    val uiState by viewModel.uiState.collectAsState()
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
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

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Licences",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            fontSize = 18.sp,
                            color = textColor
                        )
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
                    contentColor = Color.White,
                    modifier = Modifier.padding(bottom = 100.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter une licence")
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent
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
                        .padding(paddingValues),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Summary section
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Résumé",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = textColor
                            )

                            LicensesSummaryCard(
                                summary = uiState.summary,
                                surfaceColor = surfaceColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor
                            )
                        }
                    }

                    // Billing anchor day card
                    item {
                        BillingAnchorCard(
                            billingAnchorDay = uiState.billingAnchorDay,
                            surfaceColor = surfaceColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor,
                            onSetAnchorDay = { viewModel.showBillingAnchorDialog() }
                        )
                    }

                    // Pricing info
                    item {
                        PricingInfoCard(
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }

                    // Licenses list section
                    item {
                        Text(
                            "Licences (${uiState.licenses.size})",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )
                    }

                    if (uiState.licenses.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                    } else {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column {
                                    uiState.licenses.forEachIndexed { index, license ->
                                        LicenseRow(
                                            license = license,
                                            textColor = textColor,
                                            textSecondaryColor = textSecondaryColor,
                                            onAssign = { viewModel.showAssignmentDialog(license) },
                                            onRequestUnlink = { viewModel.showUnlinkConfirmDialog(license) },
                                            onCancelUnlink = { viewModel.cancelUnlinkRequest(license.id) },
                                            onCancel = { viewModel.cancelLicense(license.id) },
                                            onDelete = { viewModel.deleteLicense(license.id) }
                                        )
                                        if (index < uiState.licenses.size - 1) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
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

        // Bottom navigation is now handled at app-level in MainActivity
    }

    // Purchase dialog
    if (uiState.showPurchaseDialog) {
        PurchaseLicenseDialog(
            onDismiss = { viewModel.hidePurchaseDialog() },
            onPurchase = { quantity, isLifetime -> viewModel.purchaseLicenses(quantity, isLifetime) },
            isLoading = uiState.isPurchasing
        )
    }

    // Assignment dialog
    val selectedLicense = uiState.selectedLicenseForAssignment
    if (uiState.showAssignDialog && selectedLicense != null) {
        AssignLicenseDialog(
            license = selectedLicense,
            linkedAccounts = uiState.linkedAccountsForAssignment,
            isLoading = uiState.isLoadingAccounts,
            onDismiss = { viewModel.hideAssignmentDialog() },
            onAssign = { licenseId, accountId -> viewModel.assignLicenseToAccount(licenseId, accountId) }
        )
    }

    // Unlink confirmation dialog
    val licenseToUnlink = uiState.licenseToUnlink
    if (uiState.showUnlinkConfirmDialog && licenseToUnlink != null) {
        UnlinkConfirmDialog(
            license = licenseToUnlink,
            isLoading = uiState.isUnlinking,
            onDismiss = { viewModel.hideUnlinkConfirmDialog() },
            onConfirm = { viewModel.confirmUnlinkRequest() }
        )
    }

    // Billing anchor day dialog
    if (uiState.showBillingAnchorDialog) {
        BillingAnchorDialog(
            currentAnchorDay = uiState.billingAnchorDay,
            isLoading = uiState.isUpdatingBillingAnchor,
            onDismiss = { viewModel.hideBillingAnchorDialog() },
            onConfirm = { day -> viewModel.updateBillingAnchorDay(day) }
        )
    }

    // Stripe Deferred PaymentSheet for license purchase (preferred mode - no email required)
    uiState.deferredPaymentReady?.let { deferredState ->
        val pendingPurchase = uiState.pendingPurchase
        val isLifetime = pendingPurchase?.isLifetime == true
        val quantity = pendingPurchase?.quantity ?: 1

        StripeDeferredPaymentSheet(
            config = DeferredPaymentConfig(
                amountCents = deferredState.amountCents,
                currency = "eur",
                isSubscription = false
            ),
            merchantName = "Motium",
            primaryButtonLabel = createLicenseButtonLabel(
                amountCents = deferredState.amountCents.toInt(),
                quantity = quantity,
                isLifetime = isLifetime
            ),
            onCreateIntent = { paymentMethodId ->
                viewModel.confirmPayment(paymentMethodId)
            },
            onResult = { result ->
                viewModel.handlePaymentResult(result)
            }
        )
    }

    // Legacy: Stripe PaymentSheet for license purchase (with pre-created intent)
    uiState.paymentReady?.let { paymentState ->
        val pendingPurchase = uiState.pendingPurchase
        val isLifetime = pendingPurchase?.isLifetime == true
        val quantity = pendingPurchase?.quantity ?: 1

        StripePaymentSheet(
            clientSecret = paymentState.clientSecret,
            customerId = paymentState.customerId,
            ephemeralKey = paymentState.ephemeralKey,
            merchantName = "Motium",
            primaryButtonLabel = createLicenseButtonLabel(
                amountCents = paymentState.amountCents,
                quantity = quantity,
                isLifetime = isLifetime
            ),
            onResult = { result ->
                viewModel.handlePaymentResult(result)
            }
        )
    }

    // Loading overlay during payment processing
    if (uiState.isRefreshingAfterPayment) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = MotiumPrimary)
                    Text(
                        "Création des licences...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun LicensesSummaryCard(
    summary: LicensesSummary,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    value = summary.assignedLicenses.toString(),
                    label = "Assignées",
                    color = MotiumPrimary,
                    textSecondaryColor = textSecondaryColor
                )
                StatItem(
                    value = summary.availableLicenses.toString(),
                    label = "Disponibles",
                    color = MotiumPrimary,
                    textSecondaryColor = textSecondaryColor
                )
                StatItem(
                    value = summary.suspendedLicenses.toString(),
                    label = "Suspendues",
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
private fun BillingAnchorCard(
    billingAnchorDay: Int?,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onSetAnchorDay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MotiumPrimary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Date de renouvellement",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (billingAnchorDay != null) {
                // Date is set - show it with modify button
                Text(
                    text = "Vos licences mensuelles sont renouvelees le $billingAnchorDay de chaque mois.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onSetAnchorDay,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MotiumPrimary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MotiumPrimary.copy(alpha = 0.5f))
                    )
                ) {
                    Text("Modifier")
                }
            } else {
                // Date not set - show explanation and button
                Text(
                    text = "Unifiez vos paiements mensuels en choisissant une date de renouvellement commune pour toutes vos licences.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSetAnchorDay,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary
                    )
                ) {
                    Text("Definir la date")
                }
            }
        }
    }
}

@Composable
private fun PricingInfoCard(
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MotiumPrimary.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
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
                    "Tarif mensuel: 5 € HT / mois / licence",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    "Soit 6 € TTC avec TVA 20%",
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tarif a vie: 120 € HT / licence",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    "Soit 144 € TTC - paiement unique",
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
private fun LicenseRow(
    license: License,
    textColor: Color,
    textSecondaryColor: Color,
    onAssign: () -> Unit,
    onRequestUnlink: () -> Unit,
    onCancelUnlink: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val effectiveStatus = license.effectiveStatus
    val isClickable = effectiveStatus == LicenseEffectiveStatus.AVAILABLE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (effectiveStatus == LicenseEffectiveStatus.AVAILABLE) {
                    Modifier.clickable(onClick = onAssign)
                } else {
                    Modifier
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon based on effectiveStatus
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when (effectiveStatus) {
                        LicenseEffectiveStatus.AVAILABLE -> MotiumPrimary.copy(alpha = 0.1f)
                        LicenseEffectiveStatus.ACTIVE -> MotiumPrimary.copy(alpha = 0.1f)
                        LicenseEffectiveStatus.PENDING_UNLINK -> PendingOrange.copy(alpha = 0.1f)
                        LicenseEffectiveStatus.SUSPENDED -> PendingOrange.copy(alpha = 0.1f)
                        else -> ErrorRed.copy(alpha = 0.1f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (effectiveStatus) {
                    LicenseEffectiveStatus.AVAILABLE -> Icons.Default.AddCircle
                    LicenseEffectiveStatus.ACTIVE -> Icons.Default.CheckCircle
                    LicenseEffectiveStatus.PENDING_UNLINK -> Icons.Default.Schedule
                    LicenseEffectiveStatus.SUSPENDED -> Icons.Default.Payment
                    else -> Icons.Default.Cancel
                },
                contentDescription = null,
                tint = when (effectiveStatus) {
                    LicenseEffectiveStatus.AVAILABLE -> MotiumPrimary
                    LicenseEffectiveStatus.ACTIVE -> MotiumPrimary
                    LicenseEffectiveStatus.PENDING_UNLINK -> PendingOrange
                    LicenseEffectiveStatus.SUSPENDED -> PendingOrange
                    else -> ErrorRed
                },
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Licence #${license.id.take(8)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                // Lifetime badge
                if (license.isLifetime) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFFFFD700),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "A VIE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }

            // Status text based on effectiveStatus
            Text(
                text = when (effectiveStatus) {
                    LicenseEffectiveStatus.AVAILABLE -> "Disponible - cliquez pour assigner"
                    LicenseEffectiveStatus.ACTIVE -> "Active"
                    LicenseEffectiveStatus.PENDING_UNLINK -> "Deliaison en cours"
                    LicenseEffectiveStatus.SUSPENDED -> "Suspendue - paiement en attente"
                    LicenseEffectiveStatus.CANCELLED -> "Annulee"
                    LicenseEffectiveStatus.INACTIVE -> "Inactive"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (effectiveStatus == LicenseEffectiveStatus.AVAILABLE) MotiumPrimary else textSecondaryColor
            )

            // Show linked account info
            if (license.isAssigned && !license.linkedAccountId.isNullOrEmpty()) {
                Text(
                    text = "Liee a: ${license.linkedAccountId?.take(8)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondaryColor
                )
            }

            // Show pending unlink/cancellation info
            if (effectiveStatus == LicenseEffectiveStatus.PENDING_UNLINK) {
                // Format the effective date for display
                val dateText = license.unlinkEffectiveAt?.let { effectiveAt ->
                    val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                    val date = Date(effectiveAt.toEpochMilliseconds())
                    dateFormat.format(date)
                }

                Text(
                    text = if (dateText != null) "Résiliation le $dateText" else "Résiliation en cours",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = PendingOrange
                )
            }

            // Expiry warning for monthly licenses
            if (!license.isLifetime && license.status == LicenseStatus.ACTIVE) {
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
        }

        // Status badge or pricing column
        Column(horizontalAlignment = Alignment.End) {
            when (effectiveStatus) {
                LicenseEffectiveStatus.AVAILABLE -> {
                    Surface(
                        color = MotiumPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Disponible",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MotiumPrimary
                        )
                    }
                }
                LicenseEffectiveStatus.PENDING_UNLINK -> {
                    Surface(
                        color = PendingOrange.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${license.daysUntilUnlinkEffective ?: 0}j",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PendingOrange
                        )
                    }
                }
                else -> {
                    if (license.isLifetime) {
                        Text(
                            text = "A vie",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD700)
                        )
                    } else if (license.status == LicenseStatus.ACTIVE) {
                        Text(
                            text = String.format("%.2f EUR", license.priceMonthlyTTC),
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
                }
            }
        }

        // Options menu for licenses with actions
        if ((license.status == LicenseStatus.ACTIVE && license.isAssigned) ||
            license.canDelete()) {
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
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp)
                    )
                ) {
                    // Options for assigned licenses
                    if (license.isAssigned) {
                        if (license.isPendingUnlink) {
                            // Option to cancel unlink request
                            DropdownMenuItem(
                                text = { Text("Annuler la deliaison", color = MotiumPrimary) },
                                onClick = {
                                    showMenu = false
                                    onCancelUnlink()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Undo,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        } else {
                            // Option to request unlink
                            DropdownMenuItem(
                                text = { Text("Delier la licence", color = PendingOrange) },
                                onClick = {
                                    showMenu = false
                                    onRequestUnlink()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.LinkOff,
                                        contentDescription = null,
                                        tint = PendingOrange
                                    )
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        }

                        // NOTE: "Annuler la licence" option removed for assigned licenses
                        // User must use "Délier/Résilier la licence" (effective at renewal date)
                        // Direct cancel was causing broken state (canceled but still linked)
                    }

                    // Delete option for available (unassigned) monthly licenses
                    if (license.canDelete()) {
                        DropdownMenuItem(
                            text = { Text("Supprimer", color = ErrorRed) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = ErrorRed
                                )
                            },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        )
                    }
                }
            }
        }
    }
}

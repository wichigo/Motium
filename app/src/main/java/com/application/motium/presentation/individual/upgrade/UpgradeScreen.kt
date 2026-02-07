package com.application.motium.presentation.individual.upgrade

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.presentation.theme.MotiumPrimary
import com.application.motium.presentation.components.DeferredPaymentConfig
import com.application.motium.presentation.components.StripeDeferredPaymentSheet
import com.application.motium.presentation.components.StripePaymentSheet
import com.application.motium.presentation.components.WithdrawalWaiverSection
import com.application.motium.presentation.components.createSubscriptionButtonLabel

/**
 * Upgrade screen for individual users to subscribe to Premium or Lifetime plans.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(
    onNavigateBack: () -> Unit,
    onUpgradeSuccess: () -> Unit,
    viewModel: UpgradeViewModel = viewModel(
        factory = UpgradeViewModel.Factory(LocalContext.current)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    // Navigate on success
    LaunchedEffect(uiState.paymentSuccess, uiState.isRefreshing) {
        if (uiState.paymentSuccess && !uiState.isRefreshing) {
            onUpgradeSuccess()
            viewModel.resetSuccessState()
        }
    }

    // Show Deferred PaymentSheet when ready (preferred mode - no email required upfront)
    uiState.deferredPaymentReady?.let { deferredState ->
        val isLifetime = uiState.selectedPlan == PlanType.LIFETIME
        StripeDeferredPaymentSheet(
            config = DeferredPaymentConfig(
                amountCents = deferredState.amountCents,
                currency = "eur",
                isSubscription = false
            ),
            merchantName = "Motium",
            primaryButtonLabel = createSubscriptionButtonLabel(deferredState.amountCents.toInt(), isLifetime),
            onCreateIntent = { paymentMethodId ->
                viewModel.confirmPayment(paymentMethodId)
            },
            onResult = { result ->
                viewModel.handlePaymentResult(result)
            }
        )
    }

    // Legacy: Show PaymentSheet when ready (with pre-created intent)
    uiState.paymentReady?.let { paymentState ->
        val isLifetime = uiState.selectedPlan == PlanType.LIFETIME
        StripePaymentSheet(
            clientSecret = paymentState.clientSecret,
            customerId = paymentState.customerId,
            ephemeralKey = paymentState.ephemeralKey,
            merchantName = "Motium",
            primaryButtonLabel = createSubscriptionButtonLabel(paymentState.amountCents, isLifetime),
            onResult = { result ->
                viewModel.handlePaymentResult(result)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passez à Premium") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = Color(0xFFFFD700),
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Débloquez toutes les fonctionnalités",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Trajets illimités, export PDF, et plus encore",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Features list
            FeaturesList()

            Spacer(modifier = Modifier.height(32.dp))

            // Plan selection
            Text(
                text = "Choisissez votre formule",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Monthly plan card
            PlanCard(
                title = "Mensuel",
                price = "${SubscriptionManager.INDIVIDUAL_MONTHLY_PRICE.formatPrice()}€",
                period = "/mois",
                description = "Résiliable à tout moment",
                isSelected = uiState.selectedPlan == PlanType.MONTHLY,
                onClick = { viewModel.selectPlan(PlanType.MONTHLY) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Lifetime plan card
            PlanCard(
                title = "À vie",
                price = "${SubscriptionManager.INDIVIDUAL_LIFETIME_PRICE.formatPrice()}€",
                period = "",
                description = "Paiement unique, accès permanent",
                isSelected = uiState.selectedPlan == PlanType.LIFETIME,
                onClick = { viewModel.selectPlan(PlanType.LIFETIME) },
                badge = "Économisez 50%"
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Withdrawal waiver section (only for trial users - French consumer law compliance)
            if (uiState.requiresWithdrawalWaiver) {
                WithdrawalWaiverSection(
                    state = uiState.waiverState,
                    onImmediateExecutionChanged = { viewModel.setImmediateExecutionAccepted(it) },
                    onWaiverChanged = { viewModel.setWaiverAccepted(it) },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Subscribe button
            Button(
                onClick = { viewModel.initializePayment() },
                enabled = uiState.canProceedToPayment,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary,
                    disabledContainerColor = MotiumPrimary.copy(alpha = 0.5f)
                )
            ) {
                if (uiState.isLoading || uiState.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (uiState.selectedPlan == PlanType.LIFETIME) {
                            "Acheter maintenant"
                        } else {
                            "S'abonner"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Helper text when waiver is required but not complete
            if (uiState.requiresWithdrawalWaiver && !uiState.waiverState.isComplete) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Veuillez accepter les conditions ci-dessus pour continuer",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Terms text
            Text(
                text = "En continuant, vous acceptez nos conditions d'utilisation. " +
                        "L'abonnement mensuel se renouvelle automatiquement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FeaturesList() {
    val features = listOf(
        "Trajets illimités",
        "Export PDF des trajets",
        "Calcul automatique des indemnités",
        "Historique complet",
        "Support prioritaire"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            features.forEach { feature ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MotiumPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = feature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    title: String,
    price: String,
    period: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    badge: String? = null
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MotiumPrimary else MaterialTheme.colorScheme.outline,
        label = "borderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MotiumPrimary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
        label = "backgroundColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(2.dp, borderColor)
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = price,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (period.isNotEmpty()) {
                        Text(
                            text = period,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }

            // Badge
            if (badge != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFF9800)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Selection indicator
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Sélectionné",
                    tint = MotiumPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(20.dp)
                )
            }
        }
    }
}

/**
 * Format price to remove unnecessary decimals
 */
private fun Double.formatPrice(): String {
    return if (this == this.toLong().toDouble()) {
        this.toLong().toString()
    } else {
        String.format("%.2f", this).replace(".", ",")
    }
}


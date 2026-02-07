package com.application.motium.presentation.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.WithdrawalWaiverRepository
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.AuthResult
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.domain.model.WithdrawalWaiver
import com.application.motium.domain.model.WithdrawalWaiverState
import com.application.motium.presentation.components.DeferredPaymentConfig
import com.application.motium.presentation.components.StripeDeferredPaymentSheet
import com.application.motium.presentation.components.WithdrawalWaiverSection
import com.application.motium.presentation.components.createSubscriptionButtonLabel
import com.application.motium.presentation.theme.MotiumGreen
import com.application.motium.presentation.auth.AuthViewModel
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Blocking screen shown when trial has expired.
 * User must subscribe to continue using the app.
 * Payment is handled directly in this screen via Stripe PaymentSheet.
 */
@Composable
fun TrialExpiredScreen(
    subscriptionManager: SubscriptionManager,
    authViewModel: AuthViewModel,
    onSubscribe: (SubscriptionType) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paymentState by subscriptionManager.paymentState.collectAsState()
    var selectedPlan by remember { mutableStateOf<SubscriptionType?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Payment state
    var isLoading by remember { mutableStateOf(false) }
    var showPaymentSheet by remember { mutableStateOf(false) }
    var paymentAmountCents by remember { mutableStateOf(0L) }
    var paymentPriceType by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var paymentSuccess by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Repositories for refresh
    val localUserRepository = remember { LocalUserRepository.getInstance(context) }
    val supabaseAuthRepository = remember { SupabaseAuthRepository.getInstance(context) }
    val withdrawalWaiverRepository = remember { WithdrawalWaiverRepository.getInstance(context) }

    // Withdrawal waiver state (French consumer law compliance - Article L221-28)
    var waiverState by remember { mutableStateOf(WithdrawalWaiverState()) }
    var isSavingWaiver by remember { mutableStateOf(false) }

    // Load user ID on start
    LaunchedEffect(Unit) {
        try {
            val user = localUserRepository.getLoggedInUser()
            userId = user?.id
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to load user: ${e.message}", "TrialExpired", e)
        }
    }

    // Handle payment success - refresh user from Supabase and update authState
    LaunchedEffect(paymentSuccess) {
        if (paymentSuccess && !isRefreshing) {
            isRefreshing = true
            val uid = userId ?: return@LaunchedEffect

            // Retry a few times to allow webhook to process
            var attempts = 0
            val maxAttempts = 5
            while (attempts < maxAttempts) {
                delay(2000)
                attempts++

                try {
                    val result = supabaseAuthRepository.getUserProfile(uid)
                    if (result is AuthResult.Success) {
                        val freshUser = result.data
                        localUserRepository.saveUser(freshUser, isLocallyConnected = true)

                        val expectedType = if (paymentPriceType == "individual_lifetime")
                            SubscriptionType.LIFETIME else SubscriptionType.PREMIUM

                        if (freshUser.subscription.type == expectedType) {
                            MotiumApplication.logger.i("âœ… Subscription updated to $expectedType", "TrialExpired")
                            // CRITICAL: Refresh authState BEFORE navigating to update LaunchedEffect dependencies
                            authViewModel.refreshAuthState {
                                isRefreshing = false
                                onSubscribe(expectedType)
                            }
                            return@LaunchedEffect
                        }
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Refresh attempt $attempts failed: ${e.message}", "TrialExpired", e)
                }
            }

            // Final sync attempt
            try {
                val result = supabaseAuthRepository.getUserProfile(uid)
                if (result is AuthResult.Success) {
                    localUserRepository.saveUser(result.data, isLocallyConnected = true)
                    // Refresh authState before navigating
                    authViewModel.refreshAuthState {
                        onSubscribe(result.data.subscription.type)
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("Final sync failed: ${e.message}", "TrialExpired", e)
            }

            isRefreshing = false
        }
    }

    // Show PaymentSheet when ready
    if (showPaymentSheet && userId != null) {
        val isLifetime = paymentPriceType == "individual_lifetime"
        StripeDeferredPaymentSheet(
            config = DeferredPaymentConfig(
                amountCents = paymentAmountCents,
                currency = "eur",
                isSubscription = false
            ),
            merchantName = "Motium",
            primaryButtonLabel = createSubscriptionButtonLabel(paymentAmountCents.toInt(), isLifetime),
            onCreateIntent = { paymentMethodId ->
                subscriptionManager.confirmPaymentWithMethod(
                    paymentMethodId = paymentMethodId,
                    userId = userId!!,
                    proAccountId = null,
                    priceType = paymentPriceType,
                    quantity = 1
                )
            },
            onResult = { result ->
                showPaymentSheet = false
                when (result) {
                    is PaymentSheetResult.Completed -> {
                        MotiumApplication.logger.i("âœ… Payment completed", "TrialExpired")
                        paymentSuccess = true
                    }
                    is PaymentSheetResult.Canceled -> {
                        MotiumApplication.logger.d("Payment canceled", "TrialExpired")
                    }
                    is PaymentSheetResult.Failed -> {
                        MotiumApplication.logger.e("Payment failed: ${result.error.message}", "TrialExpired")
                        errorMessage = result.error.message ?: "Le paiement a échoué"
                    }
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Lock icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Votre essai est terminé",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pour continuer à utiliser Motium et accéder à toutes vos données, veuillez choisir un abonnement.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Monthly subscription card
            SubscriptionOptionCard(
                title = "Mensuel",
                price = "${SubscriptionManager.INDIVIDUAL_MONTHLY_PRICE.toInt()}€",
                period = "/mois",
                description = "Flexibilité maximale, sans engagement",
                isSelected = selectedPlan == SubscriptionType.PREMIUM,
                isRecommended = true,
                onClick = { selectedPlan = SubscriptionType.PREMIUM }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Lifetime subscription card
            SubscriptionOptionCard(
                title = "À vie",
                price = "${SubscriptionManager.INDIVIDUAL_LIFETIME_PRICE.toInt()}€",
                period = "une fois",
                description = "Accès illimité pour toujours",
                isSelected = selectedPlan == SubscriptionType.LIFETIME,
                isRecommended = false,
                savingsText = "Économisez après 24 mois",
                onClick = { selectedPlan = SubscriptionType.LIFETIME }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Withdrawal waiver section (French consumer law compliance - Article L221-28)
            WithdrawalWaiverSection(
                state = waiverState,
                onImmediateExecutionChanged = { waiverState = waiverState.copy(acceptedImmediateExecution = it) },
                onWaiverChanged = { waiverState = waiverState.copy(acceptedWaiver = it) }
            )

            // Helper text when waiver is not complete
            if (!waiverState.isComplete) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Veuillez accepter les conditions ci-dessus pour continuer",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Subscribe button - directly triggers Stripe payment
            Button(
                onClick = {
                    selectedPlan?.let { plan ->
                        // Save waiver before proceeding to payment
                        scope.launch {
                            isSavingWaiver = true
                            val waiver = WithdrawalWaiver(
                                acceptedImmediateExecution = waiverState.acceptedImmediateExecution,
                                acceptedWaiver = waiverState.acceptedWaiver,
                                appVersion = BuildConfig.VERSION_NAME
                            )
                            val result = withdrawalWaiverRepository.saveWaiver(waiver)
                            isSavingWaiver = false

                            if (result.isFailure) {
                                errorMessage = "Erreur lors de l'enregistrement du consentement. Veuillez réessayer."
                                return@launch
                            }

                            val isLifetime = plan == SubscriptionType.LIFETIME
                            paymentPriceType = if (isLifetime) "individual_lifetime" else "individual_monthly"
                            paymentAmountCents = subscriptionManager.getAmountCents(paymentPriceType, 1)
                            showPaymentSheet = true
                        }
                    }
                },
                enabled = selectedPlan != null && !isLoading && !isRefreshing && !isSavingWaiver && userId != null && waiverState.isComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumGreen
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading || isRefreshing || isSavingWaiver) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "S'abonner maintenant",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Features included
            Text(
                text = "Ce qui est inclus :",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            FeatureItem("Trajets illimités")
            FeatureItem("Export PDF et Excel")
            FeatureItem("Synchronisation cloud")
            FeatureItem("Historique complet")
            FeatureItem("Support prioritaire")

            Spacer(modifier = Modifier.weight(1f))

            // Logout option
            TextButton(
                onClick = { showLogoutConfirm = true }
            ) {
                Text(
                    text = "Me déconnecter",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        // Error message
        errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { errorMessage = null }) {
                        Text("OK", color = Color.White)
                    }
                }
            ) {
                Text(error)
            }
        }
    }

    // Logout confirmation dialog
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = { Text("Se déconnecter ?") },
            text = {
                Text("Vos données resteront sauvegardées. Vous pourrez vous reconnecter et vous abonner plus tard.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text("Me déconnecter", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
private fun SubscriptionOptionCard(
    title: String,
    price: String,
    period: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean,
    savingsText: String? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MotiumGreen.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MotiumGreen)
        } else {
            null
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isRecommended) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MotiumGreen,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Recommandé",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = price,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MotiumGreen else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = period,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            savingsText?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MotiumGreen,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MotiumGreen
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


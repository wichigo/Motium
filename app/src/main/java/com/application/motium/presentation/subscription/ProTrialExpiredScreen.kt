package com.application.motium.presentation.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CheckCircle
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
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.repository.OfflineFirstLicenseRepository
import com.application.motium.data.repository.OfflineFirstProAccountRepository
import com.application.motium.data.supabase.LicenseRemoteDataSource as SupabaseLicenseRepository
import com.application.motium.data.supabase.ProAccountRemoteDataSource
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.License
import com.application.motium.domain.model.ProAccount
import com.application.motium.presentation.components.DeferredPaymentConfig
import com.application.motium.presentation.components.StripeDeferredPaymentSheet
import com.application.motium.presentation.components.createLicenseButtonLabel
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.MotiumGreen
import com.application.motium.presentation.theme.MotiumPrimary
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Blocking screen shown when Pro account trial has expired AND owner doesn't have a license.
 * User must either assign an available license or purchase one to continue.
 */
@Composable
fun ProTrialExpiredScreen(
    subscriptionManager: SubscriptionManager,
    authViewModel: AuthViewModel,
    onLicenseActivated: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Repositories - Offline-first for reads, Supabase for writes
    val localUserRepository = remember { LocalUserRepository.getInstance(context) }
    val offlineProAccountRepo = remember { OfflineFirstProAccountRepository.getInstance(context) }
    val offlineLicenseRepo = remember { OfflineFirstLicenseRepository.getInstance(context) }
    val supabaseLicenseRepo = remember { SupabaseLicenseRepository.getInstance(context) }
    val supabaseProAccountRepo = remember { ProAccountRemoteDataSource.getInstance(context) }
    val licenseCacheManager = remember { com.application.motium.data.repository.LicenseCacheManager.getInstance(context) }

    // Get user ID synchronously (needed for Flow collection)
    var userId by remember { mutableStateOf<String?>(null) }
    var currentUser by remember { mutableStateOf<com.application.motium.domain.model.User?>(null) }
    var proAccountId by remember { mutableStateOf<String?>(null) }
    var networkProAccount by remember { mutableStateOf<ProAccount?>(null) }
    var networkFetchAttempted by remember { mutableStateOf(false) }
    var isLoadingProAccount by remember { mutableStateOf(true) }
    var isCreatingProAccount by remember { mutableStateOf(false) }

    // Check if user is ENTERPRISE role (allows purchase even without pro_account)
    val isEnterpriseUser = currentUser?.role == com.application.motium.domain.model.UserRole.ENTERPRISE

    // Load user ID and fetch pro account from network immediately
    // Using scope instead of LaunchedEffect to survive recomposition
    // FIX: Properly handle CancellationException to avoid crash during navigation
    LaunchedEffect(Unit) {
        val user = localUserRepository.getLoggedInUser()
        userId = user?.id
        currentUser = user
        MotiumApplication.logger.i("ProTrialExpiredScreen: userId loaded = ${user?.id}, role = ${user?.role}", "ProTrialExpired")

        // Fetch from network immediately (don't wait for local cache check)
        if (user?.id != null && !networkFetchAttempted) {
            networkFetchAttempted = true
            MotiumApplication.logger.i("ProTrialExpiredScreen: Fetching pro account from network", "ProTrialExpired")
            try {
                val result = supabaseProAccountRepo.getProAccount(user.id)
                result.fold(
                    onSuccess = { dto ->
                        if (dto != null) {
                            networkProAccount = dto.toDomainModel()
                            proAccountId = dto.id
                            MotiumApplication.logger.i("ProTrialExpiredScreen: Fetched pro account from network: ${dto.id}", "ProTrialExpired")
                        } else {
                            MotiumApplication.logger.w("ProTrialExpiredScreen: No pro account found on network", "ProTrialExpired")
                        }
                    },
                    onFailure = { e ->
                        // FIX: Don't log CancellationException as error - it's expected during navigation
                        if (e is CancellationException) {
                            MotiumApplication.logger.d("ProTrialExpiredScreen: Network fetch cancelled (navigation)", "ProTrialExpired")
                            return@LaunchedEffect
                        }
                        MotiumApplication.logger.e("ProTrialExpiredScreen: Network fetch failed: ${e.message}", "ProTrialExpired", e)
                    }
                )
            } catch (e: CancellationException) {
                // Expected during navigation - silently return
                MotiumApplication.logger.d("ProTrialExpiredScreen: LaunchedEffect cancelled (navigation)", "ProTrialExpired")
                return@LaunchedEffect
            } catch (e: Exception) {
                MotiumApplication.logger.e("ProTrialExpiredScreen: Network fetch exception: ${e.message}", "ProTrialExpired", e)
            }
        }
        isLoadingProAccount = false
    }

    // Collect Pro account reactively from local DB
    val localProAccount by offlineProAccountRepo.getProAccountForUser(userId ?: "")
        .collectAsState(initial = null)

    // Use local or network pro account
    val proAccount = localProAccount ?: networkProAccount

    // Update proAccountId when localProAccount changes
    LaunchedEffect(localProAccount) {
        val local = localProAccount
        if (local != null && proAccountId == null) {
            proAccountId = local.id
            MotiumApplication.logger.i("ProTrialExpiredScreen: proAccountId from local = ${local.id}", "ProTrialExpired")
        }
    }

    // Collect available licenses reactively from local DB (offline-first)
    val localLicenses by offlineLicenseRepo.getAvailableLicenses(proAccountId ?: "")
        .collectAsState(initial = emptyList())

    // Also fetch licenses from network if local is empty
    // FIX: Use forceRefresh to persist licenses to Room DB so reactive Flow updates
    var networkLicenses by remember { mutableStateOf<List<License>>(emptyList()) }
    var networkFetchLicensesAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(proAccountId, localLicenses, networkFetchLicensesAttempted) {
        if (proAccountId != null && localLicenses.isEmpty() && !networkFetchLicensesAttempted) {
            networkFetchLicensesAttempted = true
            try {
                MotiumApplication.logger.i("ProTrialExpiredScreen: Local licenses empty, forcing refresh from network", "ProTrialExpired")
                // Use forceRefresh to fetch AND persist to Room DB
                // This will update the reactive localLicenses Flow automatically
                val result = licenseCacheManager.forceRefresh(proAccountId!!)
                result.fold(
                    onSuccess = { licenses ->
                        // Also store in networkLicenses as immediate fallback
                        // Filter: unassigned (linkedAccountId == null) AND status is active or available
                        val available = licenses.filter {
                            it.linkedAccountId == null &&
                            (it.status == com.application.motium.domain.model.LicenseStatus.ACTIVE ||
                             it.status == com.application.motium.domain.model.LicenseStatus.AVAILABLE)
                        }
                        networkLicenses = available
                        MotiumApplication.logger.i("ProTrialExpiredScreen: forceRefresh successful, ${available.size} available licenses persisted to Room", "ProTrialExpired")
                    },
                    onFailure = { e ->
                        // Ignore CancellationException - expected when navigating away
                        if (e is CancellationException) {
                            MotiumApplication.logger.d("ProTrialExpiredScreen: forceRefresh cancelled (navigation)", "ProTrialExpired")
                            return@LaunchedEffect
                        }
                        MotiumApplication.logger.w("ProTrialExpiredScreen: forceRefresh failed: ${e.message}", "ProTrialExpired")
                        // Fallback: try direct network fetch without persistence
                        try {
                            val directResult = supabaseLicenseRepo.getAvailableLicenses(proAccountId!!)
                            directResult.fold(
                                onSuccess = { licenses ->
                                    networkLicenses = licenses
                                    MotiumApplication.logger.i("ProTrialExpiredScreen: Fallback direct fetch got ${licenses.size} licenses", "ProTrialExpired")
                                },
                                onFailure = { e2 ->
                                    if (e2 !is CancellationException) {
                                        MotiumApplication.logger.w("ProTrialExpiredScreen: Fallback also failed: ${e2.message}", "ProTrialExpired")
                                    }
                                }
                            )
                        } catch (e2: CancellationException) {
                            // Expected during navigation - silently ignore
                        } catch (e2: Exception) {
                            MotiumApplication.logger.w("ProTrialExpiredScreen: Fallback exception: ${e2.message}", "ProTrialExpired")
                        }
                    }
                )
            } catch (e: CancellationException) {
                // Expected during navigation - silently ignore
                MotiumApplication.logger.d("ProTrialExpiredScreen: License fetch cancelled (navigation)", "ProTrialExpired")
            } catch (e: Exception) {
                MotiumApplication.logger.w("ProTrialExpiredScreen: License fetch exception: ${e.message}", "ProTrialExpired")
            }
        }
    }

    // Use local or network licenses
    val availableLicenses = if (localLicenses.isNotEmpty()) localLicenses else networkLicenses

    // Derived state
    val hasAvailableLicense = availableLicenses.isNotEmpty()
    val lifetimeLicenses = availableLicenses.filter { it.isLifetime }
    val monthlyLicenses = availableLicenses.filter { !it.isLifetime }
    val hasBothTypes = lifetimeLicenses.isNotEmpty() && monthlyLicenses.isNotEmpty()

    // UI State - show loading only while we're still trying to load data
    val isLoading = isLoadingProAccount
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // License type selection state
    var selectedLicenseType by remember { mutableStateOf<String?>(null) }

    // Auto-select if only one type available
    LaunchedEffect(hasAvailableLicense, hasBothTypes, lifetimeLicenses, monthlyLicenses) {
        if (!hasBothTypes && hasAvailableLicense && selectedLicenseType == null) {
            selectedLicenseType = if (lifetimeLicenses.isNotEmpty()) "lifetime" else "monthly"
        }
    }

    // Payment state (for purchasing new licenses)
    var showPaymentSheet by remember { mutableStateOf(false) }
    var paymentAmountCents by remember { mutableStateOf(0L) }
    var paymentPriceType by remember { mutableStateOf("") }
    var selectedPurchasePlan by remember { mutableStateOf<String?>(null) }
    var isAssigningLicense by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var paymentSuccess by remember { mutableStateOf(false) }

    // Handle payment success - wait for webhook then assign license and refresh authState
    LaunchedEffect(paymentSuccess) {
        if (paymentSuccess && !isRefreshing) {
            isRefreshing = true
            val pId = proAccountId ?: return@LaunchedEffect
            val uId = userId ?: return@LaunchedEffect

            // Retry loop to wait for webhook to create license
            // FIX: Use forceRefresh to ensure licenses are persisted to Room
            var attempts = 0
            val maxAttempts = 5
            while (attempts < maxAttempts) {
                delay(2000)
                attempts++

                try {
                    MotiumApplication.logger.i("Post-payment attempt $attempts: forcing refresh from network", "ProTrialExpired")

                    // Use forceRefresh to fetch AND persist to Room DB
                    val refreshResult = licenseCacheManager.forceRefresh(pId)
                    var licenses: List<License>? = null

                    refreshResult.fold(
                        onSuccess = { allLicenses ->
                            // Filter for available licenses (not assigned, status active or available)
                            licenses = allLicenses.filter {
                                it.linkedAccountId == null &&
                                (it.status == com.application.motium.domain.model.LicenseStatus.ACTIVE ||
                                 it.status == com.application.motium.domain.model.LicenseStatus.AVAILABLE)
                            }
                            MotiumApplication.logger.i("Post-payment: forceRefresh got ${licenses?.size ?: 0} available licenses", "ProTrialExpired")
                        },
                        onFailure = { e ->
                            MotiumApplication.logger.w("Post-payment: forceRefresh failed, trying direct: ${e.message}", "ProTrialExpired")
                            // Fallback to direct network fetch
                            licenses = supabaseLicenseRepo.getAvailableLicenses(pId).getOrNull()
                        }
                    )

                    if (!licenses.isNullOrEmpty()) {
                        // Assign the first available license to owner
                        val assignResult = supabaseLicenseRepo.assignLicenseToOwner(pId, uId)
                        if (assignResult.isSuccess) {
                            MotiumApplication.logger.i("License assigned to owner after purchase", "ProTrialExpired")
                            // CRITICAL: Refresh authState before navigating
                            authViewModel.refreshAuthState {
                                isRefreshing = false
                                onLicenseActivated()
                            }
                            return@LaunchedEffect
                        }
                    }
                } catch (e: Exception) {
                    MotiumApplication.logger.e("Attempt $attempts failed: ${e.message}", "ProTrialExpired", e)
                }
            }

            // Final attempt - force refresh one more time
            try {
                MotiumApplication.logger.i("Post-payment final attempt: forcing refresh", "ProTrialExpired")
                val refreshResult = licenseCacheManager.forceRefresh(pId)
                var licenses: List<License>? = null

                refreshResult.fold(
                    onSuccess = { allLicenses ->
                        licenses = allLicenses.filter {
                            it.linkedAccountId == null &&
                            (it.status == com.application.motium.domain.model.LicenseStatus.ACTIVE ||
                             it.status == com.application.motium.domain.model.LicenseStatus.AVAILABLE)
                        }
                    },
                    onFailure = { e ->
                        MotiumApplication.logger.w("Final forceRefresh failed: ${e.message}", "ProTrialExpired")
                        licenses = supabaseLicenseRepo.getAvailableLicenses(pId).getOrNull()
                    }
                )

                if (!licenses.isNullOrEmpty()) {
                    val assignResult = supabaseLicenseRepo.assignLicenseToOwner(pId, uId)
                    if (assignResult.isSuccess) {
                        // Refresh authState before navigating
                        authViewModel.refreshAuthState {
                            onLicenseActivated()
                        }
                        return@LaunchedEffect
                    }
                }
                // FIX [P2]: Message post-paiement plus actionnable
                errorMessage = "Licence achetée avec succès ! Fermez et rouvrez l'app pour l'activer, ou appuyez sur le bouton ci-dessous."
                // Reset networkFetchLicensesAttempted to allow re-fetch
                networkFetchLicensesAttempted = false
            } catch (e: Exception) {
                errorMessage = "Erreur: ${e.message}"
            }
            isRefreshing = false
        }
    }

    // Show PaymentSheet when ready
    if (showPaymentSheet && proAccountId != null) {
        val isLifetime = paymentPriceType == "pro_license_lifetime"
        StripeDeferredPaymentSheet(
            config = DeferredPaymentConfig(
                amountCents = paymentAmountCents,
                currency = "eur",
                isSubscription = false
            ),
            merchantName = "Motium Pro",
            primaryButtonLabel = createLicenseButtonLabel(paymentAmountCents.toInt(), 1, isLifetime),
            onCreateIntent = { paymentMethodId ->
                subscriptionManager.confirmPaymentWithMethod(
                    paymentMethodId = paymentMethodId,
                    userId = null,
                    proAccountId = proAccountId,
                    priceType = paymentPriceType,
                    quantity = 1
                )
            },
            onResult = { result ->
                showPaymentSheet = false
                when (result) {
                    is PaymentSheetResult.Completed -> {
                        MotiumApplication.logger.i("Payment completed for Pro license", "ProTrialExpired")
                        paymentSuccess = true
                    }
                    is PaymentSheetResult.Canceled -> {
                        MotiumApplication.logger.d("Payment canceled", "ProTrialExpired")
                    }
                    is PaymentSheetResult.Failed -> {
                        MotiumApplication.logger.e("Payment failed: ${result.error.message}", "ProTrialExpired")
                        errorMessage = result.error.message ?: "Le paiement a echoue"
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
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MotiumGreen
            )
        } else {
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
                    text = "Licence requise",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (hasAvailableLicense) {
                        "Vous avez ${availableLicenses.size} licence(s) disponible(s) dans votre pool. Attribuez-vous une licence pour acceder a l'application."
                    } else {
                        "Achetez et attribuez-vous une licence pour acceder a Motium Pro."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (hasAvailableLicense) {
                    // Show license type selection if both types available
                    if (hasBothTypes) {
                        Text(
                            text = "Choisissez votre type de licence :",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Lifetime option
                        AvailableLicenseTypeCard(
                            title = "Licence a vie",
                            description = "Acces permanent sans renouvellement",
                            count = lifetimeLicenses.size,
                            isSelected = selectedLicenseType == "lifetime",
                            isLifetime = true,
                            onClick = { selectedLicenseType = "lifetime" }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Monthly option
                        AvailableLicenseTypeCard(
                            title = "Licence mensuelle",
                            description = "Renouvellement automatique chaque mois",
                            count = monthlyLicenses.size,
                            isSelected = selectedLicenseType == "monthly",
                            isLifetime = false,
                            onClick = { selectedLicenseType = "monthly" }
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    } else {
                        // Only one type available - show info card
                        val singleTypeLicenses = if (lifetimeLicenses.isNotEmpty()) lifetimeLicenses else monthlyLicenses
                        val isLifetimeType = lifetimeLicenses.isNotEmpty()

                        AvailableLicenseTypeCard(
                            title = if (isLifetimeType) "Licence a vie" else "Licence mensuelle",
                            description = if (isLifetimeType) "Acces permanent" else "Renouvellement mensuel",
                            count = singleTypeLicenses.size,
                            isSelected = true,
                            isLifetime = isLifetimeType,
                            onClick = { }
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Assign button
                    Button(
                        onClick = {
                            scope.launch {
                                isAssigningLicense = true
                                try {
                                    val pId = proAccountId ?: return@launch
                                    val uId = userId ?: return@launch

                                    // Get the license to assign based on selection
                                    val licenseToAssign = if (selectedLicenseType == "lifetime") {
                                        lifetimeLicenses.firstOrNull()
                                    } else {
                                        monthlyLicenses.firstOrNull()
                                    }

                                    if (licenseToAssign == null) {
                                        errorMessage = "Aucune licence disponible pour ce type"
                                        return@launch
                                    }

                                    // Use Supabase repository for write operation (needs server validation)
                                    val result = supabaseLicenseRepo.assignSpecificLicenseToOwner(
                                        proAccountId = pId,
                                        ownerUserId = uId,
                                        licenseId = licenseToAssign.id
                                    )
                                    result.fold(
                                        onSuccess = { license ->
                                            MotiumApplication.logger.i(
                                                "License ${license.id} (${if (license.isLifetime) "lifetime" else "monthly"}) assigned to owner",
                                                "ProTrialExpired"
                                            )
                                            // CRITICAL: Refresh authState before navigating
                                            authViewModel.refreshAuthState {
                                                onLicenseActivated()
                                            }
                                        },
                                        onFailure = { e ->
                                            errorMessage = "Erreur: ${e.message}"
                                        }
                                    )
                                } catch (e: Exception) {
                                    errorMessage = "Erreur: ${e.message}"
                                } finally {
                                    isAssigningLicense = false
                                }
                            }
                        },
                        enabled = selectedLicenseType != null && !isAssigningLicense,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isAssigningLicense) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "M'attribuer cette licence",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Show purchase options when no licenses available
                    LicenseOptionCard(
                        title = "Mensuel",
                        price = "6",
                        period = "/mois",
                        description = "Flexibilite maximale, sans engagement",
                        isSelected = selectedPurchasePlan == "monthly",
                        isRecommended = true,
                        onClick = { selectedPurchasePlan = "monthly" }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LicenseOptionCard(
                        title = "A vie",
                        price = "144",
                        period = "une fois",
                        description = "Acces illimite pour toujours",
                        isSelected = selectedPurchasePlan == "lifetime",
                        isRecommended = false,
                        savingsText = "Economisez apres 24 mois",
                        onClick = { selectedPurchasePlan = "lifetime" }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Purchase button
                    Button(
                        onClick = {
                            selectedPurchasePlan?.let { plan ->
                                scope.launch {
                                    // If no pro_account exists but user is ENTERPRISE, create one automatically
                                    var effectiveProAccountId = proAccountId
                                    if (effectiveProAccountId == null && isEnterpriseUser && currentUser != null) {
                                        isCreatingProAccount = true
                                        MotiumApplication.logger.i("ProTrialExpiredScreen: Creating pro_account for ENTERPRISE user", "ProTrialExpired")
                                        try {
                                            val createResult = supabaseProAccountRepo.createProAccount(
                                                userId = currentUser!!.id,
                                                companyName = currentUser!!.name, // Use user name as default company name
                                                billingEmail = currentUser!!.email
                                            )
                                            createResult.fold(
                                                onSuccess = { dto ->
                                                    effectiveProAccountId = dto.id
                                                    proAccountId = dto.id
                                                    networkProAccount = dto.toDomainModel()
                                                    MotiumApplication.logger.i("ProTrialExpiredScreen: Pro account created: ${dto.id}", "ProTrialExpired")
                                                },
                                                onFailure = { e ->
                                                    errorMessage = "Erreur création compte Pro: ${e.message}"
                                                    MotiumApplication.logger.e("ProTrialExpiredScreen: Failed to create pro_account: ${e.message}", "ProTrialExpired", e)
                                                }
                                            )
                                        } catch (e: Exception) {
                                            errorMessage = "Erreur: ${e.message}"
                                            MotiumApplication.logger.e("ProTrialExpiredScreen: Exception creating pro_account: ${e.message}", "ProTrialExpired", e)
                                        } finally {
                                            isCreatingProAccount = false
                                        }
                                    }

                                    // Proceed with payment if we have a pro_account ID
                                    if (effectiveProAccountId != null) {
                                        val isLifetime = plan == "lifetime"
                                        paymentPriceType = if (isLifetime) "pro_license_lifetime" else "pro_license_monthly"
                                        paymentAmountCents = subscriptionManager.getAmountCents(paymentPriceType, 1)
                                        showPaymentSheet = true
                                    }
                                }
                            }
                        },
                        enabled = selectedPurchasePlan != null && !isRefreshing && !isCreatingProAccount && isEnterpriseUser,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isRefreshing || isCreatingProAccount) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Acheter et activer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Features included
                Text(
                    text = "Ce qui est inclus :",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                ProFeatureItem("Acces complet a l'application")
                ProFeatureItem("Trajets illimites")
                ProFeatureItem("Export PDF et Excel")
                ProFeatureItem("Gestion des comptes lies")
                ProFeatureItem("Synchronisation cloud")
                ProFeatureItem("Support prioritaire")

                Spacer(modifier = Modifier.weight(1f))

                // Logout option
                TextButton(
                    onClick = { showLogoutConfirm = true }
                ) {
                    Text(
                        text = "Me deconnecter",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
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
            title = { Text("Se deconnecter ?") },
            text = {
                Text("Vos donnees resteront sauvegardees. Vous pourrez vous reconnecter et activer une licence plus tard.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text("Me deconnecter", color = MaterialTheme.colorScheme.error)
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
private fun AvailableLicenseTypeCard(
    title: String,
    description: String,
    count: Int,
    isSelected: Boolean,
    isLifetime: Boolean,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Surface(
                color = if (isLifetime) MotiumGreen else MotiumPrimary,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "$count dispo.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LicenseOptionCard(
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
                                    text = "Recommande",
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
                        text = "${price}EUR",
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
private fun ProFeatureItem(text: String) {
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


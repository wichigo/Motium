package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.LinkedAccountRemoteDataSource
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.data.supabase.LicenseRemoteDataSource
import com.application.motium.data.supabase.ProSettingsRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.data.supabase.TripRemoteDataSource
import com.application.motium.data.supabase.VehicleRemoteDataSource
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.License
import com.application.motium.domain.model.LicenseEffectiveStatus
import com.application.motium.domain.model.LinkStatus
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.pro.licenses.UnlinkConfirmDialog
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Screen displaying details of a linked user
 * Shows profile, contact info, activity summary and access to trips
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailsScreen(
    accountId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToUserTrips: (String) -> Unit = {},
    onNavigateToUserVehicles: (String) -> Unit = {},
    onNavigateToUserExpenses: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val themeManager = remember { ThemeManager.getInstance(context) }
    val linkedAccountRemoteDataSource = remember { LinkedAccountRemoteDataSource.getInstance(context) }
    val licenseRemoteDataSource = remember { LicenseRemoteDataSource.getInstance(context) }
    val subscriptionManager = remember { SubscriptionManager.getInstance(context) }
    val tripRemoteDataSource = remember { TripRemoteDataSource.getInstance(context) }
    val vehicleRemoteDataSource = remember { VehicleRemoteDataSource.getInstance(context) }
    val offlineFirstLicenseRepo = remember { com.application.motium.data.repository.OfflineFirstLicenseRepository.getInstance(context) }
    val licenseCacheManager = remember { com.application.motium.data.repository.LicenseCacheManager.getInstance(context) }
    val authRepository = remember { SupabaseAuthRepository.getInstance(context) }
    val proSettingsRepository = remember { ProSettingsRepository.getInstance(context) }

    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // State
    var user by remember { mutableStateOf<LinkedUserDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Activity stats
    var totalTrips by remember { mutableStateOf(0) }
    var totalMileage by remember { mutableStateOf(0.0) }
    var associatedVehicles by remember { mutableStateOf(0) }

    // License state
    var license by remember { mutableStateOf<License?>(null) }
    var availableLicensesCount by remember { mutableStateOf(0) }
    var availableLicenses by remember { mutableStateOf<List<License>>(emptyList()) }
    var proAccountId by remember { mutableStateOf<String?>(null) }

    // Department state
    var availableDepartments by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDepartmentDialog by remember { mutableStateOf(false) }
    var isUpdatingDepartment by remember { mutableStateOf(false) }

    // Dialog states
    var showAssignLicenseDialog by remember { mutableStateOf(false) }
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }
    var isUnlinking by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    // Load user details, stats, and license
    LaunchedEffect(accountId) {
        isLoading = true
        try {
            // Get Pro account ID first
            proAccountId = authRepository.getCurrentProAccountId()

            // Load user info
            val result = linkedAccountRemoteDataSource.getLinkedUserById(accountId)
            result.fold(
                onSuccess = { linkedUser ->
                    user = linkedUser

                    // Load stats and license from Supabase
                    coroutineScope.launch(Dispatchers.IO) {
                        // Get validated trips for this user
                        val tripsResult = tripRemoteDataSource.getAllTrips(accountId)
                        tripsResult.fold(
                            onSuccess = { trips ->
                                val validatedTrips = trips.filter { it.isValidated }
                                totalTrips = validatedTrips.size
                                totalMileage = validatedTrips.sumOf { it.distanceKm } // Already in km
                            },
                            onFailure = { /* Keep defaults */ }
                        )

                        // Get vehicles count
                        try {
                            val vehicles = vehicleRemoteDataSource.getAllVehiclesForUser(accountId)
                            associatedVehicles = vehicles.size
                        } catch (e: Exception) {
                            // Keep default
                        }

                        // Load license for this account - offline-first pattern
                        proAccountId?.let { proId ->
                            try {
                                // Offline-first: read from local DB (cache-first manager also uses Room)
                                license = licenseCacheManager.getLicenseForAccount(proId, accountId).first()
                                availableLicenses = licenseCacheManager.getAvailableLicensesOnce(proId)
                                availableLicensesCount = availableLicenses.size
                            } catch (e: Exception) {
                                com.application.motium.MotiumApplication.logger.e(
                                    "Error loading license: ${e.message}",
                                    "AccountDetailsScreen",
                                    e
                                )
                            }

                            // Load available departments
                            try {
                                availableDepartments = proSettingsRepository.getDepartments(proId)
                            } catch (e: Exception) {
                                // Departments are optional
                            }
                        }
                    }
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

    // Helper function to reload license data - offline-first pattern
    fun reloadLicenseData() {
        coroutineScope.launch(Dispatchers.IO) {
            proAccountId?.let { proId ->
                try {
                    // Offline-first: read from local DB
                    license = licenseCacheManager.getLicenseForAccount(proId, accountId).first()
                    availableLicenses = licenseCacheManager.getAvailableLicensesOnce(proId)
                    availableLicensesCount = availableLicenses.size
                } catch (e: Exception) {
                    com.application.motium.MotiumApplication.logger.e(
                        "Error reloading license: ${e.message}",
                        "AccountDetailsScreen",
                        e
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Détails du collaborateur",
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
                            modifier = Modifier.size(64.dp),
                            tint = ErrorRed
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Erreur: $error",
                            color = textSecondaryColor
                        )
                    }
                }
            }
            user != null -> {
                val isPersonalLifetime = user?.personalSubscriptionType?.equals("LIFETIME", ignoreCase = true) == true
                val hasProAccess = license != null || isPersonalLifetime
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
                    ) {
                        // Profile section
                        item {
                            ProfileSection(
                                user = user!!,
                                license = license,
                                isPersonalLifetime = isPersonalLifetime,
                                cardColor = cardColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor
                            )
                        }

                        // Contact Information section
                        item {
                            ContactInformationSection(
                                user = user!!,
                                cardColor = cardColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor
                            )
                        }

                        // Department section
                        item {
                            DepartmentSection(
                                currentDepartment = user!!.department,
                                isUpdating = isUpdatingDepartment,
                                cardColor = cardColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor,
                                onEditDepartment = { showDepartmentDialog = true }
                            )
                        }

                        // Activity Summary section
                        item {
                            ActivitySummarySection(
                                totalTrips = totalTrips,
                                totalMileage = totalMileage,
                                associatedVehicles = associatedVehicles,
                                cardColor = cardColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor
                            )
                        }

                        // License section
                        item {
                            LicenseSection(
                                license = license,
                                isPersonalLifetime = isPersonalLifetime,
                                availableLicensesCount = availableLicensesCount,
                                cardColor = cardColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor,
                                onAssignLicense = { showAssignLicenseDialog = true },
                                onRequestUnlink = { showUnlinkConfirmDialog = true },
                                onCancelUnlink = {
                                    license?.let { lic ->
                                        proAccountId?.let { proId ->
                                            coroutineScope.launch(Dispatchers.IO) {
                                                try {
                                                    offlineFirstLicenseRepo.cancelUnlinkRequest(lic.id, proId)
                                                    successMessage = "Demande de deliaison annulee"
                                                    reloadLicenseData()
                                                } catch (e: Exception) {
                                                    error = e.message
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        if (hasProAccess) {
                            item {
                                AccessActionsSection(
                                    associatedVehicles = associatedVehicles,
                                    backgroundColor = backgroundColor,
                                    onViewTrips = { onNavigateToUserTrips(accountId) },
                                    onViewVehicles = { onNavigateToUserVehicles(accountId) },
                                    onViewExpenses = { onNavigateToUserExpenses(accountId) }
                                )
                            }
                        }
                    }

                }
            }
        }
    }

    // Assign License Dialog - simple selection from available licenses
    if (showAssignLicenseDialog && availableLicenses.isNotEmpty()) {
        AssignLicenseFromAccountDialog(
            availableLicenses = availableLicenses,
            onDismiss = { showAssignLicenseDialog = false },
            onSelectLicense = { selectedLicense ->
                proAccountId?.let { proId ->
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            when (val result = licenseRemoteDataSource.assignLicenseWithValidation(
                                licenseId = selectedLicense.id,
                                proAccountId = proId,
                                collaboratorId = accountId
                            )) {
                                is com.application.motium.data.supabase.LicenseAssignmentResult.Success -> {
                                    licenseCacheManager.forceRefresh(proId)
                                    showAssignLicenseDialog = false
                                    successMessage = "Licence assignee avec succes"
                                    reloadLicenseData()
                                }
                                is com.application.motium.data.supabase.LicenseAssignmentResult.NeedsCancelExisting -> {
                                    val cancelResult = subscriptionManager.cancelSubscription(
                                        userId = accountId,
                                        cancelImmediately = true
                                    )
                                    if (cancelResult.isFailure) {
                                        error = cancelResult.exceptionOrNull()?.message
                                            ?: "Erreur lors de la resiliation de l'abonnement"
                                        return@launch
                                    }
                                    val assignAfterCancel = licenseRemoteDataSource.assignLicenseToAccount(
                                        licenseId = selectedLicense.id,
                                        proAccountId = proId,
                                        linkedAccountId = accountId
                                    )
                                    if (assignAfterCancel.isSuccess) {
                                        licenseCacheManager.forceRefresh(proId)
                                        showAssignLicenseDialog = false
                                        successMessage = "Abonnement resilie et licence assignee"
                                        reloadLicenseData()
                                    } else {
                                        error = assignAfterCancel.exceptionOrNull()?.message
                                            ?: "Erreur lors de l'attribution de la licence"
                                    }
                                }
                                is com.application.motium.data.supabase.LicenseAssignmentResult.AlreadyLifetime -> {
                                    error = result.message
                                }
                                is com.application.motium.data.supabase.LicenseAssignmentResult.AlreadyLicensed -> {
                                    error = result.message
                                }
                                is com.application.motium.data.supabase.LicenseAssignmentResult.LicenseNotAvailable -> {
                                    error = result.message
                                }
                                is com.application.motium.data.supabase.LicenseAssignmentResult.CollaboratorNotFound -> {
                                    error = result.message
                                }
                                is com.application.motium.data.supabase.LicenseAssignmentResult.Error -> {
                                    error = result.message
                                }
                            }
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }
            }
        )
    }

    // Unlink Confirm Dialog
    if (showUnlinkConfirmDialog && license != null) {
        UnlinkConfirmDialog(
            license = license!!,
            isLoading = isUnlinking,
            onDismiss = { showUnlinkConfirmDialog = false },
            onConfirm = {
                proAccountId?.let { proId ->
                    isUnlinking = true
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            offlineFirstLicenseRepo.requestUnlink(license!!.id, proId)
                            isUnlinking = false
                            showUnlinkConfirmDialog = false
                            // Message différent selon le type de licence
                            successMessage = if (license!!.isLifetime) {
                                "Licence déliée avec succès."
                            } else {
                                "Résiliation enregistrée. La licence sera libérée à la date de renouvellement."
                            }
                            reloadLicenseData()
                        } catch (e: Exception) {
                            isUnlinking = false
                            error = e.message
                        }
                    }
                }
            }
        )
    }

    // Department selection dialog
    if (showDepartmentDialog) {
        DepartmentSelectionDialog(
            currentDepartment = user?.department,
            availableDepartments = availableDepartments,
            onDismiss = { showDepartmentDialog = false },
            onSelectDepartment = { selectedDepartment ->
                user?.let { currentUser ->
                    proAccountId?.let { proId ->
                        isUpdatingDepartment = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                // Update department in Supabase
                                val result = linkedAccountRemoteDataSource.updateLinkDepartment(
                                    linkId = currentUser.linkId,
                                    department = selectedDepartment
                                )
                                result.fold(
                                    onSuccess = {
                                        // Reload user to get updated department
                                        val updatedResult = linkedAccountRemoteDataSource.getLinkedUserById(accountId)
                                        updatedResult.fold(
                                            onSuccess = { updatedUser ->
                                                user = updatedUser
                                                successMessage = if (selectedDepartment != null) {
                                                    "Département mis à jour"
                                                } else {
                                                    "Département retiré"
                                                }
                                            },
                                            onFailure = { /* User will see change on next load */ }
                                        )

                                        // If new department, add it to available list
                                        if (selectedDepartment != null && !availableDepartments.contains(selectedDepartment)) {
                                            proSettingsRepository.addDepartment(proId, selectedDepartment)
                                            availableDepartments = proSettingsRepository.getDepartments(proId)
                                        }
                                    },
                                    onFailure = { e ->
                                        error = e.message
                                    }
                                )
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                isUpdatingDepartment = false
                                showDepartmentDialog = false
                            }
                        }
                    }
                }
            }
        )
    }

    // Success Snackbar
    successMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            successMessage = null
        }
    }
}

@Composable
private fun ProfileSection(
    user: LinkedUserDto,
    license: License?,
    isPersonalLifetime: Boolean,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MotiumPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MotiumPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Name
        Text(
            text = user.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        // Invitation status (instead of ID)
        val invitationStatusText = when (user.status) {
            LinkStatus.ACTIVE -> "Invitation acceptée"
            LinkStatus.PENDING -> "Invitation en attente"
            LinkStatus.INACTIVE -> "Compte inactif"
            LinkStatus.REVOKED -> "Accès révoqué"
            LinkStatus.PENDING_ACTIVATION -> "Activation en cours"
            LinkStatus.PENDING_UNLINK -> "Déliaison en cours"
        }
        Text(
            text = invitationStatusText,
            style = MaterialTheme.typography.bodyMedium,
            color = textSecondaryColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Status badge based on license assignment
        val (statusColor, statusText) = when {
            license?.isPendingUnlink == true -> Pair(PendingOrange, "Déliaison en cours")
            license != null -> Pair(MotiumPrimary, "Licencié")
            isPersonalLifetime -> Pair(MotiumPrimary, "Abonné")
            else -> Pair(TextSecondaryDark, "Sans licence")
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = statusColor.copy(alpha = 0.15f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
private fun ContactInformationSection(
    user: LinkedUserDto,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Contact Information",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        // Email card
        ContactInfoCard(
            icon = Icons.Default.Email,
            label = "Email",
            value = user.userEmail,
            cardColor = cardColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor
        )

        // Phone card (if available)
        val phoneNumber = user.userPhone
        if (!phoneNumber.isNullOrBlank()) {
            ContactInfoCard(
                icon = Icons.Default.Phone,
                label = "Téléphone",
                value = phoneNumber,
                cardColor = cardColor,
                textColor = textColor,
                textSecondaryColor = textSecondaryColor
            )
        }
    }
}

@Composable
private fun ContactInfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MotiumPrimary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MotiumPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor
                )
            }
        }
    }
}

@Composable
private fun ActivitySummarySection(
    totalTrips: Int,
    totalMileage: Double,
    associatedVehicles: Int,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Activity Summary",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActivityStatItem(
                    label = "Trajets",
                    value = totalTrips.toString(),
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
                ActivityStatItem(
                    label = "Kilomètres",
                    value = String.format("%.0f", totalMileage),
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
                ActivityStatItem(
                    label = "Véhicules",
                    value = associatedVehicles.toString(),
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }
        }
    }
}

@Composable
private fun ActivityStatItem(
    label: String,
    value: String,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MotiumPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = textSecondaryColor
        )
    }
}

@Composable
private fun AccessActionsSection(
    associatedVehicles: Int,
    backgroundColor: Color,
    onViewTrips: () -> Unit,
    onViewVehicles: () -> Unit,
    onViewExpenses: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onViewTrips,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary),
            shape = RoundedCornerShape(28.dp)
        ) {
            Icon(
                Icons.Default.Route,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Voir les trajets",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        OutlinedButton(
            onClick = onViewVehicles,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MotiumPrimary),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MotiumPrimary),
            shape = RoundedCornerShape(28.dp)
        ) {
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                tint = MotiumPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Voir les véhicules ($associatedVehicles)",
                color = MotiumPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        OutlinedButton(
            onClick = onViewExpenses,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MotiumPrimary),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MotiumPrimary),
            shape = RoundedCornerShape(28.dp)
        ) {
            Icon(
                Icons.Default.Receipt,
                contentDescription = null,
                tint = MotiumPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Voir les dépenses",
                color = MotiumPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Section displaying license status and actions
 */
@Composable
private fun LicenseSection(
    license: License?,
    isPersonalLifetime: Boolean,
    availableLicensesCount: Int,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onAssignLicense: () -> Unit,
    onRequestUnlink: () -> Unit,
    onCancelUnlink: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Licence",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    // Personal lifetime subscription (no pro license assigned)
                    license == null && isPersonalLifetime -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MotiumPrimary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.AllInclusive,
                                    contentDescription = null,
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Abonnement personnel actif",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MotiumPrimary
                                )
                                Text(
                                    "Ce compte est abonné à vie et garde l'accès Pro",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor
                                )
                            }
                        }
                    }

                    // No license assigned
                    license == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(TextSecondaryDark.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.DoNotDisturbOn,
                                    contentDescription = null,
                                    tint = TextSecondaryDark,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Aucune licence",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textColor
                                )
                                Text(
                                    "Ce compte n'a pas acces aux fonctionnalites Pro",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor
                                )
                            }
                        }

                        // Assign license button
                        if (availableLicensesCount > 0) {
                            Button(
                                onClick = onAssignLicense,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MotiumPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Lier une licence ($availableLicensesCount disponibles)")
                            }
                        } else {
                            Text(
                                "Aucune licence disponible dans le pool",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondaryColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // License with pending unlink
                    license.isPendingUnlink -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PendingOrange.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.HourglassEmpty,
                                    contentDescription = null,
                                    tint = PendingOrange,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Deliaison en cours",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PendingOrange
                                )
                                val daysLeft = license.daysUntilUnlinkEffective ?: 0
                                Text(
                                    "Licence active encore $daysLeft jours",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor
                                )
                            }
                        }

                        // Cancel unlink button
                        OutlinedButton(
                            onClick = onCancelUnlink,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = PendingOrange
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Annuler la deliaison")
                        }
                    }

                    // Active license
                    license.effectiveStatus == LicenseEffectiveStatus.ACTIVE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MotiumPrimary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Licence active",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MotiumPrimary
                                )
                                Text(
                                    text = if (license.isLifetime) "Licence a vie" else "Licence mensuelle",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor
                                )
                            }
                        }

                        // Unlink license button
                        OutlinedButton(
                            onClick = onRequestUnlink,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ErrorRed
                            )
                        ) {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delier la licence")
                        }
                    }

                    // Other status (expired, cancelled, etc.)
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(TextSecondaryDark.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = TextSecondaryDark,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Licence ${license.effectiveStatus.name.lowercase()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textColor
                                )
                                Text(
                                    "Contactez l'administrateur",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondaryColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for selecting a license from the pool to assign to the account
 */
@Composable
private fun AssignLicenseFromAccountDialog(
    availableLicenses: List<License>,
    onDismiss: () -> Unit,
    onSelectLicense: (License) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Selectionner une licence",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Choisissez une licence disponible a assigner:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                availableLicenses.forEach { license ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onSelectLicense(license) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MotiumPrimary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (license.isLifetime) Icons.Default.AllInclusive else Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = MotiumPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (license.isLifetime) "Licence a vie" else "Licence mensuelle",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (license.isLifetime) {
                                        "Acces illimite"
                                    } else {
                                        "${String.format("%.2f", license.priceMonthlyTTC)} EUR/mois"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Selectionner",
                                tint = MotiumPrimary.copy(alpha = 0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

/**
 * Section displaying department with edit capability
 */
@Composable
private fun DepartmentSection(
    currentDepartment: String?,
    isUpdating: Boolean,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onEditDepartment: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Département",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isUpdating) { onEditDepartment() },
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (currentDepartment != null) MotiumPrimary.copy(alpha = 0.1f)
                            else TextSecondaryDark.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MotiumPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Business,
                            contentDescription = null,
                            tint = if (currentDepartment != null) MotiumPrimary else TextSecondaryDark,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentDepartment ?: "Aucun département attribué",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (currentDepartment != null) textColor else textSecondaryColor
                    )
                    Text(
                        text = if (currentDepartment != null) "Appuyez pour modifier" else "Appuyez pour attribuer",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondaryColor
                    )
                }

                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Modifier le département",
                    tint = MotiumPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Dialog for selecting or creating a department
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartmentSelectionDialog(
    currentDepartment: String?,
    availableDepartments: List<String>,
    onDismiss: () -> Unit,
    onSelectDepartment: (String?) -> Unit
) {
    var newDepartmentName by remember { mutableStateOf("") }
    var showNewDepartmentInput by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Département",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Sélectionnez ou créez un département:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Option to remove department
                if (currentDepartment != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = ErrorRed.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onSelectDepartment(null) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.RemoveCircleOutline,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Retirer du département",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = ErrorRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Available departments
                availableDepartments.forEach { department ->
                    val isSelected = department == currentDepartment
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MotiumPrimary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onSelectDepartment(department) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.Business,
                                contentDescription = null,
                                tint = if (isSelected) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                department,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MotiumPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Create new department
                if (showNewDepartmentInput) {
                    OutlinedTextField(
                        value = newDepartmentName,
                        onValueChange = { newDepartmentName = it },
                        label = { Text("Nouveau département") },
                        placeholder = { Text("ex. Commercial") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MotiumPrimary,
                            cursorColor = MotiumPrimary
                        ),
                        trailingIcon = {
                            if (newDepartmentName.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        onSelectDepartment(newDepartmentName.trim())
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Valider",
                                        tint = MotiumPrimary
                                    )
                                }
                            }
                        }
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MotiumPrimary.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showNewDepartmentInput = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MotiumPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Créer un nouveau département",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MotiumPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showNewDepartmentInput && newDepartmentName.isNotBlank()) {
                TextButton(
                    onClick = { onSelectDepartment(newDepartmentName.trim()) }
                ) {
                    Text("Confirmer", color = MotiumPrimary, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

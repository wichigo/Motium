package com.application.motium.presentation.individual.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.ProAccountDto
import com.application.motium.data.supabase.ProAccountRepository
import com.application.motium.data.supabase.ProSettingsRepository
import com.application.motium.domain.model.CompanyLink
import com.application.motium.domain.model.CompanyLinkPreferences
import com.application.motium.domain.model.LegalForm
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.User
import com.application.motium.domain.model.UserRole
import com.application.motium.domain.model.isPremium
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.CompanyLinkCard
import com.application.motium.presentation.components.LinkActivationDialog
import com.application.motium.presentation.components.LinkActivationSuccessDialog
import com.application.motium.presentation.components.NoCompanyLinksCard
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.components.UnlinkConfirmationDialog
import com.application.motium.presentation.components.UpgradeDialog
import com.application.motium.presentation.settings.CompanyLinkViewModel
import com.application.motium.presentation.settings.LinkActivationResult
import com.application.motium.presentation.theme.*
import com.application.motium.utils.DeepLinkHandler
import com.application.motium.utils.ThemeManager
import com.application.motium.utils.LogcatCapture
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.MotiumApplication
import com.application.motium.data.local.LocalUserRepository
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    pendingLinkToken: String? = null,
    authViewModel: AuthViewModel = viewModel(),
    // Pro-specific parameters
    isPro: Boolean = false,
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToExportAdvanced: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    // Company Link ViewModel
    val companyLinkViewModel: CompanyLinkViewModel = viewModel {
        CompanyLinkViewModel(context)
    }
    val companyLinkUiState by companyLinkViewModel.uiState.collectAsState()
    val showUnlinkConfirmation by companyLinkViewModel.showUnlinkConfirmation.collectAsState()
    val showActivationDialog by companyLinkViewModel.showActivationDialog.collectAsState()
    val activationResult by companyLinkViewModel.activationResult.collectAsState()

    // User and premium state from AuthViewModel
    val authState by authViewModel.authState.collectAsState()
    val currentUser = authState.user
    val isPremium = currentUser?.isPremium() ?: false

    // Handle pending deep link token
    LaunchedEffect(pendingLinkToken) {
        if (pendingLinkToken != null) {
            companyLinkViewModel.handlePendingToken(pendingLinkToken)
        }
    }

    // Also check DeepLinkHandler for tokens set before navigation
    LaunchedEffect(Unit) {
        val token = DeepLinkHandler.consumePendingToken()
        if (token != null) {
            companyLinkViewModel.handlePendingToken(token)
        }
    }

    // Premium dialog state
    var showPremiumDialog by remember { mutableStateOf(false) }

    // Upgrade dialog state (for Stripe subscription)
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var isPaymentLoading by remember { mutableStateOf(false) }

    // Edit Profile dialog state
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var isSavingProfile by remember { mutableStateOf(false) }
    var profileSaveError by remember { mutableStateOf<String?>(null) }

    // Change Email dialog state
    var showChangeEmailDialog by remember { mutableStateOf(false) }
    var newEmail by remember { mutableStateOf("") }
    var isChangingEmail by remember { mutableStateOf(false) }
    var emailChangeError by remember { mutableStateOf<String?>(null) }

    // Change Password dialog state
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isChangingPassword by remember { mutableStateOf(false) }
    var passwordChangeError by remember { mutableStateOf<String?>(null) }

    // Initialize form fields with current user data
    LaunchedEffect(currentUser) {
        currentUser?.let { user ->
            editName = user.name
            phoneNumber = user.phoneNumber
            address = user.address
        }
    }

    // Work-Home Trip Settings state
    val localUserRepository = remember { LocalUserRepository.getInstance(context) }
    var considerFullDistance by remember { mutableStateOf(false) }
    var showConsiderFullDistanceConfirmDialog by remember { mutableStateOf(false) }
    var pendingConsiderFullDistanceValue by remember { mutableStateOf(false) }

    // Load considerFullDistance from user settings
    LaunchedEffect(currentUser?.id) {
        if (currentUser?.id?.isNotEmpty() == true) {
            try {
                val user = localUserRepository.getLoggedInUser()
                considerFullDistance = user?.considerFullDistance ?: false
            } catch (e: Exception) {
                MotiumApplication.logger.w("Could not load considerFullDistance: ${e.message}", "SettingsScreen")
            }
        }
    }

    // Pro Company Info state (for ENTERPRISE users)
    val proAccountRepository = remember { ProAccountRepository.getInstance(context) }
    var proAccount by remember { mutableStateOf<ProAccountDto?>(null) }
    var isLoadingProAccount by remember { mutableStateOf(false) }
    var proCompanyName by remember { mutableStateOf("") }
    var proSiret by remember { mutableStateOf("") }
    var proVatNumber by remember { mutableStateOf("") }
    var proLegalForm by remember { mutableStateOf<LegalForm?>(null) }
    var proBillingAddress by remember { mutableStateOf("") }
    var proBillingEmail by remember { mutableStateOf("") }
    var showProInfoDialog by remember { mutableStateOf(false) }
    var isSavingProAccount by remember { mutableStateOf(false) }

    // Departments state (for ENTERPRISE users)
    val proSettingsRepository = remember { ProSettingsRepository.getInstance(context) }
    var departments by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingDepartments by remember { mutableStateOf(false) }

    // Load Pro account data for ENTERPRISE users
    LaunchedEffect(currentUser?.id, currentUser?.role) {
        if (currentUser?.role == UserRole.ENTERPRISE && currentUser.id.isNotEmpty()) {
            isLoadingProAccount = true
            isLoadingDepartments = true
            val result = proAccountRepository.getProAccount(currentUser.id)
            result.onSuccess { account ->
                proAccount = account
                account?.let {
                    proCompanyName = it.companyName
                    proSiret = it.siret ?: ""
                    proVatNumber = it.vatNumber ?: ""
                    proLegalForm = it.legalForm?.let { form ->
                        try { LegalForm.valueOf(form) } catch (e: Exception) { null }
                    }
                    proBillingAddress = it.billingAddress ?: ""
                    proBillingEmail = it.billingEmail ?: ""
                    // Load departments
                    departments = proSettingsRepository.getDepartments(it.id)
                }
            }
            isLoadingProAccount = false
            isLoadingDepartments = false
        }
    }

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    WithCustomColor {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
        // Bottom navigation is now handled at app-level in MainActivity
        containerColor = backgroundColor
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 100.dp)
        ) {
            // User Profile Section
            item {
                UserProfileSection(
                    currentUser = currentUser,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor,
                    onEditProfileClick = { showEditProfileDialog = true }
                )
            }

            // Profile Information Section
            item {
                ProfileInformationSection(
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor,
                    phoneNumber = phoneNumber,
                    address = address,
                    onPhoneChange = { phoneNumber = it },
                    onAddressChange = { address = it }
                )
            }

            // Pro Company Info Section (only for ENTERPRISE users)
            if (currentUser?.role == UserRole.ENTERPRISE) {
                item {
                    ProCompanyInfoSection(
                        proAccount = proAccount,
                        isLoading = isLoadingProAccount,
                        companyName = proCompanyName,
                        siret = proSiret,
                        vatNumber = proVatNumber,
                        legalForm = proLegalForm,
                        billingAddress = proBillingAddress,
                        billingEmail = proBillingEmail,
                        surfaceColor = surfaceColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        onEditClick = { showProInfoDialog = true }
                    )
                }

                // Departments Section (only for ENTERPRISE users)
                item {
                    DepartmentsSection(
                        departments = departments,
                        isLoading = isLoadingDepartments,
                        surfaceColor = surfaceColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        onAddDepartment = { newDepartment ->
                            proAccount?.let { account ->
                                scope.launch {
                                    val result = proSettingsRepository.addDepartment(account.id, newDepartment)
                                    result.onSuccess {
                                        departments = proSettingsRepository.getDepartments(account.id)
                                    }
                                }
                            }
                        },
                        onRemoveDepartment = { department ->
                            proAccount?.let { account ->
                                scope.launch {
                                    val result = proSettingsRepository.removeDepartment(account.id, department)
                                    result.onSuccess {
                                        departments = proSettingsRepository.getDepartments(account.id)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Company Link Section (only for Individual users, not Pro)
            if (!isPro) {
                item {
                    CompanyLinkSectionNew(
                        companyLinks = companyLinkUiState.companyLinks,
                        isLoading = companyLinkUiState.isLoading,
                        surfaceColor = surfaceColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor,
                        onPreferencesChange = { linkId, prefs ->
                            companyLinkViewModel.updateSharingPreferences(linkId, prefs)
                        },
                        onUnlinkClick = { link ->
                            companyLinkViewModel.requestUnlink(link)
                        }
                    )
                }
            }

            // App Appearance Section
            item {
                AppAppearanceSection(
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor,
                    themeManager = themeManager
                )
            }

            // Mileage Rates Section
            item {
                MileageRatesSection(
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            // Work-Home Trip Settings Section
            item {
                WorkHomeTripSettingsSection(
                    considerFullDistance = considerFullDistance,
                    onToggle = { newValue ->
                        if (newValue) {
                            // Show confirmation dialog when enabling
                            pendingConsiderFullDistanceValue = true
                            showConsiderFullDistanceConfirmDialog = true
                        } else {
                            // Disable directly without confirmation
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val user = localUserRepository.getLoggedInUser()
                                    if (user != null) {
                                        val updatedUser = user.copy(considerFullDistance = false)
                                        localUserRepository.updateUser(updatedUser)
                                        MotiumApplication.logger.i("considerFullDistance set to false", "SettingsScreen")
                                    }
                                } catch (e: Exception) {
                                    MotiumApplication.logger.e("Error updating considerFullDistance: ${e.message}", "SettingsScreen", e)
                                }
                            }
                            considerFullDistance = false
                        }
                    },
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            // Subscription Section
            item {
                SubscriptionSection(
                    currentUser = currentUser,
                    isPremium = isPremium,
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor,
                    onUpgradeClick = { showUpgradeDialog = true }
                )
            }

            // Logout Section
            item {
                LogoutSection(
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    onLogout = {
                        authViewModel.signOut()
                        onNavigateToLogin()
                    }
                )
            }
        }
    }

    // Premium dialog
    if (showPremiumDialog) {
        PremiumDialog(
            onDismiss = { showPremiumDialog = false },
            onUpgrade = {
                // Show upgrade dialog instead
                showPremiumDialog = false
                showUpgradeDialog = true
            },
            featureName = "l'export de donnees"
        )
    }

    // Upgrade dialog (Stripe subscription)
    if (showUpgradeDialog) {
        UpgradeDialog(
            onDismiss = { showUpgradeDialog = false },
            onSelectPlan = { isLifetime ->
                isPaymentLoading = true
                // TODO: Trigger payment flow via SubscriptionManager
                // For now, just show a message that backend is not configured
                android.widget.Toast.makeText(
                    context,
                    "Backend de paiement non configure. Integration Stripe a venir.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                isPaymentLoading = false
                showUpgradeDialog = false
            },
            isLoading = isPaymentLoading
        )
    }

    // Consider Full Distance Confirmation Dialog
    if (showConsiderFullDistanceConfirmDialog) {
        ConsiderFullDistanceConfirmDialog(
            onConfirm = {
                // Save the setting
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val user = localUserRepository.getLoggedInUser()
                        if (user != null) {
                            val updatedUser = user.copy(considerFullDistance = true)
                            localUserRepository.updateUser(updatedUser)
                            MotiumApplication.logger.i("considerFullDistance set to true", "SettingsScreen")
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.e("Error updating considerFullDistance: ${e.message}", "SettingsScreen", e)
                    }
                }
                considerFullDistance = true
                showConsiderFullDistanceConfirmDialog = false
            },
            onDismiss = {
                // Cancel - don't change the setting
                showConsiderFullDistanceConfirmDialog = false
            },
            surfaceColor = surfaceColor,
            textColor = textColor
        )
    }

    // Edit Profile Dialog
    if (showEditProfileDialog) {
        EditProfileDialog(
            name = editName,
            phoneNumber = phoneNumber,
            address = address,
            onNameChange = { editName = it },
            onPhoneChange = { phoneNumber = it },
            onAddressChange = { address = it },
            isSaving = isSavingProfile,
            errorMessage = profileSaveError,
            onSave = {
                currentUser?.let { user ->
                    isSavingProfile = true
                    profileSaveError = null
                    val updatedUser = user.copy(
                        name = editName,
                        phoneNumber = phoneNumber,
                        address = address
                    )
                    authViewModel.updateUserProfile(
                        user = updatedUser,
                        onSuccess = {
                            isSavingProfile = false
                            showEditProfileDialog = false
                            Toast.makeText(context, "Profil mis à jour", Toast.LENGTH_SHORT).show()
                        },
                        onError = { error ->
                            isSavingProfile = false
                            profileSaveError = error
                        }
                    )
                }
            },
            onDismiss = {
                showEditProfileDialog = false
                profileSaveError = null
            },
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor,
            onChangeEmailClick = {
                showEditProfileDialog = false
                newEmail = currentUser?.email ?: ""
                showChangeEmailDialog = true
            },
            onChangePasswordClick = {
                showEditProfileDialog = false
                newPassword = ""
                confirmPassword = ""
                showChangePasswordDialog = true
            }
        )
    }

    // Change Email Dialog
    if (showChangeEmailDialog) {
        ChangeEmailDialog(
            currentEmail = currentUser?.email ?: "",
            newEmail = newEmail,
            onNewEmailChange = { newEmail = it },
            isLoading = isChangingEmail,
            errorMessage = emailChangeError,
            onSave = {
                if (newEmail.isNotBlank() && newEmail != currentUser?.email) {
                    isChangingEmail = true
                    emailChangeError = null
                    authViewModel.updateEmail(
                        newEmail = newEmail,
                        onSuccess = {
                            isChangingEmail = false
                            showChangeEmailDialog = false
                            Toast.makeText(
                                context,
                                "Un email de confirmation a été envoyé à $newEmail",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onError = { error ->
                            isChangingEmail = false
                            emailChangeError = error
                        }
                    )
                }
            },
            onDismiss = {
                showChangeEmailDialog = false
                emailChangeError = null
            },
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor
        )
    }

    // Change Password Dialog
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            newPassword = newPassword,
            confirmPassword = confirmPassword,
            onNewPasswordChange = { newPassword = it },
            onConfirmPasswordChange = { confirmPassword = it },
            isLoading = isChangingPassword,
            errorMessage = passwordChangeError,
            onSave = {
                if (newPassword.length >= 6 && newPassword == confirmPassword) {
                    isChangingPassword = true
                    passwordChangeError = null
                    authViewModel.updatePassword(
                        newPassword = newPassword,
                        onSuccess = {
                            isChangingPassword = false
                            showChangePasswordDialog = false
                            Toast.makeText(context, "Mot de passe mis à jour", Toast.LENGTH_SHORT).show()
                        },
                        onError = { error ->
                            isChangingPassword = false
                            passwordChangeError = error
                        }
                    )
                } else if (newPassword.length < 6) {
                    passwordChangeError = "Le mot de passe doit contenir au moins 6 caractères"
                } else {
                    passwordChangeError = "Les mots de passe ne correspondent pas"
                }
            },
            onDismiss = {
                showChangePasswordDialog = false
                passwordChangeError = null
            },
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor
        )
    }

    // Company Link Dialogs
    // Unlink confirmation dialog
    showUnlinkConfirmation?.let { link ->
        UnlinkConfirmationDialog(
            companyName = link.companyName,
            onConfirm = { companyLinkViewModel.confirmUnlink() },
            onDismiss = { companyLinkViewModel.cancelUnlink() }
        )
    }

    // Link activation dialog (from deep link)
    showActivationDialog?.let { token ->
        LinkActivationDialog(
            onActivate = { companyLinkViewModel.activateLinkByToken(token) },
            onDismiss = { companyLinkViewModel.dismissActivationDialog() }
        )
    }

    // Activation result dialogs
    when (val result = activationResult) {
        is LinkActivationResult.Success -> {
            LinkActivationSuccessDialog(
                companyName = result.companyName,
                onDismiss = { companyLinkViewModel.clearActivationResult() }
            )
        }
        is LinkActivationResult.Error -> {
            AlertDialog(
                onDismissRequest = { companyLinkViewModel.clearActivationResult() },
                title = { Text("Erreur d'activation") },
                text = { Text(result.message) },
                confirmButton = {
                    Button(onClick = { companyLinkViewModel.clearActivationResult() }) {
                        Text("OK")
                    }
                }
            )
        }
        null -> { /* No result to show */ }
    }

    // Pro Company Info Dialog
    if (showProInfoDialog && currentUser?.role == UserRole.ENTERPRISE) {
        EditProInfoDialog(
            companyName = proCompanyName,
            siret = proSiret,
            vatNumber = proVatNumber,
            legalForm = proLegalForm,
            billingAddress = proBillingAddress,
            billingEmail = proBillingEmail,
            onCompanyNameChange = { proCompanyName = it },
            onSiretChange = { proSiret = it },
            onVatNumberChange = { proVatNumber = it },
            onLegalFormChange = { proLegalForm = it },
            onBillingAddressChange = { proBillingAddress = it },
            onBillingEmailChange = { proBillingEmail = it },
            isSaving = isSavingProAccount,
            onSave = {
                isSavingProAccount = true
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    val result = proAccountRepository.saveProAccount(
                        userId = currentUser.id,
                        companyName = proCompanyName,
                        siret = proSiret.ifEmpty { null },
                        vatNumber = proVatNumber.ifEmpty { null },
                        legalForm = proLegalForm?.name,
                        billingAddress = proBillingAddress.ifEmpty { null },
                        billingEmail = proBillingEmail.ifEmpty { null }
                    )
                    result.onSuccess { updated ->
                        proAccount = updated
                        showProInfoDialog = false
                        MotiumApplication.logger.i("Pro account saved", "SettingsScreen")
                    }.onFailure { e ->
                        MotiumApplication.logger.e("Failed to save pro account: ${e.message}", "SettingsScreen", e)
                    }
                    isSavingProAccount = false
                }
            },
            onDismiss = { showProInfoDialog = false },
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor
        )
    }
    }
}

@Composable
fun UserProfileSection(
    currentUser: User?,
    textColor: Color,
    textSecondaryColor: Color,
    onEditProfileClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(Color(0xFFE5D4C1)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "👤",
                fontSize = 64.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Name
        Text(
            text = currentUser?.name ?: "User",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 24.sp,
            color = textColor
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Email
        Text(
            text = currentUser?.email ?: "No email",
            style = MaterialTheme.typography.bodyMedium,
            color = textSecondaryColor,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Edit Profile Button
        Button(
            onClick = onEditProfileClick,
            modifier = Modifier
                .height(48.dp)
                .widthIn(min = 160.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MotiumPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                "Edit Profile",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun ProfileInformationSection(
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    phoneNumber: String,
    address: String,
    onPhoneChange: (String) -> Unit,
    onAddressChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Profile Information",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Phone Number
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = onPhoneChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Phone Number") },
                    placeholder = {
                        if (phoneNumber.isEmpty()) {
                            Text("+1 (555) 123-4567")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor,
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                        focusedBorderColor = MotiumPrimary
                    ),
                    singleLine = true
                )

                // Address
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address") },
                    placeholder = {
                        if (address.isEmpty()) {
                            Text("123 Main St, Anytown, USA")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor,
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                        focusedBorderColor = MotiumPrimary
                    ),
                    singleLine = true
                )
            }
        }
    }
}

/**
 * New Company Link Section supporting multiple company links.
 */
@Composable
fun CompanyLinkSectionNew(
    companyLinks: List<CompanyLink>,
    isLoading: Boolean,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onPreferencesChange: (String, CompanyLinkPreferences) -> Unit,
    onUnlinkClick: (CompanyLink) -> Unit
) {
    // Track which card is expanded
    var expandedLinkId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Liaisons Entreprises",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        if (isLoading) {
            // Loading state
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MotiumPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        } else if (companyLinks.isEmpty()) {
            // Empty state
            NoCompanyLinksCard(
                surfaceColor = surfaceColor,
                textColor = textColor,
                textSecondaryColor = textSecondaryColor
            )
        } else {
            // List of company links
            companyLinks.forEach { link ->
                CompanyLinkCard(
                    companyLink = link,
                    isExpanded = expandedLinkId == link.id,
                    onExpandChange = { expanded ->
                        expandedLinkId = if (expanded) link.id else null
                    },
                    onPreferencesChange = { prefs ->
                        onPreferencesChange(link.id, prefs)
                    },
                    onUnlinkClick = { onUnlinkClick(link) },
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }
        }
    }
}

/**
 * @deprecated Use CompanyLinkSectionNew instead which supports multiple company links.
 */
@Composable
fun CompanyLinkSection(
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    linkedToCompany: Boolean,
    shareProfessionalTrips: Boolean,
    sharePersonalTrips: Boolean,
    sharePersonalInfo: Boolean,
    onLinkedToCompanyChange: (Boolean) -> Unit,
    onShareProfessionalTripsChange: (Boolean) -> Unit,
    onSharePersonalTripsChange: (Boolean) -> Unit,
    onSharePersonalInfoChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Company Link",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Company Link Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Linked to a Company/Enterprise",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = textColor,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )

                    Switch(
                        checked = linkedToCompany,
                        onCheckedChange = onLinkedToCompanyChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MotiumPrimary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.4f)
                        )
                    )
                }

                // Data Sharing Permissions Section
                if (linkedToCompany) {
                    HorizontalDivider(
                        color = textSecondaryColor.copy(alpha = 0.1f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.02f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Data Sharing Permissions",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor,
                            fontSize = 14.sp
                        )

                        // Professional Trips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = shareProfessionalTrips,
                                onCheckedChange = onShareProfessionalTripsChange,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MotiumPrimary
                                )
                            )
                            Text(
                                "Professional Trips",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                fontSize = 14.sp
                            )
                        }

                        // Personal Trips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = sharePersonalTrips,
                                onCheckedChange = onSharePersonalTripsChange,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MotiumPrimary
                                )
                            )
                            Text(
                                "Personal Trips",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                fontSize = 14.sp
                            )
                        }

                        // Personal Information
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = sharePersonalInfo,
                                onCheckedChange = onSharePersonalInfoChange,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MotiumPrimary
                                )
                            )
                            Text(
                                "Personal Information (Name, Email, etc.)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppAppearanceSection(
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    themeManager: ThemeManager
) {
    var isColorDropdownExpanded by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var editingColorIndex by remember { mutableStateOf(-1) }
    val primaryColor by themeManager.primaryColor.collectAsState()
    val favoriteColors by themeManager.favoriteColors.collectAsState()
    val selectedColorIndex by themeManager.selectedColorIndex.collectAsState()

    // Couleur par défaut (fixe) + 4 couleurs personnalisables
    val defaultColor = Color(0xFF10B981) // MotiumPrimary - non modifiable

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Apparence",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header row - only this part is clickable to toggle dropdown
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isColorDropdownExpanded = !isColorDropdownExpanded }
                ) {
                    // Logo de l'app avec fond de la couleur sélectionnée
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(primaryColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = com.application.motium.R.drawable.ic_launcher_foreground),
                            contentDescription = "Logo Motium",
                            modifier = Modifier.size(48.dp),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Couleur de l'app",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Text(
                            text = if (primaryColor == defaultColor) "Par défaut" else "Personnalisée",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp,
                            color = textSecondaryColor
                        )
                    }

                    // Icône dropdown
                    Icon(
                        imageVector = if (isColorDropdownExpanded)
                            Icons.Default.KeyboardArrowUp
                        else
                            Icons.Default.KeyboardArrowDown,
                        contentDescription = "Ouvrir",
                        tint = textSecondaryColor
                    )
                }

                // Dropdown des couleurs
                AnimatedVisibility(visible = isColorDropdownExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider(
                            color = textSecondaryColor.copy(alpha = 0.2f),
                            thickness = 1.dp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Couleur 1 - Par défaut (non modifiable)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val isSelected = selectedColorIndex == -1
                                // Cercle de couleur - clickable pour appliquer
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(defaultColor)
                                        .clickable {
                                            themeManager.selectDefaultColor()
                                        }
                                        .then(
                                            if (isSelected) Modifier.border(
                                                3.dp,
                                                textColor,
                                                CircleShape
                                            ) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Sélectionné",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Défaut",
                                    fontSize = 10.sp,
                                    color = if (isSelected) primaryColor else textSecondaryColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            // Couleurs 2-5 - Personnalisables
                            favoriteColors.forEachIndexed { index, color ->
                                val isSelected = selectedColorIndex == index
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Cercle de couleur - clickable pour appliquer
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .clickable {
                                                // Appliquer la couleur par index
                                                themeManager.selectFavoriteColor(index)
                                            }
                                            .then(
                                                if (isSelected) Modifier.border(
                                                    3.dp,
                                                    textColor,
                                                    CircleShape
                                                ) else Modifier
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Sélectionné",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    // Bouton modifier sous chaque couleur personnalisable
                                    Text(
                                        text = "Modifier",
                                        fontSize = 10.sp,
                                        color = textSecondaryColor,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable {
                                                editingColorIndex = index
                                                showColorPicker = true
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Cliquez sur une couleur pour l'appliquer, ou sur \"Modifier\" pour la personnaliser",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = textSecondaryColor.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // Color picker dialog
    if (showColorPicker && editingColorIndex >= 0) {
        com.application.motium.presentation.components.ColorPickerDialog(
            currentColor = favoriteColors.getOrNull(editingColorIndex) ?: defaultColor,
            onColorSelected = { color ->
                themeManager.setFavoriteColor(editingColorIndex, color)
                themeManager.selectFavoriteColor(editingColorIndex) // Appliquer et sélectionner par index
            },
            onDismiss = { showColorPicker = false },
            textColor = textColor,
            surfaceColor = surfaceColor
        )
    }
}

@Composable
fun MileageRatesSection(
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    var expandedItem by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Mileage Rates",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        // Car
        MileageRateItemWithIcon(
            icon = Icons.Default.DirectionsCar,
            iconBackground = MotiumPrimaryTint,
            title = "Voiture",
            rate = "0.636 €/km",
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor,
            isExpanded = expandedItem == "car",
            onClick = {
                expandedItem = if (expandedItem == "car") null else "car"
            },
            detailsContent = {
                CarMileageDetails(textColor = textColor, textSecondaryColor = textSecondaryColor)
            }
        )

        // Motorcycle
        MileageRateItemWithIcon(
            icon = Icons.Default.TwoWheeler,
            iconBackground = MotiumPrimaryTint,
            title = "Moto",
            rate = "0.395 €/km",
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor,
            isExpanded = expandedItem == "motorcycle",
            onClick = {
                expandedItem = if (expandedItem == "motorcycle") null else "motorcycle"
            },
            detailsContent = {
                MotorcycleMileageDetails(textColor = textColor, textSecondaryColor = textSecondaryColor)
            }
        )

        // Bicycle
        MileageRateItemWithIcon(
            icon = Icons.Default.PedalBike,
            iconBackground = MotiumPrimaryTint,
            title = "Vélo",
            rate = "0.25 €/km",
            surfaceColor = surfaceColor,
            textColor = textColor,
            textSecondaryColor = textSecondaryColor,
            isExpanded = expandedItem == "bicycle",
            onClick = {
                expandedItem = if (expandedItem == "bicycle") null else "bicycle"
            },
            detailsContent = {
                BicycleMileageDetails(textColor = textColor, textSecondaryColor = textSecondaryColor)
            }
        )
    }
}

/**
 * Work-Home Trip Settings Section with the considerFullDistance toggle.
 */
@Composable
fun WorkHomeTripSettingsSection(
    considerFullDistance: Boolean,
    onToggle: (Boolean) -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Trajets travail-maison",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Prendre en compte toute la distance",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Par défaut, les trajets travail-maison sont plafonnés à 40 km (80 km aller-retour). Activez cette option si vous bénéficiez d'une dérogation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = textSecondaryColor
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = considerFullDistance,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MotiumPrimary
                        )
                    )
                }
            }
        }
    }
}

/**
 * Confirmation dialog when enabling considerFullDistance.
 */
@Composable
fun ConsiderFullDistanceConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    surfaceColor: Color,
    textColor: Color
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Prendre en compte toute la distance",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = textColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cette option permet de comptabiliser l'intégralité de vos trajets travail-maison sans le plafond de 40 km (80 km aller-retour).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = Color.Transparent
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Cette dérogation est réservée aux cas exceptionnels prévus par l'administration fiscale (handicap, horaires atypiques, etc.). Vous devez pouvoir justifier votre situation en cas de contrôle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary
                )
            ) {
                Text("Je confirme", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = textColor)
            }
        }
    )
}

@Composable
fun MileageRateItemWithIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBackground: Color,
    title: String,
    rate: String,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    isExpanded: Boolean = false,
    onClick: () -> Unit = {},
    detailsContent: @Composable () -> Unit = {}
) {
    // Smooth rotation animation for the chevron icon
    val rotationAngle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 200,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "chevron_rotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 200,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header (toujours visible et cliquable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(iconBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MotiumPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = rate,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp,
                            color = textSecondaryColor
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rotationAngle }
                )
            }

            // Détails (visible seulement si expanded)
            androidx.compose.animation.AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ) + fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ),
                exit = shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ) + fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(100)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = textSecondaryColor.copy(alpha = 0.2f)
                    )
                    detailsContent()
                }
            }
        }
    }
}

@Composable
fun MileageRateItem(
    icon: String,
    iconBackground: Color,
    title: String,
    rate: String,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    isExpanded: Boolean = false,
    onClick: () -> Unit = {},
    detailsContent: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header (toujours visible et cliquable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(iconBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = icon,
                            fontSize = 24.sp
                        )
                    }

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = rate,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp,
                            color = textSecondaryColor
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Détails (visible seulement si expanded)
            androidx.compose.animation.AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = textSecondaryColor.copy(alpha = 0.2f)
                    )
                    detailsContent()
                }
            }
        }
    }
}

@Composable
fun CarMileageDetails(
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Barèmes kilométriques - Voiture",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = textColor,
            fontSize = 14.sp
        )

        // En-têtes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Puissance",
                modifier = Modifier.weight(1.5f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 12.sp,
                color = textSecondaryColor
            )
            Text(
                text = "0-5k km",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 12.sp,
                color = textSecondaryColor
            )
            Text(
                text = "5k-20k km",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 12.sp,
                color = textSecondaryColor
            )
            Text(
                text = "+20k km",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 12.sp,
                color = textSecondaryColor
            )
        }

        // Barème kilométrique 2025 - Source: bpifrance-creation.fr
        val rates = listOf(
            listOf("≤ 3 CV", "0.529 €", "0.316 €", "0.370 €"),
            listOf("4 CV", "0.606 €", "0.340 €", "0.407 €"),
            listOf("5 CV", "0.636 €", "0.357 €", "0.427 €"),
            listOf("6 CV", "0.665 €", "0.374 €", "0.447 €"),
            listOf("≥ 7 CV", "0.697 €", "0.394 €", "0.470 €")
        )

        rates.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = row[0],
                    modifier = Modifier.weight(1.5f),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = textColor
                )
                Text(
                    text = row[1],
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = textColor
                )
                Text(
                    text = row[2],
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = textColor
                )
                Text(
                    text = row[3],
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = textColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun MotorcycleMileageDetails(
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Barèmes kilométriques - Moto",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = textColor,
            fontSize = 14.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Cylindrée",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 12.sp,
                color = textSecondaryColor
            )
            Text(
                text = "Tarif",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 12.sp,
                color = textSecondaryColor
            )
        }

        // Barème kilométrique 2025 - Deux-roues motorisés
        val motorcycleRates = listOf(
            listOf("< 50 cm³", "0.315 €/km"),
            listOf("50-125 cm³", "0.395 €/km"),
            listOf("> 125 cm³", "0.395 €/km")
        )

        motorcycleRates.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = row[0],
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = textColor
                )
                Text(
                    text = row[1],
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = textColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun BicycleMileageDetails(
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Barèmes kilométriques - Vélo",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = textColor,
            fontSize = 14.sp
        )

        Text(
            text = "Tarif unique : 0.25 €/km",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = textColor
        )

        Text(
            text = "Ce tarif s'applique pour tous les déplacements à vélo, qu'il s'agisse d'un vélo classique ou électrique.",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = textSecondaryColor
        )
    }
}

@Composable
fun SubscriptionSection(
    currentUser: User?,
    isPremium: Boolean,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onUpgradeClick: () -> Unit = {}
) {
    val subscriptionType = currentUser?.subscription?.type?.name ?: "FREE"
    val planText = when (subscriptionType) {
        "LIFETIME" -> "Lifetime Premium"
        "PREMIUM" -> "Premium"
        "FREE" -> "Free"
        else -> subscriptionType
    }
    val planIcon = if (isPremium) "👑" else "⭐"
    val planColor = if (isPremium) Color(0xFFFFD700) else MotiumPrimaryTint

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Subscription",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(planColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = planIcon,
                            fontSize = 24.sp
                        )
                    }

                    Column {
                        Text(
                            text = "Current Plan",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = planText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp,
                            color = if (isPremium) Color(0xFFFFD700) else textSecondaryColor,
                            fontWeight = if (isPremium) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                if (!isPremium) {
                    Button(
                        onClick = onUpgradeClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Upgrade",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeveloperOptionsSection(
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Developer Options",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            fontSize = 20.sp,
            color = textColor
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = surfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFFE4B5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📋",
                            fontSize = 24.sp
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Export Logs",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Export app logs for debugging",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                    }
                }

                Button(
                    onClick = {
                        try {
                            val logFile = MotiumApplication.logger.getLogFile()

                            if (!logFile.exists() || logFile.length() == 0L) {
                                Toast.makeText(context, "No logs available", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Motium App Logs")
                                putExtra(Intent.EXTRA_TEXT, "Motium app logs - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }

                            context.startActivity(Intent.createChooser(shareIntent, "Export Logs"))

                            MotiumApplication.logger.i("Logs exported successfully", "Settings")
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Failed to export logs: ${e.message}", "Settings", e)
                            Toast.makeText(context, "Failed to export logs: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3B82F6),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "📤 Export Logs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                // Spacer between buttons
                Spacer(modifier = Modifier.height(16.dp))

                // Logcat Export Section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFD1E7FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📱",
                            fontSize = 24.sp
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Export Logcat",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Export system logs for debugging",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                    }
                }

                Button(
                    onClick = {
                        try {
                            CoroutineScope(Dispatchers.Main).launch {
                                val logcatFile = LogcatCapture.captureMotiumLogcat(context)

                                if (logcatFile == null || !logcatFile.exists() || logcatFile.length() == 0L) {
                                    Toast.makeText(context, "No logcat logs available", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    logcatFile
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "Motium Logcat Logs")
                                    putExtra(Intent.EXTRA_TEXT, "Motium logcat logs - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                                context.startActivity(Intent.createChooser(shareIntent, "Export Logcat"))

                                MotiumApplication.logger.i("Logcat exported successfully", "Settings")
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Failed to export logcat: ${e.message}", "Settings", e)
                            Toast.makeText(context, "Failed to export logcat: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6366F1),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "📱 Export Logcat",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                // Spacer between buttons
                Spacer(modifier = Modifier.height(16.dp))

                // Activity Recognition Reset Section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFFE4E1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔄",
                            fontSize = 24.sp
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reset Activity Recognition",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Fix UID conflicts and crashes",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                    }
                }

                Button(
                    onClick = {
                        try {
                            // Réinitialiser l'Activity Recognition
                            ActivityRecognitionService.resetActivityRecognition(context)

                            // Arrêter le service
                            ActivityRecognitionService.stopService(context)

                            // Message de confirmation
                            Toast.makeText(
                                context,
                                "Activity Recognition reset! Please restart the app.",
                                Toast.LENGTH_LONG
                            ).show()

                            MotiumApplication.logger.i("✅ Activity Recognition reset from Settings", "Settings")
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Failed to reset Activity Recognition: ${e.message}", "Settings", e)
                            Toast.makeText(
                                context,
                                "Failed to reset: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "🔄 Reset Activity Recognition",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LogoutSection(
    surfaceColor: Color,
    textColor: Color,
    onLogout: () -> Unit
) {
    Button(
        onClick = onLogout,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFEF4444),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            "Sign Out",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
fun EditProfileDialog(
    name: String,
    phoneNumber: String,
    address: String,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onChangeEmailClick: () -> Unit = {},
    onChangePasswordClick: () -> Unit = {}
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon header
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MotiumPrimaryTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MotiumPrimary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = "Modifier le profil",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    text = "Mettez à jour vos informations",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Name Field
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom") },
                    placeholder = {
                        if (name.isEmpty()) {
                            Text("Votre nom")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.5f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    singleLine = true,
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Phone Number Field
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = onPhoneChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Numéro de téléphone") },
                    placeholder = {
                        if (phoneNumber.isEmpty()) {
                            Text("+33 6 12 34 56 78")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.5f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    singleLine = true,
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Address Field
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Adresse") },
                    placeholder = {
                        if (address.isEmpty()) {
                            Text("123 Rue de la Paix, Paris")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.5f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    singleLine = true,
                    enabled = !isSaving
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Sécurité section
                HorizontalDivider(
                    color = textSecondaryColor.copy(alpha = 0.2f),
                    thickness = 1.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Sécurité",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textColor,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Change Email button
                OutlinedButton(
                    onClick = onChangeEmailClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = textColor
                    ),
                    border = BorderStroke(1.dp, textSecondaryColor.copy(alpha = 0.5f)),
                    enabled = !isSaving
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MotiumPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Modifier l'email")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Change Password button
                OutlinedButton(
                    onClick = onChangePasswordClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = textColor
                    ),
                    border = BorderStroke(1.dp, textSecondaryColor.copy(alpha = 0.5f)),
                    enabled = !isSaving
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MotiumPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Modifier le mot de passe")
                }

                // Error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSaving && name.isNotBlank()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Enregistrer",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving
                    ) {
                        Text(
                            text = "Annuler",
                            color = textSecondaryColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog pour changer l'email
 */
@Composable
fun ChangeEmailDialog(
    currentEmail: String,
    newEmail: String,
    onNewEmailChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MotiumPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Modifier l'email",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Un email de confirmation sera envoyé",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Current email (read-only)
                OutlinedTextField(
                    value = currentEmail,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email actuel") },
                    enabled = false,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = textSecondaryColor,
                        disabledBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                        disabledLabelColor = textSecondaryColor
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // New email
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = onNewEmailChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nouvel email") },
                    placeholder = { Text("nouveau@email.com") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Email,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.5f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    singleLine = true,
                    enabled = !isLoading
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading && newEmail.isNotBlank() && newEmail != currentEmail
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Envoyer la confirmation")
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text("Annuler", color = textSecondaryColor)
                }
            }
        }
    }
}

/**
 * Dialog pour changer le mot de passe
 */
@Composable
fun ChangePasswordDialog(
    newPassword: String,
    confirmPassword: String,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    var passwordVisible by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MotiumPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Modifier le mot de passe",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Minimum 6 caractères",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                // New password
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = onNewPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nouveau mot de passe") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Masquer" else "Afficher",
                                tint = textSecondaryColor
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.5f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    singleLine = true,
                    enabled = !isLoading
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Confirm password
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirmer le mot de passe") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MotiumPrimary
                        )
                    },
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedLabelColor = textSecondaryColor,
                        focusedLabelColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.5f),
                        focusedBorderColor = MotiumPrimary,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    singleLine = true,
                    enabled = !isLoading,
                    isError = confirmPassword.isNotEmpty() && newPassword != confirmPassword
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading && newPassword.length >= 6 && newPassword == confirmPassword
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Modifier le mot de passe")
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text("Annuler", color = textSecondaryColor)
                }
            }
        }
    }
}

/**
 * Section displaying Pro company information (for ENTERPRISE users)
 */
@Composable
fun ProCompanyInfoSection(
    proAccount: ProAccountDto?,
    isLoading: Boolean,
    companyName: String,
    siret: String,
    vatNumber: String,
    legalForm: LegalForm?,
    billingAddress: String,
    billingEmail: String,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onEditClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Informations Entreprise",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                fontSize = 20.sp,
                color = textColor
            )
            TextButton(onClick = onEditClick) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Modifier",
                    modifier = Modifier.size(18.dp),
                    tint = MotiumPrimary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Modifier", color = MotiumPrimary)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MotiumPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else if (proAccount == null && companyName.isEmpty()) {
                // Empty state - invite to complete info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Business,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = textSecondaryColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Complétez vos informations entreprise",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textSecondaryColor
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onEditClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
                    ) {
                        Text("Compléter")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Company Name
                    ProInfoRow(
                        icon = Icons.Default.Business,
                        label = "Nom de l'entreprise",
                        value = companyName.ifEmpty { "Non renseigné" },
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                    // SIRET
                    ProInfoRow(
                        icon = Icons.Default.Numbers,
                        label = "SIRET",
                        value = siret.ifEmpty { "Non renseigné" },
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                    // VAT Number
                    ProInfoRow(
                        icon = Icons.Default.Receipt,
                        label = "N° TVA Intracommunautaire",
                        value = vatNumber.ifEmpty { "Non renseigné" },
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                    // Legal Form
                    ProInfoRow(
                        icon = Icons.Default.Gavel,
                        label = "Forme juridique",
                        value = legalForm?.name ?: "Non renseigné",
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                    // Billing Address
                    ProInfoRow(
                        icon = Icons.Default.LocationOn,
                        label = "Adresse de facturation",
                        value = billingAddress.ifEmpty { "Non renseigné" },
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                    // Billing Email
                    ProInfoRow(
                        icon = Icons.Default.Email,
                        label = "Email de facturation",
                        value = billingEmail.ifEmpty { "Non renseigné" },
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ProInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    textColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MotiumPrimary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = textSecondaryColor
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = if (value == "Non renseigné") textSecondaryColor else textColor
            )
        }
    }
}

/**
 * Section for managing departments (for ENTERPRISE users)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartmentsSection(
    departments: List<String>,
    isLoading: Boolean,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    onAddDepartment: (String) -> Unit,
    onRemoveDepartment: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newDepartmentName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Départements",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                fontSize = 20.sp,
                color = textColor
            )
            TextButton(onClick = { showAddDialog = true }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Ajouter",
                    modifier = Modifier.size(18.dp),
                    tint = MotiumPrimary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ajouter", color = MotiumPrimary)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MotiumPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else if (departments.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = textSecondaryColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Aucun département",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textSecondaryColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Créez des départements pour organiser vos collaborateurs",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondaryColor
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Créer un département")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header row - clickable to toggle expansion
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MotiumPrimary
                            )
                            Text(
                                "${departments.size} département${if (departments.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = textColor
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded)
                                Icons.Default.KeyboardArrowUp
                            else
                                Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Réduire" else "Développer",
                            tint = textSecondaryColor
                        )
                    }

                    // Departments list - animated visibility
                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                            departments.forEach { department ->
                                DepartmentRow(
                                    departmentName = department,
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor,
                                    onRemove = { onRemoveDepartment(department) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Department Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newDepartmentName = ""
            },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    "Nouveau département",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                OutlinedTextField(
                    value = newDepartmentName,
                    onValueChange = { newDepartmentName = it },
                    label = { Text("Nom du département") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MotiumPrimary,
                        focusedLabelColor = MotiumPrimary,
                        cursorColor = MotiumPrimary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newDepartmentName.isNotBlank()) {
                            onAddDepartment(newDepartmentName.trim())
                            showAddDialog = false
                            newDepartmentName = ""
                        }
                    },
                    enabled = newDepartmentName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
                ) {
                    Text("Ajouter")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    newDepartmentName = ""
                }) {
                    Text("Annuler", color = textSecondaryColor)
                }
            }
        )
    }
}

@Composable
private fun DepartmentRow(
    departmentName: String,
    textColor: Color,
    textSecondaryColor: Color,
    onRemove: () -> Unit
) {
    var showRemoveConfirmation by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MotiumPrimary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = departmentName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MotiumPrimary
                )
            }
            Text(
                text = departmentName,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }

        IconButton(onClick = { showRemoveConfirmation = true }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Supprimer",
                tint = textSecondaryColor
            )
        }
    }

    // Remove confirmation dialog
    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            containerColor = Color.White,
            tonalElevation = 0.dp,
            title = {
                Text(
                    "Supprimer le département ?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    "Le département \"$departmentName\" sera supprimé. Cette action est irréversible.",
                    color = textSecondaryColor
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove()
                        showRemoveConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false }) {
                    Text("Annuler", color = textSecondaryColor)
                }
            }
        )
    }
}

/**
 * Dialog for editing Pro company information
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProInfoDialog(
    companyName: String,
    siret: String,
    vatNumber: String,
    legalForm: LegalForm?,
    billingAddress: String,
    billingEmail: String,
    onCompanyNameChange: (String) -> Unit,
    onSiretChange: (String) -> Unit,
    onVatNumberChange: (String) -> Unit,
    onLegalFormChange: (LegalForm?) -> Unit,
    onBillingAddressChange: (String) -> Unit,
    onBillingEmailChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    var showLegalFormDropdown by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Informations Entreprise",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Scrollable content
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Company Name (required)
                    OutlinedTextField(
                        value = companyName,
                        onValueChange = onCompanyNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nom de l'entreprise *") },
                        leadingIcon = {
                            Icon(Icons.Default.Business, contentDescription = null, tint = MotiumPrimary)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedLabelColor = textSecondaryColor,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        singleLine = true
                    )

                    // SIRET
                    OutlinedTextField(
                        value = siret,
                        onValueChange = onSiretChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("SIRET (14 chiffres)") },
                        placeholder = { Text("12345678901234") },
                        leadingIcon = {
                            Icon(Icons.Default.Numbers, contentDescription = null, tint = MotiumPrimary)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedLabelColor = textSecondaryColor,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        singleLine = true
                    )

                    // VAT Number
                    OutlinedTextField(
                        value = vatNumber,
                        onValueChange = onVatNumberChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("N° TVA Intracommunautaire") },
                        placeholder = { Text("FR12345678901") },
                        leadingIcon = {
                            Icon(Icons.Default.Receipt, contentDescription = null, tint = MotiumPrimary)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedLabelColor = textSecondaryColor,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        singleLine = true
                    )

                    // Legal Form Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = legalForm?.name ?: "",
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLegalFormDropdown = true },
                            label = { Text("Forme juridique") },
                            leadingIcon = {
                                Icon(Icons.Default.Gavel, contentDescription = null, tint = MotiumPrimary)
                            },
                            trailingIcon = {
                                Icon(
                                    if (showLegalFormDropdown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            },
                            readOnly = true,
                            enabled = false,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = textColor,
                                disabledLabelColor = textSecondaryColor,
                                disabledBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                                disabledLeadingIconColor = MotiumPrimary,
                                disabledTrailingIconColor = textSecondaryColor
                            )
                        )

                        // Clickable overlay
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { showLegalFormDropdown = true }
                        )

                        DropdownMenu(
                            expanded = showLegalFormDropdown,
                            onDismissRequest = { showLegalFormDropdown = false }
                        ) {
                            LegalForm.entries.forEach { form ->
                                DropdownMenuItem(
                                    text = { Text(form.name) },
                                    onClick = {
                                        onLegalFormChange(form)
                                        showLegalFormDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Billing Address
                    OutlinedTextField(
                        value = billingAddress,
                        onValueChange = onBillingAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Adresse de facturation") },
                        leadingIcon = {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MotiumPrimary)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedLabelColor = textSecondaryColor,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        maxLines = 3
                    )

                    // Billing Email
                    OutlinedTextField(
                        value = billingEmail,
                        onValueChange = onBillingEmailChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email de facturation") },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null, tint = MotiumPrimary)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedLabelColor = textSecondaryColor,
                            focusedLabelColor = MotiumPrimary,
                            unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save button
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = companyName.isNotEmpty() && !isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Enregistrer",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}

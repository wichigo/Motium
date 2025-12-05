package com.application.motium.presentation.individual.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.domain.model.CompanyLink
import com.application.motium.domain.model.LinkStatus
import com.application.motium.domain.model.SharingPreferences
import com.application.motium.domain.model.User
import com.application.motium.domain.model.isPremium
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.CompanyLinkCard
import com.application.motium.presentation.components.LinkActivationDialog
import com.application.motium.presentation.components.LinkActivationSuccessDialog
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.NoCompanyLinksCard
import com.application.motium.presentation.components.PremiumDialog
import com.application.motium.presentation.components.UnlinkConfirmationDialog
import com.application.motium.presentation.settings.CompanyLinkViewModel
import com.application.motium.presentation.settings.LinkActivationResult
import com.application.motium.presentation.theme.*
import com.application.motium.utils.DeepLinkHandler
import com.application.motium.utils.ThemeManager
import com.application.motium.utils.LogcatCapture
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.MotiumApplication
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
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
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

    // Edit Profile dialog state
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var phoneNumber by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

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
        bottomBar = {
            MotiumBottomNavigation(
                currentRoute = "settings",
                isPremium = isPremium,
                onNavigate = { route ->
                    when (route) {
                        "home" -> onNavigateToHome()
                        "calendar" -> onNavigateToCalendar()
                        "vehicles" -> onNavigateToVehicles()
                        "export" -> onNavigateToExport()
                    }
                },
                onPremiumFeatureClick = {
                    showPremiumDialog = true
                },
                isDarkMode = isDarkMode
            )
        },
        containerColor = backgroundColor
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
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

            // Company Link Section
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

            // Subscription Section
            item {
                SubscriptionSection(
                    currentUser = currentUser,
                    isPremium = isPremium,
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            // Developer Options Section
            item {
                DeveloperOptionsSection(
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    textSecondaryColor = textSecondaryColor
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
                // Already on settings screen, just dismiss
                showPremiumDialog = false
            },
            featureName = "l'export de données"
        )
    }

    // Edit Profile Dialog
    if (showEditProfileDialog) {
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = {
                Text(
                    "Edit Profile",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = textColor
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            "Phone Number",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("+1 (555) 123-4567") },
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = textColor,
                                focusedTextColor = textColor,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                focusedBorderColor = MotiumPrimary
                            ),
                            singleLine = true
                        )
                    }

                    Column {
                        Text(
                            "Address",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("123 Main St, Anytown, USA") },
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = textColor,
                                focusedTextColor = textColor,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                focusedBorderColor = MotiumPrimary
                            ),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Here you would save the profile changes to the backend
                        MotiumApplication.logger.i(
                            "Profile updated: Phone=$phoneNumber, Address=$address",
                            "SettingsScreen"
                        )
                        showEditProfileDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditProfileDialog = false }
                ) {
                    Text("Cancel", color = MotiumPrimary)
                }
            },
            containerColor = surfaceColor
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
            Column(modifier = Modifier.fillMaxWidth()) {
                // Phone Number
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "Phone Number",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondaryColor,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = onPhoneChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("+1 (555) 123-4567") },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        singleLine = true
                    )
                }

                HorizontalDivider(
                    color = textSecondaryColor.copy(alpha = 0.1f)
                )

                // Address
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "Address",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondaryColor,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = address,
                        onValueChange = onAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("123 Main St, Anytown, USA") },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = textColor,
                            focusedTextColor = textColor,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                            focusedBorderColor = MotiumPrimary
                        ),
                        singleLine = true
                    )
                }
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
    onPreferencesChange: (String, SharingPreferences) -> Unit,
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
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isColorDropdownExpanded = !isColorDropdownExpanded },
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
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
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    themeManager.setPrimaryColor(defaultColor)
                                }
                            ) {
                                val isSelected = primaryColor == defaultColor
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(defaultColor)
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
                                val isSelected = primaryColor == color && primaryColor != defaultColor
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        // Appliquer la couleur
                                        themeManager.setPrimaryColor(color)
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(color)
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
                                        modifier = Modifier.clickable {
                                            editingColorIndex = index
                                            showColorPicker = true
                                        }
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
                themeManager.setPrimaryColor(color) // Appliquer immédiatement
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
        androidx.compose.animation.AnimatedVisibility(
            visible = expandedItem == null || expandedItem == "car",
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
        }

        // Motorcycle
        androidx.compose.animation.AnimatedVisibility(
            visible = expandedItem == null || expandedItem == "motorcycle",
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
        }

        // Bicycle
        androidx.compose.animation.AnimatedVisibility(
            visible = expandedItem == null || expandedItem == "bicycle",
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
    textSecondaryColor: Color
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
                        onClick = { /* Upgrade */ },
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

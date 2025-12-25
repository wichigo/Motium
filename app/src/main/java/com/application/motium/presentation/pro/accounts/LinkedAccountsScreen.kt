package com.application.motium.presentation.pro.accounts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.domain.model.LinkStatus
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager

/**
 * Screen for managing linked accounts (Pro feature)
 * Displays list of users linked to the Pro account with their status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedAccountsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToExportAdvanced: () -> Unit = {},
    onNavigateToAccountDetails: (String) -> Unit = {},
    onNavigateToInvitePerson: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val viewModel = remember { LinkedAccountsViewModel(context) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Comptes liés",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            fontSize = 18.sp,
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
                    onClick = onNavigateToInvitePerson,
                    containerColor = MotiumPrimary,
                    contentColor = Color.White,
                    modifier = Modifier.padding(bottom = 100.dp) // Space for bottom nav
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Inviter")
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
        } else if (uiState.linkedUsers.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.People,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = textSecondaryColor
                    )
                    Text(
                        "Aucun compte lié",
                        style = MaterialTheme.typography.titleLarge,
                        color = textSecondaryColor
                    )
                    Text(
                        "Invitez des utilisateurs pour voir leurs trajets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textSecondaryColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onNavigateToInvitePerson,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Inviter", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // State for department dropdown
            var departmentsDropdownExpanded by remember { mutableStateOf(false) }
            var departmentSearchQuery by remember { mutableStateOf("") }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Departments filter dropdown
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Filtrer par département",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // Dropdown field
                        val selectedDeptCount = uiState.selectedDepartments.size
                        val totalDeptCount = uiState.availableDepartments.size
                        val deptDisplayText = when {
                            uiState.availableDepartments.isEmpty() -> "Aucun département"
                            selectedDeptCount == 0 -> "Sélectionner des départements"
                            selectedDeptCount == totalDeptCount -> "Tous les départements ($totalDeptCount)"
                            selectedDeptCount == 1 -> uiState.selectedDepartments.first()
                            else -> "$selectedDeptCount départements sélectionnés"
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = deptDisplayText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Départements") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        if (departmentsDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Business, contentDescription = null, tint = MotiumPrimary)
                                },
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = textColor,
                                    disabledBorderColor = if (departmentsDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    disabledLabelColor = if (departmentsDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    disabledContainerColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable {
                                        if (uiState.availableDepartments.isNotEmpty()) {
                                            departmentsDropdownExpanded = !departmentsDropdownExpanded
                                        }
                                    }
                            )
                        }

                        // Expandable departments list
                        AnimatedVisibility(
                            visible = departmentsDropdownExpanded,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDarkMode) Color(0xFF1F2937) else Color.White
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier.background(if (isDarkMode) Color(0xFF1F2937) else Color.White)
                                ) {
                                    // Search field
                                    OutlinedTextField(
                                        value = departmentSearchQuery,
                                        onValueChange = { departmentSearchQuery = it },
                                        placeholder = { Text("Rechercher...") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                tint = textSecondaryColor
                                            )
                                        },
                                        trailingIcon = {
                                            if (departmentSearchQuery.isNotEmpty()) {
                                                IconButton(onClick = { departmentSearchQuery = "" }) {
                                                    Icon(
                                                        Icons.Default.Clear,
                                                        contentDescription = "Effacer",
                                                        tint = textSecondaryColor
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MotiumPrimary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            cursorColor = MotiumPrimary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )

                                    // Select all / Deselect all
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleSelectAllDepartments() }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.allDepartmentsSelected,
                                            onCheckedChange = { viewModel.toggleSelectAllDepartments() },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MotiumPrimary,
                                                checkmarkColor = Color.White,
                                                uncheckedColor = Color.Gray
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            if (uiState.allDepartmentsSelected) "Désélectionner tout" else "Sélectionner tout",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MotiumPrimary
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )

                                    // Filtered departments list
                                    val filteredDepartments = uiState.availableDepartments.filter { dept ->
                                        departmentSearchQuery.isEmpty() ||
                                        dept.contains(departmentSearchQuery, ignoreCase = true)
                                    }

                                    if (filteredDepartments.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "Aucun résultat",
                                                color = textSecondaryColor
                                            )
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .heightIn(max = 200.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            filteredDepartments.forEachIndexed { index, dept ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { viewModel.toggleDepartmentSelection(dept) }
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = uiState.selectedDepartments.contains(dept),
                                                        onCheckedChange = { viewModel.toggleDepartmentSelection(dept) },
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = MotiumPrimary,
                                                            checkmarkColor = Color.White,
                                                            uncheckedColor = Color.Gray
                                                        )
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Icon(
                                                        Icons.Default.Business,
                                                        contentDescription = null,
                                                        tint = MotiumPrimary.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        dept,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = textColor
                                                    )
                                                }
                                                if (index < filteredDepartments.size - 1) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Done button
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    ) {
                                        Button(
                                            onClick = { departmentsDropdownExpanded = false },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Terminé", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

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

                        SummaryCard(
                            totalAccounts = uiState.filteredUsers.size,
                            licensedAccounts = viewModel.getLicensedCount(),
                            unlicensedAccounts = viewModel.getUnlicensedCount(),
                            surfaceColor = surfaceColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }
                }

                // Accounts list section
                item {
                    Text(
                        "Comptes",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column {
                            uiState.filteredUsers.forEachIndexed { index, user ->
                                LinkedUserRow(
                                    user = user,
                                    licenseStatus = uiState.getLicenseStatus(user.userId),
                                    onClick = { onNavigateToAccountDetails(user.userId) },
                                    onRevoke = { viewModel.revokeUser(user.userId) },
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                                if (index < uiState.filteredUsers.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(120.dp)) // Space for FAB + bottom nav
                }
            }
        }
        }

        // Bottom navigation is now handled at app-level in MainActivity
    }
}

@Composable
private fun SummaryCard(
    totalAccounts: Int,
    licensedAccounts: Int,
    unlicensedAccounts: Int,
    surfaceColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                value = totalAccounts.toString(),
                label = "Total",
                color = MotiumPrimary,
                textSecondaryColor = textSecondaryColor
            )
            StatItem(
                value = licensedAccounts.toString(),
                label = "Licenciés",
                color = ValidatedGreen,
                textSecondaryColor = textSecondaryColor
            )
            StatItem(
                value = unlicensedAccounts.toString(),
                label = "Sans licence",
                color = TextSecondaryDark,
                textSecondaryColor = textSecondaryColor
            )
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
private fun LinkedUserRow(
    user: LinkedUserDto,
    licenseStatus: AccountLicenseStatus,
    onClick: () -> Unit,
    onRevoke: () -> Unit,
    textColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MotiumPrimary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MotiumPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = user.userEmail,
                style = MaterialTheme.typography.bodySmall,
                color = textSecondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // License status badge (based on license assignment)
        LicenseStatusBadge(licenseStatus = licenseStatus)

        // More options
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
                DropdownMenuItem(
                    text = { Text("Voir les détails", color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        showMenu = false
                        onClick()
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Visibility, contentDescription = null)
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                )
                if (user.status == LinkStatus.ACTIVE) {
                    DropdownMenuItem(
                        text = { Text("Révoquer l'accès", color = ErrorRed) },
                        onClick = {
                            showMenu = false
                            onRevoke()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.PersonRemove,
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

/**
 * Badge showing license status (whether a license is assigned)
 */
@Composable
private fun LicenseStatusBadge(licenseStatus: AccountLicenseStatus) {
    val (backgroundColor, textColor, text) = when (licenseStatus) {
        AccountLicenseStatus.LICENSED -> Triple(
            ValidatedGreen.copy(alpha = 0.15f),
            ValidatedGreen,
            "Licencié"
        )
        AccountLicenseStatus.UNLICENSED -> Triple(
            TextSecondaryDark.copy(alpha = 0.15f),
            TextSecondaryDark,
            "Sans licence"
        )
        AccountLicenseStatus.PENDING_UNLINK -> Triple(
            PendingOrange.copy(alpha = 0.15f),
            PendingOrange,
            "Déliaison"
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun StatusBadge(status: LinkStatus) {
    val (backgroundColor, textColor, text) = when (status) {
        LinkStatus.ACTIVE -> Triple(
            ValidatedGreen.copy(alpha = 0.15f),
            ValidatedGreen,
            "Actif"
        )
        LinkStatus.PENDING -> Triple(
            PendingOrange.copy(alpha = 0.15f),
            PendingOrange,
            "En attente"
        )
        LinkStatus.INACTIVE -> Triple(
            TextSecondaryDark.copy(alpha = 0.15f),
            TextSecondaryDark,
            "Inactif"
        )
        LinkStatus.REVOKED -> Triple(
            ErrorRed.copy(alpha = 0.15f),
            ErrorRed,
            "Révoqué"
        )
        LinkStatus.PENDING_ACTIVATION -> Triple(
            PendingOrange.copy(alpha = 0.15f),
            PendingOrange,
            "Activation..."
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

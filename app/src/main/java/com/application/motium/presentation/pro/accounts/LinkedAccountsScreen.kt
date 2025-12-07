package com.application.motium.presentation.pro.accounts

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onNavigateToAccountDetails: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val viewModel = remember { LinkedAccountsViewModel(context) }

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
                        "Comptes liés",
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
                onClick = { viewModel.showInviteDialog() },
                containerColor = MotiumPrimary,
                contentColor = Color.White
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    Button(
                        onClick = { viewModel.showInviteDialog() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary
                        )
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Inviter un compte")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Summary card
                item {
                    SummaryCard(
                        totalAccounts = uiState.linkedUsers.size,
                        activeAccounts = viewModel.getActiveCount(),
                        pendingAccounts = viewModel.getPendingCount(),
                        cardColor = cardColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Comptes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor
                    )
                }

                items(uiState.linkedUsers) { user ->
                    LinkedUserCard(
                        user = user,
                        onClick = { onNavigateToAccountDetails(user.userId) },
                        onRevoke = { viewModel.revokeUser(user.userId) },
                        cardColor = cardColor,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
                }
            }
        }
    }

    // Invite dialog
    if (uiState.showInviteDialog) {
        InviteAccountDialog(
            onDismiss = { viewModel.hideInviteDialog() },
            onInvite = { email -> viewModel.inviteUser(email) },
            isLoading = uiState.isInviting
        )
    }
}

@Composable
private fun SummaryCard(
    totalAccounts: Int,
    activeAccounts: Int,
    pendingAccounts: Int,
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
                value = activeAccounts.toString(),
                label = "Actifs",
                color = ValidatedGreen,
                textSecondaryColor = textSecondaryColor
            )
            StatItem(
                value = pendingAccounts.toString(),
                label = "En attente",
                color = PendingOrange,
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
private fun LinkedUserCard(
    user: LinkedUserDto,
    onClick: () -> Unit,
    onRevoke: () -> Unit,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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

            // Status badge
            StatusBadge(status = user.status)

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
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Voir les détails") },
                        onClick = {
                            showMenu = false
                            onClick()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Visibility, contentDescription = null)
                        }
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
                            }
                        )
                    }
                }
            }
        }
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
        LinkStatus.UNLINKED -> Triple(
            TextSecondaryDark.copy(alpha = 0.15f),
            TextSecondaryDark,
            "Délié"
        )
        LinkStatus.REVOKED -> Triple(
            ErrorRed.copy(alpha = 0.15f),
            ErrorRed,
            "Révoqué"
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

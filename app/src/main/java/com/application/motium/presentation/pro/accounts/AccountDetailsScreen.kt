package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.domain.model.LinkStatus
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager

/**
 * Screen displaying details of a linked user
 * Shows sharing preferences and shared data access
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailsScreen(
    accountId: String,
    onNavigateBack: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val repository = remember { LinkedAccountRepository.getInstance(context) }

    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // State
    var user by remember { mutableStateOf<LinkedUserDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load user details
    LaunchedEffect(accountId) {
        isLoading = true
        try {
            val result = repository.getLinkedUserById(accountId)
            result.fold(
                onSuccess = { linkedUser ->
                    user = linkedUser
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        user?.displayName ?: "Détails du compte",
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // Profile header
                    item {
                        ProfileHeader(
                            user = user!!,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }

                    // Sharing preferences
                    item {
                        SharingPreferencesCard(
                            user = user!!,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }

                    // Shared data info
                    item {
                        SharedDataInfoCard(
                            user = user!!,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    user: LinkedUserDto,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MotiumPrimary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MotiumPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            Text(
                text = user.userEmail,
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondaryColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Status badge
            val (statusColor, statusText) = when (user.status) {
                LinkStatus.ACTIVE -> ValidatedGreen to "Actif"
                LinkStatus.PENDING -> PendingOrange to "En attente"
                LinkStatus.UNLINKED -> TextSecondaryDark to "Délié"
                LinkStatus.REVOKED -> ErrorRed to "Révoqué"
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = statusColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = statusText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
private fun SharingPreferencesCard(
    user: LinkedUserDto,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Données partagées",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            SharingItem("Trajets professionnels", user.shareProfessionalTrips, textSecondaryColor)
            SharingItem("Trajets personnels", user.sharePersonalTrips, textSecondaryColor)
            SharingItem("Véhicules", user.shareVehicleInfo, textSecondaryColor)
            SharingItem("Dépenses", user.shareExpenses, textSecondaryColor)
        }
    }
}

@Composable
private fun SharingItem(label: String, isShared: Boolean, textSecondaryColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = textSecondaryColor
        )
        Icon(
            imageVector = if (isShared) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isShared) ValidatedGreen else textSecondaryColor.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SharedDataInfoCard(
    user: LinkedUserDto,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = textSecondaryColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (user.isActive) {
                    "Les données partagées par cet utilisateur sont visibles dans vos exports."
                } else {
                    "L'utilisateur doit accepter l'invitation pour partager ses données."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondaryColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.LinkedAccountRepository
import com.application.motium.domain.model.LinkedAccount
import com.application.motium.domain.model.LinkedAccountStatus
import com.application.motium.domain.model.Trip
import com.application.motium.domain.model.TripType
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Screen displaying details of a linked account
 * Shows shared trips, vehicles, and expenses based on sharing preferences
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
    val scope = rememberCoroutineScope()

    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val cardColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // State
    var account by remember { mutableStateOf<LinkedAccount?>(null) }
    var sharedTrips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load account details
    LaunchedEffect(accountId) {
        isLoading = true
        try {
            val result = repository.getLinkedAccountById(accountId)
            result.fold(
                onSuccess = { acc ->
                    account = acc
                    // Load shared data based on preferences
                    if (acc.sharingPreferences.shareProfessionalTrips || acc.sharingPreferences.sharePersonalTrips) {
                        val tripsResult = repository.getSharedTrips(accountId)
                        tripsResult.onSuccess { trips ->
                            sharedTrips = trips
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        account?.displayName ?: "Détails du compte",
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
            account != null -> {
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
                            account = account!!,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }

                    // Sharing preferences
                    item {
                        SharingPreferencesCard(
                            account = account!!,
                            cardColor = cardColor,
                            textColor = textColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }

                    // Shared trips section
                    if (account!!.sharingPreferences.shareProfessionalTrips || account!!.sharingPreferences.sharePersonalTrips) {
                        item {
                            Text(
                                "Trajets partagés",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor
                            )
                        }

                        if (sharedTrips.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = cardColor),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Aucun trajet partagé",
                                            color = textSecondaryColor
                                        )
                                    }
                                }
                            }
                        } else {
                            items(sharedTrips.take(10)) { trip ->
                                SharedTripCard(
                                    trip = trip,
                                    cardColor = cardColor,
                                    textColor = textColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                            }

                            if (sharedTrips.size > 10) {
                                item {
                                    TextButton(
                                        onClick = { /* Show all trips */ },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "Voir tous les trajets (${sharedTrips.size})",
                                            color = MotiumPrimary
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
}

@Composable
private fun ProfileHeader(
    account: LinkedAccount,
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
                    text = account.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MotiumPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = account.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            Text(
                text = account.userEmail,
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondaryColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Status badge
            val (statusColor, statusText) = when (account.status) {
                LinkedAccountStatus.ACTIVE -> ValidatedGreen to "Actif"
                LinkedAccountStatus.PENDING -> PendingOrange to "En attente"
                LinkedAccountStatus.REVOKED -> ErrorRed to "Révoqué"
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
    account: LinkedAccount,
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

            val prefs = account.sharingPreferences
            SharingItem("Trajets professionnels", prefs.shareProfessionalTrips, textSecondaryColor)
            SharingItem("Trajets personnels", prefs.sharePersonalTrips, textSecondaryColor)
            SharingItem("Véhicules", prefs.shareVehicleInfo, textSecondaryColor)
            SharingItem("Dépenses", prefs.shareExpenses, textSecondaryColor)
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
private fun SharedTripCard(
    trip: Trip,
    cardColor: Color,
    textColor: Color,
    textSecondaryColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trip type icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (trip.type == TripType.PROFESSIONAL)
                            MotiumPrimary.copy(alpha = 0.1f)
                        else
                            ProfessionalBlue.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (trip.type == TripType.PROFESSIONAL)
                        Icons.Default.Work else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (trip.type == TripType.PROFESSIONAL)
                        MotiumPrimary else ProfessionalBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trip.startAddress ?: "Départ inconnu",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1
                )
                Text(
                    text = "→ ${trip.endAddress ?: "Arrivée inconnue"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondaryColor,
                    maxLines = 1
                )
                Text(
                    text = formatTripDate(trip.startTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondaryColor
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format("%.1f km", trip.distanceKm),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MotiumPrimary
                )
            }
        }
    }
}

private fun formatTripDate(instant: Instant): String {
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val dayName = when (dateTime.dayOfWeek) {
        kotlinx.datetime.DayOfWeek.MONDAY -> "Lun"
        kotlinx.datetime.DayOfWeek.TUESDAY -> "Mar"
        kotlinx.datetime.DayOfWeek.WEDNESDAY -> "Mer"
        kotlinx.datetime.DayOfWeek.THURSDAY -> "Jeu"
        kotlinx.datetime.DayOfWeek.FRIDAY -> "Ven"
        kotlinx.datetime.DayOfWeek.SATURDAY -> "Sam"
        kotlinx.datetime.DayOfWeek.SUNDAY -> "Dim"
        else -> ""
    }
    return "$dayName ${dateTime.dayOfMonth}/${dateTime.monthNumber} - ${String.format("%02d:%02d", dateTime.hour, dateTime.minute)}"
}

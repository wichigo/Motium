package com.application.motium.presentation.subscription

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.presentation.theme.MotiumGreen

/**
 * Subscription screen for managing premium plans
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    subscriptionManager: SubscriptionManager,
    currentSubscription: SubscriptionType,
    onBack: () -> Unit,
    onSubscribe: (SubscriptionType) -> Unit,
    modifier: Modifier = Modifier
) {
    val paymentState by subscriptionManager.paymentState.collectAsState()
    var selectedPlan by remember { mutableStateOf<SubscriptionType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Abonnement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "Choisissez votre forfait",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Débloquez toutes les fonctionnalités de Motium",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Current plan indicator
            CurrentPlanBadge(subscriptionType = currentSubscription)
            Spacer(modifier = Modifier.height(24.dp))

            // Plan cards
            // PREMIUM Plan (Monthly)
            PlanCard(
                title = "Mensuel",
                price = "${SubscriptionManager.PREMIUM_MONTHLY_PRICE}€",
                period = "/ mois",
                features = listOf(
                    PlanFeature("Trajets illimités", true),
                    PlanFeature("Suivi GPS", true),
                    PlanFeature("Historique complet", true),
                    PlanFeature("Export PDF & CSV", true),
                    PlanFeature("Support prioritaire", true),
                    PlanFeature("Sans engagement", true)
                ),
                isCurrentPlan = currentSubscription == SubscriptionType.PREMIUM,
                isPopular = true,
                onClick = {
                    if (currentSubscription == SubscriptionType.TRIAL || currentSubscription == SubscriptionType.EXPIRED) {
                        selectedPlan = SubscriptionType.PREMIUM
                        onSubscribe(SubscriptionType.PREMIUM)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // LIFETIME Plan
            PlanCard(
                title = "À vie",
                price = "${SubscriptionManager.LIFETIME_PRICE}€",
                period = "paiement unique",
                features = listOf(
                    PlanFeature("Trajets illimités", true),
                    PlanFeature("Toutes les fonctionnalités Premium", true),
                    PlanFeature("Mises à jour à vie", true),
                    PlanFeature("Aucun abonnement", true),
                    PlanFeature("Support VIP", true),
                    PlanFeature("Économisez après 20 mois", true)
                ),
                isCurrentPlan = currentSubscription == SubscriptionType.LIFETIME,
                isPopular = false,
                isBestValue = true,
                onClick = {
                    if (currentSubscription != SubscriptionType.LIFETIME) {
                        selectedPlan = SubscriptionType.LIFETIME
                        onSubscribe(SubscriptionType.LIFETIME)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Legal text
            Text(
                text = "L'abonnement Premium est renouvelé automatiquement chaque mois. " +
                        "Vous pouvez annuler à tout moment depuis les paramètres de votre compte.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Restore purchases button
            TextButton(onClick = { /* TODO: Restore purchases */ }) {
                Text("Restaurer mes achats")
            }
        }

        // Loading overlay
        AnimatedVisibility(
            visible = paymentState is SubscriptionManager.PaymentState.Loading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MotiumGreen)
            }
        }
    }
}

@Composable
private fun CurrentPlanBadge(subscriptionType: SubscriptionType) {
    val badgeInfo = when (subscriptionType) {
        SubscriptionType.TRIAL -> BadgeInfo(
            backgroundColor = MotiumGreen.copy(alpha = 0.1f),
            contentColor = MotiumGreen,
            icon = Icons.Default.Timer,
            text = "Essai gratuit en cours"
        )
        SubscriptionType.EXPIRED -> BadgeInfo(
            backgroundColor = Color(0xFFFF5252).copy(alpha = 0.1f),
            contentColor = Color(0xFFFF5252),
            icon = Icons.Default.Warning,
            text = "Essai terminé"
        )
        SubscriptionType.PREMIUM -> BadgeInfo(
            backgroundColor = MotiumGreen.copy(alpha = 0.1f),
            contentColor = MotiumGreen,
            icon = Icons.Default.CheckCircle,
            text = "Abonnement Premium actif"
        )
        SubscriptionType.LIFETIME -> BadgeInfo(
            backgroundColor = Color(0xFFFF9500).copy(alpha = 0.1f),
            contentColor = Color(0xFFFF9500),
            icon = Icons.Default.Star,
            text = "Accès à vie"
        )
    }

    Surface(
        color = badgeInfo.backgroundColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = badgeInfo.icon,
                contentDescription = null,
                tint = badgeInfo.contentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = badgeInfo.text,
                style = MaterialTheme.typography.bodyMedium,
                color = badgeInfo.contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private data class BadgeInfo(
    val backgroundColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
    val text: String
)

data class PlanFeature(val text: String, val included: Boolean)

@Composable
private fun PlanCard(
    title: String,
    price: String,
    period: String,
    features: List<PlanFeature>,
    isCurrentPlan: Boolean,
    isPopular: Boolean,
    originalPrice: String? = null,
    isBestValue: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor = when {
        isCurrentPlan -> MotiumGreen
        isPopular -> MotiumGreen.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    val backgroundColor = when {
        isPopular -> MotiumGreen.copy(alpha = 0.05f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(enabled = !isCurrentPlan, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPopular) 4.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isPopular) {
                        Surface(
                            color = MotiumGreen,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Populaire",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (isBestValue) {
                        Surface(
                            color = Color(0xFFFF9500),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Meilleur rapport",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Price
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (originalPrice != null) {
                    Text(
                        text = originalPrice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textDecoration = TextDecoration.LineThrough
                    )
                }
                Text(
                    text = price,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isPopular) MotiumGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = period,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Features
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                features.forEach { feature ->
                    FeatureRow(feature = feature)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action button
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCurrentPlan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPopular || isBestValue) MotiumGreen else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (isCurrentPlan) "Forfait actuel" else "Choisir ce forfait",
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(feature: PlanFeature) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (feature.included) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (feature.included) MotiumGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = feature.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (feature.included)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

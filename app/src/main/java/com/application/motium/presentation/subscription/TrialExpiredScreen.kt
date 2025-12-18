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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.presentation.theme.MotiumGreen

/**
 * Blocking screen shown when trial has expired.
 * User must subscribe to continue using the app.
 */
@Composable
fun TrialExpiredScreen(
    subscriptionManager: SubscriptionManager,
    onSubscribe: (SubscriptionType) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val paymentState by subscriptionManager.paymentState.collectAsState()
    var selectedPlan by remember { mutableStateOf<SubscriptionType?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

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
                price = "5€",
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
                price = "100€",
                period = "une fois",
                description = "Accès illimité pour toujours",
                isSelected = selectedPlan == SubscriptionType.LIFETIME,
                isRecommended = false,
                savingsText = "Économisez après 20 mois",
                onClick = { selectedPlan = SubscriptionType.LIFETIME }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Subscribe button
            Button(
                onClick = { selectedPlan?.let { onSubscribe(it) } },
                enabled = selectedPlan != null && paymentState !is SubscriptionManager.PaymentState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumGreen
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (paymentState is SubscriptionManager.PaymentState.Loading) {
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
        if (paymentState is SubscriptionManager.PaymentState.Error) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text((paymentState as SubscriptionManager.PaymentState.Error).message)
            }
        }
    }

    // Logout confirmation dialog
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
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

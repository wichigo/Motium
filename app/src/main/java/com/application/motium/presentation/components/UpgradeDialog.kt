package com.application.motium.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Dialog for Individual users to upgrade to Premium
 */
@Composable
fun UpgradeDialog(
    onDismiss: () -> Unit,
    onSelectPlan: (isLifetime: Boolean) -> Unit,
    isLoading: Boolean = false
) {
    var selectedLifetime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Passer a Premium",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Choisissez votre formule",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Monthly option
                PlanOptionCard(
                    title = "Mensuel",
                    price = "${SubscriptionManager.INDIVIDUAL_MONTHLY_PRICE.toInt()} € / mois",
                    description = "Facturation mensuelle, annulable a tout moment",
                    isSelected = !selectedLifetime,
                    onClick = { selectedLifetime = false },
                    enabled = !isLoading
                )

                // Lifetime option
                PlanOptionCard(
                    title = "A vie",
                    price = "${SubscriptionManager.INDIVIDUAL_LIFETIME_PRICE.toInt()} €",
                    description = "Paiement unique, acces permanent",
                    isSelected = selectedLifetime,
                    onClick = { selectedLifetime = true },
                    enabled = !isLoading,
                    badge = "Recommande"
                )

                // Features reminder
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MotiumPrimary.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Avantages Premium:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("- Trajets illimites", style = MaterialTheme.typography.bodySmall)
                        Text("- Export des donnees (PDF, CSV)", style = MaterialTheme.typography.bodySmall)
                        Text("- Support prioritaire", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelectPlan(selectedLifetime) },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (selectedLifetime) {
                        "Payer ${SubscriptionManager.INDIVIDUAL_LIFETIME_PRICE.toInt()} €"
                    } else {
                        "S'abonner"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Annuler")
            }
        }
    )
}

@Composable
private fun PlanOptionCard(
    title: String,
    price: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    badge: String? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick, enabled = enabled),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MotiumPrimary.copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected)
            BorderStroke(2.dp, MotiumPrimary)
        else
            null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    badge?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MotiumPrimary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                it,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Text(
                price,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MotiumPrimary
            )
        }
    }
}


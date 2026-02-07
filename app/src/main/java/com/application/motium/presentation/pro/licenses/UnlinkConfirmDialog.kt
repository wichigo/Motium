package com.application.motium.presentation.pro.licenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.motium.domain.model.License
import com.application.motium.presentation.theme.ErrorRed
import com.application.motium.presentation.theme.PendingOrange

/**
 * Dialog to confirm unlink/cancel request.
 * - Lifetime: déliaison à la prochaine date de renouvellement
 * - Mensuelle: effective à la date de renouvellement
 */
@Composable
fun UnlinkConfirmDialog(
    license: License,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isLifetime = license.isLifetime
    val title = if (isLifetime) "Délier la licence" else "Résilier la licence"
    val confirmText = if (isLifetime) "Planifier la déliaison" else "Confirmer la résiliation"

    // Format end date for monthly licenses
    val endDateText = license.endDate?.let { endDate ->
        val instant = endDate.toEpochMilliseconds()
        val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(instant))
        date
    } ?: "la prochaine date de renouvellement"

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = Color.White,
        tonalElevation = 0.dp,
        icon = {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = PendingOrange,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Warning card - different message based on license type
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = PendingOrange.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (isLifetime) "Déliaison au renouvellement" else "Résiliation au renouvellement",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PendingOrange
                        )
                        Text(
                            if (isLifetime) {
                                "Pour éviter les abus, la licence restera active jusqu'à la prochaine date de renouvellement du compte Pro. L'utilisateur conservera l'accès jusque-là."
                            } else {
                                "La licence restera active jusqu'au $endDateText (date de renouvellement). L'utilisateur conservera l'accès jusqu'à cette date."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }

                // Explanation text
                Text(
                    "Après la date de renouvellement :",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BulletPoint("La licence sera libérée et retournera dans votre pool")
                    BulletPoint("L'utilisateur n'aura plus accès aux fonctionnalités Pro")
                    BulletPoint("Vous pourrez réassigner la licence à un autre compte")
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Vous pourrez annuler cette demande à tout moment avant la date de renouvellement.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Annuler")
            }
        }
    )
}

@Composable
private fun BulletPoint(text: String) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "\u2022  ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

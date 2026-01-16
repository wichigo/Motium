package com.application.motium.presentation.pro.licenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.application.motium.domain.model.License
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Dialog for purchasing new licenses (monthly or lifetime)
 */
@Composable
fun PurchaseLicenseDialog(
    onDismiss: () -> Unit,
    onPurchase: (quantity: Int, isLifetime: Boolean) -> Unit,
    isLoading: Boolean = false
) {
    var quantity by remember { mutableIntStateOf(1) }
    var isLifetime by remember { mutableStateOf(false) }

    // Calculate prices based on mode
    val unitPriceHT = if (isLifetime) License.LICENSE_LIFETIME_PRICE_HT else License.LICENSE_PRICE_HT
    val priceHT = quantity * unitPriceHT
    val vat = priceHT * License.VAT_RATE
    val priceTTC = priceHT + vat

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Acheter des licences",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Plan type selector
                Text(
                    "Type de licence",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isLifetime,
                        onClick = { isLifetime = false },
                        label = { Text("Mensuel") },
                        enabled = !isLoading
                    )
                    FilterChip(
                        selected = isLifetime,
                        onClick = { isLifetime = true },
                        label = { Text("A vie") },
                        enabled = !isLoading
                    )
                }

                // Quantity selector
                Text(
                    "Nombre de licences",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (quantity > 1) quantity-- },
                        enabled = quantity > 1 && !isLoading
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Moins")
                    }

                    Text(
                        text = quantity.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    IconButton(
                        onClick = { if (quantity < 100) quantity++ },
                        enabled = quantity < 100 && !isLoading
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Plus")
                    }
                }

                // Price breakdown
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "$quantity x ${unitPriceHT.toInt()} € HT" +
                                    if (!isLifetime) " /mois" else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                String.format("%.2f €", priceHT),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "TVA 20%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                String.format("%.2f €", vat),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (isLifetime) "Total TTC" else "Total TTC / mois",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                String.format("%.2f €", priceTTC),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MotiumPrimary
                            )
                        }
                    }
                }

                // Lifetime benefits notice or Stripe notice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MotiumPrimary.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (isLifetime) {
                            "Les licences a vie n'expirent jamais et ne necessitent aucun renouvellement. Paiement unique via Stripe."
                        } else {
                            "Le paiement sera effectue via Stripe. Vous recevrez une facture par email chaque mois."
                        },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPurchase(quantity, isLifetime) },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Payer ${String.format("%.2f €", priceTTC)}")
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

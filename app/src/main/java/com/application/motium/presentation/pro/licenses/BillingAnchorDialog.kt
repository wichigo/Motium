package com.application.motium.presentation.pro.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.application.motium.presentation.theme.*

/**
 * Dialog for selecting the billing anchor day (1-15).
 * This day is used as the renewal date for all monthly licenses.
 */
@Composable
fun BillingAnchorDialog(
    currentAnchorDay: Int?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedDay by remember { mutableStateOf(currentAnchorDay ?: 1) }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MotiumPrimary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MotiumPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = if (currentAnchorDay != null) "Modifier la date" else "Date de renouvellement",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                Text(
                    text = "Choisissez le jour du mois pour le renouvellement de vos licences mensuelles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Day selector grid (1-15)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items((1..15).toList()) { day ->
                        DayChip(
                            day = day,
                            isSelected = day == selectedDay,
                            onClick = { selectedDay = day }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Info box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MotiumPrimary.copy(alpha = 0.08f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MotiumPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cette modification s'appliquera aux prochains achats de licences.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("Annuler")
                    }

                    Button(
                        onClick = { onConfirm(selectedDay) },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Confirmer")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayChip(
    day: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MotiumPrimary else Color.Transparent
            )
            .then(
                if (!isSelected) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}

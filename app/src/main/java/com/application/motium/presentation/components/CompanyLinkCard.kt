package com.application.motium.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.domain.model.CompanyLink
import com.application.motium.domain.model.CompanyLinkPreferences
import com.application.motium.domain.model.LinkStatus
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Card component displaying a company link with expandable details.
 */
@Composable
fun CompanyLinkCard(
    companyLink: CompanyLink,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onPreferencesChange: (CompanyLinkPreferences) -> Unit,
    onUnlinkClick: () -> Unit,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandChange(!isExpanded) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header - always visible
            CompanyLinkHeader(
                companyName = companyLink.companyName,
                status = companyLink.status,
                isExpanded = isExpanded,
                textColor = textColor,
                textSecondaryColor = textSecondaryColor
            )

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))

                    // Sharing preferences section
                    CompanyLinkPreferencesSection(
                        shareProfessionalTrips = companyLink.shareProfessionalTrips,
                        sharePersonalTrips = companyLink.sharePersonalTrips,
                        sharePersonalInfo = companyLink.sharePersonalInfo,
                        onPreferencesChange = onPreferencesChange,
                        enabled = companyLink.status == LinkStatus.ACTIVE,
                        textColor = textColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    // Unlink button
                    if (companyLink.status == LinkStatus.ACTIVE) {
                        HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.1f))
                        UnlinkButtonRow(
                            onUnlinkClick = onUnlinkClick,
                            textColor = textColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanyLinkHeader(
    companyName: String,
    status: LinkStatus,
    isExpanded: Boolean,
    textColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Business,
                contentDescription = null,
                tint = MotiumPrimary,
                modifier = Modifier.size(24.dp)
            )

            Column {
                Text(
                    text = companyName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = textColor,
                    fontSize = 15.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinkStatusBadge(status = status)

            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = textSecondaryColor
            )
        }
    }
}

/**
 * Badge showing the link status with appropriate color.
 */
@Composable
fun LinkStatusBadge(
    status: LinkStatus,
    modifier: Modifier = Modifier
) {
    val (text, backgroundColor) = when (status) {
        LinkStatus.ACTIVE -> "Actif" to Color(0xFF10B981)
        LinkStatus.PENDING -> "En attente" to Color(0xFFF59E0B)
        LinkStatus.UNLINKED -> "Délié" to Color(0xFF6B7280)
        LinkStatus.REVOKED -> "Révoqué" to Color(0xFFEF4444)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor.copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Text(
            text = text,
            color = backgroundColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CompanyLinkPreferencesSection(
    shareProfessionalTrips: Boolean,
    sharePersonalTrips: Boolean,
    sharePersonalInfo: Boolean,
    onPreferencesChange: (CompanyLinkPreferences) -> Unit,
    enabled: Boolean,
    textColor: Color,
    textSecondaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.02f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Permissions de partage",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = textColor,
            fontSize = 13.sp
        )

        // Professional Trips
        SharingCheckbox(
            label = "Trajets professionnels",
            checked = shareProfessionalTrips,
            onCheckedChange = { checked ->
                onPreferencesChange(
                    CompanyLinkPreferences(
                        shareProfessionalTrips = checked,
                        sharePersonalTrips = sharePersonalTrips,
                        sharePersonalInfo = sharePersonalInfo
                    )
                )
            },
            enabled = enabled,
            textColor = textColor
        )

        // Personal Trips
        SharingCheckbox(
            label = "Trajets personnels",
            checked = sharePersonalTrips,
            onCheckedChange = { checked ->
                onPreferencesChange(
                    CompanyLinkPreferences(
                        shareProfessionalTrips = shareProfessionalTrips,
                        sharePersonalTrips = checked,
                        sharePersonalInfo = sharePersonalInfo
                    )
                )
            },
            enabled = enabled,
            textColor = textColor
        )

        // Personal Information
        SharingCheckbox(
            label = "Informations personnelles",
            checked = sharePersonalInfo,
            onCheckedChange = { checked ->
                onPreferencesChange(
                    CompanyLinkPreferences(
                        shareProfessionalTrips = shareProfessionalTrips,
                        sharePersonalTrips = sharePersonalTrips,
                        sharePersonalInfo = checked
                    )
                )
            },
            enabled = enabled,
            textColor = textColor
        )

        if (!enabled) {
            Text(
                text = "Modifications désactivées - compte délié",
                style = MaterialTheme.typography.bodySmall,
                color = textSecondaryColor,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SharingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    textColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = MotiumPrimary,
                disabledCheckedColor = MotiumPrimary.copy(alpha = 0.4f)
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) textColor else textColor.copy(alpha = 0.5f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun UnlinkButtonRow(
    onUnlinkClick: () -> Unit,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.End
    ) {
        OutlinedButton(
            onClick = onUnlinkClick,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFEF4444)
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = Color(0xFFEF4444).copy(alpha = 0.5f)
            )
        ) {
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Délier le compte")
        }
    }
}

/**
 * Dialog to confirm unlinking from a company.
 */
@Composable
fun UnlinkConfirmationDialog(
    companyName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = null,
                tint = Color(0xFFEF4444)
            )
        },
        title = {
            Text("Délier de $companyName ?")
        },
        text = {
            Text(
                "Après la déliaison, $companyName n'aura plus accès à vos données de trajets. " +
                "L'entreprise conservera vos informations de contact et pourra vous réinviter ultérieurement."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444)
                )
            ) {
                Text("Délier", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

/**
 * Dialog shown when activating a company link from deep link.
 */
@Composable
fun LinkActivationDialog(
    onActivate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Business,
                contentDescription = null,
                tint = MotiumPrimary
            )
        },
        title = {
            Text("Invitation de liaison")
        },
        text = {
            Text(
                "Vous avez reçu une invitation pour vous lier à une entreprise. " +
                "Cela leur permettra de voir vos trajets selon vos préférences de partage. " +
                "Voulez-vous activer cette liaison ?"
            )
        },
        confirmButton = {
            Button(
                onClick = onActivate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary
                )
            ) {
                Text("Activer la liaison", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

/**
 * Success dialog after activating a company link.
 */
@Composable
fun LinkActivationSuccessDialog(
    companyName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Business,
                contentDescription = null,
                tint = Color(0xFF10B981)
            )
        },
        title = {
            Text("Liaison activée")
        },
        text = {
            Text(
                "Vous êtes maintenant lié à $companyName. " +
                "Vous pouvez modifier vos préférences de partage à tout moment dans les paramètres."
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary
                )
            ) {
                Text("Compris", color = Color.White)
            }
        }
    )
}

/**
 * Empty state when no company links exist.
 */
@Composable
fun NoCompanyLinksCard(
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Business,
                contentDescription = null,
                tint = textSecondaryColor,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "Aucune entreprise liée",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = textColor
            )

            Text(
                text = "Vous recevrez un email d'invitation si une entreprise souhaite se lier à votre compte.",
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondaryColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

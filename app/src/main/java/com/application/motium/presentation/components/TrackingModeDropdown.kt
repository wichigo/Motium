package com.application.motium.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.application.motium.domain.model.TrackingMode
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Data class pour les options du dropdown
 */
private data class TrackingModeOption(
    val mode: TrackingMode,
    val label: String,
    val description: String,
    val icon: ImageVector
)

/**
 * Dropdown menu pour sélectionner le mode d'auto-tracking.
 * Utilise un style personnalisé avec fond blanc/surface.
 *
 * @param selectedMode Le mode actuellement sélectionné
 * @param onModeSelected Callback quand un nouveau mode est sélectionné
 * @param enabled Si le dropdown est interactif
 * @param modifier Modifier pour personnaliser le layout
 */
@Composable
fun TrackingModeDropdown(
    selectedMode: TrackingMode,
    onModeSelected: (TrackingMode) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val trackingModes = remember {
        listOf(
            TrackingModeOption(
                mode = TrackingMode.ALWAYS,
                label = "Toujours",
                description = "Suivi automatique permanent",
                icon = Icons.Default.AllInclusive
            ),
            TrackingModeOption(
                mode = TrackingMode.WORK_HOURS_ONLY,
                label = "Pro",
                description = "Uniquement pendant les horaires de travail",
                icon = Icons.Default.Schedule
            ),
            TrackingModeOption(
                mode = TrackingMode.DISABLED,
                label = "Jamais",
                description = "Contrôle manuel uniquement",
                icon = Icons.Default.Cancel
            )
        )
    }

    var expanded by remember { mutableStateOf(false) }
    val selectedOption = trackingModes.find { it.mode == selectedMode } ?: trackingModes.last()

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedOption.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Suivi automatique") },
            trailingIcon = {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = if (enabled) MotiumPrimary else Color.Gray
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = selectedOption.icon,
                    contentDescription = null,
                    tint = when (selectedMode) {
                        TrackingMode.ALWAYS -> MotiumPrimary
                        TrackingMode.WORK_HOURS_ONLY -> MotiumPrimary
                        TrackingMode.DISABLED -> Color.Gray
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            enabled = false,
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                disabledLeadingIconColor = when (selectedMode) {
                    TrackingMode.ALWAYS -> MotiumPrimary
                    TrackingMode.WORK_HOURS_ONLY -> MotiumPrimary
                    TrackingMode.DISABLED -> Color.Gray
                },
                disabledTrailingIconColor = if (enabled) MotiumPrimary else Color.Gray
            )
        )
        // Transparent overlay to catch clicks
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { expanded = !expanded }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            trackingModes.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = when (option.mode) {
                                    TrackingMode.ALWAYS -> MotiumPrimary
                                    TrackingMode.WORK_HOURS_ONLY -> MotiumPrimary
                                    TrackingMode.DISABLED -> Color.Gray
                                }
                            )
                            Column {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    },
                    onClick = {
                        onModeSelected(option.mode)
                        expanded = false
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                )
            }
        }
    }
}

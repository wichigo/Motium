package com.application.motium.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Cancel
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
 *
 * @param selectedMode Le mode actuellement sélectionné
 * @param onModeSelected Callback quand un nouveau mode est sélectionné
 * @param enabled Si le dropdown est interactif
 * @param modifier Modifier pour personnaliser le layout
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedOption.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Auto Tracking") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
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
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(12.dp),
            enabled = enabled,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedBorderColor = MotiumPrimary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                focusedLabelColor = MotiumPrimary
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
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
                                    style = MaterialTheme.typography.bodyLarge
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
                    leadingIcon = null
                )
            }
        }
    }
}

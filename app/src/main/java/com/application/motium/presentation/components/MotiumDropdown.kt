package com.application.motium.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Composant dropdown personnalisé avec le style Motium.
 * Utilise un fond blanc/surface au lieu du fond vert par défaut de Material 3.
 *
 * @param value La valeur actuellement sélectionnée (texte affiché)
 * @param label Le label du champ
 * @param options Liste des options disponibles (paires value -> label)
 * @param onOptionSelected Callback quand une option est sélectionnée
 * @param leadingIcon Icône optionnelle à gauche
 * @param modifier Modifier
 * @param enabled Si le dropdown est interactif
 */
@Composable
fun <T> MotiumDropdown(
    value: String,
    label: String,
    options: List<Pair<T, String>>,
    onOptionSelected: (T) -> Unit,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = if (enabled) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = if (enabled) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            enabled = false,
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                disabledLeadingIconColor = if (enabled) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledTrailingIconColor = if (enabled) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onOptionSelected(optionValue)
                        expanded = false
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                )
            }
        }
    }
}

/**
 * Version simplifiée pour les options String.
 */
@Composable
fun MotiumStringDropdown(
    value: String,
    label: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    MotiumDropdown(
        value = value,
        label = label,
        options = options.map { it to it },
        onOptionSelected = onOptionSelected,
        leadingIcon = leadingIcon,
        modifier = modifier,
        enabled = enabled
    )
}

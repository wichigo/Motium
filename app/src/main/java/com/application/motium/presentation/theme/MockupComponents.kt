package com.application.motium.presentation.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Composants réutilisables du nouveau design Motium
 * Pour assurer la cohérence visuelle sur tous les écrans
 */

/**
 * Card moderne avec le nouveau style Motium
 * - Coins arrondis 16dp
 * - Élévation plate (0dp)
 * - Support automatique du dark mode
 */
@Composable
fun MockupCard(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardColor = if (isDarkMode) DarkSurface else MockupCardBackground

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(content = content)
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(content = content)
        }
    }
}

/**
 * Bouton principal vert proéminent
 * - Coins très arrondis (28dp)
 * - Élévation légère (4dp)
 * - Couleur verte MockupGreen
 */
@Composable
fun MockupPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MockupGreen,
            contentColor = Color.White,
            disabledContainerColor = MockupLightGray,
            disabledContentColor = MockupTextGray
        ),
        shape = RoundedCornerShape(28.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            fontSize = 18.sp
        )
    }
}

/**
 * Bouton secondaire avec bordure
 * - Coins arrondis (16dp)
 * - Transparent avec bordure verte
 */
@Composable
fun MockupSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MockupGreen,
            disabledContentColor = MockupTextGray
        ),
        shape = RoundedCornerShape(16.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(width = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            fontSize = 16.sp
        )
    }
}

/**
 * Switch moderne avec la couleur verte
 */
@Composable
fun MockupSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MockupGreen,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MockupLightGray,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = MockupLightGray,
            disabledCheckedTrackColor = MockupBorderGray
        )
    )
}

/**
 * Chip/Badge moderne pour les tags
 */
@Composable
fun MockupChip(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MockupGreen.copy(alpha = 0.1f),
    textColor: Color = MockupGreen
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
            fontSize = 12.sp
        )
    }
}

/**
 * Divider moderne
 */
@Composable
fun MockupDivider(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = if (isDarkMode) Color(0xFF374151) else MockupLightGray
    )
}

/**
 * TextField moderne avec le nouveau style
 */
@Composable
fun MockupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val colors = getMockupColors(isDarkMode)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MockupGreen,
            unfocusedBorderColor = colors.border,
            focusedLabelColor = MockupGreen,
            unfocusedLabelColor = colors.textSecondary,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = MockupGreen
        )
    )
}

/**
 * Section header pour regrouper les éléments
 */
@Composable
fun MockupSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean
) {
    val colors = getMockupColors(isDarkMode)

    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = colors.textPrimary
    )
}

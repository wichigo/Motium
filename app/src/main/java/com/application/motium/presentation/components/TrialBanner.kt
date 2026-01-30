package com.application.motium.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.application.motium.presentation.theme.MotiumGreen

/**
 * Banner shown when user is in trial period with limited days remaining.
 * Shows countdown and prompts to subscribe.
 *
 * @param daysRemaining Number of days left in trial (show when <= 3)
 * @param onSubscribeClick Callback when user clicks to subscribe
 * @param onDismiss Callback when user dismisses the banner (optional, temporary dismiss)
 */
@Composable
fun TrialBanner(
    daysRemaining: Int,
    onSubscribeClick: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(true) }

    // Only show if days remaining is 3 or less
    AnimatedVisibility(
        visible = isVisible && daysRemaining <= 3,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        val bannerColor = when {
            daysRemaining <= 1 -> MaterialTheme.colorScheme.error
            daysRemaining == 2 -> Color(0xFFFF9800) // Orange
            else -> MotiumGreen
        }

        val bannerBackground = when {
            daysRemaining <= 1 -> Brush.horizontalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            )
            daysRemaining == 2 -> Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFFFF9800).copy(alpha = 0.9f),
                    Color(0xFFFF9800).copy(alpha = 0.7f)
                )
            )
            else -> Brush.horizontalGradient(
                colors = listOf(
                    MotiumGreen.copy(alpha = 0.9f),
                    MotiumGreen.copy(alpha = 0.7f)
                )
            )
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(bannerBackground)
                .clickable(onClick = onSubscribeClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timer icon
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Text content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (daysRemaining) {
                            0 -> "Dernier jour d'essai !"
                            1 -> "Plus qu'1 jour d'essai"
                            else -> "Plus que $daysRemaining jours d'essai"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Appuyez pour vous abonner",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }

                // Subscribe button
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable(onClick = onSubscribeClick)
                ) {
                    Text(
                        text = "S'abonner",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = bannerColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                // Dismiss button (optional)
                onDismiss?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            isVisible = false
                            it()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fermer",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact trial indicator for use in app bars or headers.
 */
@Composable
fun TrialIndicator(
    daysRemaining: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (daysRemaining > 14) return // Don't show if more than 14 days

    val indicatorColor = when {
        daysRemaining <= 1 -> MaterialTheme.colorScheme.error
        daysRemaining <= 3 -> Color(0xFFFF9800) // Orange
        else -> MotiumGreen
    }

    Surface(
        onClick = onClick,
        color = indicatorColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = indicatorColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = when (daysRemaining) {
                    0 -> "Dernier jour"
                    1 -> "1 jour"
                    else -> "$daysRemaining jours"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = indicatorColor
            )
        }
    }
}

package com.application.motium.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.application.motium.presentation.theme.MotiumPrimary
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.R
import com.application.motium.presentation.theme.MotiumTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sync status states for the indicator.
 */
sealed class SyncState {
    /** Device is offline - show CloudOff icon */
    data object Offline : SyncState()

    /** User needs to re-login for sync (session expired, no refresh token) */
    data object NeedsRelogin : SyncState()

    /** Operations are pending sync - show animated Sync icon with count */
    data class Syncing(val pendingCount: Int) : SyncState()

    /** Some operations failed - show Warning icon with count */
    data class Failed(val failedCount: Int, val pendingCount: Int) : SyncState()

    /** Sync conflicts requiring user resolution - show Error icon with count */
    data class Conflict(val conflictCount: Int) : SyncState()

    /** Everything is synced - show CloudDone icon */
    data object Synced : SyncState()
}

/**
 * Composable that displays the current sync status.
 * Shows different states:
 * - Offline: CloudOff icon with "Mode hors-ligne"
 * - NeedsRelogin: Login icon with "Reconnexion requise"
 * - Conflict: Error icon with conflict count (needs user resolution)
 * - Failed: Warning icon with failed count (can retry)
 * - Syncing: Animated Sync icon with pending count
 * - Synced: CloudDone icon with "Synchronisé"
 *
 * Tapping triggers an immediate sync when online (or navigates to conflicts/login).
 *
 * @param isOnline StateFlow of network connectivity
 * @param pendingOperationsCount Flow of pending operations count
 * @param failedOperationsCount Flow of failed operations count (optional, defaults to 0)
 * @param conflictCount Flow of conflict entities count (optional, defaults to 0)
 * @param needsRelogin Whether user needs to re-login for sync (optional, defaults to false)
 * @param onSyncClick Callback when user taps to trigger sync
 * @param onConflictClick Callback when user taps to view conflicts (optional)
 * @param onReloginClick Callback when user taps to re-login (optional)
 * @param modifier Modifier for the component
 */
@Composable
fun SyncStatusIndicator(
    isOnline: StateFlow<Boolean>,
    pendingOperationsCount: Flow<Int>,
    failedOperationsCount: Flow<Int>? = null,
    conflictCount: Flow<Int>? = null,
    needsRelogin: Boolean = false,
    onSyncClick: () -> Unit,
    onConflictClick: (() -> Unit)? = null,
    onReloginClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val online by isOnline.collectAsState()
    val pendingCount by pendingOperationsCount.collectAsState(initial = 0)
    val failedCount by (failedOperationsCount ?: MutableStateFlow(0)).collectAsState(initial = 0)
    val conflicts by (conflictCount ?: MutableStateFlow(0)).collectAsState(initial = 0)

    val syncState = when {
        !online -> SyncState.Offline
        needsRelogin -> SyncState.NeedsRelogin
        conflicts > 0 -> SyncState.Conflict(conflicts)
        failedCount > 0 -> SyncState.Failed(failedCount, pendingCount)
        pendingCount > 0 -> SyncState.Syncing(pendingCount)
        else -> SyncState.Synced
    }

    val clickHandler = when {
        syncState is SyncState.Conflict && onConflictClick != null -> onConflictClick
        syncState is SyncState.NeedsRelogin && onReloginClick != null -> onReloginClick
        else -> onSyncClick
    }

    SyncStatusIndicatorContent(
        syncState = syncState,
        onSyncClick = clickHandler,
        modifier = modifier
    )
}

@Composable
private fun SyncStatusIndicatorContent(
    syncState: SyncState,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor: Color
    val contentColor: Color
    val icon: ImageVector
    val text: String
    val isAnimated: Boolean

    when (syncState) {
        is SyncState.Offline -> {
            backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            icon = Icons.Default.CloudOff
            text = stringResource(R.string.sync_status_offline)
            isAnimated = false
        }
        is SyncState.NeedsRelogin -> {
            backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            icon = Icons.Default.Login
            text = stringResource(R.string.sync_status_needs_relogin)
            isAnimated = false
        }
        is SyncState.Failed -> {
            backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            icon = Icons.Default.Warning
            text = stringResource(R.string.sync_status_failed, syncState.failedCount)
            isAnimated = false
        }
        is SyncState.Conflict -> {
            backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
            contentColor = MaterialTheme.colorScheme.onError
            icon = Icons.Default.Error
            text = stringResource(R.string.sync_status_conflict, syncState.conflictCount)
            isAnimated = false
        }
        is SyncState.Syncing -> {
            backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            icon = Icons.Default.Sync
            text = stringResource(R.string.sync_status_pending, syncState.pendingCount)
            isAnimated = true
        }
        is SyncState.Synced -> {
            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            icon = Icons.Default.CloudDone
            text = stringResource(R.string.sync_status_synced)
            isAnimated = false
        }
    }

    // Rotation animation for syncing state
    val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "syncRotation"
    )

    Surface(
        modifier = modifier
            .clickable(enabled = syncState != SyncState.Offline) { onSyncClick() },
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier
                    .size(16.dp)
                    .then(if (isAnimated) Modifier.rotate(rotation) else Modifier)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Compact version of the sync indicator - just shows an icon.
 * Use in constrained spaces like a TopAppBar action.
 */
@Composable
fun SyncStatusIconButton(
    isOnline: StateFlow<Boolean>,
    pendingOperationsCount: Flow<Int>,
    failedOperationsCount: Flow<Int>? = null,
    conflictCount: Flow<Int>? = null,
    needsRelogin: Boolean = false,
    onSyncClick: () -> Unit,
    onConflictClick: (() -> Unit)? = null,
    onReloginClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val online by isOnline.collectAsState()
    val pendingCount by pendingOperationsCount.collectAsState(initial = 0)
    val failedCount by (failedOperationsCount ?: MutableStateFlow(0)).collectAsState(initial = 0)
    val conflicts by (conflictCount ?: MutableStateFlow(0)).collectAsState(initial = 0)

    val syncState = when {
        !online -> SyncState.Offline
        needsRelogin -> SyncState.NeedsRelogin
        conflicts > 0 -> SyncState.Conflict(conflicts)
        failedCount > 0 -> SyncState.Failed(failedCount, pendingCount)
        pendingCount > 0 -> SyncState.Syncing(pendingCount)
        else -> SyncState.Synced
    }

    val icon: ImageVector
    val tint: Color
    val isAnimated: Boolean

    when (syncState) {
        is SyncState.Offline -> {
            icon = Icons.Default.CloudOff
            tint = MaterialTheme.colorScheme.error
            isAnimated = false
        }
        is SyncState.NeedsRelogin -> {
            icon = Icons.Default.Login
            tint = MaterialTheme.colorScheme.error
            isAnimated = false
        }
        is SyncState.Conflict -> {
            icon = Icons.Default.Error
            tint = MaterialTheme.colorScheme.error
            isAnimated = false
        }
        is SyncState.Failed -> {
            icon = Icons.Default.Warning
            tint = MaterialTheme.colorScheme.error
            isAnimated = false
        }
        is SyncState.Syncing -> {
            icon = Icons.Default.Sync
            tint = MotiumPrimary
            isAnimated = true
        }
        is SyncState.Synced -> {
            icon = Icons.Default.CloudDone
            tint = MaterialTheme.colorScheme.tertiary
            isAnimated = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "syncRotation"
    )

    val clickHandler = when {
        syncState is SyncState.Conflict && onConflictClick != null -> onConflictClick
        syncState is SyncState.NeedsRelogin && onReloginClick != null -> onReloginClick
        else -> onSyncClick
    }

    Icon(
        imageVector = icon,
        contentDescription = when (syncState) {
            is SyncState.Offline -> stringResource(R.string.sync_status_offline)
            is SyncState.NeedsRelogin -> stringResource(R.string.sync_status_needs_relogin)
            is SyncState.Conflict -> stringResource(R.string.sync_status_conflict, syncState.conflictCount)
            is SyncState.Failed -> stringResource(R.string.sync_status_failed, syncState.failedCount)
            is SyncState.Syncing -> stringResource(R.string.sync_status_pending, syncState.pendingCount)
            is SyncState.Synced -> stringResource(R.string.sync_status_synced)
        },
        tint = tint,
        modifier = modifier
            .size(24.dp)
            .clickable(enabled = syncState != SyncState.Offline) { clickHandler() }
            .then(if (isAnimated) Modifier.rotate(rotation) else Modifier)
    )
}

// ==================== PREVIEWS ====================

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorOfflinePreview() {
    MotiumTheme {
        SyncStatusIndicatorContent(
            syncState = SyncState.Offline,
            onSyncClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorFailedPreview() {
    MotiumTheme {
        SyncStatusIndicatorContent(
            syncState = SyncState.Failed(failedCount = 2, pendingCount = 3),
            onSyncClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorConflictPreview() {
    MotiumTheme {
        SyncStatusIndicatorContent(
            syncState = SyncState.Conflict(conflictCount = 3),
            onSyncClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorSyncingPreview() {
    MotiumTheme {
        SyncStatusIndicatorContent(
            syncState = SyncState.Syncing(5),
            onSyncClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorSyncedPreview() {
    MotiumTheme {
        SyncStatusIndicatorContent(
            syncState = SyncState.Synced,
            onSyncClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}


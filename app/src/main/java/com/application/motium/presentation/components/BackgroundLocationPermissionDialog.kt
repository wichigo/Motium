package com.application.motium.presentation.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Dialog explaining why background location permission ("Toujours") is needed
 * and providing a button to open app settings.
 */
@Composable
fun BackgroundLocationPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Icon
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MotiumPrimary,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = "Localisation en arrière-plan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Description
                Text(
                    text = "Pour que le suivi automatique des trajets fonctionne même quand l'application est fermée, veuillez autoriser la localisation \"Toujours\".",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = "Dans les paramètres :",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MotiumPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Appuyez sur \"Autorisations\"\n2. Appuyez sur \"Localisation\"\n3. Sélectionnez \"Toujours autoriser\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MotiumPrimary,
                        ),
                    ) {
                        Text("Plus tard")
                    }

                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MotiumPrimary,
                        ),
                    ) {
                        Text("Ouvrir paramètres")
                    }
                }
            }
        }
    }
}

/**
 * Opens the app's settings page where the user can change location permissions.
 */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

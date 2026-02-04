package com.application.motium.presentation.components.gdpr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.motium.domain.model.ConsentInfo
import com.application.motium.domain.model.ConsentType
import com.application.motium.presentation.theme.MotiumPrimary
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// === États des dialogues ===

sealed class ExportDialogState {
    data object Hidden : ExportDialogState()
    data object Confirming : ExportDialogState()
    data object Processing : ExportDialogState()
    data object Downloading : ExportDialogState()
    data class Ready(val downloadUrl: String, val expiresAt: Instant?) : ExportDialogState()
    data class Error(val message: String) : ExportDialogState()
}

sealed class DeletionDialogState {
    data object Hidden : DeletionDialogState()
    data object Step1Warning : DeletionDialogState()
    data object Step2Confirm : DeletionDialogState()
    data object Processing : DeletionDialogState()
    data object Success : DeletionDialogState()
    data class Error(val message: String) : DeletionDialogState()
}

// === Écran de gestion des consentements ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentManagementScreen(
    consents: List<ConsentInfo>,
    isLoading: Boolean,
    onConsentChange: (ConsentType, Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gérer mes consentements") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Conformément au RGPD, vous pouvez contrôler l'utilisation de vos données personnelles.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Consentements obligatoires
                item {
                    Text(
                        text = "Consentements obligatoires",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MotiumPrimary
                    )
                }

                items(consents.filter { it.isRequired }) { consent ->
                    ConsentToggleItem(
                        consent = consent,
                        onToggle = { onConsentChange(consent.type, it) },
                        enabled = false // Les consentements obligatoires ne peuvent pas être désactivés
                    )
                }

                // Consentements optionnels
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Consentements optionnels",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MotiumPrimary
                    )
                }

                items(consents.filter { !it.isRequired }) { consent ->
                    ConsentToggleItem(
                        consent = consent,
                        onToggle = { onConsentChange(consent.type, it) },
                        enabled = true
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Les consentements obligatoires sont nécessaires au fonctionnement de l'application. Les modifications sont enregistrées automatiquement.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // Bottom padding for navigation bar
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}

@Composable
fun ConsentToggleItem(
    consent: ConsentInfo,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.clickable { onToggle(!consent.granted) }
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = consent.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (consent.isRequired) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Obligatoire",
                            modifier = Modifier.size(16.dp),
                            tint = MotiumPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = consent.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                consent.grantedAt?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    val dateTime = it.toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        text = "Accordé le ${dateTime.dayOfMonth}/${dateTime.monthNumber}/${dateTime.year}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = consent.granted,
                onCheckedChange = onToggle,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MotiumPrimary,
                    checkedTrackColor = MotiumPrimary.copy(alpha = 0.5f),
                    // Couleurs pour les toggles désactivés mais validés (consentements obligatoires)
                    disabledCheckedThumbColor = MotiumPrimary,
                    disabledCheckedTrackColor = MotiumPrimary.copy(alpha = 0.5f)
                )
            )
        }
    }
}

// === Dialogue d'export des données ===

@Composable
fun DataExportDialog(
    state: ExportDialogState,
    onExport: () -> Unit,
    onDownload: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (state == ExportDialogState.Hidden) return

    AlertDialog(
        onDismissRequest = {
            if (state !is ExportDialogState.Processing && state !is ExportDialogState.Downloading) onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        icon = {
            when (state) {
                is ExportDialogState.Processing -> CircularProgressIndicator(modifier = Modifier.size(48.dp))
                is ExportDialogState.Downloading -> CircularProgressIndicator(modifier = Modifier.size(48.dp))
                is ExportDialogState.Ready -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MotiumPrimary,
                    modifier = Modifier.size(48.dp)
                )
                is ExportDialogState.Error -> Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                else -> Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MotiumPrimary,
                    modifier = Modifier.size(48.dp)
                )
            }
        },
        title = {
            Text(
                text = when (state) {
                    is ExportDialogState.Confirming -> "Exporter mes données"
                    is ExportDialogState.Processing -> "Export en cours..."
                    is ExportDialogState.Downloading -> "Téléchargement..."
                    is ExportDialogState.Ready -> "Export prêt !"
                    is ExportDialogState.Error -> "Erreur d'export"
                    else -> ""
                }
            )
        },
        text = {
            Column {
                when (state) {
                    is ExportDialogState.Confirming -> {
                        Text("Conformément à l'article 15 du RGPD, vous pouvez télécharger toutes vos données personnelles.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "L'export inclut :",
                            fontWeight = FontWeight.Medium
                        )
                        Text("• Profil utilisateur")
                        Text("• Historique des trajets")
                        Text("• Véhicules")
                        Text("• Dépenses")
                        Text("• Consentements")
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Le lien de téléchargement sera valide 24 heures.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ExportDialogState.Processing -> {
                        Text("Vos données sont en cours de préparation. Cela peut prendre quelques instants...")
                    }
                    is ExportDialogState.Downloading -> {
                        Text("Téléchargement de votre export en cours...")
                    }
                    is ExportDialogState.Ready -> {
                        Text("Vos données sont prêtes à être téléchargées.")
                        state.expiresAt?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            val dateTime = it.toLocalDateTime(TimeZone.currentSystemDefault())
                            Text(
                                text = "Lien valide jusqu'au ${dateTime.dayOfMonth}/${dateTime.monthNumber}/${dateTime.year} à ${dateTime.hour}:${dateTime.minute.toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is ExportDialogState.Error -> {
                        Text(state.message)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (state) {
                is ExportDialogState.Confirming -> {
                    Button(
                        onClick = onExport,
                        colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
                    ) {
                        Text("Demander l'export")
                    }
                }
                is ExportDialogState.Ready -> {
                    Button(
                        onClick = { onDownload(state.downloadUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Télécharger")
                    }
                }
                is ExportDialogState.Error -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary)
                    ) {
                        Text("Fermer")
                    }
                }
                is ExportDialogState.Downloading -> {}
                else -> {}
            }
        },
        dismissButton = {
            if (state is ExportDialogState.Confirming || state is ExportDialogState.Ready) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MotiumPrimary)
                ) {
                    Text("Annuler")
                }
            }
        }
    )
}

// === Dialogue de suppression du compte ===

@Composable
fun DeleteAccountDialog(
    state: DeletionDialogState,
    onProceedToConfirm: () -> Unit,
    onConfirmDelete: (confirmation: String, reason: String?) -> Unit,
    onNavigateToLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state == DeletionDialogState.Hidden) return

    var reason by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showConfirmationError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (state !is DeletionDialogState.Processing && state !is DeletionDialogState.Success) {
                onDismiss()
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        icon = {
            when (state) {
                is DeletionDialogState.Processing -> CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.error
                )
                is DeletionDialogState.Success -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MotiumPrimary,
                    modifier = Modifier.size(48.dp)
                )
                is DeletionDialogState.Error -> Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                else -> Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
            }
        },
        title = {
            Text(
                text = when (state) {
                    is DeletionDialogState.Step1Warning -> "Supprimer mon compte"
                    is DeletionDialogState.Step2Confirm -> "Confirmation requise"
                    is DeletionDialogState.Processing -> "Suppression en cours..."
                    is DeletionDialogState.Success -> "Compte supprimé"
                    is DeletionDialogState.Error -> "Erreur"
                    else -> ""
                },
                color = if (state is DeletionDialogState.Success) MotiumPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                when (state) {
                    is DeletionDialogState.Step1Warning -> {
                        Text(
                            text = "⚠️ Cette action est IRRÉVERSIBLE",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Conformément à l'article 17 du RGPD (droit à l'effacement), toutes vos données seront définitivement supprimées :")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("• Profil et identifiants")
                        Text("• Historique des trajets")
                        Text("• Véhicules enregistrés")
                        Text("• Dépenses")
                        Text("• Abonnements (résiliés)")
                        Text("• Consentements et préférences")
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            label = { Text("Raison (optionnelle)") },
                            placeholder = { Text("Pourquoi souhaitez-vous partir ?") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3
                        )
                    }
                    is DeletionDialogState.Step2Confirm -> {
                        Text(
                            text = "Pour confirmer la suppression, tapez exactement :",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "DELETE_MY_ACCOUNT",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = {
                                confirmation = it
                                showConfirmationError = false
                            },
                            label = { Text("Confirmation") },
                            isError = showConfirmationError,
                            supportingText = if (showConfirmationError) {
                                { Text("Tapez exactement 'DELETE_MY_ACCOUNT'") }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                    }
                    is DeletionDialogState.Processing -> {
                        Text("Suppression de vos données en cours...")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Veuillez ne pas fermer l'application.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is DeletionDialogState.Success -> {
                        Text("Votre compte et toutes vos données ont été supprimés.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Un email de confirmation vous a été envoyé.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is DeletionDialogState.Error -> {
                        Text(state.message)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (state) {
                is DeletionDialogState.Step1Warning -> {
                    Button(
                        onClick = onProceedToConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Continuer")
                    }
                }
                is DeletionDialogState.Step2Confirm -> {
                    Button(
                        onClick = {
                            if (confirmation == "DELETE_MY_ACCOUNT") {
                                onConfirmDelete(confirmation, reason.ifBlank { null })
                            } else {
                                showConfirmationError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SUPPRIMER DÉFINITIVEMENT")
                    }
                }
                is DeletionDialogState.Success -> {
                    Button(onClick = onNavigateToLogin) {
                        Text("Retour à l'accueil")
                    }
                }
                is DeletionDialogState.Error -> {
                    Button(onClick = onDismiss) {
                        Text("Fermer")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state is DeletionDialogState.Step1Warning || state is DeletionDialogState.Step2Confirm) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Annuler")
                }
            }
        }
    )
}

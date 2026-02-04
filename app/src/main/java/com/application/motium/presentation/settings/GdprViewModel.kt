package com.application.motium.presentation.settings

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseGdprRepository
import com.application.motium.domain.model.ConsentInfo
import com.application.motium.domain.model.ConsentType
import com.application.motium.presentation.components.gdpr.DeletionDialogState
import com.application.motium.presentation.components.gdpr.ExportDialogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GdprUiState(
    val consents: List<ConsentInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val accountDeleted: Boolean = false
)

class GdprViewModel(application: Application) : AndroidViewModel(application) {

    private val gdprRepository = SupabaseGdprRepository.getInstance(application)

    private val _uiState = MutableStateFlow(GdprUiState())
    val uiState: StateFlow<GdprUiState> = _uiState.asStateFlow()

    private val _exportDialogState = MutableStateFlow<ExportDialogState>(ExportDialogState.Hidden)
    val exportDialogState: StateFlow<ExportDialogState> = _exportDialogState.asStateFlow()

    private val _deletionDialogState = MutableStateFlow<DeletionDialogState>(DeletionDialogState.Hidden)
    val deletionDialogState: StateFlow<DeletionDialogState> = _deletionDialogState.asStateFlow()

    // Raison temporaire stockée entre Step1 et Step2
    private var pendingDeletionReason: String? = null

    companion object {
        private const val TAG = "GdprViewModel"
    }

    // Note: loadConsents() is called explicitly by screens that need it
    // (ConsentManagementRoute), not in init, to avoid JWT errors when
    // SettingsScreen loads the ViewModel for export/delete dialogs only.

    fun loadConsents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            gdprRepository.getConsents()
                .onSuccess { consents ->
                    // Si la liste est vide, utiliser les consentements par défaut
                    val finalConsents = if (consents.isEmpty()) {
                        MotiumApplication.logger.i("Aucun consentement trouvé, utilisation des valeurs par défaut", TAG)
                        ConsentInfo.getDefaultConsents()
                    } else {
                        consents
                    }
                    _uiState.update { it.copy(consents = finalConsents, isLoading = false) }
                    MotiumApplication.logger.i("Consentements chargés: ${finalConsents.size}", TAG)
                }
                .onFailure { e ->
                    // En cas d'erreur, utiliser les consentements par défaut
                    MotiumApplication.logger.e("Erreur chargement consentements: ${e.message}, utilisation des valeurs par défaut", TAG, e)
                    _uiState.update {
                        it.copy(
                            consents = ConsentInfo.getDefaultConsents(),
                            isLoading = false,
                            error = null // On ne montre pas l'erreur car on a des valeurs par défaut
                        )
                    }
                }
        }
    }

    fun updateConsent(type: ConsentType, granted: Boolean) {
        // Mise à jour optimiste immédiate de l'UI
        val previousState = _uiState.value
        _uiState.update { state ->
            state.copy(
                consents = state.consents.map { consent ->
                    if (consent.type == type) {
                        consent.copy(
                            granted = granted,
                            grantedAt = if (granted) kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()) else consent.grantedAt,
                            revokedAt = if (!granted) kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()) else null
                        )
                    } else consent
                }
            )
        }
        MotiumApplication.logger.i("Consentement ${type.name} mis à jour localement: $granted", TAG)

        // Persister dans Supabase en arrière-plan
        viewModelScope.launch {
            val currentVersion = previousState.consents
                .find { it.type == type }?.version ?: "1.0"

            gdprRepository.updateConsent(type, granted, currentVersion)
                .onSuccess {
                    MotiumApplication.logger.i("Consentement ${type.name} persisté dans Supabase", TAG)
                }
                .onFailure { e ->
                    // En cas d'erreur, on garde quand même l'état local (offline-first)
                    MotiumApplication.logger.w("Erreur persistance consentement (gardé localement): ${e.message}", TAG)
                }
        }
    }

    // === Export ===

    fun showExportDialog() {
        _exportDialogState.value = ExportDialogState.Confirming
    }

    fun hideExportDialog() {
        _exportDialogState.value = ExportDialogState.Hidden
    }

    fun requestDataExport() {
        viewModelScope.launch {
            _exportDialogState.value = ExportDialogState.Processing

            gdprRepository.requestDataExport()
                .onSuccess { result ->
                    if (result.success && result.downloadUrl != null) {
                        _exportDialogState.value = ExportDialogState.Ready(
                            downloadUrl = result.downloadUrl,
                            expiresAt = result.expiresAt
                        )
                    } else {
                        _exportDialogState.value = ExportDialogState.Error(
                            result.errorMessage ?: "Erreur lors de l'export"
                        )
                    }
                }
                .onFailure { e ->
                    _exportDialogState.value = ExportDialogState.Error(
                        e.message ?: "Erreur lors de l'export"
                    )
                }
        }
    }

    fun downloadExport(url: String) {
        viewModelScope.launch {
            _exportDialogState.value = ExportDialogState.Downloading

            gdprRepository.downloadExportFile(url)
                .onSuccess { file ->
                    shareExportFile(file)
                    Toast.makeText(
                        getApplication(),
                        "Export téléchargé",
                        Toast.LENGTH_SHORT
                    ).show()
                    _exportDialogState.value = ExportDialogState.Hidden
                }
                .onFailure { e ->
                    _exportDialogState.value = ExportDialogState.Error(
                        e.message ?: "Erreur lors du téléchargement"
                    )
                }
        }
    }

    private fun shareExportFile(file: File) {
        try {
            val context = getApplication<Application>()
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Export RGPD Motium")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Partager l'export RGPD")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Erreur partage export: ${e.message}", TAG, e)
            Toast.makeText(
                getApplication(),
                e.message ?: "Erreur lors du partage",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // === Suppression ===

    fun showDeleteDialog() {
        pendingDeletionReason = null
        _deletionDialogState.value = DeletionDialogState.Step1Warning
    }

    fun hideDeleteDialog() {
        pendingDeletionReason = null
        _deletionDialogState.value = DeletionDialogState.Hidden
    }

    fun proceedToDeleteConfirmation(reason: String?) {
        pendingDeletionReason = reason
        _deletionDialogState.value = DeletionDialogState.Step2Confirm
    }

    fun requestAccountDeletion(confirmation: String, reason: String?) {
        viewModelScope.launch {
            _deletionDialogState.value = DeletionDialogState.Processing

            val finalReason = reason ?: pendingDeletionReason

            gdprRepository.requestAccountDeletion(confirmation, finalReason)
                .onSuccess { result ->
                    if (result.success) {
                        _deletionDialogState.value = DeletionDialogState.Success
                        _uiState.update { it.copy(accountDeleted = true) }
                        MotiumApplication.logger.i("Compte supprimé avec succès", TAG)
                    } else {
                        _deletionDialogState.value = DeletionDialogState.Error(
                            result.errorMessage ?: "Erreur lors de la suppression"
                        )
                    }
                }
                .onFailure { e ->
                    _deletionDialogState.value = DeletionDialogState.Error(
                        e.message ?: "Erreur lors de la suppression"
                    )
                    MotiumApplication.logger.e("Erreur suppression compte: ${e.message}", TAG, e)
                }
        }
    }
}

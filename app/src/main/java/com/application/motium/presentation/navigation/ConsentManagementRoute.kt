package com.application.motium.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.presentation.components.gdpr.ConsentManagementScreen
import com.application.motium.presentation.settings.GdprViewModel

@Composable
fun ConsentManagementRoute(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    val gdprViewModel: GdprViewModel = viewModel {
        GdprViewModel(context.applicationContext as android.app.Application)
    }
    val uiState by gdprViewModel.uiState.collectAsState()

    // Load consents when screen opens
    LaunchedEffect(Unit) {
        gdprViewModel.loadConsents()
    }

    ConsentManagementScreen(
        consents = uiState.consents,
        isLoading = uiState.isLoading,
        onConsentChange = { type, granted ->
            gdprViewModel.updateConsent(type, granted)
        },
        onNavigateBack = onNavigateBack
    )
}

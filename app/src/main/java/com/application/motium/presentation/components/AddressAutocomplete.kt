package com.application.motium.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.application.motium.data.geocoding.NominatimResult
import com.application.motium.data.geocoding.NominatimService
import com.application.motium.presentation.theme.MotiumPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressAutocomplete(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onAddressSelected: (NominatimResult) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    placeholder: String = ""
) {
    var suggestions by remember { mutableStateOf<List<NominatimResult>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isProgrammaticChange by remember { mutableStateOf(false) }
    var isInitialLoad by remember { mutableStateOf(true) }

    val nominatimService = remember { NominatimService.getInstance() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(value) {
        // Ignorer le chargement initial (quand on pré-remplit le champ)
        if (isInitialLoad) {
            isInitialLoad = false
            return@LaunchedEffect
        }

        // Ignorer les changements programmatiques (quand on sélectionne une suggestion)
        if (isProgrammaticChange) {
            isProgrammaticChange = false
            return@LaunchedEffect
        }

        if (value.length >= 3) {
            isLoading = true
            delay(300) // Debounce

            scope.launch {
                try {
                    val results = nominatimService.searchAddress(value)

                    suggestions = results
                    showSuggestions = results.isNotEmpty()
                } catch (e: Exception) {
                    suggestions = emptyList()
                    showSuggestions = false
                } finally {
                    isLoading = false
                }
            }
        } else {
            suggestions = emptyList()
            showSuggestions = false
            isLoading = false
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                showSuggestions = true
            },
            label = if (label.isNotEmpty()) { { Text(label) } } else null,
            placeholder = if (placeholder.isNotEmpty()) { { Text(placeholder) } } else null,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = leadingIcon,
            trailingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedBorderColor = MotiumPrimary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedLabelColor = MotiumPrimary
            )
        )

        if (showSuggestions && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {
                LazyColumn(
                    modifier = Modifier.background(Color.White)
                ) {
                    items(suggestions) { suggestion ->
                        AddressSuggestionItem(
                            suggestion = suggestion,
                            onClick = {
                                // Fermer d'abord les suggestions pour éviter le double-clic
                                showSuggestions = false
                                suggestions = emptyList()

                                // Marquer comme changement programmatique
                                isProgrammaticChange = true

                                // Utiliser le format personnalisé si disponible
                                val formattedAddress = suggestion.address?.formatAddress()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: suggestion.display_name

                                // Ensuite mettre à jour la valeur et notifier
                                onValueChange(formattedAddress)
                                onAddressSelected(suggestion)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressSuggestionItem(
    suggestion: NominatimResult,
    onClick: () -> Unit
) {
    // Utiliser le format personnalisé: numéro rue, code postal ville, pays
    val formattedAddress = suggestion.address?.formatAddress()
        ?.takeIf { it.isNotBlank() }
        ?: suggestion.display_name

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MotiumPrimary,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formattedAddress,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1F2937), // Dark gray for text
                maxLines = 2
            )
        }
    }
}
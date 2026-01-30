package com.application.motium.presentation.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.domain.model.WithdrawalWaiverState

/**
 * Section de consentement pour la renonciation au droit de rétractation.
 * Conformément à l'article L221-28 du Code de la consommation français.
 *
 * IMPORTANT: Les cases NE SONT PAS pré-cochées (obligation légale).
 */
@Composable
fun WithdrawalWaiverSection(
    state: WithdrawalWaiverState,
    onImmediateExecutionChanged: (Boolean) -> Unit,
    onWaiverChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Titre
            Text(
                text = "Droit de rétractation",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Checkbox 1 - Exécution immédiate
            WaiverCheckboxRow(
                checked = state.acceptedImmediateExecution,
                onCheckedChange = onImmediateExecutionChanged,
                text = "J'accepte que l'accès au service Premium Motium commence immédiatement après la validation de mon paiement, sans attendre l'expiration du délai de rétractation de 14 jours."
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Checkbox 2 - Renonciation
            WaiverCheckboxRow(
                checked = state.acceptedWaiver,
                onCheckedChange = onWaiverChanged,
                text = "Je reconnais expressément renoncer à mon droit de rétractation de 14 jours prévu par l'article L221-28 du Code de la consommation, dès que j'aurai accès au service Premium."
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Lien vers les CGV
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://motium.org/terms"))
                    context.startActivity(intent)
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = Color(0xFF4CAF50),
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append("Voir les conditions générales de vente")
                        }
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun WaiverCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4CAF50),
                uncheckedColor = Color(0xFF757575),
                checkmarkColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0B0B0),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

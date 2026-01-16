package com.application.motium.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.EmailRepository
import kotlinx.coroutines.launch

/**
 * Screen shown when user opens an invitation link without being logged in.
 * Offers options to:
 * - Sign in with Google
 * - Create a password (for new users)
 * - Sign in with existing email/password
 */
@Composable
fun AcceptInvitationScreen(
    invitationToken: String,
    invitationEmail: String?,
    companyName: String? = null,
    onGoogleSignIn: () -> Unit,
    onExistingAccount: () -> Unit,
    onAccountCreated: (userId: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val emailRepository = remember { EmailRepository.getInstance(context) }

    // State
    var showCreatePassword by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(invitationEmail ?: "") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Clear error when inputs change
    LaunchedEffect(email, password, confirmPassword) {
        errorMessage = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Company icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Business,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                Text(
                    text = "Invitation professionnelle",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Company name
                if (!companyName.isNullOrBlank()) {
                    Text(
                        text = companyName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "vous invite à rejoindre son espace Motium",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (!showCreatePassword) {
                    // Main options
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Pour accepter cette invitation :",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )

                            // Google Sign In
                            Button(
                                onClick = onGoogleSignIn,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Continuer avec Google")
                            }

                            // Create password (new account)
                            OutlinedButton(
                                onClick = { showCreatePassword = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Créer un mot de passe")
                            }

                            // Existing account
                            TextButton(
                                onClick = onExistingAccount,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("J'ai déjà un compte Motium")
                            }
                        }
                    }
                } else {
                    // Create password form
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Créer votre compte",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )

                            // Email field (pre-filled, read-only if invitation has email)
                            OutlinedTextField(
                                value = email,
                                onValueChange = { if (invitationEmail == null) email = it },
                                label = { Text("Email") },
                                leadingIcon = {
                                    Icon(Icons.Default.Email, contentDescription = null)
                                },
                                enabled = invitationEmail == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Name field
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Nom complet") },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = null)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Password field
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Mot de passe") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            if (passwordVisible) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = if (passwordVisible) "Masquer" else "Afficher"
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next
                                ),
                                supportingText = {
                                    Text("Minimum 8 caractères")
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Confirm password field
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text("Confirmer le mot de passe") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                                supportingText = {
                                    if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                                        Text(
                                            "Les mots de passe ne correspondent pas",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Error message
                            errorMessage?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            // Create account button
                            Button(
                                onClick = {
                                    // Validate
                                    when {
                                        email.isBlank() -> {
                                            errorMessage = "Veuillez entrer votre email"
                                        }
                                        !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                                            errorMessage = "Email invalide"
                                        }
                                        password.length < 8 -> {
                                            errorMessage = "Le mot de passe doit contenir au moins 8 caractères"
                                        }
                                        password != confirmPassword -> {
                                            errorMessage = "Les mots de passe ne correspondent pas"
                                        }
                                        else -> {
                                            isLoading = true
                                            errorMessage = null
                                            scope.launch {
                                                try {
                                                    val result = emailRepository.setInitialPassword(
                                                        invitationToken = invitationToken,
                                                        email = email.trim().lowercase(),
                                                        password = password,
                                                        name = name.trim().ifBlank { null }
                                                    )
                                                    result.fold(
                                                        onSuccess = { setPasswordResult ->
                                                            MotiumApplication.logger.i(
                                                                "Account created successfully for ${setPasswordResult.companyName}",
                                                                "AcceptInvitation"
                                                            )
                                                            onAccountCreated(setPasswordResult.userId)
                                                        },
                                                        onFailure = { e ->
                                                            val message = when {
                                                                e is EmailRepository.SetPasswordException -> {
                                                                    when (e.errorCode) {
                                                                        "weak_password" -> "Le mot de passe est trop faible"
                                                                        "user_exists" -> "Un compte existe déjà avec cet email. Connectez-vous plutôt."
                                                                        "email_mismatch" -> "L'email ne correspond pas à l'invitation"
                                                                        "invalid_token" -> "Cette invitation n'est plus valide"
                                                                        "invitation_expired" -> "Cette invitation a expiré"
                                                                        else -> e.message ?: "Erreur lors de la création du compte"
                                                                    }
                                                                }
                                                                else -> e.message ?: "Erreur lors de la création du compte"
                                                            }
                                                            errorMessage = message
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    errorMessage = e.message ?: "Erreur inattendue"
                                                } finally {
                                                    isLoading = false
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !isLoading && email.isNotBlank() && password.length >= 8 && password == confirmPassword,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Créer mon compte")
                                }
                            }

                            // Back button
                            TextButton(
                                onClick = { showCreatePassword = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retour")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Benefits card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "En acceptant cette invitation :",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        BenefitItem("Enregistrez vos trajets professionnels automatiquement")
                        BenefitItem("Partagez vos trajets avec votre entreprise")
                        BenefitItem("Bénéficiez d'une licence Pro gratuite")
                        BenefitItem("Exportez vos notes de frais")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cancel button
                TextButton(onClick = onCancel) {
                    Text("Annuler")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // Loading overlay
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun BenefitItem(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer
    )
}

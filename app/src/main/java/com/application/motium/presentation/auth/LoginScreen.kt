package com.application.motium.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.app.Activity
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.application.motium.utils.CredentialManagerHelper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onForgotPassword: () -> Unit = {},
    viewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else Color.White
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight
    val borderColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E5E7)

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isEmailValid by remember { mutableStateOf(true) }

    val loginState by viewModel.loginState.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val passwordFocusRequester = remember { FocusRequester() }

    // Autofill setup
    val autofill = LocalAutofill.current
    val emailAutofillNode = remember {
        AutofillNode(
            autofillTypes = listOf(AutofillType.EmailAddress),
            onFill = { email = it }
        )
    }
    val passwordAutofillNode = remember {
        AutofillNode(
            autofillTypes = listOf(AutofillType.Password),
            onFill = { password = it }
        )
    }

    // Get activity for Credential Manager
    val activity = context as? Activity
    val credentialManager = remember { CredentialManagerHelper.getInstance(context) }

    // Try to get saved credentials on first load
    LaunchedEffect(Unit) {
        activity?.let { act ->
            when (val result = credentialManager.getCredentials(act)) {
                is CredentialManagerHelper.CredentialResult.Success -> {
                    // Auto-fill with saved credentials
                    email = result.email
                    password = result.password
                }
                is CredentialManagerHelper.CredentialResult.Cancelled,
                is CredentialManagerHelper.CredentialResult.NoCredentials,
                is CredentialManagerHelper.CredentialResult.Error -> {
                    // User cancelled or no saved credentials - continue normally
                }
            }
        }
    }

    // Save credentials after successful login
    LaunchedEffect(loginState.credentialsToSave) {
        loginState.credentialsToSave?.let { credentials ->
            activity?.let { act ->
                // Save credentials to password manager (Samsung Pass, Google, etc.)
                credentialManager.saveCredentials(
                    activity = act,
                    email = credentials.email,
                    password = credentials.password
                )
            }
            // Clear credentials after save attempt
            viewModel.clearCredentialsToSave()
        }
    }

    // Navigate to home if authenticated
    LaunchedEffect(authState.isAuthenticated) {
        if (authState.isAuthenticated) {
            onNavigateToHome()
        }
    }

    // Clear error after showing
    LaunchedEffect(loginState.error) {
        if (loginState.error != null) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearLoginError()
        }
    }

    // Loading state during session restoration
    if (authState.isLoading && !loginState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MotiumPrimary,
                    strokeWidth = 4.dp
                )
                Text(
                    text = "Connexion en cours...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textSecondaryColor
                )
            }
        }
        return
    }

    WithCustomColor {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            // Gradient background at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.4f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MotiumPrimary.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(60.dp))

                // Logo - Stylized "M" as a route path
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MotiumPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.size(48.dp)
                    ) {
                        val width = size.width
                        val height = size.height
                        val strokeWidth = width * 0.12f

                        // Draw "M" as a continuous route/path
                        val path = Path().apply {
                            // Start at bottom left
                            moveTo(width * 0.15f, height * 0.85f)
                            // Go up to top left
                            lineTo(width * 0.15f, height * 0.2f)
                            // Diagonal down to center
                            lineTo(width * 0.5f, height * 0.6f)
                            // Diagonal up to top right
                            lineTo(width * 0.85f, height * 0.2f)
                            // Go down to bottom right
                            lineTo(width * 0.85f, height * 0.85f)
                        }

                        drawPath(
                            path = path,
                            color = Color.White,
                            style = Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Add small circles at start and end points (like map markers)
                        val dotRadius = strokeWidth * 0.6f
                        drawCircle(
                            color = Color.White,
                            radius = dotRadius,
                            center = Offset(width * 0.15f, height * 0.85f)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = dotRadius,
                            center = Offset(width * 0.85f, height * 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                Text(
                    text = "Bon retour !",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    ),
                    color = textColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Connectez-vous pour continuer",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textSecondaryColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Login Form
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Email field
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Adresse email",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                isEmailValid = android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()
                            },
                            placeholder = {
                                Text(
                                    "Entrez votre email",
                                    color = textSecondaryColor
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Email,
                                    contentDescription = null,
                                    tint = textSecondaryColor
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    emailAutofillNode.boundingBox = coordinates.boundsInWindow()
                                }
                                .onFocusChanged { focusState ->
                                    autofill?.run {
                                        if (focusState.isFocused) {
                                            requestAutofillForNode(emailAutofillNode)
                                        } else {
                                            cancelAutofillForNode(emailAutofillNode)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = borderColor,
                                focusedBorderColor = MotiumPrimary,
                                unfocusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                                focusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                                cursorColor = MotiumPrimary
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                                autoCorrect = false
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { passwordFocusRequester.requestFocus() }
                            ),
                            isError = !isEmailValid && email.isNotEmpty(),
                            singleLine = true
                        )
                        if (!isEmailValid && email.isNotEmpty()) {
                            Text(
                                text = "Veuillez entrer une adresse email valide",
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed
                            )
                        }
                    }

                    // Password field
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Mot de passe",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = {
                                Text(
                                    "Entrez votre mot de passe",
                                    color = textSecondaryColor
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = textSecondaryColor
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility
                                        else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Masquer" else "Afficher",
                                        tint = textSecondaryColor
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(passwordFocusRequester)
                                .onGloballyPositioned { coordinates ->
                                    passwordAutofillNode.boundingBox = coordinates.boundsInWindow()
                                }
                                .onFocusChanged { focusState ->
                                    autofill?.run {
                                        if (focusState.isFocused) {
                                            requestAutofillForNode(passwordAutofillNode)
                                        } else {
                                            cancelAutofillForNode(passwordAutofillNode)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = borderColor,
                                focusedBorderColor = MotiumPrimary,
                                unfocusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                                focusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                                cursorColor = MotiumPrimary
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                autoCorrect = false
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    if (email.isNotEmpty() && password.isNotEmpty() && isEmailValid) {
                                        viewModel.signIn(email, password)
                                    }
                                }
                            ),
                            singleLine = true
                        )
                    }

                    // Forgot password
                    TextButton(
                        onClick = onForgotPassword,
                        modifier = Modifier.align(Alignment.End),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "Mot de passe oublié ?",
                            color = MotiumPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Error message
                if (loginState.error != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = ErrorRed.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = ErrorRed
                            )
                            Text(
                                text = loginState.error ?: "",
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Login button
                Button(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.signIn(email, password)
                    },
                    enabled = email.isNotEmpty() && password.isNotEmpty() && isEmailValid && !loginState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MotiumPrimary,
                        contentColor = Color.White,
                        disabledContainerColor = MotiumPrimary.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (loginState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Se connecter",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Divider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = borderColor
                    )
                    Text(
                        text = "ou",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = textSecondaryColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = borderColor
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Google Sign-In button
                OutlinedButton(
                    onClick = { viewModel.initiateGoogleSignIn() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = surfaceColor.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Google "G" icon representation
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "G",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF4285F4)
                            )
                        }
                        Text(
                            text = "Continuer avec Google",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Register link
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pas encore de compte ? ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textSecondaryColor
                    )
                    TextButton(
                        onClick = onNavigateToRegister,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "S'inscrire",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MotiumPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

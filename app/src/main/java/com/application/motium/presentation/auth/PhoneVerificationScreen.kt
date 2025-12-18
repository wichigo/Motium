package com.application.motium.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager

/**
 * Phone verification screen for registration.
 * Two-step process: enter phone number, then verify with OTP code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneVerificationScreen(
    onNavigateBack: () -> Unit = {},
    onVerificationComplete: (verifiedPhone: String) -> Unit = {},
    viewModel: PhoneVerificationViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else Color.White
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight
    val borderColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E5E7)

    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Check device eligibility when screen is shown
    LaunchedEffect(Unit) {
        viewModel.checkDeviceEligibility()
    }

    // Navigate when verification is complete
    LaunchedEffect(uiState.isVerified) {
        if (uiState.isVerified && uiState.verifiedPhoneNumber != null) {
            onVerificationComplete(uiState.verifiedPhoneNumber!!)
        }
    }

    // Clear error after delay
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearError()
        }
    }

    WithCustomColor {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            // Gradient background
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

            // Back button
            IconButton(
                onClick = {
                    if (uiState.step == VerificationStep.ENTER_OTP) {
                        viewModel.goBackToPhoneEntry()
                    } else {
                        onNavigateBack()
                    }
                },
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = textColor
                )
            }

            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(80.dp))

                // Icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MotiumPrimary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.step == VerificationStep.ENTER_PHONE)
                            Icons.Default.Phone
                        else
                            Icons.Default.Sms,
                        contentDescription = null,
                        tint = MotiumPrimary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Title
                Text(
                    text = if (uiState.step == VerificationStep.ENTER_PHONE)
                        "Vérification du téléphone"
                    else
                        "Entrez le code",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = textColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (uiState.step == VerificationStep.ENTER_PHONE)
                        "Pour sécuriser votre compte et prévenir les abus, veuillez vérifier votre numéro de téléphone."
                    else
                        "Nous avons envoyé un code à 6 chiffres au ${uiState.otpSentTo}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textSecondaryColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Device check loading
                if (uiState.isCheckingDevice) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MotiumPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Vérification de l'appareil...",
                        color = textSecondaryColor
                    )
                } else if (!uiState.deviceEligible) {
                    // Device not eligible
                    DeviceNotEligibleCard(
                        error = uiState.error ?: "Appareil non éligible",
                        textColor = textColor,
                        onNavigateBack = onNavigateBack
                    )
                } else {
                    // Main content based on step
                    when (uiState.step) {
                        VerificationStep.ENTER_PHONE -> {
                            PhoneEntryContent(
                                phoneNumber = uiState.phoneNumber,
                                countryCode = uiState.countryCode,
                                onPhoneChange = viewModel::updatePhoneNumber,
                                onCountryCodeChange = viewModel::updateCountryCode,
                                onSubmit = {
                                    keyboardController?.hide()
                                    viewModel.sendOtp()
                                },
                                isLoading = uiState.isLoading,
                                error = uiState.error,
                                surfaceColor = surfaceColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor,
                                borderColor = borderColor
                            )
                        }
                        VerificationStep.ENTER_OTP -> {
                            OtpEntryContent(
                                otpCode = uiState.otpCode,
                                onOtpChange = viewModel::updateOtpCode,
                                onSubmit = {
                                    keyboardController?.hide()
                                    viewModel.verifyOtp()
                                },
                                onResend = viewModel::resendOtp,
                                isLoading = uiState.isLoading,
                                error = uiState.error,
                                resendCount = uiState.resendCount,
                                surfaceColor = surfaceColor,
                                textColor = textColor,
                                textSecondaryColor = textSecondaryColor,
                                borderColor = borderColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PhoneEntryContent(
    phoneNumber: String,
    countryCode: String,
    onPhoneChange: (String) -> Unit,
    onCountryCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean,
    error: String?,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    borderColor: Color
) {
    var showCountryPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Numéro de téléphone",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = textColor
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Country code selector
            OutlinedButton(
                onClick = { showCountryPicker = true },
                modifier = Modifier.width(100.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = surfaceColor.copy(alpha = 0.5f)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.linearGradient(listOf(borderColor, borderColor))
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)
            ) {
                Text(
                    text = countryCode,
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = textSecondaryColor
                )
            }

            // Phone number field
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { value ->
                    // Only accept digits
                    onPhoneChange(value.filter { it.isDigit() })
                },
                placeholder = {
                    Text(
                        "6 12 34 56 78",
                        color = textSecondaryColor
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = borderColor,
                    focusedBorderColor = MotiumPrimary,
                    unfocusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                    focusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                    cursorColor = MotiumPrimary
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit() }
                ),
                singleLine = true
            )
        }
    }

    // Error message
    if (error != null) {
        Spacer(modifier = Modifier.height(16.dp))
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
                    text = error,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Submit button
    Button(
        onClick = onSubmit,
        enabled = phoneNumber.length >= 9 && !isLoading,
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
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = "Envoyer le code",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    // Country picker dialog
    if (showCountryPicker) {
        CountryCodePickerDialog(
            onDismiss = { showCountryPicker = false },
            onSelect = { code ->
                onCountryCodeChange(code)
                showCountryPicker = false
            }
        )
    }
}

@Composable
private fun OtpEntryContent(
    otpCode: String,
    onOtpChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    isLoading: Boolean,
    error: String?,
    resendCount: Int,
    surfaceColor: Color,
    textColor: Color,
    textSecondaryColor: Color,
    borderColor: Color
) {
    val focusRequester = remember { FocusRequester() }

    // Request focus when shown
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // OTP input boxes
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hidden text field for keyboard input
            BasicTextField(
                value = otpCode,
                onValueChange = { value ->
                    if (value.length <= 6) {
                        onOtpChange(value)
                        if (value.length == 6) {
                            onSubmit()
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit() }
                ),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .size(0.dp) // Hidden
            )

            // Visual OTP boxes
            repeat(6) { index ->
                val char = otpCode.getOrNull(index)?.toString() ?: ""
                val isFocused = index == otpCode.length

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceColor.copy(alpha = 0.5f))
                        .border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MotiumPrimary else borderColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { focusRequester.requestFocus() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = textColor
                    )
                }
            }
        }
    }

    // Error message
    if (error != null) {
        Spacer(modifier = Modifier.height(16.dp))
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
                    text = error,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Verify button
    Button(
        onClick = onSubmit,
        enabled = otpCode.length == 6 && !isLoading,
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
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = "Vérifier",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Resend code
    TextButton(
        onClick = onResend,
        enabled = !isLoading
    ) {
        Text(
            text = if (resendCount > 0)
                "Renvoyer le code (${resendCount})"
            else
                "Renvoyer le code",
            color = MotiumPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeviceNotEligibleCard(
    error: String,
    textColor: Color,
    onNavigateBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ErrorRed.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Block,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "Inscription impossible",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = textColor
            )

            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onNavigateBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Retour à la connexion")
            }
        }
    }
}

@Composable
private fun CountryCodePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val countryCodes = listOf(
        "+33" to "France",
        "+32" to "Belgique",
        "+41" to "Suisse",
        "+352" to "Luxembourg",
        "+377" to "Monaco",
        "+1" to "États-Unis / Canada",
        "+44" to "Royaume-Uni",
        "+49" to "Allemagne",
        "+34" to "Espagne",
        "+39" to "Italie"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sélectionner un pays") },
        text = {
            Column {
                countryCodes.forEach { (code, name) ->
                    TextButton(
                        onClick = { onSelect(code) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name)
                            Text(
                                code,
                                fontWeight = FontWeight.Bold,
                                color = MotiumPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

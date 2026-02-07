package com.application.motium.presentation.pro.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.motium.data.supabase.ProSettingsRepository
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Screen for inviting a new person (employee) to link with Pro account
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitePersonScreen(
    onNavigateBack: () -> Unit = {},
    onInviteSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val authRepository = remember { SupabaseAuthRepository.getInstance(context) }
    val proSettingsRepository = remember { ProSettingsRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // Form state
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var selectedDepartment by remember { mutableStateOf<String?>(null) }

    // Departments from pro settings
    var departments by remember { mutableStateOf<List<String>>(emptyList()) }
    var departmentDropdownExpanded by remember { mutableStateOf(false) }

    // Validation errors
    var fullNameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }

    // Loading state
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingDepartments by remember { mutableStateOf(true) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Load departments on init
    LaunchedEffect(Unit) {
        try {
            val proAccountId = authRepository.getCurrentProAccountId()
            if (proAccountId != null) {
                departments = proSettingsRepository.getDepartments(proAccountId)
            }
        } catch (e: Exception) {
            // Departments are optional
        } finally {
            isLoadingDepartments = false
        }
    }

    fun validateForm(): Boolean {
        var isValid = true

        if (fullName.isBlank()) {
            fullNameError = "Le nom complet est requis"
            isValid = false
        } else {
            fullNameError = null
        }

        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            emailError = "L'email est requis"
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            emailError = "Email invalide"
            isValid = false
        } else {
            emailError = null
        }

        return isValid
    }

    fun invitePerson() {
        if (!validateForm()) return

        scope.launch {
            isLoading = true
            try {
                val proAccountId = authRepository.getCurrentProAccountId()
                if (proAccountId == null) {
                    snackbarHostState.showSnackbar("Compte Pro non trouvé")
                    isLoading = false
                    return@launch
                }

                // Get company name from pro account
                val proAccountRemoteDataSource = com.application.motium.data.supabase.ProAccountRemoteDataSource.getInstance(context)
                val authState = authRepository.authState.first()
                val currentUser = authState.user
                if (currentUser == null) {
                    snackbarHostState.showSnackbar("Utilisateur non connecté")
                    isLoading = false
                    return@launch
                }
                val proAccountResult = proAccountRemoteDataSource.getProAccount(currentUser.id)
                val companyName = proAccountResult.getOrNull()?.companyName
                if (companyName == null) {
                    snackbarHostState.showSnackbar("Nom de l'entreprise non trouvé")
                    isLoading = false
                    return@launch
                }

                // Save new department if it doesn't exist yet
                val trimmedDepartment = selectedDepartment?.trim()?.takeIf { it.isNotBlank() }
                if (trimmedDepartment != null && !departments.contains(trimmedDepartment)) {
                    val addDeptResult = proSettingsRepository.addDepartment(proAccountId, trimmedDepartment)
                    if (addDeptResult.isSuccess) {
                        // Update local list
                        departments = departments + trimmedDepartment
                    }
                    // Continue even if department save fails - invitation is the priority
                }

                val linkedAccountRemoteDataSource = com.application.motium.data.supabase.LinkedAccountRemoteDataSource.getInstance(context)
                val result = linkedAccountRemoteDataSource.inviteUserWithDetails(
                    proAccountId = proAccountId,
                    companyName = companyName,
                    email = email.trim(),
                    fullName = fullName.trim(),
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    department = trimmedDepartment
                )

                result.fold(
                    onSuccess = {
                        snackbarHostState.showSnackbar("Invitation envoyée avec succès")
                        onInviteSuccess()
                    },
                    onFailure = { e ->
                        snackbarHostState.showSnackbar("Erreur: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Erreur: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Inviter une personne",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        fontSize = 18.sp,
                        color = textColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Retour",
                            tint = textColor
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = backgroundColor
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Full Name
            Column {
                Text(
                    "Nom complet",
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fullName,
                    onValueChange = {
                        fullName = it
                        fullNameError = null
                    },
                    placeholder = { Text("ex. Jean Dupont", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = fullNameError != null,
                    supportingText = fullNameError?.let { { Text(it, color = ErrorRed) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor,
                        cursorColor = MotiumPrimary
                    ),
                    singleLine = true
                )
            }

            // Email
            Column {
                Text(
                    "Email",
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailError = null
                    },
                    placeholder = { Text("ex. jean.dupont@example.com", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = emailError != null,
                    supportingText = emailError?.let { { Text(it, color = ErrorRed) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor,
                        cursorColor = MotiumPrimary
                    ),
                    singleLine = true
                )
            }

            // Phone
            Column {
                Text(
                    "Numéro de téléphone",
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    placeholder = { Text("ex. +33 6 12 34 56 78", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MotiumPrimary,
                        unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor,
                        cursorColor = MotiumPrimary
                    ),
                    singleLine = true
                )
            }

            // Department dropdown
            Column {
                Text(
                    "Département",
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (isLoadingDepartments) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        placeholder = { Text("Chargement...", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            disabledContainerColor = surfaceColor
                        ),
                        singleLine = true
                    )
                } else if (departments.isEmpty()) {
                    OutlinedTextField(
                        value = selectedDepartment ?: "",
                        onValueChange = { selectedDepartment = it },
                        placeholder = { Text("ex. Commercial", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MotiumPrimary,
                            unfocusedBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                            focusedContainerColor = surfaceColor,
                            unfocusedContainerColor = surfaceColor,
                            cursorColor = MotiumPrimary
                        ),
                        singleLine = true,
                        supportingText = {
                            Text(
                                "Aucun département configuré. Vous pouvez en saisir un manuellement.",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondaryColor
                            )
                        }
                    )
                } else {
                    // Custom dropdown implementation
                    Box {
                        OutlinedTextField(
                            value = selectedDepartment ?: "",
                            onValueChange = {},
                            placeholder = { Text("Sélectionner un département", color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { departmentDropdownExpanded = true },
                            shape = RoundedCornerShape(12.dp),
                            readOnly = true,
                            enabled = false,
                            trailingIcon = {
                                Icon(
                                    if (departmentDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = textSecondaryColor
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledBorderColor = textSecondaryColor.copy(alpha = 0.3f),
                                disabledContainerColor = surfaceColor,
                                disabledTextColor = textColor
                            ),
                            singleLine = true
                        )

                        // Clickable overlay
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { departmentDropdownExpanded = true }
                        )

                        DropdownMenu(
                            expanded = departmentDropdownExpanded,
                            onDismissRequest = { departmentDropdownExpanded = false },
                            modifier = Modifier
                                .background(surfaceColor)
                                .widthIn(min = 200.dp)
                        ) {
                            departments.forEach { department ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            department,
                                            color = textColor
                                        )
                                    },
                                    onClick = {
                                        selectedDepartment = department
                                        departmentDropdownExpanded = false
                                    },
                                    modifier = Modifier.background(surfaceColor)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Invite button
            Button(
                onClick = { invitePerson() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoading && fullName.isNotBlank() && email.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotiumPrimary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        "Inviter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Extra space at bottom to clear navigation bar
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}


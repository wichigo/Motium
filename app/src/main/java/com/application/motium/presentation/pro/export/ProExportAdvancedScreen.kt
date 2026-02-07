package com.application.motium.presentation.pro.export

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.application.motium.data.supabase.LinkedUserDto
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.theme.*
import com.application.motium.utils.ThemeManager
import kotlinx.datetime.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Pro Export Advanced Screen - Multi-account export
 * Allows Pro users to export data from all linked accounts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProExportAdvancedScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToVehicles: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLinkedAccounts: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val viewModel: ProExportAdvancedViewModel = viewModel(
        factory = ProExportAdvancedViewModelFactory(context)
    )

    val uiState by viewModel.uiState.collectAsState()
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val backgroundColor = if (isDarkMode) BackgroundDark else BackgroundLight
    val surfaceColor = if (isDarkMode) SurfaceDark else SurfaceLight
    val textColor = if (isDarkMode) TextDark else TextLight
    val textSecondaryColor = if (isDarkMode) TextSecondaryDark else TextSecondaryLight

    // Calendar state
    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerMode by remember { mutableStateOf("start") }
    var selectedField by remember { mutableStateOf<String?>(null) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccessMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Exports Pro",
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
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MotiumPrimary)
            }
        } else {
            // State for dropdowns
            var departmentsDropdownExpanded by remember { mutableStateOf(false) }
            var departmentSearchQuery by remember { mutableStateOf("") }
            var accountsDropdownExpanded by remember { mutableStateOf(false) }
            var accountSearchQuery by remember { mutableStateOf("") }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Departments section (dropdown with search)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Départements",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // Dropdown field
                        val selectedDeptCount = uiState.selectedDepartments.size
                        val totalDeptCount = uiState.availableDepartments.size
                        val deptDisplayText = when {
                            uiState.availableDepartments.isEmpty() -> "Aucun département"
                            selectedDeptCount == 0 -> "Sélectionner des départements"
                            selectedDeptCount == totalDeptCount -> "Tous les départements ($totalDeptCount)"
                            selectedDeptCount == 1 -> uiState.selectedDepartments.first()
                            else -> "$selectedDeptCount départements sélectionnés"
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = deptDisplayText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Départements") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        if (departmentsDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Business, contentDescription = null, tint = MotiumPrimary)
                                },
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = textColor,
                                    disabledBorderColor = if (departmentsDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    disabledLabelColor = if (departmentsDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    disabledContainerColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (uiState.availableDepartments.isNotEmpty()) {
                                            departmentsDropdownExpanded = !departmentsDropdownExpanded
                                        }
                                    }
                            )
                        }

                        // Expandable departments list
                        AnimatedVisibility(
                            visible = departmentsDropdownExpanded,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDarkMode) Color(0xFF1F2937) else Color.White
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier.background(if (isDarkMode) Color(0xFF1F2937) else Color.White)
                                ) {
                                    // Search field
                                    OutlinedTextField(
                                        value = departmentSearchQuery,
                                        onValueChange = { departmentSearchQuery = it },
                                        placeholder = { Text("Rechercher...") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                tint = textSecondaryColor
                                            )
                                        },
                                        trailingIcon = {
                                            if (departmentSearchQuery.isNotEmpty()) {
                                                IconButton(onClick = { departmentSearchQuery = "" }) {
                                                    Icon(
                                                        Icons.Default.Clear,
                                                        contentDescription = "Effacer",
                                                        tint = textSecondaryColor
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MotiumPrimary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            cursorColor = MotiumPrimary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )

                                    // Select all / Deselect all
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleSelectAllDepartments() }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.allDepartmentsSelected,
                                            onCheckedChange = { viewModel.toggleSelectAllDepartments() },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MotiumPrimary,
                                                checkmarkColor = Color.White,
                                                uncheckedColor = Color.Gray
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            if (uiState.allDepartmentsSelected) "Désélectionner tout" else "Sélectionner tout",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MotiumPrimary
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )

                                    // Filtered departments list
                                    val filteredDepartments = uiState.availableDepartments.filter { dept ->
                                        departmentSearchQuery.isEmpty() ||
                                        dept.contains(departmentSearchQuery, ignoreCase = true)
                                    }

                                    if (filteredDepartments.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "Aucun résultat",
                                                color = textSecondaryColor
                                            )
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .heightIn(max = 200.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            filteredDepartments.forEachIndexed { index, dept ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { viewModel.toggleDepartmentSelection(dept) }
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = uiState.selectedDepartments.contains(dept),
                                                        onCheckedChange = { viewModel.toggleDepartmentSelection(dept) },
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = MotiumPrimary,
                                                            checkmarkColor = Color.White,
                                                            uncheckedColor = Color.Gray
                                                        )
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Icon(
                                                        Icons.Default.Business,
                                                        contentDescription = null,
                                                        tint = MotiumPrimary.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        dept,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = textColor
                                                    )
                                                }
                                                if (index < filteredDepartments.size - 1) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Done button
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    ) {
                                        Button(
                                            onClick = { departmentsDropdownExpanded = false },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Terminé", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Accounts section (dropdown with search)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Comptes",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // Dropdown field - uses filteredUsers (filtered by department)
                        val filteredUsers = uiState.filteredUsers
                        val selectedInFiltered = filteredUsers.count { it.userId in uiState.selectedUserIds }
                        val totalCount = filteredUsers.size
                        val displayText = when {
                            filteredUsers.isEmpty() -> "Aucun compte (filtré)"
                            selectedInFiltered == 0 -> "Sélectionner des comptes"
                            selectedInFiltered == totalCount -> "Tous les comptes ($totalCount)"
                            selectedInFiltered == 1 -> filteredUsers.find { it.userId in uiState.selectedUserIds }?.displayName ?: "1 compte"
                            else -> "$selectedInFiltered comptes sélectionnés"
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = displayText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Collaborateurs") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    Icon(
                                        if (accountsDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MotiumPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.People, contentDescription = null, tint = MotiumPrimary)
                                },
                                enabled = false,
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = textColor,
                                    disabledBorderColor = if (accountsDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    disabledLabelColor = if (accountsDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    disabledContainerColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (filteredUsers.isNotEmpty()) {
                                            accountsDropdownExpanded = !accountsDropdownExpanded
                                        }
                                    }
                            )
                        }

                        // Expandable accounts list
                        AnimatedVisibility(
                            visible = accountsDropdownExpanded,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDarkMode) Color(0xFF1F2937) else Color.White
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier.background(if (isDarkMode) Color(0xFF1F2937) else Color.White)
                                ) {
                                    // Search field
                                    OutlinedTextField(
                                        value = accountSearchQuery,
                                        onValueChange = { accountSearchQuery = it },
                                        placeholder = { Text("Rechercher...") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                tint = textSecondaryColor
                                            )
                                        },
                                        trailingIcon = {
                                            if (accountSearchQuery.isNotEmpty()) {
                                                IconButton(onClick = { accountSearchQuery = "" }) {
                                                    Icon(
                                                        Icons.Default.Clear,
                                                        contentDescription = "Effacer",
                                                        tint = textSecondaryColor
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MotiumPrimary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            cursorColor = MotiumPrimary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )

                                    // Select all / Deselect all button
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleSelectAll() }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.allSelected,
                                            onCheckedChange = { viewModel.toggleSelectAll() },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MotiumPrimary,
                                                checkmarkColor = Color.White,
                                                uncheckedColor = if (isDarkMode) Color.Gray else Color.Gray
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            if (uiState.allSelected) "Désélectionner tout" else "Sélectionner tout",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MotiumPrimary
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )

                                    // Filtered users list (from department-filtered users)
                                    val searchFilteredUsers = filteredUsers.filter { user ->
                                        accountSearchQuery.isEmpty() ||
                                        user.displayName.contains(accountSearchQuery, ignoreCase = true) ||
                                        user.userEmail.contains(accountSearchQuery, ignoreCase = true)
                                    }

                                    if (searchFilteredUsers.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "Aucun résultat",
                                                color = textSecondaryColor
                                            )
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .heightIn(max = 250.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            // Only show users with userId (not pending invitations)
                                            searchFilteredUsers.filter { it.userId != null }.forEachIndexed { index, user ->
                                                UserSelectionRow(
                                                    user = user,
                                                    isSelected = user.userId?.let { uiState.selectedUserIds.contains(it) } ?: false,
                                                    onToggle = { user.userId?.let { viewModel.toggleUserSelection(it) } },
                                                    textColor = textColor,
                                                    textSecondaryColor = textSecondaryColor
                                                )
                                                if (index < searchFilteredUsers.size - 1) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Done button
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    ) {
                                        Button(
                                            onClick = { accountsDropdownExpanded = false },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Terminé", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick selection section
                item {
                    ProQuickExportSection(
                        viewModel = viewModel,
                        isExporting = uiState.isExporting
                    )
                }

                // Period section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Période",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // From/To inputs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (selectedField == "start" && showDatePicker) {
                                            showDatePicker = false
                                            selectedField = null
                                        } else {
                                            datePickerMode = "start"
                                            selectedField = "start"
                                            showDatePicker = true
                                        }
                                    }
                            ) {
                                OutlinedTextField(
                                    value = formatInstant(uiState.startDate),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Du") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledContainerColor = Color.Transparent,
                                        disabledBorderColor = if (selectedField == "start") MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledTextColor = textColor,
                                        disabledLabelColor = if (selectedField == "start") MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = false
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (selectedField == "end" && showDatePicker) {
                                            showDatePicker = false
                                            selectedField = null
                                        } else {
                                            datePickerMode = "end"
                                            selectedField = "end"
                                            showDatePicker = true
                                        }
                                    }
                            ) {
                                OutlinedTextField(
                                    value = formatInstant(uiState.endDate),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Au") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledContainerColor = Color.Transparent,
                                        disabledBorderColor = if (selectedField == "end") MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledTextColor = textColor,
                                        disabledLabelColor = if (selectedField == "end") MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = false
                                )
                            }
                        }

                        // Calendar - Animated
                        AnimatedVisibility(
                            visible = showDatePicker,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // Calendar header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = {
                                            val newMonth = currentMonth.clone() as Calendar
                                            newMonth.add(Calendar.MONTH, -1)
                                            currentMonth = newMonth
                                        }) {
                                            Icon(
                                                Icons.Default.KeyboardArrowLeft,
                                                contentDescription = "Mois précédent",
                                                tint = textSecondaryColor
                                            )
                                        }

                                        Text(
                                            SimpleDateFormat("MMMM yyyy", Locale.FRENCH).format(currentMonth.time),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = textColor
                                        )

                                        IconButton(onClick = {
                                            val newMonth = currentMonth.clone() as Calendar
                                            newMonth.add(Calendar.MONTH, 1)
                                            currentMonth = newMonth
                                        }) {
                                            Icon(
                                                Icons.Default.KeyboardArrowRight,
                                                contentDescription = "Mois suivant",
                                                tint = textSecondaryColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Days of week
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        listOf("D", "L", "M", "M", "J", "V", "S").forEach { day ->
                                            Text(
                                                day,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = textSecondaryColor,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Calendar grid
                                    ProCalendarGrid(
                                        currentMonth = currentMonth,
                                        startDate = uiState.startDate,
                                        endDate = uiState.endDate,
                                        isDarkMode = isDarkMode,
                                        textColor = textColor,
                                        onDateSelected = { selectedDay ->
                                            val calendar = currentMonth.clone() as Calendar
                                            calendar.set(Calendar.DAY_OF_MONTH, selectedDay)
                                            calendar.set(Calendar.HOUR_OF_DAY, 0)
                                            calendar.set(Calendar.MINUTE, 0)
                                            calendar.set(Calendar.SECOND, 0)
                                            calendar.set(Calendar.MILLISECOND, 0)
                                            val selectedInstant = Instant.fromEpochMilliseconds(calendar.timeInMillis)

                                            if (datePickerMode == "start") {
                                                viewModel.setStartDate(selectedInstant)
                                                if (selectedInstant > uiState.endDate) {
                                                    viewModel.setEndDate(selectedInstant)
                                                }
                                                datePickerMode = "end"
                                                selectedField = "end"
                                            } else {
                                                viewModel.setEndDate(selectedInstant)
                                                if (selectedInstant < uiState.startDate) {
                                                    viewModel.setStartDate(selectedInstant)
                                                }
                                                showDatePicker = false
                                                selectedField = null
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Filters section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Filtres",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        // Trip Type filter
                        run {
                            var tripTypeExpanded by remember { mutableStateOf(false) }
                            val tripTypeText = when {
                                uiState.includeProTrips && uiState.includePersoTrips -> "Tous les trajets"
                                uiState.includeProTrips -> "Trajets professionnels"
                                uiState.includePersoTrips -> "Trajets personnels"
                                else -> "Aucun trajet"
                            }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = tripTypeText,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Type de trajets") },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = {
                                        Icon(
                                            if (tripTypeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Route, contentDescription = null, tint = MotiumPrimary)
                                    },
                                    enabled = false,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(top = 8.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { tripTypeExpanded = !tripTypeExpanded }
                                )

                                DropdownMenu(
                                    expanded = tripTypeExpanded,
                                    onDismissRequest = { tripTypeExpanded = false },
                                    modifier = Modifier.background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Tous les trajets", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeProTrips(true)
                                            viewModel.setIncludePersoTrips(true)
                                            tripTypeExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Trajets professionnels", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeProTrips(true)
                                            viewModel.setIncludePersoTrips(false)
                                            tripTypeExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Trajets personnels", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeProTrips(false)
                                            viewModel.setIncludePersoTrips(true)
                                            tripTypeExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        }

                        // Expenses filter
                        run {
                            var expenseExpanded by remember { mutableStateOf(false) }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = if (uiState.includeExpenses) "Avec dépenses" else "Sans dépenses",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Dépenses") },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = {
                                        Icon(
                                            if (expenseExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MotiumPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Receipt, contentDescription = null, tint = MotiumPrimary)
                                    },
                                    enabled = false,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(top = 8.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { expenseExpanded = !expenseExpanded }
                                )

                                DropdownMenu(
                                    expanded = expenseExpanded,
                                    onDismissRequest = { expenseExpanded = false },
                                    modifier = Modifier.background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Avec dépenses", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeExpenses(true)
                                            expenseExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sans dépenses", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            viewModel.setIncludeExpenses(false)
                                            expenseExpanded = false
                                        },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        }

                        // Vehicles filter (filtered by selected users)
                        run {
                            var vehiclesDropdownExpanded by remember { mutableStateOf(false) }
                            var vehicleSearchQuery by remember { mutableStateOf("") }

                            val filteredVehicles = uiState.filteredVehicles
                            val selectedInFiltered = filteredVehicles.count { it.id in uiState.selectedVehicleIds }
                            val totalCount = filteredVehicles.size
                            val vehDisplayText = when {
                                uiState.isLoadingVehicles -> "Chargement..."
                                filteredVehicles.isEmpty() -> "Aucun véhicule disponible"
                                selectedInFiltered == 0 -> "Sélectionner des véhicules"
                                selectedInFiltered == totalCount -> "Tous les véhicules ($totalCount)"
                                selectedInFiltered == 1 -> filteredVehicles.find { it.id in uiState.selectedVehicleIds }?.name ?: "1 véhicule"
                                else -> "$selectedInFiltered véhicules sélectionnés"
                            }

                            Column {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = vehDisplayText,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Véhicules") },
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                            if (uiState.isLoadingVehicles) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    color = MotiumPrimary,
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(
                                                    if (vehiclesDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = MotiumPrimary
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = MotiumPrimary)
                                        },
                                        enabled = false,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            disabledTextColor = textColor,
                                            disabledBorderColor = if (vehiclesDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            disabledLabelColor = if (vehiclesDropdownExpanded) MotiumPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            disabledContainerColor = Color.Transparent
                                        ),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .padding(top = 8.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                if (filteredVehicles.isNotEmpty() && !uiState.isLoadingVehicles) {
                                                    vehiclesDropdownExpanded = !vehiclesDropdownExpanded
                                                }
                                            }
                                    )
                                }

                                // Expandable vehicles list
                                AnimatedVisibility(
                                    visible = vehiclesDropdownExpanded,
                                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                                    exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isDarkMode) Color(0xFF1F2937) else Color.White
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.background(if (isDarkMode) Color(0xFF1F2937) else Color.White)
                                        ) {
                                            // Search field
                                            OutlinedTextField(
                                                value = vehicleSearchQuery,
                                                onValueChange = { vehicleSearchQuery = it },
                                                placeholder = { Text("Rechercher...") },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Search,
                                                        contentDescription = null,
                                                        tint = textSecondaryColor
                                                    )
                                                },
                                                trailingIcon = {
                                                    if (vehicleSearchQuery.isNotEmpty()) {
                                                        IconButton(onClick = { vehicleSearchQuery = "" }) {
                                                            Icon(
                                                                Icons.Default.Clear,
                                                                contentDescription = "Effacer",
                                                                tint = textSecondaryColor
                                                            )
                                                        }
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = MotiumPrimary,
                                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                                    focusedContainerColor = Color.Transparent,
                                                    unfocusedContainerColor = Color.Transparent,
                                                    cursorColor = MotiumPrimary
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                singleLine = true
                                            )

                                            // Select all / Deselect all button
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { viewModel.toggleSelectAllVehicles() }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = uiState.allVehiclesSelected,
                                                    onCheckedChange = { viewModel.toggleSelectAllVehicles() },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = MotiumPrimary,
                                                        checkmarkColor = Color.White,
                                                        uncheckedColor = if (isDarkMode) Color.Gray else Color.Gray
                                                    )
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    if (uiState.allVehiclesSelected) "Désélectionner tout" else "Sélectionner tout",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MotiumPrimary
                                                )
                                            }

                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                            )

                                            // Filtered vehicles list - grouped by collaborator
                                            val searchFilteredVehicles = filteredVehicles.filter { vehicle ->
                                                vehicleSearchQuery.isEmpty() ||
                                                    vehicle.name.contains(vehicleSearchQuery, ignoreCase = true) ||
                                                    vehicle.type.contains(vehicleSearchQuery, ignoreCase = true) ||
                                                    vehicle.userDisplayName.contains(vehicleSearchQuery, ignoreCase = true)
                                            }

                                            // Group vehicles by user
                                            val vehiclesByUser = searchFilteredVehicles.groupBy { it.userDisplayName }

                                            if (vehiclesByUser.isEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(24.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        "Aucun véhicule trouvé",
                                                        color = textSecondaryColor
                                                    )
                                                }
                                            } else {
                                                Column(
                                                    modifier = Modifier
                                                        .heightIn(max = 300.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    vehiclesByUser.entries.forEachIndexed { userIndex, (userName, userVehicles) ->
                                                        // Section header - Collaborator name
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(
                                                                    if (isDarkMode) Color(0xFF374151).copy(alpha = 0.5f)
                                                                    else Color(0xFFF3F4F6)
                                                                )
                                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(28.dp)
                                                                    .clip(CircleShape)
                                                                    .background(MotiumPrimary.copy(alpha = 0.15f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = userName.firstOrNull()?.uppercase() ?: "?",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = MotiumPrimary
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Text(
                                                                userName,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = textColor
                                                            )
                                                            Spacer(modifier = Modifier.weight(1f))
                                                            Text(
                                                                "${userVehicles.size} véhicule${if (userVehicles.size > 1) "s" else ""}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = textSecondaryColor
                                                            )
                                                        }

                                                        // Vehicles for this user
                                                        userVehicles.forEachIndexed { vehicleIndex, vehicle ->
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable { viewModel.toggleVehicleSelection(vehicle.id) }
                                                                    .padding(start = 32.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Checkbox(
                                                                    checked = vehicle.id in uiState.selectedVehicleIds,
                                                                    onCheckedChange = { viewModel.toggleVehicleSelection(vehicle.id) },
                                                                    colors = CheckboxDefaults.colors(
                                                                        checkedColor = MotiumPrimary,
                                                                        checkmarkColor = Color.White,
                                                                        uncheckedColor = Color.Gray
                                                                    )
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Icon(
                                                                    Icons.Default.DirectionsCar,
                                                                    contentDescription = null,
                                                                    tint = MotiumPrimary.copy(alpha = 0.7f),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Column(modifier = Modifier.weight(1f)) {
                                                                    Text(
                                                                        vehicle.name,
                                                                        style = MaterialTheme.typography.bodyMedium,
                                                                        fontWeight = FontWeight.Medium,
                                                                        color = textColor
                                                                    )
                                                                    Text(
                                                                        "${vehicle.type}${vehicle.power?.let { " â€¢ $it CV" } ?: ""}",
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = textSecondaryColor
                                                                    )
                                                                }
                                                            }
                                                            if (vehicleIndex < userVehicles.size - 1) {
                                                                HorizontalDivider(
                                                                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                                                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
                                                                )
                                                            }
                                                        }

                                                        // Separator between user sections
                                                        if (userIndex < vehiclesByUser.size - 1) {
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                        }
                                                    }
                                                }
                                            }

                                            // Done button
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp)
                                            ) {
                                                Button(
                                                    onClick = { vehiclesDropdownExpanded = false },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(containerColor = MotiumPrimary),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text("Terminé", fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Toggle "Inclure photos" - visible uniquement si includeExpenses = true
                        AnimatedVisibility(
                            visible = uiState.includeExpenses,
                            enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
                            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isDarkMode) Color(0xFF1F2937) else Color(0xFFF3F4F6)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Photo,
                                        contentDescription = null,
                                        tint = MotiumPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            "Inclure les photos",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = textColor
                                        )
                                        Text(
                                            "Ajoute les justificatifs au PDF",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = textSecondaryColor
                                        )
                                    }
                                }
                                Switch(
                                    checked = uiState.includePhotos,
                                    onCheckedChange = { viewModel.setIncludePhotos(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MotiumPrimary,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFD1D5DB)
                                    )
                                )
                            }
                        }
                    }
                }

                // Export buttons section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Options d'export",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = textColor
                        )

                        val canExport = uiState.selectedUserIds.isNotEmpty() && !uiState.isExporting

                        // CSV Export
                        Button(
                            onClick = {
                                viewModel.setExportFormat(ExportFormatOption.CSV)
                                viewModel.exportData()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canExport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MotiumPrimary,
                                disabledContainerColor = MotiumPrimary.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (uiState.isExporting && uiState.exportFormat == ExportFormatOption.CSV) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Exporter en CSV",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        // PDF Export
                        Button(
                            onClick = {
                                viewModel.setExportFormat(ExportFormatOption.PDF)
                                viewModel.exportData()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canExport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB),
                                disabledContainerColor = if (isDarkMode) Color(0xFF374151).copy(alpha = 0.5f) else Color(0xFFE5E7EB).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (uiState.isExporting && uiState.exportFormat == ExportFormatOption.PDF) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = if (isDarkMode) Color.White else Color.Black,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Exporter en PDF",
                                color = if (isDarkMode) Color.White else Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        // Excel Export
                        Button(
                            onClick = {
                                viewModel.setExportFormat(ExportFormatOption.EXCEL)
                                viewModel.exportData()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = canExport,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) Color(0xFF374151) else Color(0xFFE5E7EB),
                                disabledContainerColor = if (isDarkMode) Color(0xFF374151).copy(alpha = 0.5f) else Color(0xFFE5E7EB).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (uiState.isExporting && uiState.exportFormat == ExportFormatOption.EXCEL) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = if (isDarkMode) Color.White else Color.Black,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Exporter en Excel",
                                color = if (isDarkMode) Color.White else Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(120.dp)) // Space for bottom nav
                }
            }
        }
        }

        // Bottom navigation is now handled at app-level in MainActivity
    }
}

@Composable
private fun UserSelectionRow(
    user: LinkedUserDto,
    isSelected: Boolean,
    onToggle: () -> Unit,
    textColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MotiumPrimary,
                checkmarkColor = Color.White,
                uncheckedColor = Color.Gray
            )
        )

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MotiumPrimary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MotiumPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
            Text(
                text = user.userEmail,
                style = MaterialTheme.typography.bodySmall,
                color = textSecondaryColor
            )
        }
    }
}

@Composable
private fun ProCalendarGrid(
    currentMonth: Calendar,
    startDate: Instant,
    endDate: Instant,
    isDarkMode: Boolean,
    textColor: Color,
    onDateSelected: (Int) -> Unit
) {
    val daysInMonth = currentMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfMonth = Calendar.getInstance().apply {
        time = currentMonth.time
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val startDayOfWeek = firstDayOfMonth.get(Calendar.DAY_OF_WEEK) - 1

    val startCal = Calendar.getInstance().apply { timeInMillis = startDate.toEpochMilliseconds() }
    val endCal = Calendar.getInstance().apply { timeInMillis = endDate.toEpochMilliseconds() }

    val selectedStartDay = if (startCal.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH) &&
        startCal.get(Calendar.YEAR) == currentMonth.get(Calendar.YEAR)) {
        startCal.get(Calendar.DAY_OF_MONTH)
    } else -1

    val selectedEndDay = if (endCal.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH) &&
        endCal.get(Calendar.YEAR) == currentMonth.get(Calendar.YEAR)) {
        endCal.get(Calendar.DAY_OF_MONTH)
    } else -1

    Column {
        var dayCounter = 1
        for (week in 0..5) {
            if (dayCounter > daysInMonth) break

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (dayOfWeek in 0..6) {
                    val dayNumber = if (week == 0 && dayOfWeek < startDayOfWeek) {
                        0
                    } else if (dayCounter <= daysInMonth) {
                        dayCounter++
                    } else {
                        0
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayNumber > 0) {
                            val isStartDay = dayNumber == selectedStartDay
                            val isEndDay = dayNumber == selectedEndDay
                            val isInRange = if (selectedStartDay > 0 && selectedEndDay > 0) {
                                dayNumber in selectedStartDay..selectedEndDay
                            } else false

                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isInRange && !isStartDay && !isEndDay) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .background(
                                                if (isDarkMode) MotiumPrimary.copy(alpha = 0.2f)
                                                else MotiumPrimary.copy(alpha = 0.15f)
                                            )
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isStartDay || isEndDay -> MotiumPrimary
                                                else -> Color.Transparent
                                            }
                                        )
                                        .clickable { onDateSelected(dayNumber) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        dayNumber.toString(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isStartDay || isEndDay) Color.White else textColor,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatInstant(instant: Instant): String {
    val date = Date(instant.toEpochMilliseconds())
    return SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(date)
}

enum class ExportFormatOption(val displayName: String) {
    CSV("CSV"),
    PDF("PDF"),
    EXCEL("Excel")
}

/**
 * Pro Quick Export Section with period shortcuts
 * Sets the date range, selects all users and enables all options
 * User then uses existing export buttons at the bottom
 */
@Composable
private fun ProQuickExportSection(
    viewModel: ProExportAdvancedViewModel,
    isExporting: Boolean
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val textColor = if (isDarkMode) TextDark else TextLight

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                tint = MotiumPrimary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "Sélection rapide",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = textColor
            )
        }

        Text(
            "Configure la période, sélectionne tous les collaborateurs et active toutes les options",
            style = MaterialTheme.typography.bodySmall,
            color = if (isDarkMode) TextSecondaryDark else TextSecondaryLight
        )

        // Quick period chips - 2x2 grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProQuickExportChip(
                label = "1 jour",
                onClick = {
                    viewModel.applyQuickPeriod(ProExportAdvancedViewModel.QuickExportPeriod.TODAY)
                },
                modifier = Modifier.weight(1f)
            )
            ProQuickExportChip(
                label = "1 semaine",
                onClick = {
                    viewModel.applyQuickPeriod(ProExportAdvancedViewModel.QuickExportPeriod.THIS_WEEK)
                },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProQuickExportChip(
                label = "1 mois",
                onClick = {
                    viewModel.applyQuickPeriod(ProExportAdvancedViewModel.QuickExportPeriod.THIS_MONTH)
                },
                modifier = Modifier.weight(1f)
            )
            ProQuickExportChip(
                label = "1 an",
                onClick = {
                    viewModel.applyQuickPeriod(ProExportAdvancedViewModel.QuickExportPeriod.THIS_YEAR)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProQuickExportChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isDarkMode) Color(0xFF1E3A5F) else MotiumPrimary.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            label,
            color = if (isDarkMode) Color.White else MotiumPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}


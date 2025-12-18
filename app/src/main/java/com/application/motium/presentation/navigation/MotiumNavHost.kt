package com.application.motium.presentation.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.auth.ForgotPasswordScreen
import com.application.motium.presentation.auth.LoginScreen
import com.application.motium.presentation.auth.PhoneVerificationScreen
import com.application.motium.presentation.auth.RegisterScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.application.motium.presentation.individual.home.NewHomeScreen
import com.application.motium.presentation.individual.calendar.CalendarScreen
import com.application.motium.presentation.individual.vehicles.VehiclesScreen
import com.application.motium.presentation.individual.export.ExportScreen
import com.application.motium.presentation.individual.settings.SettingsScreen
import com.application.motium.presentation.individual.tripdetails.TripDetailsScreen
import com.application.motium.presentation.individual.addtrip.AddTripScreen
import com.application.motium.presentation.individual.edittrip.EditTripScreen
import com.application.motium.presentation.individual.expense.AddExpenseScreen
import com.application.motium.presentation.individual.expense.ExpenseDetailsScreen
import com.application.motium.presentation.debug.LogViewerScreen
import com.application.motium.presentation.splash.SplashScreen
import com.application.motium.presentation.subscription.TrialExpiredScreen
import androidx.compose.ui.platform.LocalContext
import com.application.motium.data.TripRepository
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.MotiumApplication
import com.application.motium.utils.DeepLinkHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MotiumNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    authViewModel: AuthViewModel
) {
    val authState by authViewModel.authState.collectAsState()

    // Navigation automatique basée sur l'état d'authentification
    LaunchedEffect(authState.isLoading, authState.isAuthenticated, authState.user?.role, authState.user?.subscription?.type) {
        MotiumApplication.logger.d("🧭 Navigation LaunchedEffect triggered", "Navigation")
        MotiumApplication.logger.d("   - isLoading: ${authState.isLoading}", "Navigation")
        MotiumApplication.logger.d("   - isAuthenticated: ${authState.isAuthenticated}", "Navigation")
        MotiumApplication.logger.d("   - user: ${authState.user?.email}", "Navigation")
        MotiumApplication.logger.d("   - role: ${authState.user?.role}", "Navigation")
        MotiumApplication.logger.d("   - subscription: ${authState.user?.subscription?.type}", "Navigation")

        if (!authState.isLoading) {
            // Chargement terminé, naviguer vers la destination appropriée
            if (authState.isAuthenticated) {
                // Check if trial has expired - block access
                val subscriptionType = authState.user?.subscription?.type
                val hasValidAccess = authState.user?.subscription?.hasValidAccess() == true

                if (subscriptionType == SubscriptionType.EXPIRED || !hasValidAccess) {
                    MotiumApplication.logger.i("🚫 Trial expired - navigating to trial_expired", "Navigation")
                    navController.navigate("trial_expired") {
                        popUpTo("splash") { inclusive = true }
                        popUpTo("login") { inclusive = true }
                        popUpTo("register") { inclusive = true }
                        popUpTo("home") { inclusive = true }
                        popUpTo("enterprise_home") { inclusive = true }
                        launchSingleTop = true
                    }
                    return@LaunchedEffect
                }

                // Check if there's a pending deep link to handle (company link invitation)
                if (DeepLinkHandler.hasPendingLink()) {
                    val isEnterprise = authState.user?.role?.name == "ENTERPRISE"
                    val settingsRoute = if (isEnterprise) "pro_settings" else "settings"

                    MotiumApplication.logger.i("🔗 Pending deep link detected - navigating to $settingsRoute", "Navigation")

                    navController.navigate(settingsRoute) {
                        popUpTo("splash") { inclusive = true }
                        popUpTo("login") { inclusive = true }
                        popUpTo("register") { inclusive = true }
                        launchSingleTop = true
                    }
                } else {
                    // Déterminer si l'utilisateur est une entreprise en utilisant le rôle
                    val isEnterprise = authState.user?.role?.name == "ENTERPRISE"
                    val homeRoute = if (isEnterprise) "enterprise_home" else "home"

                    MotiumApplication.logger.i("🧭 Navigating to: $homeRoute (isEnterprise: $isEnterprise)", "Navigation")

                    // Si l'utilisateur est connecté, aller à l'accueil approprié
                    navController.navigate(homeRoute) {
                        popUpTo("splash") { inclusive = true }
                        popUpTo("login") { inclusive = true }
                        popUpTo("register") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            } else {
                MotiumApplication.logger.i("🧭 Navigating to: login (not authenticated)", "Navigation")
                // Si l'utilisateur n'est pas connecté, aller à la connexion
                navController.navigate("login") {
                    popUpTo("splash") { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "splash",
        modifier = modifier,
        // Disable crossfade animations to prevent OpenGL context conflicts with maps
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        // Splash/Loading screen
        composable("splash") {
            SplashScreen()
        }
        // Authentication screens
        composable("login") {
            LoginScreen(
                onNavigateToRegister = { navController.navigate("register") },
                onNavigateToHome = {
                    // Ne pas naviguer manuellement, laisser le LaunchedEffect gérer la redirection
                    // selon le rôle de l'utilisateur (INDIVIDUAL -> home, ENTERPRISE -> enterprise_home)
                },
                onForgotPassword = { navController.navigate("forgot_password") },
                viewModel = authViewModel
            )
        }

        composable("forgot_password") {
            ForgotPasswordScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = authViewModel
            )
        }

        // Trial expired screen - blocks access until subscription
        composable("trial_expired") {
            val context = LocalContext.current
            val subscriptionManager = SubscriptionManager.getInstance(context)

            TrialExpiredScreen(
                subscriptionManager = subscriptionManager,
                onSubscribe = { subscriptionType ->
                    // Handle subscription flow
                    // TODO: Implement actual payment flow
                    MotiumApplication.logger.i("Subscription requested: $subscriptionType", "Navigation")
                },
                onLogout = {
                    authViewModel.signOut()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("register") {
            RegisterScreen(
                onNavigateToLogin = { navController.navigate("login") },
                onNavigateToPhoneVerification = { email, password, name, isProfessional, organizationName ->
                    // Encode parameters for safe navigation
                    val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8.toString())
                    val encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8.toString())
                    val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
                    val encodedOrg = URLEncoder.encode(organizationName, StandardCharsets.UTF_8.toString())
                    navController.navigate("phone_verification/$encodedEmail/$encodedPassword/$encodedName/$isProfessional/$encodedOrg")
                },
                viewModel = authViewModel
            )
        }

        // Phone verification during registration
        composable(
            route = "phone_verification/{email}/{password}/{name}/{isProfessional}/{organizationName}",
            arguments = listOf(
                navArgument("email") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("isProfessional") { type = NavType.BoolType },
                navArgument("organizationName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val email = URLDecoder.decode(
                backStackEntry.arguments?.getString("email") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val password = URLDecoder.decode(
                backStackEntry.arguments?.getString("password") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("name") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val isProfessional = backStackEntry.arguments?.getBoolean("isProfessional") ?: false
            val organizationName = URLDecoder.decode(
                backStackEntry.arguments?.getString("organizationName") ?: "",
                StandardCharsets.UTF_8.toString()
            )

            PhoneVerificationScreen(
                onNavigateBack = { navController.popBackStack() },
                onVerificationComplete = { verifiedPhone ->
                    // Complete registration with verified phone
                    authViewModel.signUpWithVerification(
                        email = email,
                        password = password,
                        name = name,
                        isProfessional = isProfessional,
                        organizationName = organizationName,
                        verifiedPhone = verifiedPhone
                    )
                    // Navigation will be handled by authState LaunchedEffect
                }
            )
        }
        composable("home") {
            NewHomeScreen(
                onNavigateToCalendar = { navController.navigate("calendar") },
                onNavigateToCalendarPlanning = { navController.navigate("calendar_planning") },
                onNavigateToVehicles = { navController.navigate("vehicles") },
                onNavigateToExport = { navController.navigate("export") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("trip_details/$tripId")
                },
                onNavigateToAddTrip = { navController.navigate("add_trip") },
                onNavigateToAddExpense = { date ->
                    navController.navigate("add_expense/$date")
                },
                onNavigateToExpenseDetails = { date ->
                    navController.navigate("expense_details/$date")
                },
                authViewModel = authViewModel
            )
        }

        composable("add_trip") {
            val context = LocalContext.current
            val tripRepository = TripRepository.getInstance(context)

            AddTripScreen(
                onNavigateBack = { navController.popBackStack() },
                onTripSaved = { trip ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Save trip (this also syncs to Supabase)
                            MotiumApplication.logger.i("💾 Saving trip: ${trip.id}, distance=${trip.totalDistance}m, vehicle=${trip.vehicleId}, type=${trip.tripType}", "MotiumNavHost")
                            tripRepository.saveTrip(trip)
                            MotiumApplication.logger.i("✅ Trip saved: ${trip.id}", "MotiumNavHost")

                            // Navigate back on main thread
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                navController.popBackStack()
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("❌ Failed to save trip: ${e.message}", "MotiumNavHost", e)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Failed to save trip: ${e.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )
        }

        composable(
            route = "trip_details/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            TripDetailsScreen(
                tripId = tripId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { tripId ->
                    navController.navigate("edit_trip/$tripId")
                },
                authViewModel = authViewModel
            )
        }

        composable(
            route = "edit_trip/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            EditTripScreen(
                tripId = tripId,
                onNavigateBack = { navController.popBackStack() },
                onTripUpdated = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "add_expense/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            AddExpenseScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() },
                onExpenseSaved = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "edit_expense/{expenseId}",
            arguments = listOf(navArgument("expenseId") { type = NavType.StringType })
        ) { backStackEntry ->
            val expenseId = backStackEntry.arguments?.getString("expenseId") ?: ""
            com.application.motium.presentation.individual.expense.EditExpenseScreen(
                expenseId = expenseId,
                onNavigateBack = { navController.popBackStack() },
                onExpenseSaved = {
                    navController.popBackStack()
                },
                onExpenseDeleted = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "expense_details/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            ExpenseDetailsScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEditExpense = { expenseId ->
                    navController.navigate("edit_expense/$expenseId")
                }
            )
        }

        composable("calendar") {
            CalendarScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("home") },
                onNavigateToVehicles = { navController.navigate("vehicles") },
                onNavigateToExport = { navController.navigate("export") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("trip_details/$tripId")
                },
                onNavigateToAddExpense = { date ->
                    navController.navigate("add_expense/$date")
                },
                onNavigateToExpenseDetails = { date ->
                    navController.navigate("expense_details/$date")
                },
                authViewModel = authViewModel
            )
        }

        // Route pour ouvrir directement l'onglet Planning du calendrier
        composable("calendar_planning") {
            CalendarScreen(
                initialTab = 1, // Planning tab
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("home") },
                onNavigateToVehicles = { navController.navigate("vehicles") },
                onNavigateToExport = { navController.navigate("export") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("trip_details/$tripId")
                },
                onNavigateToAddExpense = { date ->
                    navController.navigate("add_expense/$date")
                },
                onNavigateToExpenseDetails = { date ->
                    navController.navigate("expense_details/$date")
                },
                authViewModel = authViewModel
            )
        }

        composable("vehicles") {
            VehiclesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("home") },
                onNavigateToCalendar = { navController.navigate("calendar") },
                onNavigateToExport = { navController.navigate("export") },
                onNavigateToSettings = { navController.navigate("settings") },
                authViewModel = authViewModel
            )
        }

        composable("export") {
            ExportScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("home") },
                onNavigateToCalendar = { navController.navigate("calendar") },
                onNavigateToVehicles = { navController.navigate("vehicles") },
                onNavigateToSettings = { navController.navigate("settings") },
                authViewModel = authViewModel
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("home") },
                onNavigateToCalendar = { navController.navigate("calendar") },
                onNavigateToVehicles = { navController.navigate("vehicles") },
                onNavigateToExport = { navController.navigate("export") },
                onNavigateToLogViewer = { navController.navigate("log_viewer") },
                onNavigateToLogin = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                authViewModel = authViewModel
            )
        }

        composable("log_viewer") {
            LogViewerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============================================
        // PRO INTERFACE SCREENS
        // ============================================

        // Pro Home - Uses Individual NewHomeScreen with Pro navigation targets
        // Pro users can access Pro-specific features via the expandable + menu
        composable("enterprise_home") {
            NewHomeScreen(
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToCalendarPlanning = { navController.navigate("pro_calendar") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("pro_trip_details/$tripId")
                },
                onNavigateToAddTrip = { navController.navigate("pro_add_trip") },
                onNavigateToAddExpense = { date ->
                    navController.navigate("pro_add_expense/$date")
                },
                onNavigateToExpenseDetails = { date ->
                    navController.navigate("pro_expense_details/$date")
                },
                authViewModel = authViewModel,
                // Pro-specific parameters
                isPro = true,
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") }
            )
        }

        // Pro Calendar - Reuses Individual CalendarScreen
        composable("pro_calendar") {
            CalendarScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("pro_trip_details/$tripId")
                },
                onNavigateToAddExpense = { date ->
                    navController.navigate("pro_add_expense/$date")
                },
                onNavigateToExpenseDetails = { date ->
                    navController.navigate("pro_expense_details/$date")
                },
                authViewModel = authViewModel,
                // Pro-specific parameters
                isPro = true,
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") }
            )
        }

        // Pro Vehicles - Reuses Individual VehiclesScreen
        composable("pro_vehicles") {
            VehiclesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                authViewModel = authViewModel,
                // Pro-specific parameters
                isPro = true,
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") }
            )
        }

        // Pro Export (Standard) - Reuses Individual ExportScreen
        composable("pro_export") {
            ExportScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                authViewModel = authViewModel,
                // Pro-specific parameters
                isPro = true,
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") }
            )
        }

        // Pro Settings - Reuses Individual SettingsScreen
        composable("pro_settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToLogViewer = { navController.navigate("log_viewer") },
                onNavigateToLogin = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                authViewModel = authViewModel,
                // Pro-specific parameters
                isPro = true,
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") }
            )
        }

        // Pro Trip operations - Reuse Individual screens
        composable("pro_add_trip") {
            val context = LocalContext.current
            val tripRepository = TripRepository.getInstance(context)

            AddTripScreen(
                onNavigateBack = { navController.popBackStack() },
                onTripSaved = { trip ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            MotiumApplication.logger.i("💾 Saving pro trip: ${trip.id}", "MotiumNavHost")
                            tripRepository.saveTrip(trip)
                            MotiumApplication.logger.i("✅ Pro trip saved: ${trip.id}", "MotiumNavHost")

                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                navController.popBackStack()
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("❌ Failed to save pro trip: ${e.message}", "MotiumNavHost", e)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Failed to save trip: ${e.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )
        }

        // Pro trip details with optional linkedUserId for viewing linked users' trips
        composable(
            route = "pro_trip_details/{tripId}/{linkedUserId}",
            arguments = listOf(
                navArgument("tripId") { type = NavType.StringType },
                navArgument("linkedUserId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            val linkedUserId = backStackEntry.arguments?.getString("linkedUserId")
            TripDetailsScreen(
                tripId = tripId,
                linkedUserId = linkedUserId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { tripId ->
                    navController.navigate("pro_edit_trip/$tripId")
                },
                authViewModel = authViewModel
            )
        }

        // Pro trip details without linkedUserId (for owner's own trips)
        composable(
            route = "pro_trip_details/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            TripDetailsScreen(
                tripId = tripId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { tripId ->
                    navController.navigate("pro_edit_trip/$tripId")
                },
                authViewModel = authViewModel
            )
        }

        composable(
            route = "pro_edit_trip/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            EditTripScreen(
                tripId = tripId,
                onNavigateBack = { navController.popBackStack() },
                onTripUpdated = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "pro_add_expense/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            AddExpenseScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() },
                onExpenseSaved = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "pro_expense_details/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            ExpenseDetailsScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEditExpense = { expenseId ->
                    navController.navigate("edit_expense/$expenseId")
                }
            )
        }

        // ============================================
        // PRO-SPECIFIC SCREENS (from expandable menu)
        // ============================================

        // Linked Accounts Management
        composable("pro_linked_accounts") {
            com.application.motium.presentation.pro.accounts.LinkedAccountsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") },
                onNavigateToAccountDetails = { accountId ->
                    navController.navigate("pro_account_details/$accountId")
                },
                onNavigateToInvitePerson = { navController.navigate("pro_invite_person") },
                authViewModel = authViewModel
            )
        }

        // Invite Person Screen
        composable("pro_invite_person") {
            com.application.motium.presentation.pro.accounts.InvitePersonScreen(
                onNavigateBack = { navController.popBackStack() },
                onInviteSuccess = { navController.popBackStack() }
            )
        }

        composable(
            route = "pro_account_details/{accountId}",
            arguments = listOf(navArgument("accountId") { type = NavType.StringType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            com.application.motium.presentation.pro.accounts.AccountDetailsScreen(
                accountId = accountId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToUserTrips = { userId ->
                    navController.navigate("pro_user_trips/$userId")
                },
                authViewModel = authViewModel
            )
        }

        // Linked User Trips Screen - View trips for a specific linked user
        composable(
            route = "pro_user_trips/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val linkedUserId = backStackEntry.arguments?.getString("userId") ?: ""
            com.application.motium.presentation.pro.accounts.LinkedUserTripsScreen(
                userId = linkedUserId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTripDetails = { tripId ->
                    // Pass the linked user's ID to allow viewing their trips
                    navController.navigate("pro_trip_details/$tripId/$linkedUserId")
                }
            )
        }

        // Licenses Management
        composable("pro_licenses") {
            com.application.motium.presentation.pro.licenses.LicensesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") },
                authViewModel = authViewModel
            )
        }

        // Pro Export Advanced (Multi-account export)
        composable("pro_export_advanced") {
            com.application.motium.presentation.pro.export.ProExportAdvancedScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("pro_calendar") },
                onNavigateToVehicles = { navController.navigate("pro_vehicles") },
                onNavigateToExport = { navController.navigate("pro_export") },
                onNavigateToSettings = { navController.navigate("pro_settings") },
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                authViewModel = authViewModel
            )
        }
    }
}
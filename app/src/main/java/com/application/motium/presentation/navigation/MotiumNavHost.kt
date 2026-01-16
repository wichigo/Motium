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
import com.application.motium.presentation.auth.AcceptInvitationScreen
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.auth.ForgotPasswordScreen
import com.application.motium.presentation.auth.LoginScreen
import com.application.motium.presentation.auth.RegisterScreen
import com.application.motium.presentation.auth.ResetPasswordScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.application.motium.presentation.individual.home.NewHomeScreen
import com.application.motium.presentation.individual.calendar.CalendarScreen
import com.application.motium.presentation.individual.vehicles.VehiclesScreen
import com.application.motium.presentation.individual.export.ExportScreen
import com.application.motium.presentation.individual.settings.SettingsScreen
import com.application.motium.presentation.navigation.ConsentManagementRoute
import com.application.motium.presentation.individual.tripdetails.TripDetailsScreen
import com.application.motium.presentation.individual.addtrip.AddTripScreen
import com.application.motium.presentation.individual.edittrip.EditTripScreen
import com.application.motium.presentation.individual.expense.AddExpenseScreen
import com.application.motium.presentation.individual.expense.ExpenseDetailsScreen
import com.application.motium.presentation.debug.LogViewerScreen
import com.application.motium.presentation.splash.SplashScreen
import com.application.motium.presentation.subscription.TrialExpiredScreen
import com.application.motium.presentation.subscription.SubscriptionExpiredScreen
import com.application.motium.presentation.subscription.ProTrialExpiredScreen
import com.application.motium.presentation.individual.upgrade.UpgradeScreen
import androidx.compose.ui.platform.LocalContext
import com.application.motium.data.TripRepository
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.subscription.SubscriptionManager
import com.application.motium.domain.model.SubscriptionType
import com.application.motium.domain.model.UserRole
import com.application.motium.MotiumApplication
import com.application.motium.presentation.auth.ProLicenseState
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
    val proLicenseState by authViewModel.proLicenseState.collectAsState()

    // Navigation automatique basée sur l'état d'authentification
    LaunchedEffect(authState.isLoading, authState.isAuthenticated, authState.user?.role, authState.user?.subscription?.type, authState.initialSyncDone) {
        MotiumApplication.logger.i("🧭 Navigation LaunchedEffect triggered", "Navigation")
        MotiumApplication.logger.i("   - isLoading: ${authState.isLoading}", "Navigation")
        MotiumApplication.logger.i("   - isAuthenticated: ${authState.isAuthenticated}", "Navigation")
        MotiumApplication.logger.i("   - user: ${authState.user?.email}", "Navigation")
        MotiumApplication.logger.i("   - role: ${authState.user?.role}", "Navigation")
        MotiumApplication.logger.i("   - subscription: ${authState.user?.subscription?.type}", "Navigation")
        MotiumApplication.logger.i("   - initialSyncDone: ${authState.initialSyncDone}", "Navigation")

        if (!authState.isLoading) {
            // Chargement terminé, naviguer vers la destination appropriée
            if (authState.isAuthenticated && authState.user != null) {
                val subscriptionType = authState.user?.subscription?.type
                val hasValidAccess = authState.user?.subscription?.hasValidAccess() == true
                val isProUser = authState.user?.role == UserRole.ENTERPRISE
                val userId = authState.user?.id

                // PRO USERS: Trigger license check in ViewModel (non-blocking)
                // Navigation will be handled by the separate proLicenseState LaunchedEffect
                MotiumApplication.logger.i("🔍 Checking if Pro user: isProUser=$isProUser, userId=$userId", "Navigation")
                if (isProUser && userId != null) {
                    MotiumApplication.logger.i("✅ Pro user detected - triggering license check", "Navigation")
                    authViewModel.checkProLicense(userId)
                    return@LaunchedEffect  // Wait for proLicenseState to update
                }
                // INDIVIDUAL USERS: Check subscription/trial expiration
                // IMPORTANT: Attendre initialSyncDone pour éviter de naviguer vers expired screen
                // avec des données Room obsolètes (ex: user a payé mais Room pas encore mis à jour)
                else if (subscriptionType == SubscriptionType.EXPIRED || !hasValidAccess) {
                    if (!authState.initialSyncDone) {
                        MotiumApplication.logger.d("⏳ Waiting for initial sync before expired decision...", "Navigation")
                        return@LaunchedEffect  // Attendre que la sync soit terminée
                    }

                    // Determine if this is a subscription expiration (had paid) or trial expiration
                    val hadPaidSubscription = authState.user?.subscription?.stripeCustomerId != null ||
                        authState.user?.subscription?.stripeSubscriptionId != null ||
                        subscriptionType == SubscriptionType.PREMIUM

                    val targetRoute = if (hadPaidSubscription) "subscription_expired" else "trial_expired"
                    MotiumApplication.logger.i("🚫 Access expired (sync done) - navigating to $targetRoute", "Navigation")

                    navController.navigate(targetRoute) {
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
                    // Individual user - navigate to home
                    MotiumApplication.logger.i("🧭 Navigating to: home (individual user)", "Navigation")
                    navController.navigate("home") {
                        popUpTo("splash") { inclusive = true }
                        popUpTo("login") { inclusive = true }
                        popUpTo("register") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            } else {
                // Reset pro license state on logout
                authViewModel.resetProLicenseState()
                authViewModel.resetLoginState()

                // Check if there's a pending password reset deep link
                if (DeepLinkHandler.hasPendingReset()) {
                    val resetToken = DeepLinkHandler.consumePendingResetToken()
                    if (resetToken != null) {
                        MotiumApplication.logger.i("🔗 Pending password reset detected - navigating to reset_password", "Navigation")
                        val encodedToken = URLEncoder.encode(resetToken, StandardCharsets.UTF_8.toString())
                        navController.navigate("reset_password/$encodedToken") {
                            popUpTo("splash") { inclusive = true }
                            launchSingleTop = true
                        }
                        return@LaunchedEffect
                    }
                }

                // Check if there's a pending company invitation deep link (user not logged in)
                if (DeepLinkHandler.hasPendingLink()) {
                    val invitationToken = DeepLinkHandler.pendingLinkToken
                    if (invitationToken != null) {
                        MotiumApplication.logger.i("🔗 Pending invitation detected (not logged in) - navigating to accept_invitation", "Navigation")
                        val encodedToken = URLEncoder.encode(invitationToken, StandardCharsets.UTF_8.toString())
                        navController.navigate("accept_invitation/$encodedToken") {
                            popUpTo("splash") { inclusive = true }
                            launchSingleTop = true
                        }
                        return@LaunchedEffect
                    }
                }

                // Prevent redundant navigation if already on login screen
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute != "login" && currentRoute?.startsWith("accept_invitation") != true) {
                    MotiumApplication.logger.i("🧭 Navigating to: login (not authenticated)", "Navigation")
                    // Si l'utilisateur n'est pas connecté, aller à la connexion
                    navController.navigate("login") {
                        popUpTo("splash") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    // Separate LaunchedEffect for Pro user license state navigation
    // This ensures network calls in ViewModel survive recomposition
    LaunchedEffect(proLicenseState) {
        MotiumApplication.logger.i("🔐 ProLicenseState changed: $proLicenseState", "Navigation")
        when (proLicenseState) {
            is ProLicenseState.Licensed -> {
                // Check for pending deep link first
                if (DeepLinkHandler.hasPendingLink()) {
                    MotiumApplication.logger.i("🔗 Pro user with pending deep link - navigating to pro_settings", "Navigation")
                    navController.navigate("pro_settings") {
                        popUpTo("splash") { inclusive = true }
                        popUpTo("login") { inclusive = true }
                        popUpTo("register") { inclusive = true }
                        launchSingleTop = true
                    }
                } else {
                    MotiumApplication.logger.i("🧭 Pro user licensed - navigating to enterprise_home", "Navigation")
                    navController.navigate("enterprise_home") {
                        popUpTo("splash") { inclusive = true }
                        popUpTo("login") { inclusive = true }
                        popUpTo("register") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            is ProLicenseState.NotLicensed, is ProLicenseState.NoProAccount -> {
                MotiumApplication.logger.i("🚫 Pro user not licensed - navigating to pro_trial_expired", "Navigation")
                navController.navigate("pro_trial_expired") {
                    popUpTo(0) { inclusive = true }  // Clear entire back stack safely
                    launchSingleTop = true
                }
            }
            is ProLicenseState.Error -> {
                // On error (network issue), allow access in offline mode (fail-open)
                // This prevents getting stuck on the splash screen when offline with no cache
                val errorState = proLicenseState as ProLicenseState.Error
                MotiumApplication.logger.w(
                    "License check error - defaulting to enterprise_home (offline mode): ${errorState.message}",
                    "Navigation"
                )
                navController.navigate("enterprise_home") {
                    popUpTo("splash") { inclusive = true }
                    popUpTo("login") { inclusive = true }
                    popUpTo("register") { inclusive = true }
                    launchSingleTop = true
                }
            }
            ProLicenseState.Idle, ProLicenseState.Loading -> {
                // Do nothing - still loading or not yet triggered
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

        // Password reset screen (from deep link)
        composable(
            route = "reset_password/{token}",
            arguments = listOf(navArgument("token") { type = NavType.StringType })
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token") ?: ""
            val decodedToken = URLDecoder.decode(token, StandardCharsets.UTF_8.toString())
            ResetPasswordScreen(
                token = decodedToken,
                onNavigateToLogin = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateBack = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Accept invitation screen (from deep link, user not logged in)
        composable(
            route = "accept_invitation/{token}",
            arguments = listOf(navArgument("token") { type = NavType.StringType })
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token") ?: ""
            val decodedToken = URLDecoder.decode(token, StandardCharsets.UTF_8.toString())
            AcceptInvitationScreen(
                invitationToken = decodedToken,
                invitationEmail = null, // Will be fetched from the invitation
                companyName = null,     // Will be fetched from the invitation
                onGoogleSignIn = {
                    // Store token for after Google sign-in
                    // pendingLinkToken is already set, navigate to login with Google hint
                    navController.navigate("login") {
                        popUpTo("accept_invitation/$token") { inclusive = true }
                    }
                },
                onExistingAccount = {
                    // Navigate to login screen
                    navController.navigate("login") {
                        popUpTo("accept_invitation/$token") { inclusive = true }
                    }
                },
                onAccountCreated = { userId ->
                    // Account created, consume the token and sign in
                    DeepLinkHandler.consumePendingToken()
                    MotiumApplication.logger.i("Account created via invitation, user: $userId", "Navigation")
                    // The authViewModel will pick up the new session
                    // Navigate to login to complete authentication
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onCancel = {
                    DeepLinkHandler.consumePendingToken()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Trial expired screen - blocks access until subscription
        // Payment is handled directly in this screen
        composable("trial_expired") {
            val context = LocalContext.current
            val subscriptionManager = SubscriptionManager.getInstance(context)

            TrialExpiredScreen(
                subscriptionManager = subscriptionManager,
                authViewModel = authViewModel,
                onSubscribe = { subscriptionType ->
                    // After successful payment, navigate to home
                    MotiumApplication.logger.i("✅ Subscription activated: $subscriptionType, navigating to home", "Navigation")
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onLogout = {
                    authViewModel.signOut()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Subscription expired screen - blocks access after subscription cancellation/expiration
        // User must resubscribe to continue using the app
        composable("subscription_expired") {
            val context = LocalContext.current
            val subscriptionManager = SubscriptionManager.getInstance(context)

            SubscriptionExpiredScreen(
                subscriptionManager = subscriptionManager,
                authViewModel = authViewModel,
                onSubscribe = { subscriptionType ->
                    // After successful payment, navigate to home
                    MotiumApplication.logger.i("✅ Subscription renewed: $subscriptionType, navigating to home", "Navigation")
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onLogout = {
                    authViewModel.signOut()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Pro trial expired screen - blocks Pro users until license assigned
        // User must assign themselves a license (or purchase one first)
        composable("pro_trial_expired") {
            val context = LocalContext.current
            val subscriptionManager = SubscriptionManager.getInstance(context)

            ProTrialExpiredScreen(
                subscriptionManager = subscriptionManager,
                authViewModel = authViewModel,
                onLicenseActivated = {
                    // After successful license assignment, navigate to enterprise home
                    MotiumApplication.logger.i("✅ Pro license activated, navigating to enterprise_home", "Navigation")
                    navController.navigate("enterprise_home") {
                        popUpTo(0) { inclusive = true }
                    }
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
                viewModel = authViewModel
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
                onNavigateToUpgrade = { navController.navigate("upgrade") },
                authViewModel = authViewModel,
                onNavigateToConsents = { navController.navigate("settings/consents") }
            )
        }

        // GDPR Consent Management
        composable("settings/consents") {
            ConsentManagementRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("log_viewer") {
            LogViewerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Upgrade screen for individual users to subscribe to Premium
        composable("upgrade") {
            UpgradeScreen(
                onNavigateBack = { navController.popBackStack() },
                onUpgradeSuccess = {
                    // After successful upgrade, navigate back to home
                    // and let LaunchedEffect refresh the auth state
                    navController.navigate("home") {
                        popUpTo("upgrade") { inclusive = true }
                    }
                }
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
                onNavigateToUpgrade = { /* Pro users use licenses, not individual upgrade */ },
                authViewModel = authViewModel,
                // Pro-specific parameters
                isPro = true,
                onNavigateToLinkedAccounts = { navController.navigate("pro_linked_accounts") },
                onNavigateToLicenses = { navController.navigate("pro_licenses") },
                onNavigateToExportAdvanced = { navController.navigate("pro_export_advanced") },
                onNavigateToConsents = { navController.navigate("pro_settings/consents") }
            )
        }

        // GDPR Consent Management for Pro users
        composable("pro_settings/consents") {
            ConsentManagementRoute(
                onNavigateBack = { navController.popBackStack() }
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
                onNavigateToUserVehicles = { userId ->
                    navController.navigate("pro_user_vehicles/$userId")
                },
                authViewModel = authViewModel
            )
        }

        // Linked User Vehicles Screen - View vehicles for a specific linked user
        composable(
            route = "pro_user_vehicles/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            com.application.motium.presentation.pro.accounts.LinkedAccountVehiclesScreen(
                accountId = userId,
                onNavigateBack = { navController.popBackStack() }
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
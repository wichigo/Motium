package com.application.motium.presentation.navigation

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
import com.application.motium.presentation.auth.LoginScreen
import com.application.motium.presentation.auth.RegisterScreen
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
import com.application.motium.presentation.enterprise.home.EnterpriseHomeScreen
import com.application.motium.presentation.enterprise.home.EnterpriseNewHomeScreen
import com.application.motium.presentation.enterprise.calendar.EnterpriseCalendarScreen
import com.application.motium.presentation.enterprise.vehicles.EnterpriseVehiclesScreen
import com.application.motium.presentation.enterprise.export.EnterpriseExportScreen
import com.application.motium.presentation.enterprise.settings.EnterpriseSettingsScreen
import com.application.motium.presentation.enterprise.addtrip.EnterpriseAddTripScreen
import com.application.motium.presentation.enterprise.edittrip.EnterpriseEditTripScreen
import com.application.motium.presentation.enterprise.tripdetails.EnterpriseTripDetailsScreen
import com.application.motium.presentation.enterprise.expense.EnterpriseAddExpenseScreen
import com.application.motium.presentation.enterprise.expense.EnterpriseExpenseDetailsScreen
import com.application.motium.presentation.enterprise.employees.EmployeesManagementScreen
import com.application.motium.presentation.enterprise.employees.EmployeeDetailsScreen
import com.application.motium.presentation.enterprise.schedule.EmployeeScheduleScreen
import com.application.motium.presentation.enterprise.export.EmployeeExportScreen
import com.application.motium.presentation.enterprise.facturation.EmployeeFacturationScreen
import androidx.compose.ui.platform.LocalContext
import com.application.motium.data.TripRepository
import com.application.motium.data.ExpenseRepository
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
    LaunchedEffect(authState.isLoading, authState.isAuthenticated, authState.user?.role) {
        MotiumApplication.logger.d("🧭 Navigation LaunchedEffect triggered", "Navigation")
        MotiumApplication.logger.d("   - isLoading: ${authState.isLoading}", "Navigation")
        MotiumApplication.logger.d("   - isAuthenticated: ${authState.isAuthenticated}", "Navigation")
        MotiumApplication.logger.d("   - user: ${authState.user?.email}", "Navigation")
        MotiumApplication.logger.d("   - role: ${authState.user?.role}", "Navigation")

        if (!authState.isLoading) {
            // Chargement terminé, naviguer vers la destination appropriée
            if (authState.isAuthenticated) {
                // Check if there's a pending deep link to handle (company link invitation)
                if (DeepLinkHandler.hasPendingLink()) {
                    val isEnterprise = authState.user?.role?.name == "ENTERPRISE"
                    val settingsRoute = if (isEnterprise) "enterprise_settings" else "settings"

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
        modifier = modifier
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
                onForgotPassword = { /* TODO: Implement forgot password */ },
                viewModel = authViewModel
            )
        }

        composable("register") {
            RegisterScreen(
                onNavigateToLogin = { navController.navigate("login") },
                onNavigateToHome = {
                    // Ne pas naviguer manuellement, laisser le LaunchedEffect gérer la redirection
                    // selon le rôle de l'utilisateur (INDIVIDUAL -> home, ENTERPRISE -> enterprise_home)
                },
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
            route = "expense_details/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            ExpenseDetailsScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() }
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

        // Enterprise/Professional interface screens
        composable("enterprise_home") {
            EnterpriseNewHomeScreen(
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("enterprise_trip_details/$tripId")
                },
                onNavigateToAddTrip = { navController.navigate("enterprise_add_trip") },
                onNavigateToAddExpense = { date ->
                    navController.navigate("enterprise_add_expense/$date")
                },
                onNavigateToExpenseDetails = { date ->
                    navController.navigate("enterprise_expense_details/$date")
                },
                authViewModel = authViewModel
            )
        }

        composable("employees_management") {
            EmployeesManagementScreen(
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                onNavigateToSchedule = { navController.navigate("employee_schedule") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToEmployeeExport = { navController.navigate("employee_export") },
                onNavigateToFacturation = { navController.navigate("employee_facturation") },
                onNavigateToEmployeeDetails = { employeeId ->
                    navController.navigate("employee_details/$employeeId")
                },
                authViewModel = authViewModel
            )
        }

        composable(
            route = "employee_details/{employeeId}",
            arguments = listOf(navArgument("employeeId") { type = NavType.StringType })
        ) { backStackEntry ->
            val employeeId = backStackEntry.arguments?.getString("employeeId") ?: ""
            EmployeeDetailsScreen(
                employeeId = employeeId,
                employeeName = "Employee Details",
                onNavigateBack = { navController.popBackStack() },
                authViewModel = authViewModel
            )
        }

        composable("employee_schedule") {
            EmployeeScheduleScreen(
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                onNavigateToEmployees = { navController.navigate("employees_management") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToEmployeeExport = { navController.navigate("employee_export") },
                onNavigateToFacturation = { navController.navigate("employee_facturation") },
                authViewModel = authViewModel
            )
        }

        composable("employee_export") {
            EmployeeExportScreen(
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                onNavigateToEmployees = { navController.navigate("employees_management") },
                onNavigateToSchedule = { navController.navigate("employee_schedule") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToFacturation = { navController.navigate("employee_facturation") },
                authViewModel = authViewModel
            )
        }

        composable("employee_facturation") {
            EmployeeFacturationScreen(
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                onNavigateToEmployees = { navController.navigate("employees_management") },
                onNavigateToSchedule = { navController.navigate("employee_schedule") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToEmployeeExport = { navController.navigate("employee_export") },
                authViewModel = authViewModel
            )
        }

        // Enterprise versions of screens with EnterpriseBottomNavigationSimple
        composable("enterprise_calendar") {
            EnterpriseCalendarScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                onNavigateToTripDetails = { tripId ->
                    navController.navigate("enterprise_trip_details/$tripId")
                },
                authViewModel = authViewModel
            )
        }

        composable("enterprise_vehicles") {
            EnterpriseVehiclesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                authViewModel = authViewModel
            )
        }

        composable("enterprise_export") {
            EnterpriseExportScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToSettings = { navController.navigate("enterprise_settings") },
                authViewModel = authViewModel
            )
        }

        composable("enterprise_settings") {
            EnterpriseSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHome = { navController.navigate("enterprise_home") },
                onNavigateToCalendar = { navController.navigate("enterprise_calendar") },
                onNavigateToVehicles = { navController.navigate("enterprise_vehicles") },
                onNavigateToExport = { navController.navigate("enterprise_export") },
                onNavigateToLogViewer = { navController.navigate("log_viewer") },
                onNavigateToLogin = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                authViewModel = authViewModel
            )
        }

        // Enterprise versions of trip/expense screens
        composable("enterprise_add_trip") {
            val context = LocalContext.current
            val tripRepository = TripRepository.getInstance(context)

            EnterpriseAddTripScreen(
                onNavigateBack = { navController.popBackStack() },
                onTripSaved = { trip ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            MotiumApplication.logger.i("💾 Saving enterprise trip: ${trip.id}, distance=${trip.totalDistance}m, vehicle=${trip.vehicleId}, type=${trip.tripType}", "MotiumNavHost")
                            tripRepository.saveTrip(trip)
                            MotiumApplication.logger.i("✅ Enterprise trip saved: ${trip.id}", "MotiumNavHost")

                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                navController.popBackStack()
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("❌ Failed to save enterprise trip: ${e.message}", "MotiumNavHost", e)
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
            route = "enterprise_trip_details/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            EnterpriseTripDetailsScreen(
                tripId = tripId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { tripId ->
                    navController.navigate("enterprise_edit_trip/$tripId")
                },
                authViewModel = authViewModel
            )
        }

        composable(
            route = "enterprise_edit_trip/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            EnterpriseEditTripScreen(
                tripId = tripId,
                onNavigateBack = { navController.popBackStack() },
                onTripUpdated = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "enterprise_add_expense/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            EnterpriseAddExpenseScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() },
                onExpenseSaved = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "enterprise_expense_details/{date}",
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: ""
            EnterpriseExpenseDetailsScreen(
                date = date,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
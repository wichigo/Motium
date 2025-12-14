package com.application.motium.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.ProBottomNavigation
import com.application.motium.presentation.navigation.MotiumNavHost
import com.application.motium.presentation.theme.MotiumTheme
import com.application.motium.utils.DeepLinkHandler
import com.application.motium.utils.GoogleSignInHelper
import com.application.motium.utils.PermissionManager
import com.application.motium.utils.ThemeManager
import com.application.motium.service.SupabaseConnectionService
import com.application.motium.service.DozeModeFix
import com.application.motium.data.TripRepository
import kotlinx.coroutines.launch
class MainActivity : ComponentActivity() {

    private val googleSignInHelper = GoogleSignInHelper(this)
    private lateinit var authRepository: SupabaseAuthRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Toutes les permissions ont été accordées", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Certaines permissions sont nécessaires pour le fonctionnement de l'application", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Logger l'ouverture de l'activité principale
        MotiumApplication.logger.i("MainActivity started", "MainActivity")

        // Initialize auth repository for session management (using singleton)
        authRepository = SupabaseAuthRepository.getInstance(this)

        // Initialize Google Sign-In helper
        googleSignInHelper.initialize(this)

        // Vérifier et demander les permissions au démarrage
        if (!PermissionManager.hasAllRequiredPermissions(this)) {
            MotiumApplication.logger.i("Requesting permissions", "MainActivity")
            PermissionManager.requestAllPermissions(this)
        } else {
            MotiumApplication.logger.i("All permissions already granted", "MainActivity")
        }

        // Handle deep link from intent
        handleDeepLink(intent)

        setContent {
            val themeManager = ThemeManager.getInstance(this)
            val isDarkMode by themeManager.isDarkMode.collectAsState()

            MotiumTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MotiumApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Note: refreshSession() est géré automatiquement par SupabaseAuthRepository en arrière-plan
        // Ne pas l'appeler ici pour éviter de perturber l'initialisation au démarrage

        lifecycleScope.launch {
            try {
                MotiumApplication.logger.i("🔄 App resumed", "MainActivity")

                // Démarrer le service de connexion si l'utilisateur est authentifié
                if (authRepository.isUserAuthenticated()) {
                    MotiumApplication.logger.i("🔗 User authenticated - starting connection service", "MainActivity")
                    SupabaseConnectionService.startService(this@MainActivity)

                    // CRITICAL: Si l'auto-tracking est activé, demander l'exemption d'optimisation de batterie
                    val tripRepository = TripRepository.getInstance(this@MainActivity)
                    if (tripRepository.isAutoTrackingEnabled()) {
                        if (!DozeModeFix.isIgnoringBatteryOptimizations(this@MainActivity)) {
                            MotiumApplication.logger.i(
                                "⚠️ Auto-tracking enabled but app not exempted from battery optimization - requesting exemption",
                                "MainActivity"
                            )
                            DozeModeFix.requestBatteryOptimizationExemption(this@MainActivity)
                        } else {
                            MotiumApplication.logger.i(
                                "✅ App already exempted from battery optimization",
                                "MainActivity"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                MotiumApplication.logger.e("❌ Erreur au resume: ${e.message}", "MainActivity", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Ne pas arrêter le service quand l'app passe en arrière-plan
        // Il doit continuer à maintenir la connexion
        MotiumApplication.logger.i("⏸️ App paused - connection service continues", "MainActivity")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep links when app is already running
        handleDeepLink(intent)
    }

    /**
     * Process deep link from intent.
     * Extracts token for company link invitations and stores it for later processing.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return

        val handled = DeepLinkHandler.handleIntent(intent)
        if (handled) {
            MotiumApplication.logger.i("Deep link processed successfully", "MainActivity")
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionManager.handlePermissionResult(
            requestCode = requestCode,
            permissions = permissions,
            grantResults = grantResults,
            onPermissionGranted = {
                MotiumApplication.logger.i("Permissions granted successfully", "MainActivity")
                Toast.makeText(this, "Permissions accordées", Toast.LENGTH_SHORT).show()
            },
            onPermissionDenied = {
                MotiumApplication.logger.w("Some permissions were denied", "MainActivity")
                Toast.makeText(this, "Permissions refusées - Fonctionnalités limitées", Toast.LENGTH_LONG).show()
            }
        )
    }
}

// Routes that should NOT show bottom navigation
private val noNavigationRoutes = setOf("splash", "login", "register", "log_viewer")

// Routes that use Pro navigation (enterprise users)
private val proRoutes = setOf(
    "enterprise_home", "pro_home", "pro_calendar", "pro_vehicles", "pro_export", "pro_settings",
    "pro_linked_accounts", "pro_licenses", "pro_export_advanced",
    "pro_trip_details", "pro_edit_trip", "pro_add_trip", "pro_add_expense", "pro_expense_details",
    "pro_account_details", "pro_invite_person", "pro_user_trips"
)

// Map from current route to the navigation route name for highlighting
private fun getNavRouteForHighlight(currentRoute: String?): String {
    if (currentRoute == null) return ""

    // Handle parameterized routes - extract base route
    val baseRoute = currentRoute.substringBefore("/")

    return when {
        // Pro routes
        baseRoute in setOf("enterprise_home", "pro_home") -> "pro_home"
        baseRoute == "pro_calendar" -> "pro_calendar"
        baseRoute in setOf("pro_vehicles") -> "pro_vehicles"
        baseRoute == "pro_export" -> "pro_export"
        baseRoute == "pro_settings" -> "pro_settings"
        baseRoute == "pro_linked_accounts" -> "pro_linked_accounts"
        baseRoute == "pro_licenses" -> "pro_licenses"
        baseRoute == "pro_export_advanced" -> "pro_export_advanced"
        // Pro detail screens - highlight parent nav item
        baseRoute in setOf("pro_trip_details", "pro_edit_trip", "pro_add_trip") -> "pro_home"
        baseRoute in setOf("pro_add_expense", "pro_expense_details") -> "pro_home"
        baseRoute in setOf("pro_account_details", "pro_invite_person", "pro_user_trips") -> "pro_linked_accounts"

        // Individual routes
        baseRoute == "home" -> "home"
        baseRoute in setOf("calendar", "calendar_planning") -> "calendar"
        baseRoute == "vehicles" -> "vehicles"
        baseRoute == "export" -> "export"
        baseRoute == "settings" -> "settings"
        // Individual detail screens - highlight home
        baseRoute in setOf("trip_details", "edit_trip", "add_trip") -> "home"
        baseRoute in setOf("add_expense", "expense_details", "edit_expense") -> "home"

        else -> baseRoute
    }
}

@Composable
fun MotiumApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val authViewModel: AuthViewModel = viewModel {
        AuthViewModel(
            context = context,
            googleSignInHelper = GoogleSignInHelper(context)
        )
    }

    // Track current route
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val baseRoute = currentRoute?.substringBefore("/")

    // Determine if we should show navigation
    val shouldShowNavigation = baseRoute != null && baseRoute !in noNavigationRoutes

    // Determine if this is a Pro route (for choosing which navigation to show)
    val isProRoute = baseRoute != null && (baseRoute.startsWith("pro_") || baseRoute == "enterprise_home")

    // Get the route for nav highlighting
    val navHighlightRoute = getNavRouteForHighlight(currentRoute)

    Box(modifier = Modifier.fillMaxSize()) {
        MotiumNavHost(
            navController = navController,
            modifier = Modifier.fillMaxSize(),
            authViewModel = authViewModel
        )

        // App-level bottom navigation - always visible on appropriate screens
        if (shouldShowNavigation) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                if (isProRoute) {
                    ProBottomNavigation(
                        currentRoute = navHighlightRoute,
                        onNavigate = { route ->
                            // Navigate only if different from current
                            if (route != navHighlightRoute) {
                                navController.navigate(route) {
                                    // Pop up to the start destination of the graph to
                                    // avoid building up a large stack of destinations
                                    popUpTo("enterprise_home") {
                                        saveState = true
                                    }
                                    // Avoid multiple copies of the same destination
                                    launchSingleTop = true
                                    // Restore state when reselecting a previously selected item
                                    restoreState = true
                                }
                            }
                        },
                        isDarkMode = isDarkMode
                    )
                } else {
                    MotiumBottomNavigation(
                        currentRoute = navHighlightRoute,
                        onNavigate = { route ->
                            // Navigate only if different from current
                            if (route != navHighlightRoute) {
                                navController.navigate(route) {
                                    // Pop up to home to avoid building up a large stack
                                    popUpTo("home") {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        isDarkMode = isDarkMode
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MotiumAppPreview() {
    MotiumTheme {
        MotiumApp()
    }
}
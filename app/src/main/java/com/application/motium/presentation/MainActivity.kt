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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.components.BackgroundLocationPermissionDialog
import com.application.motium.presentation.components.MotiumBottomNavigation
import com.application.motium.presentation.components.ProBottomNavigation
import com.application.motium.presentation.components.openAppSettings
import com.application.motium.presentation.navigation.MotiumNavHost
import com.application.motium.presentation.theme.MotiumTheme
import com.application.motium.utils.DeepLinkHandler
import com.application.motium.utils.GoogleSignInHelper
import com.application.motium.utils.PermissionManager
import com.application.motium.utils.ThemeManager
import com.application.motium.service.SupabaseConnectionService
import com.application.motium.service.DozeModeFix
import com.application.motium.service.ActivityRecognitionService
import com.application.motium.data.TripRepository
import com.application.motium.data.local.LocalUserRepository
import com.application.motium.data.supabase.WorkScheduleRepository
import com.application.motium.data.sync.OfflineFirstSyncManager
import com.application.motium.data.sync.AutoTrackingScheduleWorker
import com.application.motium.domain.model.TrackingMode
import com.application.motium.worker.ActivityRecognitionHealthWorker
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
                    MotiumApp(googleSignInHelper = googleSignInHelper)
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

                    // FIX (2026-01-24): Trigger sync when app comes to foreground (not just HomeScreen)
                    // This ensures pending operations are synced when user returns from ANY screen
                    // Rate-limiting in triggerImmediateSync() prevents excessive syncs (1 min minimum)
                    val syncManager = OfflineFirstSyncManager.getInstance(this@MainActivity)
                    syncManager.triggerImmediateSync()
                    MotiumApplication.logger.i("🔄 Triggered sync on app resume", "MainActivity")

                    val tripRepository = TripRepository.getInstance(this@MainActivity)
                    val localUserRepository = LocalUserRepository.getInstance(this@MainActivity)
                    val workScheduleRepository = WorkScheduleRepository.getInstance(this@MainActivity)

                    // CRITICAL: Sync auto-tracking cache from Room BEFORE checking isAutoTrackingEnabled()
                    // This fixes the issue where settings saved in Room are not reflected in SharedPreferences
                    // cache on app startup, causing auto-tracking to not start even when mode is ALWAYS
                    val userId = localUserRepository.getLoggedInUser()?.id
                    if (userId != null) {
                        tripRepository.syncAutoTrackingCacheFromRoom(userId)
                    }

                    // Ensure workers are scheduled based on tracking mode
                    when (tripRepository.getTrackingMode()) {
                        TrackingMode.ALWAYS -> {
                            ActivityRecognitionHealthWorker.schedule(this@MainActivity)
                        }
                        TrackingMode.WORK_HOURS_ONLY -> {
                            AutoTrackingScheduleWorker.schedule(this@MainActivity)
                            ActivityRecognitionHealthWorker.schedule(this@MainActivity)
                            AutoTrackingScheduleWorker.runNow(this@MainActivity)

                            if (userId != null) {
                                val shouldTrack = workScheduleRepository.shouldAutotrackOfflineFirst(userId)
                                tripRepository.setAutoTrackingEnabled(shouldTrack)
                                if (!shouldTrack) {
                                    ActivityRecognitionService.stopService(this@MainActivity)
                                }
                            }
                        }
                        TrackingMode.DISABLED -> {
                            AutoTrackingScheduleWorker.cancel(this@MainActivity)
                            ActivityRecognitionHealthWorker.cancel(this@MainActivity)
                            tripRepository.setAutoTrackingEnabled(false)
                            ActivityRecognitionService.stopService(this@MainActivity)
                        }
                    }

                    // NOW check the synced cache value
                    if (tripRepository.isAutoTrackingEnabled()) {
                        // Start ActivityRecognitionService to show notification
                        MotiumApplication.logger.i(
                            "🚀 Auto-tracking enabled - starting ActivityRecognitionService",
                            "MainActivity"
                        )
                        ActivityRecognitionService.startService(this@MainActivity)

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
     * Extracts token for company link invitations or password reset and stores it for later processing.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return

        val deepLinkType = DeepLinkHandler.handleIntent(intent)
        if (deepLinkType != null) {
            MotiumApplication.logger.i("Deep link processed: $deepLinkType", "MainActivity")
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
private val noNavigationRoutes = setOf("splash", "login", "register", "log_viewer", "trial_expired", "subscription_expired", "upgrade", "pro_trial_expired")

// Routes that use Pro navigation (enterprise users)
private val proRoutes = setOf(
    "enterprise_home", "pro_home", "pro_calendar", "pro_vehicles", "pro_export", "pro_settings",
    "pro_linked_accounts", "pro_licenses", "pro_export_advanced",
    "pro_trip_details", "pro_edit_trip", "pro_add_trip", "pro_add_expense", "pro_expense_details",
    "pro_account_details", "pro_invite_person", "pro_user_trips", "pro_user_vehicles", "pro_user_expenses"
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
        baseRoute in setOf("pro_account_details", "pro_invite_person", "pro_user_trips", "pro_user_vehicles", "pro_user_expenses") -> "pro_linked_accounts"

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
fun MotiumApp(googleSignInHelper: GoogleSignInHelper) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode by themeManager.isDarkMode.collectAsState()

    val authViewModel: AuthViewModel = viewModel {
        AuthViewModel(
            context = context,
            googleSignInHelper = googleSignInHelper
        )
    }

    // Background location permission dialog state
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }
    var hasCheckedBackgroundPermission by remember { mutableStateOf(false) }

    // Check background location permission when app resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Only check if basic location permission is granted but background is not
                val hasBasicLocation = PermissionManager.hasLocationPermission(context)
                val hasBackgroundLocation = PermissionManager.hasBackgroundLocationPermission(context)

                MotiumApplication.logger.i(
                    "Permission check - Basic location: $hasBasicLocation, Background location: $hasBackgroundLocation",
                    "PermissionCheck"
                )

                if (hasBasicLocation && !hasBackgroundLocation && !hasCheckedBackgroundPermission) {
                    // Show dialog only once per session (user can dismiss it)
                    showBackgroundLocationDialog = true
                    hasCheckedBackgroundPermission = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
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

    // Background location permission dialog
    if (showBackgroundLocationDialog) {
        BackgroundLocationPermissionDialog(
            onDismiss = { showBackgroundLocationDialog = false },
            onOpenSettings = {
                showBackgroundLocationDialog = false
                openAppSettings(context)
            },
        )
    }

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
                                if (route == "enterprise_home") {
                                    // When navigating to Home, clear all back stack
                                    // Don't restore state to avoid returning to detail screens
                                    navController.navigate(route) {
                                        popUpTo("enterprise_home") {
                                            inclusive = true
                                        }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(route) {
                                        // Pop up to the start destination of the graph to
                                        // avoid building up a large stack of destinations
                                        popUpTo("enterprise_home") {
                                            // Don't save state to avoid navigation corruption loops
                                            saveState = false
                                        }
                                        // Avoid multiple copies of the same destination
                                        launchSingleTop = true
                                        // Removed restoreState to avoid navigation loops when
                                        // previously saved state includes nested navigation
                                    }
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
                                if (route == "home") {
                                    // When navigating to Home, clear all back stack above home
                                    // Don't restore state to avoid returning to Trip Details
                                    navController.navigate(route) {
                                        popUpTo("home") {
                                            inclusive = true
                                        }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(route) {
                                        // Pop up to home to avoid building up a large stack
                                        popUpTo("home") {
                                            // Don't save state to avoid navigation corruption loops
                                            saveState = false
                                        }
                                        launchSingleTop = true
                                        // Removed restoreState to avoid navigation loops
                                    }
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

// Preview removed - requires GoogleSignInHelper which needs Activity initialization

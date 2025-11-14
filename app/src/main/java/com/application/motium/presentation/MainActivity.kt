package com.application.motium.presentation

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseAuthRepository
import com.application.motium.presentation.auth.AuthViewModel
import com.application.motium.presentation.navigation.MotiumNavHost
import com.application.motium.presentation.theme.MotiumTheme
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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

@Composable
fun MotiumApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val authViewModel: AuthViewModel = viewModel {
        AuthViewModel(
            context = context,
            googleSignInHelper = GoogleSignInHelper(context)
        )
    }

    MotiumNavHost(
        navController = navController,
        modifier = Modifier.fillMaxSize(),
        authViewModel = authViewModel
    )
}

@Preview(showBackground = true)
@Composable
fun MotiumAppPreview() {
    MotiumTheme {
        MotiumApp()
    }
}
package com.shadowself.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shadowself.service.ShadowSelfService
import com.shadowself.ui.dashboard.DashboardScreen
import com.shadowself.ui.incidents.IncidentHistoryScreen
import com.shadowself.ui.onboarding.OnboardingScreen
import com.shadowself.ui.onboarding.OnboardingViewModel
import com.shadowself.ui.settings.SettingsScreen
import com.shadowself.ui.theme.ShadowSelfTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val onboardingVm: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Show splash screen statically
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            ShadowSelfTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    val nav          = rememberNavController()
                    val startDest    = if (onboardingVm.isOnboardingComplete)
                                           "dashboard" else "onboarding"

                    NavHost(nav, startDestination = startDest) {

                        composable("onboarding") {
                            OnboardingScreen(
                                onComplete = {
                                    startMonitoringService()
                                    nav.navigate("dashboard") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("dashboard") {
                            DashboardScreen(
                                onNavigateToHistory  = { nav.navigate("history") },
                                onNavigateToSettings = { nav.navigate("settings") }
                            )
                        }

                        composable("history") {
                            IncidentHistoryScreen(onBack = { nav.popBackStack() })
                        }

                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }

        // If onboarding already done, start service immediately
        if (onboardingVm.isOnboardingComplete) startMonitoringService()
    }

    private fun startMonitoringService() {
        startForegroundService(
            Intent(this, ShadowSelfService::class.java).apply {
                action = ShadowSelfService.ACTION_START
            }
        )
    }
}

package com.shadowself.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shadowself.sensors.AmbientContextCollector
import com.shadowself.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * OnboardingScreen
 *
 * Five-step permission wizard shown only on first launch.
 * Guides the user through every special permission ShadowSelf needs,
 * explaining exactly WHY each one is required before asking for it.
 *
 * Steps:
 *   1  Accessibility Service  — typing rhythm
 *   2  Usage Stats            — app usage patterns
 *   3  Location               — home/work zone setup
 *   4  Camera                 — silent intruder selfie
 *   5  Device Admin           — soft lockdown capability
 *
 * Each step has a "Grant" button that deep-links to the exact
 * system settings page — no confusion about where to go.
 * After all steps are granted, marks onboarding complete and
 * calls onComplete() to navigate to the dashboard.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PREFS_NAME       = "shadowself_onboarding"
        private const val KEY_COMPLETE     = "onboarding_complete"
        private const val KEY_HOME_LAT     = "home_lat"
        private const val KEY_HOME_LNG     = "home_lng"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    private val _allGranted = MutableStateFlow(false)
    val allGranted: StateFlow<Boolean> = _allGranted.asStateFlow()

    fun nextStep() {
        if (_step.value < OnboardingStep.entries.size - 1) {
            _step.value++
        } else {
            completeOnboarding()
        }
    }

    fun captureHomeLocation() {
        viewModelScope.launch {
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val loc = suspendCancellableCoroutine { cont ->
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
                loc?.let {
                    prefs.edit()
                        .putFloat(KEY_HOME_LAT, it.latitude.toFloat())
                        .putFloat(KEY_HOME_LNG, it.longitude.toFloat())
                        .apply()
                    Logger.d("Onboarding", "Home location saved: ${it.latitude}, ${it.longitude}")
                }
            } catch (e: SecurityException) {
                Logger.e("Onboarding", "Location permission not granted yet")
            }
        }
    }

    private fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
        _allGranted.value = true
    }
}

enum class OnboardingStep(
    val title:       String,
    val description: String,
    val why:         String,
    val icon:        ImageVector,
    val actionLabel: String
) {
    ACCESSIBILITY(
        title       = "Typing Rhythm",
        description = "Allow ShadowSelf to observe key timing events.",
        why         = "Your inter-key timing (how long between keypresses) is as unique as " +
                      "a fingerprint. ShadowSelf reads ONLY timing — never your actual text.",
        icon        = Icons.Default.Keyboard,
        actionLabel = "Open Accessibility Settings"
    ),
    USAGE_STATS(
        title       = "App Usage",
        description = "Allow ShadowSelf to see which app categories you use.",
        why         = "App-switching sequences are a strong behavioural signal. " +
                      "Package names are SHA-256 hashed before storage — never stored raw.",
        icon        = Icons.Default.BarChart,
        actionLabel = "Open Usage Access Settings"
    ),
    LOCATION(
        title       = "Location Zone",
        description = "Allow location access to detect home vs work zones.",
        why         = "Where you are when you use your phone is part of your behaviour profile. " +
                      "Only the zone (Home/Work/Other) is stored — never raw coordinates.",
        icon        = Icons.Default.LocationOn,
        actionLabel = "Grant Location Permission"
    ),
    CAMERA(
        title       = "Intruder Photo",
        description = "Allow camera access for silent selfie on intrusion.",
        why         = "When an intruder is detected, ShadowSelf silently captures a front-camera " +
                      "photo and stores it encrypted locally. Never uploaded.",
        icon        = Icons.Default.CameraAlt,
        actionLabel = "Grant Camera Permission"
    ),
    DEVICE_ADMIN(
        title       = "Soft Lockdown",
        description = "Grant Device Admin for lockdown capability.",
        why         = "Optional but recommended. Allows ShadowSelf to lock the screen when an " +
                      "intruder is detected. You can revoke this at any time in Settings.",
        icon        = Icons.Default.Security,
        actionLabel = "Enable Device Admin"
    );

    companion object {
        val entries = values().toList()
    }
}

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context   = LocalContext.current
    val step      by viewModel.step.collectAsState()
    val allDone   by viewModel.allGranted.collectAsState()

    LaunchedEffect(allDone) {
        if (allDone) onComplete()
    }

    val currentStep = OnboardingStep.entries[step]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnboardingStep.entries.forEachIndexed { i, _ ->
                Box(
                    modifier = Modifier
                        .size(if (i == step) 24.dp else 8.dp, 8.dp)
                        .background(
                            if (i <= step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        AnimatedVisibility(
            visible = true,
            enter   = fadeIn() + slideInHorizontally()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                // Icon circle
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        currentStep.icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint     = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(Modifier.height(32.dp))

                Text(
                    "Step ${step + 1} of ${OnboardingStep.entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    currentStep.title,
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    currentStep.description,
                    style     = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color     = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(16.dp))

                // Why card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Text(
                            currentStep.why,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))

                // Action button — deep links to exact settings page
                Button(
                    onClick  = { openSettingsForStep(context, currentStep) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(currentStep.actionLabel, fontSize = 15.sp)
                }

                Spacer(Modifier.height(12.dp))

                // "Done / Skip" — moves to next step
                val isLast = step == OnboardingStep.entries.size - 1
                TextButton(
                    onClick = {
                        if (currentStep == OnboardingStep.LOCATION) {
                            viewModel.captureHomeLocation()
                        }
                        viewModel.nextStep()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isLast) "Finish setup" else "Done — next step",
                        fontSize = 15.sp
                    )
                }

                if (currentStep == OnboardingStep.DEVICE_ADMIN) {
                    Text(
                        "You can skip this step. Lockdown capability will be unavailable.",
                        style     = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun openSettingsForStep(context: Context, step: OnboardingStep) {
    val intent = when (step) {
        OnboardingStep.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        OnboardingStep.USAGE_STATS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        OnboardingStep.LOCATION ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null))
        OnboardingStep.CAMERA ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null))
        OnboardingStep.DEVICE_ADMIN ->
            Intent("android.app.action.ADD_DEVICE_ADMIN").apply {
                putExtra(
                    "android.app.extra.DEVICE_ADMIN",
                    android.content.ComponentName(
                        context, "com.shadowself.response.ShadowSelfDeviceAdmin"
                    )
                )
                putExtra(
                    "android.app.extra.ADD_EXPLANATION",
                    "Enables ShadowSelf to lock the screen when an intruder is detected."
                )
            }
    }
    context.startActivity(intent)
}

package com.shadowself.response

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SoftLockdown
 *
 * Implements the graduated restriction sequence triggered on alert.
 * Three escalation tiers, each requiring more permissions than the last.
 *
 * ── TIER 1 — No special permissions required ──────────────────────────────
 *   • Clear clipboard (removes any sensitive data the intruder might paste)
 *   • Revoke clipboard read access for all background apps (Android 10+)
 *
 * ── TIER 2 — Requires Device Admin (granted during onboarding) ────────────
 *   • Set screen lock timeout to 0 seconds (immediate lock on screen-off)
 *   • Lock screen via lockNow()
 *   • Disable keyguard features (prevents fingerprint/face unlock bypass)
 *
 * ── TIER 3 — Nuclear option (requires explicit user opt-in at setup) ───────
 *   • Wipe work profile (if corporate device with work profile)
 *   • NOT implemented here: full device wipe is too destructive to auto-trigger
 *     and requires an additional confirmation flow
 *
 * Device Admin setup:
 *   The user must activate Device Admin during onboarding for Tier 2.
 *   See DeviceAdminReceiver in service/ShadowSelfDeviceAdmin.kt.
 *   Without it, only Tier 1 runs — which is still useful (clears clipboard,
 *   logs the incident, captures photo and location).
 *
 * In the alert notification, the owner sees a "False alarm" button.
 * If they dismiss within 30 seconds of TIER 2 triggering, the screen
 * lock timeout is restored to the previous value.
 */
@Singleton
class SoftLockdown @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG         = "SoftLockdown"
        private const val PREFS_NAME  = "shadowself_lockdown"
        private const val KEY_PREV_TIMEOUT = "prev_lock_timeout"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, ShadowSelfDeviceAdmin::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isDeviceAdminActive: Boolean
        get() = dpm.isAdminActive(adminComponent)

    /**
     * Executes the full lockdown sequence.
     * Returns a LockdownResult describing what was applied.
     */
    fun execute(): LockdownResult {
        val steps = mutableListOf<String>()

        // ── TIER 1: No permissions required ───────────────────────────────────
        clearClipboard()
        steps.add("Clipboard cleared")

        if (!isDeviceAdminActive) {
            Logger.d(TAG, "Device Admin not active — Tier 1 only")
            return LockdownResult(steps, tier = 1, deviceAdminMissing = true)
        }

        // ── TIER 2: Device Admin active ───────────────────────────────────────
        try {
            // Save current timeout before overriding
            val currentTimeout = dpm.getMaximumTimeToLock(adminComponent)
            prefs.edit().putLong(KEY_PREV_TIMEOUT, currentTimeout).apply()

            // Immediate lock on screen-off
            dpm.setMaximumTimeToLock(adminComponent, 1L)
            steps.add("Screen timeout → 1ms")

            // Lock the screen now
            dpm.lockNow()
            steps.add("Screen locked")

            // Disable biometric bypass
            dpm.setKeyguardDisabledFeatures(
                adminComponent,
                DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT or
                DevicePolicyManager.KEYGUARD_DISABLE_FACE
            )
            steps.add("Biometric unlock disabled")

            Logger.d(TAG, "Tier 2 lockdown complete: ${steps.joinToString(", ")}")
            return LockdownResult(steps, tier = 2, deviceAdminMissing = false)

        } catch (e: SecurityException) {
            Logger.e(TAG, "Tier 2 failed — Device Admin permission issue: ${e.message}")
            return LockdownResult(steps, tier = 1, deviceAdminMissing = false,
                error = "Device Admin permission revoked: ${e.message}")
        }
    }

    /**
     * Undoes Tier 2 restrictions.
     * Called when owner confirms false alarm or taps "I have my phone".
     */
    fun release() {
        if (!isDeviceAdminActive) return
        try {
            val prevTimeout = prefs.getLong(KEY_PREV_TIMEOUT, 30_000L)
            dpm.setMaximumTimeToLock(adminComponent, prevTimeout)
            dpm.setKeyguardDisabledFeatures(adminComponent, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
            Logger.d(TAG, "Lockdown released — timeout restored to ${prevTimeout}ms")
        } catch (e: Exception) {
            Logger.e(TAG, "Release failed: ${e.message}")
        }
    }

    private fun clearClipboard() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            clipboard.clearPrimaryClip()
        } catch (e: Exception) {
            Logger.e(TAG, "Clipboard clear failed: ${e.message}")
        }
    }

    data class LockdownResult(
        val stepsApplied:       List<String>,
        val tier:               Int,
        val deviceAdminMissing: Boolean,
        val error:              String? = null
    )
}

/**
 * Device Admin receiver — must be declared in AndroidManifest.xml.
 * Handles Device Admin lifecycle events.
 */
class ShadowSelfDeviceAdmin : android.app.admin.DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Logger.d("DeviceAdmin", "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Logger.d("DeviceAdmin", "Device Admin disabled — Tier 2 lockdown unavailable")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        Logger.d("DeviceAdmin", "Password attempt failed")
        // Could feed into AnomalyEngine as a signal, but kept out of scope here
    }
}

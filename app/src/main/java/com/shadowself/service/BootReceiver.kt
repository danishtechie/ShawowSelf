package com.shadowself.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts ShadowSelfService after device reboot.
 * Requires RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val serviceIntent = Intent(context, ShadowSelfService::class.java).apply {
            action = ShadowSelfService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}

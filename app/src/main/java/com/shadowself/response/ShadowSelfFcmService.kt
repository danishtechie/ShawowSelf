package com.shadowself.response

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shadowself.util.Logger

/**
 * Receives FCM push messages on the OWNER's paired device.
 * When a ShadowSelf alert fires on the monitored phone, it sends
 * an FCM message to the owner's device token. This service
 * displays the incoming alert notification.
 */
class ShadowSelfFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG      = "ShadowSelfFcm"
        private const val CHANNEL  = "shadowself_remote_alert"
        private const val NOTIF_ID = 3001
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Logger.d(TAG, "FCM received: ${message.data}")
        val title      = message.data["title"]      ?: "ShadowSelf Alert"
        val body       = message.data["body"]       ?: "Unusual activity detected"
        val confidence = message.data["confidence"] ?: "?"

        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ShadowSelf Remote Alerts",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText("$body (confidence: $confidence%)")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    override fun onNewToken(token: String) {
        Logger.d(TAG, "New FCM token: $token")
        // Store token in SharedPreferences; send to paired device setup
    }
}

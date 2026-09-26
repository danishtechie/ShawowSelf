package com.shadowself.response

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.shadowself.data.local.IncidentEntity
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FcmAlertNotifier
 *
 * Sends a high-priority FCM notification to the owner's registered device
 * when an intrusion is detected.
 *
 * Updated to use FCM HTTP v1 API with OAuth2 authentication using a
 * service account JSON file.
 */
@Singleton
class FcmAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG              = "FcmAlertNotifier"
        private const val PREFS_NAME       = "shadowself_fcm"
        private const val KEY_PAIRED_TOKEN = "paired_device_token"
        private const val LOCAL_CHANNEL_ID = "shadowself_alerts"
        private const val LOCAL_NOTIF_ID   = 3001
        private const val SERVICE_ACCOUNT_FILE = "service-account.json"
    }

    private val prefs by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    suspend fun notify(incident: IncidentEntity): Boolean {
        showLocalNotification(incident)

        val pairedToken = prefs.getString(KEY_PAIRED_TOKEN, null)
        if (pairedToken.isNullOrBlank()) {
            Logger.d(TAG, "No paired device token — local notification only")
            return false
        }

        return try {
            sendFcmMessageV1(pairedToken, incident)
            Logger.d(TAG, "FCM alert sent for incident #${incident.id}")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "FCM send failed: ${e.message}")
            false
        }
    }

    fun storePairedToken(token: String) {
        prefs.edit().putString(KEY_PAIRED_TOKEN, token).apply()
        Logger.d(TAG, "Paired device token stored")
    }

    suspend fun getOwnToken(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to get FCM token: ${e.message}")
        null
    }

    /**
     * Sends a message via FCM HTTP v1 API.
     * Uses OAuth2 access token from the service account JSON.
     */
    private suspend fun sendFcmMessageV1(recipientToken: String, incident: IncidentEntity) = withContext(Dispatchers.IO) {
        val accessToken = getAccessToken()
        val projectId   = getProjectId()

        val payload = mapOf(
            "message" to mapOf(
                "token" to recipientToken,
                "data" to mapOf(
                    "type"          to "SHADOW_SELF_ALERT",
                    "incidentId"    to incident.id.toString(),
                    "timestampMs"   to incident.timestampMs.toString(),
                    "alertLevel"    to incident.alertLevel,
                    "confidence"    to incident.confidenceScore.toString()
                ),
                "android" to mapOf(
                    "priority" to "high",
                    "ttl" to "300s"
                )
            )
        )

        val url  = URL("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            doOutput      = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; UTF-8")
        }

        val body = Gson().toJson(payload)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorResponse = conn.errorStream?.bufferedReader()?.readText()
            conn.disconnect()
            throw Exception("FCM HTTP $responseCode: $errorResponse")
        }
        conn.disconnect()
    }

    private fun getAccessToken(): String {
        val stream = context.assets.open(SERVICE_ACCOUNT_FILE)
        val credentials = GoogleCredentials.fromStream(stream)
            .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    private fun getProjectId(): String {
        val stream = context.assets.open(SERVICE_ACCOUNT_FILE)
        val json = stream.bufferedReader().use { it.readText() }
        val map = Gson().fromJson(json, Map::class.java)
        return map["project_id"] as String
    }

    private fun showLocalNotification(incident: IncidentEntity) {
        val nm = context.getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(LOCAL_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                LOCAL_CHANNEL_ID,
                "ShadowSelf Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description     = "Intruder detection alerts"
                enableVibration(true)
                setShowBadge(true)
            })
        }

        val falseAlarmIntent = PendingIntent.getBroadcast(
            context, incident.id.toInt(),
            Intent("com.shadowself.FALSE_ALARM").putExtra("incidentId", incident.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, LOCAL_CHANNEL_ID)
            .setContentTitle("⚠ ShadowSelf Alert")
            .setContentText("Unknown behaviour detected — tap to review")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "False alarm", falseAlarmIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        nm.notify(LOCAL_NOTIF_ID, notification)
    }
}

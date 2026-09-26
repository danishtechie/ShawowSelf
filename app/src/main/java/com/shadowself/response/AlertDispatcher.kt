package com.shadowself.response

import android.content.Context
import com.google.gson.Gson
import com.shadowself.anomaly.WindowVerdict
import com.shadowself.data.local.IncidentDao
import com.shadowself.data.local.IncidentEntity
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlertDispatcher
 *
 * Orchestrates the full five-step intruder response sequence.
 * Called by AnomalyEngine when SlidingWindowBuffer reaches ALERT level.
 *
 * Sequence (all on Dispatchers.IO, steps 1–2 run concurrently):
 *
 *   Step 1 ┐ Silent front-camera selfie  → encrypted JPEG in filesDir
 *   Step 2 ┘ GPS location snapshot       → lat/lng + address
 *           ↓ (both complete)
 *   Step 3   Persist IncidentEntity to Room DB
 *   Step 4   FCM push to paired device + local notification
 *   Step 5   Soft lockdown (clear clipboard → lock screen)
 *
 * Steps 1 and 2 run concurrently via coroutine launch to minimise latency —
 * camera capture typically takes 1–3 seconds; GPS typically 0.5–2 seconds.
 * Step 3 persists whatever was captured even if one of them failed.
 *
 * Debounce: max one full alert sequence every MIN_ALERT_INTERVAL_MS.
 * Suppression: manual pause by owner (e.g. handing phone to someone).
 *
 * All state (lastAlertMs, suppressedUntil) survives only in memory —
 * if the service is restarted, the debounce resets. This is intentional:
 * after a restart the model may have been retrained and the new session
 * should start with a clean alert state.
 */
@Singleton
class AlertDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraCapture:    SilentCameraCapture,
    private val locationSnap:     LocationSnapshotter,
    private val fcmNotifier:      FcmAlertNotifier,
    private val softLockdown:     SoftLockdown,
    private val incidentDao:      IncidentDao
) {
    companion object {
        private const val TAG                   = "AlertDispatcher"
        private const val MIN_ALERT_INTERVAL_MS = 5 * 60 * 1_000L  // 5 minutes
    }

    @Volatile private var lastAlertMs     = 0L
    @Volatile private var suppressedUntil = 0L

    suspend fun triggerAlert(
        confidence:    Float,
        timestampMs:   Long,
        alertLevel:    WindowVerdict.AlertLevel,
        windowHistory: List<Float>
    ) = withContext(Dispatchers.IO) {

        // ── Guards ────────────────────────────────────────────────────────────
        val now = System.currentTimeMillis()

        if (now < suppressedUntil) {
            Logger.d(TAG, "Alert suppressed — ${(suppressedUntil - now) / 1000}s remaining")
            return@withContext
        }
        if (now - lastAlertMs < MIN_ALERT_INTERVAL_MS) {
            Logger.d(TAG, "Debounced — ${(now - lastAlertMs) / 1000}s since last alert")
            return@withContext
        }
        if (alertLevel < WindowVerdict.AlertLevel.ALERT) {
            Logger.d(TAG, "Level $alertLevel — logging only")
            return@withContext
        }

        lastAlertMs = now
        Logger.d(TAG, "ALERT — confidence=${"%.3f".format(confidence)} scores=$windowHistory")

        // ── Step 1 + 2: Camera + Location (concurrent) ────────────────────────
        var photoPath: String? = null
        var latitude:  Double? = null
        var longitude: Double? = null
        var address:   String? = null

        coroutineScope {
            val photoJob    = launch {
                val result = cameraCapture.capture()
                if (result is SilentCameraCapture.CaptureResult.Success) {
                    photoPath = result.encryptedPath
                } else {
                    Logger.d(TAG, "Photo capture skipped: ${(result as SilentCameraCapture.CaptureResult.Failed).reason}")
                }
            }
            val locationJob = launch {
                locationSnap.snapshot()?.let { snap ->
                    latitude  = snap.latitude
                    longitude = snap.longitude
                    address   = locationSnap.reverseGeocode(snap.latitude, snap.longitude)
                }
            }
            photoJob.join()
            locationJob.join()
        }

        // ── Step 3: Persist incident ──────────────────────────────────────────
        val incident = IncidentEntity(
            timestampMs       = timestampMs,
            confidenceScore   = confidence,
            alertLevel        = alertLevel.name,
            photoPath         = photoPath,
            photoEncrypted    = photoPath != null,
            latitude          = latitude,
            longitude         = longitude,
            addressLine       = address,
            windowScoresJson  = Gson().toJson(windowHistory),
            qualityScore      = 0f
        )
        val incidentId = incidentDao.insertIncident(incident)
        val saved      = incident.copy()
        Logger.d(TAG, "Incident #$incidentId persisted")

        // ── Step 4: Notify ────────────────────────────────────────────────────
        val notified = fcmNotifier.notify(saved)
        if (notified) incidentDao.markNotificationSent(incidentId)

        // ── Step 5: Lockdown ──────────────────────────────────────────────────
        val lockResult = softLockdown.execute()
        if (lockResult.tier >= 2) incidentDao.markLockdownTriggered(incidentId)
        Logger.d(TAG, "Lockdown tier ${lockResult.tier}: ${lockResult.stepsApplied}")

        Logger.d(TAG, "Alert sequence complete for incident #$incidentId")
    }

    // ── Owner feedback handlers ────────────────────────────────────────────────

    fun onFalseAlarm(incidentId: Long) {
        softLockdown.release()
        Logger.d(TAG, "False alarm confirmed for incident #$incidentId — lockdown released")
    }

    fun suppressFor(durationMs: Long) {
        suppressedUntil = System.currentTimeMillis() + durationMs
        Logger.d(TAG, "Alerts suppressed for ${durationMs / 1000}s")
    }

    fun isSuppressed(): Boolean = System.currentTimeMillis() < suppressedUntil
}

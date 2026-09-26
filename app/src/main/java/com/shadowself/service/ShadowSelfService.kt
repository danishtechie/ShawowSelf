package com.shadowself.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shadowself.features.FeatureExtractionPipeline
import com.shadowself.sensors.AccelerometerCollector
import com.shadowself.sensors.AmbientContextCollector
import com.shadowself.sensors.AppUsageCollector
import com.shadowself.sensors.TouchBiometricCollector
import com.shadowself.sensors.TypingRhythmCollector
import com.shadowself.sensors.UnlockPatternCollector
import com.shadowself.data.repository.SensorDataRepository
import com.shadowself.domain.model.SensorWindow
import com.shadowself.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ShadowSelfService — the heart of the app.
 *
 * Runs as a ForegroundService to survive background restrictions.
 * Coordinates all six sensor collectors, merges their streams via
 * Kotlin Flow, and pushes assembled SensorWindows into the
 * FeatureExtractionPipeline every WINDOW_STEP_MS milliseconds.
 *
 * Lifecycle:
 *   startService(ACTION_START)  → starts collection
 *   startService(ACTION_STOP)   → graceful shutdown
 *   startService(ACTION_PAUSE)  → pause without stopping (owner verified)
 *   startService(ACTION_RESUME) → resume after pause
 *
 * All heavy work runs on Dispatchers.Default via serviceScope.
 * The service itself never blocks the main thread.
 */
@AndroidEntryPoint
class ShadowSelfService : Service() {

    companion object {
        const val ACTION_START  = "com.shadowself.ACTION_START"
        const val ACTION_STOP   = "com.shadowself.ACTION_STOP"
        const val ACTION_PAUSE  = "com.shadowself.ACTION_PAUSE"
        const val ACTION_RESUME = "com.shadowself.ACTION_RESUME"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID      = "shadowself_monitor"
        const val CHANNEL_NAME    = "ShadowSelf Monitor"

        // 30-second sliding window assembled for one inference pass
        const val WINDOW_INTERVAL_MS = 30_000L
        // 50% overlap: new window every 15 seconds
        const val WINDOW_STEP_MS     = 15_000L

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    }

    @Inject lateinit var typingCollector:   TypingRhythmCollector
    @Inject lateinit var touchCollector:    TouchBiometricCollector
    @Inject lateinit var accelCollector:    AccelerometerCollector
    @Inject lateinit var appUsageCollector: AppUsageCollector
    @Inject lateinit var ambientCollector:  AmbientContextCollector
    @Inject lateinit var unlockCollector:   UnlockPatternCollector
    @Inject lateinit var featurePipeline:   FeatureExtractionPipeline
    @Inject lateinit var repository:        SensorDataRepository

    // SupervisorJob: one failing collector doesn't bring down the others
    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var windowTimerJob: kotlinx.coroutines.Job? = null

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Logger.d("ShadowSelfService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START  -> startMonitoring()
            ACTION_STOP   -> stopMonitoring()
            ACTION_PAUSE  -> pauseMonitoring()
            ACTION_RESUME -> resumeMonitoring()
        }
        return START_STICKY  // OS restarts us if killed, replays last intent
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        stopAllCollectors()
        Logger.d("ShadowSelfService", "Service destroyed")
        super.onDestroy()
    }

    // ── State transitions ──────────────────────────────────────────────────────

    private fun startMonitoring() {
        if (_serviceState.value is ServiceState.Running) return
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring active"))
        _serviceState.value = ServiceState.Running
        startAllCollectors()
        startWindowTimer()
        Logger.d("ShadowSelfService", "Monitoring started")
    }

    private fun stopMonitoring() {
        windowTimerJob?.cancel()
        stopAllCollectors()
        _serviceState.value = ServiceState.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseMonitoring() {
        windowTimerJob?.cancel()
        stopAllCollectors()
        _serviceState.value = ServiceState.Paused
        updateNotification("Paused — owner verified")
    }

    private fun resumeMonitoring() {
        if (_serviceState.value !is ServiceState.Paused) return
        startAllCollectors()
        startWindowTimer()
        _serviceState.value = ServiceState.Running
        updateNotification("Monitoring active")
    }

    // ── Collector management ───────────────────────────────────────────────────

    private fun startAllCollectors() {
        typingCollector.start()
        touchCollector.start()
        accelCollector.start()
        appUsageCollector.start()
        ambientCollector.start()
        unlockCollector.start()
    }

    private fun stopAllCollectors() {
        typingCollector.stop()
        touchCollector.stop()
        accelCollector.stop()
        appUsageCollector.stop()
        ambientCollector.stop()
        unlockCollector.stop()
    }

    // ── Window assembly ────────────────────────────────────────────────────────

    /**
     * Every WINDOW_STEP_MS we snapshot all six collectors, wrap into a
     * SensorWindow, persist for training, and hand off to the pipeline.
     *
     * The timer approach (vs combine()) is deliberate: even when the phone
     * is idle (no typing, no touch), we still want a window so the model
     * can learn "idle" as part of the owner's behavioral profile.
     */
    private fun startWindowTimer() {
        windowTimerJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(WINDOW_STEP_MS)
                assembleAndDispatchWindow()
            }
        }
    }

    private suspend fun assembleAndDispatchWindow() {
        val window = SensorWindow(
            timestampMs    = System.currentTimeMillis(),
            typingEvents   = typingCollector.drainBuffer(),
            touchEvents    = touchCollector.drainBuffer(),
            motionSamples  = accelCollector.drainBuffer(),
            appUsageEvents = appUsageCollector.drainBuffer(),
            ambientData    = ambientCollector.getCurrentSnapshot(),
            unlockEvents   = unlockCollector.drainBuffer()
        )
        repository.saveRawWindow(window)
        featurePipeline.process(window)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW  // Silent — no sound or vibration
        ).apply {
            description = "ShadowSelf behavioral monitoring"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ShadowSelfService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShadowSelf")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}

sealed class ServiceState {
    object Stopped : ServiceState()
    object Running : ServiceState()
    object Paused  : ServiceState()
}

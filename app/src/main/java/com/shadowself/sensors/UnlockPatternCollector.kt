package com.shadowself.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.shadowself.domain.model.UnlockEvent
import com.shadowself.domain.model.UnlockMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Captures behavioral signals around the screen unlock moment.
 *
 * Listens for ACTION_SCREEN_ON → ACTION_USER_PRESENT broadcast sequence.
 * During the window between these two events, samples the orientation sensor
 * to measure the tilt angle the user holds the phone at when unlocking.
 * This is remarkably consistent per-person and inconsistent across people.
 */
@Singleton
class UnlockPatternCollector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val orientSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val buffer          = ConcurrentLinkedQueue<UnlockEvent>()
    private val MAX_BUFFER      = 50

    @Volatile private var screenOnTimeMs = 0L
    @Volatile private var currentTiltDeg = 0f

    private val _dataFlow = MutableStateFlow<List<UnlockEvent>>(emptyList())
    val dataFlow: StateFlow<List<UnlockEvent>> = _dataFlow.asStateFlow()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON  -> {
                    screenOnTimeMs = System.currentTimeMillis()
                    sensorManager.registerListener(
                        this@UnlockPatternCollector, orientSensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Screen is fully unlocked
                    sensorManager.unregisterListener(this@UnlockPatternCollector)
                    val wakeToFirstTapMs = System.currentTimeMillis() - screenOnTimeMs
                    if (buffer.size < MAX_BUFFER) {
                        buffer.add(UnlockEvent(
                            timestampMs      = System.currentTimeMillis(),
                            tiltAngleDeg     = currentTiltDeg,
                            wakeToFirstTapMs = wakeToFirstTapMs,
                            unlockMethod     = UnlockMethod.UNKNOWN  // Can't detect method directly
                        ))
                    }
                    _dataFlow.tryEmit(buffer.toList())
                }
                Intent.ACTION_SCREEN_OFF -> {
                    sensorManager.unregisterListener(this@UnlockPatternCollector)
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenReceiver, filter)
    }

    fun stop() {
        try { context.unregisterReceiver(screenReceiver) } catch (e: Exception) { }
        sensorManager.unregisterListener(this)
        buffer.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            // Tilt = angle from vertical (0° = flat on table, 90° = held upright)
            currentTiltDeg = Math.toDegrees(
                atan2(sqrt(x * x + y * y).toDouble(), z.toDouble())
            ).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    fun drainBuffer(): List<UnlockEvent> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    fun getCurrentBuffer(): List<UnlockEvent> = buffer.toList()
}

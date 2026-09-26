package com.shadowself.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.shadowself.domain.model.MotionSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Collects accelerometer + gyroscope data for gait fingerprinting.
 *
 * Sampling rate: SENSOR_DELAY_GAME (~50Hz) gives enough resolution to
 * capture step frequency (typically 1–3 Hz) and micro-movement signatures
 * without excessive battery drain.
 *
 * The fused magnitude √(x²+y²+z²) is pre-computed here to avoid doing
 * it repeatedly in the feature extraction layer.
 */
@Singleton
class AccelerometerCollector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val buffer  = ConcurrentLinkedQueue<MotionSample>()
    private val MAX_BUFFER = 3000  // ~60s at 50Hz

    // Latest gyro reading — fused with next accel event
    @Volatile private var lastGyroX = 0f
    @Volatile private var lastGyroY = 0f
    @Volatile private var lastGyroZ = 0f

    private val _dataFlow = MutableStateFlow<List<MotionSample>>(emptyList())
    val dataFlow: StateFlow<List<MotionSample>> = _dataFlow.asStateFlow()

    fun start() {
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        buffer.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGyroX = event.values[0]
                lastGyroY = event.values[1]
                lastGyroZ = event.values[2]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x    = event.values[0]
                val y    = event.values[1]
                val z    = event.values[2]
                val mag  = sqrt(x * x + y * y + z * z)

                if (buffer.size < MAX_BUFFER) {
                    buffer.add(MotionSample(
                        timestampMs = System.currentTimeMillis(),
                        accelX = x, accelY = y, accelZ = z,
                        gyroX  = lastGyroX,
                        gyroY  = lastGyroY,
                        gyroZ  = lastGyroZ,
                        magnitude = mag
                    ))
                }
                // Emit every 50 samples to avoid flooding the flow
                if (buffer.size % 50 == 0) {
                    _dataFlow.tryEmit(buffer.toList())
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* ignored */ }

    fun drainBuffer(): List<MotionSample> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    fun getCurrentBuffer(): List<MotionSample> = buffer.toList()
}

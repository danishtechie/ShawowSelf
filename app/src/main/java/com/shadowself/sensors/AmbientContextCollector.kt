package com.shadowself.sensors

import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.BatteryManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shadowself.domain.model.AmbientSnapshot
import com.shadowself.domain.model.LocationZone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures ambient context: light level, time, location zone, battery state.
 *
 * Location is resolved to a ZONE (HOME/WORK/OTHER) via geofence comparison
 * against saved home/work coordinates. Raw GPS coordinates are never stored.
 *
 * HOME and WORK zones are registered during onboarding and stored as
 * encrypted geofence circles (lat/lng + 200m radius) in EncryptedSharedPreferences.
 */
@Singleton
class AmbientContextCollector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager  = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val fusedLocation  = LocationServices.getFusedLocationProviderClient(context)

    @Volatile private var currentLux       = 0f
    @Volatile private var currentZone      = LocationZone.UNKNOWN
    @Volatile private var isCharging       = false
    @Volatile private var batteryPct       = 100

    private val _dataFlow = MutableStateFlow<AmbientSnapshot>(buildSnapshot())
    val dataFlow: StateFlow<AmbientSnapshot> = _dataFlow.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPct  = if (scale > 0) (level * 100 / scale) else 100
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            isCharging  = plugged != 0
        }
    }

    fun start() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startLocationPolling()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try { context.unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun startLocationPolling() {
        try {
            fusedLocation.getCurrentLocation(Priority.PRIORITY_LOW_POWER, null)
                .addOnSuccessListener { location ->
                    location?.let { currentZone = resolveZone(it.latitude, it.longitude) }
                }
        } catch (e: SecurityException) {
            currentZone = LocationZone.UNKNOWN
        }
    }

    /**
     * Compares current coordinates to saved home/work geofence centres.
     * Returns UNKNOWN if location permission isn't granted or zones not set up.
     */
    private fun resolveZone(lat: Double, lng: Double): LocationZone {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context, "shadowself_zones",
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val homeLat = prefs.getFloat("home_lat", Float.MIN_VALUE).toDouble()
        val homeLng = prefs.getFloat("home_lng", Float.MIN_VALUE).toDouble()
        val workLat = prefs.getFloat("work_lat", Float.MIN_VALUE).toDouble()
        val workLng = prefs.getFloat("work_lng", Float.MIN_VALUE).toDouble()

        return when {
            homeLat != Float.MIN_VALUE.toDouble() &&
                haversineMeters(lat, lng, homeLat, homeLng) < 200 -> LocationZone.HOME
            workLat != Float.MIN_VALUE.toDouble() &&
                haversineMeters(lat, lng, workLat, workLng) < 200 -> LocationZone.WORK
            homeLat != Float.MIN_VALUE.toDouble() ||
                workLat != Float.MIN_VALUE.toDouble()              -> LocationZone.OTHER
            else -> LocationZone.UNKNOWN
        }
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R  = 6_371_000.0
        val dL = Math.toRadians(lat2 - lat1)
        val dG = Math.toRadians(lng2 - lng1)
        val a  = Math.sin(dL / 2).let { it * it } +
                 Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                 Math.sin(dG / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun getCurrentSnapshot(): AmbientSnapshot = buildSnapshot()

    private fun buildSnapshot(): AmbientSnapshot {
        val cal = Calendar.getInstance()
        return AmbientSnapshot(
            timestampMs  = System.currentTimeMillis(),
            lightLux     = currentLux,
            hourOfDay    = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek    = cal.get(Calendar.DAY_OF_WEEK),
            locationZone = currentZone,
            isCharging   = isCharging,
            batteryPct   = batteryPct
        )
    }
}

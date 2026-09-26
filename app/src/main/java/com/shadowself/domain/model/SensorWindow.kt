package com.shadowself.domain.model

data class SensorWindow(
    val timestampMs:    Long,
    val typingEvents:   List<TypingEvent>,
    val touchEvents:    List<TouchEvent>,
    val motionSamples:  List<MotionSample>,
    val appUsageEvents: List<AppUsageEvent>,
    val ambientData:    AmbientSnapshot,
    val unlockEvents:   List<UnlockEvent>
) {
    val hasMinimumData: Boolean get() =
        typingEvents.size  >= 5  ||
        touchEvents.size   >= 3  ||
        motionSamples.size >= 30
}

data class TypingEvent(
    val timestampMs:    Long,
    val keyCode:        Int,
    val dwellTimeMs:    Long,
    val flightTimeMs:   Long,
    val typingSpeedWpm: Float
)

data class TouchEvent(
    val timestampMs: Long,
    val action:      TouchAction,
    val x: Float, val y: Float,
    val pressure: Float, val size: Float,
    val velocityX: Float, val velocityY: Float
)

enum class TouchAction { DOWN, MOVE, UP, SCROLL, FLING }

data class MotionSample(
    val timestampMs: Long,
    val accelX: Float, val accelY: Float, val accelZ: Float,
    val gyroX: Float,  val gyroY: Float,  val gyroZ: Float,
    val magnitude: Float
)

data class AppUsageEvent(
    val timestampMs: Long,
    val packageHash: String,
    val durationMs:  Long,
    val category:    AppCategory
)

enum class AppCategory {
    COMMUNICATION, SOCIAL, PRODUCTIVITY, ENTERTAINMENT,
    BROWSER, SYSTEM, FINANCE, OTHER
}

data class AmbientSnapshot(
    val timestampMs:  Long,
    val lightLux:     Float,
    val hourOfDay:    Int,
    val dayOfWeek:    Int,
    val locationZone: LocationZone,
    val isCharging:   Boolean,
    val batteryPct:   Int
)

enum class LocationZone { HOME, WORK, OTHER, UNKNOWN }

data class UnlockEvent(
    val timestampMs:      Long,
    val tiltAngleDeg:     Float,
    val wakeToFirstTapMs: Long,
    val unlockMethod:     UnlockMethod
)

enum class UnlockMethod { PIN, PATTERN, FINGERPRINT, FACE, SMART_LOCK, UNKNOWN }

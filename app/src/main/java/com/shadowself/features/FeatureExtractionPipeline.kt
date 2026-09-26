package com.shadowself.features

import com.shadowself.domain.model.AppUsageEvent
import com.shadowself.domain.model.MotionSample
import com.shadowself.domain.model.SensorWindow
import com.shadowself.domain.model.TouchEvent
import com.shadowself.domain.model.TouchAction
import com.shadowself.domain.model.TypingEvent
import com.shadowself.model.AnomalyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * FeatureExtractionPipeline
 *
 * Transforms raw SensorWindow data into a fixed-size float array
 * suitable for TFLite inference.
 *
 * Output vector size: 64 features
 *   [0–11]  Typing features        (12 values)
 *   [12–23] Touch biometric features (12 values)
 *   [24–39] Motion/gait features   (16 values)
 *   [40–47] App usage features     (8 values)
 *   [48–55] Ambient context        (8 values)
 *   [56–63] Unlock pattern         (8 values)
 *
 * All values are normalised to 0–1 before being passed to the model.
 */
@Singleton
class FeatureExtractionPipeline @Inject constructor(
    private val anomalyEngine: AnomalyEngine
) {
    companion object {
        const val FEATURE_VECTOR_SIZE = 64
    }

    suspend fun process(window: SensorWindow) = withContext(Dispatchers.Default) {
        if (!window.hasMinimumData) return@withContext

        val features = FloatArray(FEATURE_VECTOR_SIZE)
        var idx = 0

        // ── Typing features [0–11] ────────────────────────────────────────────
        idx = extractTypingFeatures(window.typingEvents, features, idx)

        // ── Touch features [12–23] ────────────────────────────────────────────
        idx = extractTouchFeatures(window.touchEvents, features, idx)

        // ── Motion/gait features [24–39] ──────────────────────────────────────
        idx = extractMotionFeatures(window.motionSamples, features, idx)

        // ── App usage features [40–47] ────────────────────────────────────────
        idx = extractAppUsageFeatures(window.appUsageEvents, features, idx)

        // ── Ambient context [48–55] ───────────────────────────────────────────
        with(window.ambientData) {
            features[idx++] = (lightLux / 20_000f).coerceIn(0f, 1f)
            features[idx++] = hourOfDay / 23f
            features[idx++] = (dayOfWeek - 1) / 6f
            features[idx++] = locationZone.ordinal / 3f
            features[idx++] = if (isCharging) 1f else 0f
            features[idx++] = batteryPct / 100f
            features[idx++] = if (hourOfDay in 6..22) 1f else 0f  // daytime flag
            features[idx++] = if (dayOfWeek in 2..6) 1f else 0f   // weekday flag
        }

        // ── Unlock pattern [56–63] ────────────────────────────────────────────
        val unlocks = window.unlockEvents
        features[idx++] = unlocks.size / 10f
        if (unlocks.isNotEmpty()) {
            features[idx++] = mean(unlocks.map { it.tiltAngleDeg }) / 90f
            features[idx++] = stdDev(unlocks.map { it.tiltAngleDeg }) / 45f
            features[idx++] = mean(unlocks.map { it.wakeToFirstTapMs.toFloat() }) / 10_000f
            features[idx++] = stdDev(unlocks.map { it.wakeToFirstTapMs.toFloat() }) / 5_000f
            features[idx++] = unlocks.map { it.unlockMethod.ordinal }.average().toFloat() / 5f
            features[idx++] = 0f  // reserved
            features[idx++] = 0f  // reserved
        } else {
            repeat(7) { features[idx++] = 0f }
        }

        // Pass to the TFLite inference engine
        anomalyEngine.runInference(features, window.timestampMs)
    }

    // ── Typing feature extraction ─────────────────────────────────────────────

    private fun extractTypingFeatures(
        events: List<TypingEvent>,
        out: FloatArray,
        startIdx: Int
    ): Int {
        var idx = startIdx
        if (events.isEmpty()) {
            repeat(12) { out[idx++] = 0f }
            return idx
        }
        val dwells  = events.map { it.dwellTimeMs.toFloat() }
        val flights = events.filter { it.flightTimeMs > 0 }.map { it.flightTimeMs.toFloat() }

        out[idx++] = events.size / 200f                        // event count (norm)
        out[idx++] = (mean(dwells) / 300f).coerceIn(0f, 1f)   // mean dwell
        out[idx++] = (stdDev(dwells) / 150f).coerceIn(0f, 1f) // dwell variance
        out[idx++] = if (flights.isNotEmpty())
            (mean(flights) / 500f).coerceIn(0f, 1f) else 0f   // mean flight
        out[idx++] = if (flights.isNotEmpty())
            (stdDev(flights) / 300f).coerceIn(0f, 1f) else 0f // flight variance
        out[idx++] = (events.last().typingSpeedWpm / 150f)
            .coerceIn(0f, 1f)                                  // WPM
        // Key group distribution (5 groups → normalised counts)
        val groupCounts = IntArray(5)
        events.forEach { if (it.keyCode in 1..5) groupCounts[it.keyCode - 1]++ }
        val total = events.size.toFloat()
        for (g in 0..4) out[idx++] = groupCounts[g] / total
        out[idx++] = burstiness(events.map { it.timestampMs })  // rhythmic consistency
        return idx
    }

    // ── Touch feature extraction ──────────────────────────────────────────────

    private fun extractTouchFeatures(
        events: List<TouchEvent>,
        out: FloatArray,
        startIdx: Int
    ): Int {
        var idx = startIdx
        if (events.isEmpty()) {
            repeat(12) { out[idx++] = 0f }
            return idx
        }
        val downs    = events.filter { it.action == TouchAction.DOWN }
        val ups      = events.filter { it.action == TouchAction.UP }
        val moves    = events.filter { it.action == TouchAction.MOVE }

        val pressures = downs.map { it.pressure }
        val sizes     = downs.map { it.size }
        val velocs    = ups.map { sqrt(it.velocityX.pow(2) + it.velocityY.pow(2)) }

        out[idx++] = events.size / 500f
        out[idx++] = if (pressures.isNotEmpty()) mean(pressures) else 0f
        out[idx++] = if (pressures.isNotEmpty()) stdDev(pressures) else 0f
        out[idx++] = if (sizes.isNotEmpty()) mean(sizes) else 0f
        out[idx++] = if (sizes.isNotEmpty()) stdDev(sizes) else 0f
        out[idx++] = if (velocs.isNotEmpty())
            (mean(velocs) / 5000f).coerceIn(0f, 1f) else 0f   // mean fling speed
        out[idx++] = if (velocs.isNotEmpty())
            (stdDev(velocs) / 3000f).coerceIn(0f, 1f) else 0f // fling speed variance
        // Tap-to-move ratio — how "deliberate" vs "scrolly"
        out[idx++] = if (events.isNotEmpty()) downs.size / events.size.toFloat() else 0f
        // Left/right bias
        out[idx++] = if (downs.isNotEmpty())
            downs.filter { it.x < 0.5f }.size / downs.size.toFloat() else 0.5f
        // Top/bottom bias
        out[idx++] = if (downs.isNotEmpty())
            downs.filter { it.y < 0.5f }.size / downs.size.toFloat() else 0.5f
        out[idx++] = moves.size / 500f
        out[idx++] = burstiness(events.map { it.timestampMs })
        return idx
    }

    // ── Motion / gait feature extraction ─────────────────────────────────────

    private fun extractMotionFeatures(
        samples: List<MotionSample>,
        out: FloatArray,
        startIdx: Int
    ): Int {
        var idx = startIdx
        if (samples.isEmpty()) {
            repeat(16) { out[idx++] = 0f }
            return idx
        }
        val mags   = samples.map { it.magnitude }
        val accX   = samples.map { it.accelX }
        val accY   = samples.map { it.accelY }
        val accZ   = samples.map { it.accelZ }
        val gyroX  = samples.map { it.gyroX }
        val gyroY  = samples.map { it.gyroY }
        val gyroZ  = samples.map { it.gyroZ }

        out[idx++] = (mean(mags) / 20f).coerceIn(0f, 1f)          // mean magnitude
        out[idx++] = (stdDev(mags) / 10f).coerceIn(0f, 1f)        // magnitude variance
        out[idx++] = (mean(accX.map { abs(it) }) / 20f).coerceIn(0f, 1f)
        out[idx++] = (mean(accY.map { abs(it) }) / 20f).coerceIn(0f, 1f)
        out[idx++] = (mean(accZ.map { abs(it) }) / 20f).coerceIn(0f, 1f)
        out[idx++] = (stdDev(accX) / 10f).coerceIn(0f, 1f)
        out[idx++] = (stdDev(accY) / 10f).coerceIn(0f, 1f)
        out[idx++] = (stdDev(accZ) / 10f).coerceIn(0f, 1f)
        out[idx++] = (mean(gyroX.map { abs(it) }) / 5f).coerceIn(0f, 1f)
        out[idx++] = (mean(gyroY.map { abs(it) }) / 5f).coerceIn(0f, 1f)
        out[idx++] = (mean(gyroZ.map { abs(it) }) / 5f).coerceIn(0f, 1f)
        // Zero-crossing rate on X axis — captures step cadence
        out[idx++] = zeroCrossingRate(accX).coerceIn(0f, 1f)
        out[idx++] = zeroCrossingRate(accY).coerceIn(0f, 1f)
        // Activity level: high mag variance = walking/running, low = stationary
        val activityLevel = when {
            stdDev(mags) > 5f  -> 1.0f
            stdDev(mags) > 2f  -> 0.5f
            else               -> 0.0f
        }
        out[idx++] = activityLevel
        out[idx++] = burstiness(samples.map { it.timestampMs })
        out[idx++] = samples.size / 1500f   // sample density
        return idx
    }

    // ── App usage feature extraction ──────────────────────────────────────────

    private fun extractAppUsageFeatures(
        events: List<AppUsageEvent>,
        out: FloatArray,
        startIdx: Int
    ): Int {
        var idx = startIdx
        if (events.isEmpty()) {
            repeat(8) { out[idx++] = 0f }
            return idx
        }
        val total = events.sumOf { it.durationMs }.toFloat()
        val countMap = events.groupBy { it.category }.mapValues { it.value.size }
        val durMap   = events.groupBy { it.category }.mapValues {
            it.value.sumOf { e -> e.durationMs }.toFloat()
        }

        out[idx++] = events.size / 20f
        out[idx++] = if (total > 0) (durMap[com.shadowself.domain.model.AppCategory.COMMUNICATION] ?: 0f) / total else 0f
        out[idx++] = if (total > 0) (durMap[com.shadowself.domain.model.AppCategory.SOCIAL] ?: 0f) / total else 0f
        out[idx++] = if (total > 0) (durMap[com.shadowself.domain.model.AppCategory.PRODUCTIVITY] ?: 0f) / total else 0f
        out[idx++] = if (total > 0) (durMap[com.shadowself.domain.model.AppCategory.BROWSER] ?: 0f) / total else 0f
        out[idx++] = if (total > 0) (durMap[com.shadowself.domain.model.AppCategory.FINANCE] ?: 0f) / total else 0f
        // App switching rate (transitions per minute)
        out[idx++] = (events.size / (30f / 60f) / 60f).coerceIn(0f, 1f)
        // Session diversity: how many distinct apps
        out[idx++] = (events.map { it.packageHash }.distinct().size / 10f).coerceIn(0f, 1f)
        return idx
    }

    // ── Math utilities ────────────────────────────────────────────────────────

    private fun mean(values: List<Float>): Float =
        if (values.isEmpty()) 0f else values.sum() / values.size

    private fun stdDev(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val m = mean(values)
        return sqrt(values.map { (it - m).pow(2) }.sum() / values.size)
    }

    private fun zeroCrossingRate(values: List<Float>): Float {
        if (values.size < 2) return 0f
        var crossings = 0
        for (i in 1 until values.size) {
            if ((values[i] > 0) != (values[i - 1] > 0)) crossings++
        }
        return crossings.toFloat() / values.size
    }

    /**
     * Burstiness: how irregular the event timing is.
     * 0 = perfectly regular (metronome), 1 = highly irregular.
     * Based on the coefficient of variation of inter-event intervals.
     */
    private fun burstiness(timestamps: List<Long>): Float {
        if (timestamps.size < 3) return 0f
        val intervals = timestamps.zipWithNext { a, b -> (b - a).toFloat() }
            .filter { it > 0 }
        if (intervals.size < 2) return 0f
        val cv = stdDev(intervals) / mean(intervals)
        return (cv / 3f).coerceIn(0f, 1f)
    }
}

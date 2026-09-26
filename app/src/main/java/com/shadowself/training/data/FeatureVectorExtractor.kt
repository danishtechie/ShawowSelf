package com.shadowself.training.data

import com.shadowself.domain.model.*
import com.shadowself.features.FeatureExtractionPipeline.Companion.FEATURE_VECTOR_SIZE
import kotlin.math.*

/**
 * FeatureVectorExtractor
 *
 * Stateless extractor: converts a SensorWindow into a fixed 64-float vector
 * using the EXACT same feature indices as FeatureExtractionPipeline.
 *
 * Both classes must stay in sync — any change to feature ordering here
 * must be mirrored in FeatureExtractionPipeline and vice versa.
 *
 * Unlike FeatureExtractionPipeline (which runs inline during collection),
 * this class operates in batch mode for the training loop — it returns
 * null instead of zero-padding when a window has insufficient data,
 * so garbage windows are excluded from training rather than corrupting it.
 */
class FeatureVectorExtractor {

    fun extractOrNull(window: SensorWindow): FloatArray? {
        if (!window.hasMinimumData) return null
        return try {
            extract(window)
        } catch (e: Exception) {
            null  // Malformed window — skip silently
        }
    }

    fun extract(window: SensorWindow): FloatArray {
        val v = FloatArray(FEATURE_VECTOR_SIZE)
        var i = 0

        // ── [0–11] Typing ─────────────────────────────────────────────────────
        i = typingFeatures(window.typingEvents, v, i)

        // ── [12–23] Touch ─────────────────────────────────────────────────────
        i = touchFeatures(window.touchEvents, v, i)

        // ── [24–39] Motion/gait ───────────────────────────────────────────────
        i = motionFeatures(window.motionSamples, v, i)

        // ── [40–47] App usage ─────────────────────────────────────────────────
        i = appUsageFeatures(window.appUsageEvents, v, i)

        // ── [48–55] Ambient context ───────────────────────────────────────────
        with(window.ambientData) {
            v[i++] = (lightLux / 20_000f).coerceIn(0f, 1f)
            v[i++] = hourOfDay / 23f
            v[i++] = (dayOfWeek - 1) / 6f
            v[i++] = locationZone.ordinal / 3f
            v[i++] = if (isCharging) 1f else 0f
            v[i++] = batteryPct / 100f
            v[i++] = if (hourOfDay in 6..22) 1f else 0f
            v[i++] = if (dayOfWeek in 2..6) 1f else 0f
        }

        // ── [56–63] Unlock pattern ────────────────────────────────────────────
        val ul = window.unlockEvents
        v[i++] = (ul.size / 10f).coerceIn(0f, 1f)
        if (ul.isNotEmpty()) {
            v[i++] = (mean(ul.map { it.tiltAngleDeg }) / 90f).coerceIn(0f, 1f)
            v[i++] = (stdDev(ul.map { it.tiltAngleDeg }) / 45f).coerceIn(0f, 1f)
            v[i++] = (mean(ul.map { it.wakeToFirstTapMs.toFloat() }) / 10_000f).coerceIn(0f, 1f)
            v[i++] = (stdDev(ul.map { it.wakeToFirstTapMs.toFloat() }) / 5_000f).coerceIn(0f, 1f)
            v[i++] = ul.map { it.unlockMethod.ordinal }.average().toFloat() / 5f
            v[i++] = 0f
            v[i++] = 0f
        } else {
            repeat(7) { v[i++] = 0f }
        }

        require(i == FEATURE_VECTOR_SIZE) {
            "Feature vector size mismatch: wrote $i, expected $FEATURE_VECTOR_SIZE"
        }
        return v
    }

    // ── Typing ────────────────────────────────────────────────────────────────

    private fun typingFeatures(events: List<TypingEvent>, v: FloatArray, start: Int): Int {
        var i = start
        if (events.isEmpty()) { repeat(12) { v[i++] = 0f }; return i }
        val dwells  = events.map { it.dwellTimeMs.toFloat() }
        val flights = events.filter { it.flightTimeMs > 0 }.map { it.flightTimeMs.toFloat() }
        v[i++] = (events.size / 200f).coerceIn(0f, 1f)
        v[i++] = (mean(dwells) / 300f).coerceIn(0f, 1f)
        v[i++] = (stdDev(dwells) / 150f).coerceIn(0f, 1f)
        v[i++] = if (flights.isNotEmpty()) (mean(flights) / 500f).coerceIn(0f, 1f) else 0f
        v[i++] = if (flights.isNotEmpty()) (stdDev(flights) / 300f).coerceIn(0f, 1f) else 0f
        v[i++] = (events.last().typingSpeedWpm / 150f).coerceIn(0f, 1f)
        val groups = IntArray(5)
        events.forEach { if (it.keyCode in 1..5) groups[it.keyCode - 1]++ }
        groups.forEach { v[i++] = it / events.size.toFloat() }
        v[i++] = burstiness(events.map { it.timestampMs })
        return i
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    private fun touchFeatures(events: List<TouchEvent>, v: FloatArray, start: Int): Int {
        var i = start
        if (events.isEmpty()) { repeat(12) { v[i++] = 0f }; return i }
        val downs = events.filter { it.action == TouchAction.DOWN }
        val ups   = events.filter { it.action == TouchAction.UP }
        val moves = events.filter { it.action == TouchAction.MOVE }
        val pressures = downs.map { it.pressure }
        val sizes     = downs.map { it.size }
        val velocs    = ups.map { sqrt(it.velocityX.pow(2) + it.velocityY.pow(2)) }
        v[i++] = (events.size / 500f).coerceIn(0f, 1f)
        v[i++] = if (pressures.isNotEmpty()) mean(pressures) else 0f
        v[i++] = if (pressures.isNotEmpty()) stdDev(pressures) else 0f
        v[i++] = if (sizes.isNotEmpty()) mean(sizes) else 0f
        v[i++] = if (sizes.isNotEmpty()) stdDev(sizes) else 0f
        v[i++] = if (velocs.isNotEmpty()) (mean(velocs) / 5000f).coerceIn(0f, 1f) else 0f
        v[i++] = if (velocs.isNotEmpty()) (stdDev(velocs) / 3000f).coerceIn(0f, 1f) else 0f
        v[i++] = if (events.isNotEmpty()) downs.size / events.size.toFloat() else 0f
        v[i++] = if (downs.isNotEmpty()) downs.count { it.x < 0.5f } / downs.size.toFloat() else 0.5f
        v[i++] = if (downs.isNotEmpty()) downs.count { it.y < 0.5f } / downs.size.toFloat() else 0.5f
        v[i++] = (moves.size / 500f).coerceIn(0f, 1f)
        v[i++] = burstiness(events.map { it.timestampMs })
        return i
    }

    // ── Motion/gait ───────────────────────────────────────────────────────────

    private fun motionFeatures(samples: List<MotionSample>, v: FloatArray, start: Int): Int {
        var i = start
        if (samples.isEmpty()) { repeat(16) { v[i++] = 0f }; return i }
        val mags  = samples.map { it.magnitude }
        val accX  = samples.map { it.accelX }
        val accY  = samples.map { it.accelY }
        val accZ  = samples.map { it.accelZ }
        val gyroX = samples.map { it.gyroX }
        val gyroY = samples.map { it.gyroY }
        val gyroZ = samples.map { it.gyroZ }
        v[i++] = (mean(mags) / 20f).coerceIn(0f, 1f)
        v[i++] = (stdDev(mags) / 10f).coerceIn(0f, 1f)
        v[i++] = (mean(accX.map { abs(it) }) / 20f).coerceIn(0f, 1f)
        v[i++] = (mean(accY.map { abs(it) }) / 20f).coerceIn(0f, 1f)
        v[i++] = (mean(accZ.map { abs(it) }) / 20f).coerceIn(0f, 1f)
        v[i++] = (stdDev(accX) / 10f).coerceIn(0f, 1f)
        v[i++] = (stdDev(accY) / 10f).coerceIn(0f, 1f)
        v[i++] = (stdDev(accZ) / 10f).coerceIn(0f, 1f)
        v[i++] = (mean(gyroX.map { abs(it) }) / 5f).coerceIn(0f, 1f)
        v[i++] = (mean(gyroY.map { abs(it) }) / 5f).coerceIn(0f, 1f)
        v[i++] = (mean(gyroZ.map { abs(it) }) / 5f).coerceIn(0f, 1f)
        v[i++] = zeroCrossingRate(accX).coerceIn(0f, 1f)
        v[i++] = zeroCrossingRate(accY).coerceIn(0f, 1f)
        v[i++] = when { stdDev(mags) > 5f -> 1.0f; stdDev(mags) > 2f -> 0.5f; else -> 0.0f }
        v[i++] = burstiness(samples.map { it.timestampMs })
        v[i++] = (samples.size / 1500f).coerceIn(0f, 1f)
        return i
    }

    // ── App usage ─────────────────────────────────────────────────────────────

    private fun appUsageFeatures(events: List<AppUsageEvent>, v: FloatArray, start: Int): Int {
        var i = start
        if (events.isEmpty()) { repeat(8) { v[i++] = 0f }; return i }
        val total  = events.sumOf { it.durationMs }.toFloat()
        val durMap = events.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.durationMs }.toFloat() }
        v[i++] = (events.size / 20f).coerceIn(0f, 1f)
        v[i++] = if (total > 0) (durMap[AppCategory.COMMUNICATION] ?: 0f) / total else 0f
        v[i++] = if (total > 0) (durMap[AppCategory.SOCIAL] ?: 0f) / total else 0f
        v[i++] = if (total > 0) (durMap[AppCategory.PRODUCTIVITY] ?: 0f) / total else 0f
        v[i++] = if (total > 0) (durMap[AppCategory.BROWSER] ?: 0f) / total else 0f
        v[i++] = if (total > 0) (durMap[AppCategory.FINANCE] ?: 0f) / total else 0f
        v[i++] = (events.size / (30f / 60f) / 60f).coerceIn(0f, 1f)
        v[i++] = (events.map { it.packageHash }.distinct().size / 10f).coerceIn(0f, 1f)
        return i
    }

    // ── Stat helpers ──────────────────────────────────────────────────────────

    private fun mean(v: List<Float>)  = if (v.isEmpty()) 0f else v.sum() / v.size
    private fun stdDev(v: List<Float>): Float {
        if (v.size < 2) return 0f
        val m = mean(v)
        return sqrt(v.sumOf { (it - m).toDouble().pow(2) }.toFloat() / v.size)
    }
    private fun zeroCrossingRate(v: List<Float>): Float {
        if (v.size < 2) return 0f
        return v.zipWithNext().count { (a, b) -> (a > 0) != (b > 0) }.toFloat() / v.size
    }
    private fun burstiness(ts: List<Long>): Float {
        if (ts.size < 3) return 0f
        val intervals = ts.zipWithNext { a, b -> (b - a).toFloat() }.filter { it > 0 }
        if (intervals.size < 2) return 0f
        return (stdDev(intervals) / mean(intervals) / 3f).coerceIn(0f, 1f)
    }
}

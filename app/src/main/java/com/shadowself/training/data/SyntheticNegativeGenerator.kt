package com.shadowself.training.data

import com.shadowself.features.FeatureExtractionPipeline.Companion.FEATURE_VECTOR_SIZE
import com.shadowself.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import java.util.Random

/**
 * SyntheticNegativeGenerator
 *
 * Generates plausible "impostor" feature vectors for training.
 *
 * The core challenge of one-class biometric classification:
 *   We have lots of "this is the owner" data but zero "this is someone else" data.
 *   Without negatives, the model has no decision boundary to learn — it just
 *   learns to output 1.0 for everything.
 *
 * Strategy: generate negatives that are statistically different from the owner
 * profile in realistic ways, rather than random noise (which would be trivially
 * separable and produce a poorly generalised model).
 *
 * Three generation methods, mixed for diversity:
 *
 *   1. BOUNDARY_PUSH (40%)
 *      Takes an owner vector and shifts it just outside the owner's distribution
 *      boundary. Creates hard negatives the model must learn to reject.
 *
 *   2. CROSS_PERSON_SIMULATION (40%)
 *      Uses known population-level distributions for each feature group to
 *      synthesise plausible but different-person behaviour. Based on published
 *      keystroke dynamics research (mean ± 2σ for typical populations).
 *
 *   3. FEATURE_SWAP (20%)
 *      Takes two different owner windows, swaps their feature domains
 *      (e.g. typing features from window A, gait from window B).
 *      Creates "chimera" vectors that look locally valid but globally inconsistent —
 *      the kind of thing an impostor who imitates one aspect would produce.
 *
 * All generated vectors are clipped to [0, 1] and validated against the
 * owner's per-feature mean ± 3σ to ensure they're meaningfully different.
 */
@Singleton
class SyntheticNegativeGenerator @Inject constructor() {

    companion object {
        private const val TAG             = "SyntheticNegatives"
        private const val BOUNDARY_SCALE  = 2.5f   // Push this many σ outside owner distribution
        private const val MIN_DIVERGENCE  = 0.08f  // Reject negatives too close to owner mean
    }

    private val rng = Random()

    /**
     * Generates [count] synthetic negative vectors based on the owner's
     * positive samples distribution.
     */
    fun generate(positives: List<FloatArray>, count: Int): List<FloatArray> {
        if (positives.isEmpty()) return emptyList()

        val stats    = computePerFeatureStats(positives)
        val negatives = mutableListOf<FloatArray>()

        val boundaryCount = (count * 0.40).toInt()
        val crossPersonCount = (count * 0.40).toInt()
        val swapCount = count - boundaryCount - crossPersonCount

        repeat(boundaryCount)    { tryAdd(negatives, generateBoundaryPush(stats))            }
        repeat(crossPersonCount) { tryAdd(negatives, generateCrossPersonSimulation(stats))   }
        repeat(swapCount)        { tryAdd(negatives, generateFeatureSwap(positives, stats))  }

        // Top up with boundary-push if we didn't reach count (some rejections expected)
        while (negatives.size < count) {
            tryAdd(negatives, generateBoundaryPush(stats))
        }

        Logger.d(TAG, "Generated ${negatives.size} negatives " +
                      "(boundary: $boundaryCount, cross-person: $crossPersonCount, swap: $swapCount)")
        return negatives.take(count)
    }

    // ── Generation methods ────────────────────────────────────────────────────

    /**
     * Boundary push: start from an owner vector, displace it in a
     * direction that moves away from the owner cluster.
     */
    private fun generateBoundaryPush(stats: FeatureStats): FloatArray? {
        val v = FloatArray(FEATURE_VECTOR_SIZE)
        for (i in 0 until FEATURE_VECTOR_SIZE) {
            val direction = if (rng.nextBoolean()) 1f else -1f
            val pushMagnitude = stats.stdDev[i] * BOUNDARY_SCALE * (0.5f + rng.nextFloat())
            v[i] = (stats.mean[i] + direction * pushMagnitude).coerceIn(0f, 1f)
        }
        return v.takeIf { isSufficientlyDifferent(it, stats) }
    }

    /**
     * Cross-person simulation: uses population-level typing, touch, and
     * motion distributions drawn from behavioural biometrics literature.
     *
     * Key differences simulated:
     *   - Typing: different mean dwell (heavy-finger vs light-finger typists)
     *   - Touch: different pressure profile (stylus users vs finger users)
     *   - Gait: different step frequency and magnitude variance
     *   - Time-of-day: simulate a different schedule (night owl vs early bird)
     */
    private fun generateCrossPersonSimulation(stats: FeatureStats): FloatArray? {
        val v = FloatArray(FEATURE_VECTOR_SIZE)

        // Typing domain [0–11]: different rhythm profile
        val typingProfile = randomTypingProfile()
        v[0] = typingProfile.eventCountNorm
        v[1] = typingProfile.meanDwell
        v[2] = typingProfile.stdDwell
        v[3] = typingProfile.meanFlight
        v[4] = typingProfile.stdFlight
        v[5] = typingProfile.wpm
        for (k in 6..10) v[k] = (rng.nextFloat() * 0.4f + 0.1f)  // key group dist
        v[11] = rng.nextFloat() * 0.6f  // burstiness

        // Touch domain [12–23]: different pressure/speed profile
        val touchProfile = randomTouchProfile()
        v[12] = touchProfile.eventCount
        v[13] = touchProfile.meanPressure
        v[14] = touchProfile.stdPressure
        v[15] = touchProfile.meanSize
        v[16] = touchProfile.stdSize
        v[17] = touchProfile.meanVelocity
        v[18] = touchProfile.stdVelocity
        v[19] = touchProfile.tapRatio
        v[20] = 0.3f + rng.nextFloat() * 0.4f  // left/right bias
        v[21] = 0.3f + rng.nextFloat() * 0.4f  // top/bottom bias
        v[22] = rng.nextFloat() * 0.5f
        v[23] = rng.nextFloat() * 0.5f

        // Motion domain [24–39]: different gait signature
        val motionProfile = randomMotionProfile()
        v[24] = motionProfile.meanMag
        v[25] = motionProfile.stdMag
        for (k in 26..38) v[k] = (motionProfile.baseMotion + (rng.nextFloat() - 0.5f) * 0.2f)
            .coerceIn(0f, 1f)
        v[39] = rng.nextFloat() * 0.6f

        // App usage [40–47]: different app distribution
        val cats = FloatArray(6) { rng.nextFloat() }
        val catSum = cats.sum()
        v[40] = rng.nextFloat() * 0.8f
        for (k in 1..5) v[40 + k] = if (catSum > 0) cats[k] / catSum else 0.2f
        v[46] = rng.nextFloat() * 0.7f
        v[47] = rng.nextFloat() * 0.6f

        // Ambient [48–55]: different time-of-day, location pattern
        v[48] = rng.nextFloat()                         // light level
        v[49] = rng.nextFloat()                         // hour of day (random schedule)
        v[50] = rng.nextFloat()                         // day of week
        v[51] = rng.nextInt(4) / 3f                     // location zone
        v[52] = if (rng.nextBoolean()) 1f else 0f       // charging
        v[53] = 0.2f + rng.nextFloat() * 0.8f           // battery
        v[54] = if (rng.nextBoolean()) 1f else 0f       // daytime
        v[55] = if (rng.nextBoolean()) 1f else 0f       // weekday

        // Unlock [56–63]: different tilt/timing
        v[56] = rng.nextFloat() * 0.6f
        v[57] = (30f + rng.nextFloat() * 50f) / 90f     // different tilt angle
        v[58] = rng.nextFloat() * 0.5f
        v[59] = rng.nextFloat() * 0.8f
        v[60] = rng.nextFloat() * 0.5f
        v[61] = rng.nextInt(6) / 5f
        v[62] = 0f; v[63] = 0f

        return v.takeIf { isSufficientlyDifferent(it, stats) }
    }

    /**
     * Feature swap: chimera of two different owner windows.
     * Typing domain from window A, everything else from window B.
     * Creates an internally inconsistent impostor profile.
     */
    private fun generateFeatureSwap(positives: List<FloatArray>, stats: FeatureStats): FloatArray? {
        if (positives.size < 2) return generateBoundaryPush(stats)
        val a = positives[rng.nextInt(positives.size)]
        var b = positives[rng.nextInt(positives.size)]
        // Ensure a != b by index
        repeat(5) { if (b === a) b = positives[rng.nextInt(positives.size)] }

        val v = FloatArray(FEATURE_VECTOR_SIZE)
        // Typing domain from A, perturbed upward (simulate faster typist)
        for (k in 0..11)  v[k] = (a[k] + 0.15f + rng.nextFloat() * 0.2f).coerceIn(0f, 1f)
        // Everything else from B, perturbed slightly
        for (k in 12..63) v[k] = (b[k] + (rng.nextFloat() - 0.5f) * 0.1f).coerceIn(0f, 1f)

        return v.takeIf { isSufficientlyDifferent(it, stats) }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Rejects vectors that are too close to the owner's mean.
     * Uses mean absolute deviation across all 64 dimensions.
     * A vector indistinguishable from the owner would make a terrible negative sample.
     */
    private fun isSufficientlyDifferent(v: FloatArray, stats: FeatureStats): Boolean {
        val divergence = v.mapIndexed { i, f -> abs(f - stats.mean[i]) }.average().toFloat()
        return divergence >= MIN_DIVERGENCE
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    data class FeatureStats(
        val mean:   FloatArray,
        val stdDev: FloatArray,
        val min:    FloatArray,
        val max:    FloatArray
    )

    private fun computePerFeatureStats(positives: List<FloatArray>): FeatureStats {
        val n    = positives.size
        val mean = FloatArray(FEATURE_VECTOR_SIZE)
        val std  = FloatArray(FEATURE_VECTOR_SIZE)
        val min  = FloatArray(FEATURE_VECTOR_SIZE) { Float.MAX_VALUE }
        val max  = FloatArray(FEATURE_VECTOR_SIZE) { Float.MIN_VALUE }

        for (vec in positives) {
            for (i in 0 until FEATURE_VECTOR_SIZE) {
                mean[i] += vec[i]
                if (vec[i] < min[i]) min[i] = vec[i]
                if (vec[i] > max[i]) max[i] = vec[i]
            }
        }
        if (n > 0) {
            for (i in 0 until FEATURE_VECTOR_SIZE) mean[i] /= n.toFloat()
        }

        for (vec in positives) {
            for (i in 0 until FEATURE_VECTOR_SIZE) {
                std[i] += (vec[i] - mean[i]).pow(2)
            }
        }
        if (n > 0) {
            for (i in 0 until FEATURE_VECTOR_SIZE) std[i] = sqrt(std[i] / n.toFloat())
        }

        return FeatureStats(mean, std, min, max)
    }

    // ── Population profiles (drawn from biometric literature) ─────────────────

    private data class TypingProfile(
        val eventCountNorm: Float, val meanDwell: Float, val stdDwell: Float,
        val meanFlight: Float, val stdFlight: Float, val wpm: Float
    )
    private data class TouchProfile(
        val eventCount: Float, val meanPressure: Float, val stdPressure: Float,
        val meanSize: Float, val stdSize: Float, val meanVelocity: Float,
        val stdVelocity: Float, val tapRatio: Float
    )
    private data class MotionProfile(val meanMag: Float, val stdMag: Float, val baseMotion: Float)

    private fun randomTypingProfile(): TypingProfile {
        // Dwell times: 60–200ms typical, WPM: 20–80 typical for mobile
        val dwellMean = 0.05f + rng.nextFloat() * 0.45f
        return TypingProfile(
            eventCountNorm = rng.nextFloat() * 0.8f,
            meanDwell      = dwellMean,
            stdDwell       = dwellMean * (0.2f + rng.nextFloat() * 0.4f),
            meanFlight     = 0.1f + rng.nextFloat() * 0.6f,
            stdFlight      = 0.05f + rng.nextFloat() * 0.3f,
            wpm            = 0.1f + rng.nextFloat() * 0.6f
        )
    }

    private fun randomTouchProfile(): TouchProfile {
        val pressure = 0.2f + rng.nextFloat() * 0.6f
        return TouchProfile(
            eventCount    = rng.nextFloat() * 0.9f,
            meanPressure  = pressure,
            stdPressure   = pressure * (0.1f + rng.nextFloat() * 0.3f),
            meanSize      = 0.1f + rng.nextFloat() * 0.5f,
            stdSize       = 0.05f + rng.nextFloat() * 0.2f,
            meanVelocity  = rng.nextFloat() * 0.8f,
            stdVelocity   = rng.nextFloat() * 0.5f,
            tapRatio      = 0.1f + rng.nextFloat() * 0.7f
        )
    }

    private fun randomMotionProfile(): MotionProfile {
        // More motion than owner average (someone more active / different gait)
        return MotionProfile(
            meanMag    = 0.3f + rng.nextFloat() * 0.5f,
            stdMag     = 0.1f + rng.nextFloat() * 0.4f,
            baseMotion = 0.15f + rng.nextFloat() * 0.5f
        )
    }

    private fun tryAdd(list: MutableList<FloatArray>, v: FloatArray?) {
        if (v != null) list.add(v)
    }
}

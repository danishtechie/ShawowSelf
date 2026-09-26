package com.shadowself.anomaly

import com.shadowself.features.FeatureExtractionPipeline.Companion.FEATURE_VECTOR_SIZE
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SignalQualityGate
 *
 * Assesses whether a feature vector has enough signal to be worth inferring on.
 *
 * Why this matters:
 *   A phone sitting untouched on a table at 3am generates:
 *     - Zero typing events
 *     - Zero touch events
 *     - Minimal accelerometer variance (just gravity)
 *     - No app usage changes
 *   Feeding this to the model would produce a low confidence score simply
 *   because there's no behavioural signal — NOT because someone else has
 *   the phone. Without a quality gate, idle periods trigger false alerts.
 *
 * Quality score components (each 0.0–1.0, then weighted average):
 *   Typing presence      (weight 0.25): were there any typing events?
 *   Touch presence       (weight 0.25): were there touch interactions?
 *   Motion variance      (weight 0.20): is the phone being held/moved?
 *   Feature non-zero     (weight 0.15): fraction of non-zero feature dims
 *   Domain diversity     (weight 0.15): how many signal domains have data?
 *
 * A window must score >= MINIMUM_QUALITY to proceed to inference.
 * The threshold is deliberately low (0.25) — we only skip truly empty windows.
 *
 * Feature vector layout (must match FeatureExtractionPipeline):
 *   [0]      typing event count (normalised)
 *   [12]     touch event count (normalised)
 *   [24]     motion magnitude mean
 *   [25]     motion magnitude std
 *   [40]     app usage event count
 */
@Singleton
class SignalQualityGate @Inject constructor() {

    companion object {
        const val MINIMUM_QUALITY = 0.25f   // Below this: skip inference

        // Feature indices for quick signal presence checks
        private const val IDX_TYPING_COUNT  = 0
        private const val IDX_TOUCH_COUNT   = 12
        private const val IDX_MOTION_MEAN   = 24
        private const val IDX_MOTION_STD    = 25
        private const val IDX_APP_COUNT     = 40
        private const val IDX_HOUR          = 49   // Hour of day [48–55]

        // Weights for each quality component
        private const val W_TYPING   = 0.25f
        private const val W_TOUCH    = 0.25f
        private const val W_MOTION   = 0.20f
        private const val W_NONZERO  = 0.15f
        private const val W_DOMAINS  = 0.15f

        // Thresholds for "signal present" per domain
        private const val TYPING_PRESENT_THRESHOLD = 0.02f   // >0 events normalised
        private const val TOUCH_PRESENT_THRESHOLD  = 0.01f
        private const val MOTION_STD_THRESHOLD     = 0.03f   // Some variance = being held
    }

    data class QualityAssessment(
        val score:          Float,
        val isAcceptable:   Boolean,
        val reason:         String,
        val typingScore:    Float,
        val touchScore:     Float,
        val motionScore:    Float,
        val nonZeroScore:   Float,
        val diversityScore: Float
    )

    fun assess(features: FloatArray): QualityAssessment {
        require(features.size == FEATURE_VECTOR_SIZE) {
            "Expected $FEATURE_VECTOR_SIZE features, got ${features.size}"
        }

        val typingScore  = computeTypingScore(features)
        val touchScore   = computeTouchScore(features)
        val motionScore  = computeMotionScore(features)
        val nonZeroScore = computeNonZeroScore(features)
        val domainScore  = computeDomainDiversityScore(features)

        val composite = typingScore  * W_TYPING  +
                        touchScore   * W_TOUCH   +
                        motionScore  * W_MOTION  +
                        nonZeroScore * W_NONZERO +
                        domainScore  * W_DOMAINS

        val isAcceptable = composite >= MINIMUM_QUALITY
        val reason = when {
            !isAcceptable && motionScore < 0.1f -> "Phone appears stationary/idle"
            !isAcceptable && typingScore < 0.01f && touchScore < 0.01f ->
                "No interaction detected in this window"
            !isAcceptable -> "Insufficient signal (score=${"%.2f".format(composite)})"
            else          -> "OK (score=${"%.2f".format(composite)})"
        }

        return QualityAssessment(
            score          = composite,
            isAcceptable   = isAcceptable,
            reason         = reason,
            typingScore    = typingScore,
            touchScore     = touchScore,
            motionScore    = motionScore,
            nonZeroScore   = nonZeroScore,
            diversityScore = domainScore
        )
    }

    // ── Component scorers ─────────────────────────────────────────────────────

    /** Normalised typing event count — 0 if no typing, scales to 1.0 at high activity */
    private fun computeTypingScore(f: FloatArray): Float {
        val count = f[IDX_TYPING_COUNT]
        return when {
            count > TYPING_PRESENT_THRESHOLD -> (count * 2f).coerceIn(0f, 1f)
            else                             -> 0f
        }
    }

    /** Touch activity score — 0 if no touches */
    private fun computeTouchScore(f: FloatArray): Float {
        val count = f[IDX_TOUCH_COUNT]
        return when {
            count > TOUCH_PRESENT_THRESHOLD -> (count * 2f).coerceIn(0f, 1f)
            else                            -> 0f
        }
    }

    /**
     * Motion score — distinguishes between:
     *   - Phone on table:        low mean, near-zero std → score near 0
     *   - Phone being held still: moderate mean, low std → score ~0.4
     *   - Phone in motion:        varies →               → score 0.6–1.0
     *
     * We care more about std than mean — the earth's gravity creates a non-zero
     * magnitude even when the phone is stationary (≈9.81 m/s²), so raw mean
     * alone doesn't tell us if the phone is being actively used.
     */
    private fun computeMotionScore(f: FloatArray): Float {
        val mean = f[IDX_MOTION_MEAN]
        val std  = f[IDX_MOTION_STD]
        return when {
            std  > MOTION_STD_THRESHOLD * 3  -> 1.0f   // Active motion
            std  > MOTION_STD_THRESHOLD * 1  -> 0.6f   // Being held
            mean > 0.3f                       -> 0.3f   // Held still
            else                              -> 0.0f   // On a flat surface
        }
    }

    /** Fraction of feature dimensions that are non-zero */
    private fun computeNonZeroScore(f: FloatArray): Float {
        val nonZero = f.count { it > 0.001f }
        return nonZero.toFloat() / FEATURE_VECTOR_SIZE
    }

    /** How many of the 6 signal domains have any data present */
    private fun computeDomainDiversityScore(f: FloatArray): Float {
        var domains = 0
        if (f[IDX_TYPING_COUNT] > TYPING_PRESENT_THRESHOLD) domains++  // Typing
        if (f[IDX_TOUCH_COUNT] > TOUCH_PRESENT_THRESHOLD)   domains++  // Touch
        if (f[IDX_MOTION_STD]  > MOTION_STD_THRESHOLD)      domains++  // Motion
        if (f[IDX_APP_COUNT]   > 0.01f)                      domains++  // App usage
        if (f[IDX_HOUR]        > 0f)                         domains++  // Ambient (always present)
        // Unlock events [56] — present occasionally
        if (f[56] > 0f) domains++
        return domains / 6f
    }
}

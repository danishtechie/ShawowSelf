package com.shadowself.anomaly

/**
 * Domain models for the anomaly detection engine.
 */

// ── Per-inference result ───────────────────────────────────────────────────────

data class InferenceResult(
    val timestampMs: Long,
    val rawScore:    Float,                    // Model output [0.0 – 1.0]
    val threshold:   Float,                    // Threshold at time of inference
    val quality:     SignalQualityGate.QualityAssessment,
    val verdict:     WindowVerdict.ScoreClass  // How this individual score classifies
)

// ── Window-level verdict ───────────────────────────────────────────────────────

data class WindowVerdict(
    val alertLevel:    AlertLevel,
    val averageScore:  Float,
    val weightedScore: Float,
    val lowCount:      Int,     // Windows below threshold in current ring
    val totalCount:    Int,     // Windows in ring
    val shouldAlert:   Boolean, // True only on ALERT level transition
    val reason:        String
) {
    enum class AlertLevel {
        NONE,        // All good
        WATCH,       // Starting to look suspicious — monitor closely
        SUSPICIOUS,  // Likely not the owner — prepare to alert
        ALERT;       // Trigger full alert sequence

        /** Returns the next escalation level, capped at ALERT */
        fun next(): AlertLevel = when (this) {
            NONE       -> WATCH
            WATCH      -> SUSPICIOUS
            SUSPICIOUS -> ALERT
            ALERT      -> ALERT
        }
    }

    enum class ScoreClass {
        CONFIDENT_OWNER,   // score ≥ threshold + 0.15
        LIKELY_OWNER,      // score ≥ threshold
        UNCERTAIN,         // score ≥ threshold - 0.10
        LIKELY_INTRUDER    // score < threshold - 0.10
    }
}

// ── Engine state (observed by UI) ─────────────────────────────────────────────

sealed class EngineState {
    object ColdStart  : EngineState()  // No model loaded yet
    object Monitoring : EngineState()  // All normal
    data class Watch(val latestScore: Float)      : EngineState()
    data class Suspicious(val latestScore: Float) : EngineState()
    data class Alert(val latestScore: Float)      : EngineState()
    data class Suppressed(val until: Long)        : EngineState()
}

// ── Anomaly record (persisted to Room) ────────────────────────────────────────

data class AnomalyRecord(
    val timestampMs:   Long,
    val rawScore:      Float,
    val threshold:     Float,
    val windowVerdict: WindowVerdict,
    val quality:       Float,
    var feedback:      Feedback = Feedback.NONE,
    var isTruePositive: Boolean = false
) {
    enum class Feedback { NONE, FALSE_ALARM, TRUE_POSITIVE }
}

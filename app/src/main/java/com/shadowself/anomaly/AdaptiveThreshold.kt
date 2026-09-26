package com.shadowself.anomaly

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdaptiveThreshold
 *
 * Manages the decision boundary between "owner" and "not owner".
 *
 * The threshold has two layers of adaptation:
 *
 *   SHORT-TERM: Exponential moving average (EMA) of recent scores.
 *   The owner's confidence naturally varies — typing while walking
 *   scores lower than typing while sitting. If the EMA drifts
 *   significantly below the threshold (owner's baseline is legitimately
 *   lower than expected), the threshold gently follows it downward.
 *   This prevents the model from crying wolf every time the owner
 *   has an unusual-but-normal session (hospital waiting room, bus, etc).
 *
 *   LONG-TERM: Direct adjustments from owner feedback.
 *   False alarm → threshold moves down by FEEDBACK_STEP (owner confirmed
 *   this IS their behaviour, so we should accept lower scores).
 *   True positive → threshold moves up by FEEDBACK_STEP (owner confirmed
 *   an intrusion, so we should be stricter).
 *
 * Hard bounds: [MIN_THRESHOLD, MAX_THRESHOLD]
 *   Min = 0.35: below this, the model accepts almost anything — useless.
 *   Max = 0.92: above this, the model is so strict even normal owner
 *   variation would trigger alerts continuously.
 *
 * Drift protection: if EMA drift would move the threshold more than
 * DRIFT_CAP per week, it's capped. This prevents a gradual "boiling frog"
 * attack where an impostor slowly trains the threshold down over days.
 *
 * All state is persisted in EncryptedSharedPreferences — survives restarts.
 */
@Singleton
class AdaptiveThreshold @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG             = "AdaptiveThreshold"

        // Default starting threshold — tuned to minimise false positives
        // at the cost of slightly lower recall
        const val DEFAULT_THRESHOLD       = 0.65f
        const val MIN_THRESHOLD           = 0.35f
        const val MAX_THRESHOLD           = 0.92f

        // EMA smoothing factor α — lower = slower to react to score drift
        // α = 0.05 means ~20 windows (5 minutes) to update the EMA meaningfully
        private const val EMA_ALPHA       = 0.05f

        // How much feedback adjusts the threshold per event
        private const val FALSE_ALARM_STEP  = 0.025f   // Relax (lower threshold)
        private const val TRUE_POS_STEP     = 0.035f   // Tighten (raise threshold)

        // EMA is allowed to pull threshold at most this far per 7-day window
        private const val DRIFT_CAP_7_DAYS = 0.08f
        private const val DRIFT_WINDOW_MS   = 7L * 24 * 60 * 60 * 1000

        // EMA only pulls threshold when it consistently scores BELOW this gap
        private const val EMA_PULL_GAP    = 0.10f
        private const val EMA_PULL_STEP   = 0.005f   // Tiny step per observation

        // Prefs keys
        private const val KEY_THRESHOLD   = "threshold"
        private const val KEY_EMA         = "ema"
        private const val KEY_DRIFT_START = "drift_start_ms"
        private const val KEY_DRIFT_USED  = "drift_used"
        private const val KEY_FA_COUNT    = "false_alarm_count"
        private const val KEY_TP_COUNT    = "true_pos_count"
    }

    @Volatile private var _current       = DEFAULT_THRESHOLD
    @Volatile private var emaScore       = DEFAULT_THRESHOLD  // EMA of recent owner scores
    @Volatile private var driftStartMs   = 0L
    @Volatile private var driftUsed      = 0f
    @Volatile private var falseAlarmCount = 0
    @Volatile private var truePosCount   = 0

    val current: Float get() = _current

    // Derived stats for UI display
    val falseAlarms:   Int get() = falseAlarmCount
    val truePositives: Int get() = truePosCount
    val ema:           Float get() = emaScore

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "shadowself_threshold", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Logger.e(TAG, "EncryptedSharedPreferences failed — using plain prefs: ${e.message}")
            context.getSharedPreferences("shadowself_threshold_plain", Context.MODE_PRIVATE)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun restoreFromPrefs() {
        _current       = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        emaScore       = prefs.getFloat(KEY_EMA, DEFAULT_THRESHOLD)
        driftStartMs   = prefs.getLong(KEY_DRIFT_START, System.currentTimeMillis())
        driftUsed      = prefs.getFloat(KEY_DRIFT_USED, 0f)
        falseAlarmCount = prefs.getInt(KEY_FA_COUNT, 0)
        truePosCount   = prefs.getInt(KEY_TP_COUNT, 0)
        Logger.d(TAG, "Restored threshold=$_current ema=$emaScore " +
                      "FA=$falseAlarmCount TP=$truePosCount")
    }

    private fun persist() {
        prefs.edit()
            .putFloat(KEY_THRESHOLD, _current)
            .putFloat(KEY_EMA, emaScore)
            .putLong(KEY_DRIFT_START, driftStartMs)
            .putFloat(KEY_DRIFT_USED, driftUsed)
            .putInt(KEY_FA_COUNT, falseAlarmCount)
            .putInt(KEY_TP_COUNT, truePosCount)
            .apply()
    }

    // ── Score observation (called every inference cycle) ──────────────────────

    /**
     * Updates the EMA and optionally pulls the threshold toward it.
     * Only called for windows that passed the quality gate.
     */
    fun observeScore(rawScore: Float) {
        // Update EMA
        emaScore = EMA_ALPHA * rawScore + (1 - EMA_ALPHA) * emaScore

        // Pull threshold downward if owner's EMA is consistently well below threshold
        // (prevents threshold becoming unreachably high for the legitimate owner)
        val emaBelowThreshold = _current - emaScore
        if (emaBelowThreshold > EMA_PULL_GAP) {
            applyDriftPull(EMA_PULL_STEP)
        }

        // Reset weekly drift window
        val nowMs = System.currentTimeMillis()
        if (nowMs - driftStartMs > DRIFT_WINDOW_MS) {
            driftStartMs = nowMs
            driftUsed    = 0f
        }
    }

    // ── Feedback adjustments ──────────────────────────────────────────────────

    fun onFalseAlarm() {
        falseAlarmCount++
        _current = (_current - FALSE_ALARM_STEP).coerceAtLeast(MIN_THRESHOLD)
        Logger.d(TAG, "False alarm #$falseAlarmCount — threshold → $_current")
        persist()
    }

    fun onTruePositive() {
        truePosCount++
        _current = (_current + TRUE_POS_STEP).coerceAtMost(MAX_THRESHOLD)
        Logger.d(TAG, "True positive #$truePosCount — threshold → $_current")
        persist()
    }

    // ── Manual calibration (called from settings) ─────────────────────────────

    fun manuallySet(value: Float) {
        _current = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        Logger.d(TAG, "Manual threshold set to $_current")
        persist()
    }

    fun reset() {
        _current        = DEFAULT_THRESHOLD
        emaScore        = DEFAULT_THRESHOLD
        driftUsed       = 0f
        driftStartMs    = System.currentTimeMillis()
        falseAlarmCount = 0
        truePosCount    = 0
        persist()
        Logger.d(TAG, "Threshold reset to default")
    }

    // ── Drift cap logic ───────────────────────────────────────────────────────

    private fun applyDriftPull(amount: Float) {
        val remaining = DRIFT_CAP_7_DAYS - driftUsed
        if (remaining <= 0f) return   // Weekly drift budget exhausted

        val actualAmount = minOf(amount, remaining)
        _current  = (_current - actualAmount).coerceAtLeast(MIN_THRESHOLD)
        driftUsed += actualAmount
        persist()
    }

    // ── Debugging / stats ─────────────────────────────────────────────────────

    fun stats(): ThresholdStats = ThresholdStats(
        current         = _current,
        ema             = emaScore,
        defaultValue    = DEFAULT_THRESHOLD,
        minValue        = MIN_THRESHOLD,
        maxValue        = MAX_THRESHOLD,
        falseAlarmCount = falseAlarmCount,
        truePosCount    = truePosCount,
        weeklyDriftUsed = driftUsed,
        weeklyDriftCap  = DRIFT_CAP_7_DAYS
    )
}

data class ThresholdStats(
    val current:         Float,
    val ema:             Float,
    val defaultValue:    Float,
    val minValue:        Float,
    val maxValue:        Float,
    val falseAlarmCount: Int,
    val truePosCount:    Int,
    val weeklyDriftUsed: Float,
    val weeklyDriftCap:  Float
) {
    val driftBudgetPct: Float get() = weeklyDriftUsed / weeklyDriftCap
    fun summary() = "threshold=${"%.3f".format(current)} " +
                    "ema=${"%.3f".format(ema)} " +
                    "FA=$falseAlarmCount TP=$truePosCount"
}

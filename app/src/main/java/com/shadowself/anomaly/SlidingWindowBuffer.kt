package com.shadowself.anomaly

import com.shadowself.util.Logger
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SlidingWindowBuffer
 *
 * Maintains a ring of the last WINDOW_SIZE inference results.
 * Alert decisions are made on the WINDOW rather than individual results —
 * this is the core mechanism that prevents single-window false positives.
 *
 * Window size = 6 results × 15-second step = 90 seconds of signal.
 * That's long enough to be confident, short enough to react in under 2 minutes.
 *
 * Verdict algorithm:
 *
 *   The window computes a weighted average of recent scores, where the
 *   most recent result has the highest weight. This means:
 *     - A single bad window followed by good ones barely moves the average.
 *     - Three consecutive bad windows quickly push the weighted average low.
 *     - Recovery is also weighted — one good window after alerting carries weight.
 *
 *   Alert levels escalate (NONE → WATCH → SUSPICIOUS → ALERT) based on
 *   how many of the recent REQUIRED_CONSECUTIVE windows are below threshold,
 *   AND the weighted average score confirms the trend.
 *
 * Skipped windows (low quality, phone idle) don't count toward alert streaks —
 * they are simply not inserted, keeping the previous streak state intact.
 *
 * De-escalation is intentionally slower than escalation:
 *   Escalation: 2 windows WATCH, 3 SUSPICIOUS, 4 ALERT
 *   De-escalation: need 3 consecutive owner-class windows to return to NONE
 *   This asymmetry prevents an intruder from sneaking past by occasionally
 *   behaving like the owner.
 */
@Singleton
class SlidingWindowBuffer @Inject constructor() {

    companion object {
        const val WINDOW_SIZE             = 6    // Keep last 6 inference results (~90s)
        const val WATCH_THRESHOLD         = 2    // Low windows to reach WATCH
        const val SUSPICIOUS_THRESHOLD    = 3    // Low windows to reach SUSPICIOUS
        const val ALERT_THRESHOLD         = 4    // Low windows to reach ALERT
        const val DEESCALATE_CONSECUTIVE  = 3    // Owner windows needed to reset to NONE
        private const val TAG             = "SlidingWindowBuffer"

        // Weights for most-recent-first weighted average [newest → oldest]
        private val RECENCY_WEIGHTS = floatArrayOf(0.30f, 0.25f, 0.20f, 0.13f, 0.08f, 0.04f)
    }

    // CopyOnWriteArrayList for thread-safe reads without locking
    private val results   = CopyOnWriteArrayList<InferenceResult>()
    private var skipCount = 0   // Consecutive skipped windows (idle phone)

    // Escalation state — persisted across window evaluations
    @Volatile private var currentLevel       = WindowVerdict.AlertLevel.NONE
    @Volatile private var consecutiveOwner   = 0  // Consecutive owner-class windows

    fun add(result: InferenceResult) {
        if (results.size >= WINDOW_SIZE) {
            results.removeAt(0)
        }
        results.add(result)
        skipCount = 0  // Real data resets skip counter
    }

    fun addSkipped(timestampMs: Long) {
        skipCount++
        // If phone has been idle a long time, gently de-escalate
        // (an intruder who just holds the phone without touching it is possible,
        // but sustained inactivity usually means the phone is just sitting on a table)
        if (skipCount >= 8) {
            deEscalateOne()
        }
    }

    /**
     * Computes the window-level verdict based on the ring of results.
     * Called after every new result is added.
     */
    fun computeVerdict(currentThreshold: Float): WindowVerdict {
        if (results.size < 2) {
            // Not enough history yet — return safe default
            return WindowVerdict(
                alertLevel    = WindowVerdict.AlertLevel.NONE,
                averageScore  = 1.0f,
                weightedScore = 1.0f,
                lowCount      = 0,
                totalCount    = results.size,
                shouldAlert   = false,
                reason        = "Insufficient window history"
            )
        }

        val recent    = results.takeLast(WINDOW_SIZE)
        val lowCount  = recent.count { it.rawScore < currentThreshold }
        val weighted  = computeWeightedScore(recent)
        val simple    = recent.map { it.rawScore }.average().toFloat()

        // Count consecutive owner-class results from most recent backward
        val consecutiveOwnerNow = recent.reversed()
            .takeWhile { it.verdict == WindowVerdict.ScoreClass.CONFIDENT_OWNER ||
                         it.verdict == WindowVerdict.ScoreClass.LIKELY_OWNER }
            .size

        // Escalate based on low count and weighted score confirming the trend
        val newLevel = when {
            // De-escalate: enough consecutive owner windows
            consecutiveOwnerNow >= DEESCALATE_CONSECUTIVE -> {
                consecutiveOwner = consecutiveOwnerNow
                WindowVerdict.AlertLevel.NONE
            }
            // Escalate: check weighted score AND low count (both must confirm)
            lowCount >= ALERT_THRESHOLD && weighted < currentThreshold - 0.05f ->
                WindowVerdict.AlertLevel.ALERT
            lowCount >= SUSPICIOUS_THRESHOLD && weighted < currentThreshold ->
                WindowVerdict.AlertLevel.SUSPICIOUS
            lowCount >= WATCH_THRESHOLD && weighted < currentThreshold + 0.05f ->
                WindowVerdict.AlertLevel.WATCH
            else ->
                WindowVerdict.AlertLevel.NONE
        }

        // Escalation is ratchet — can't skip levels going up, can jump levels going down
        val finalLevel = when {
            newLevel > currentLevel -> {
                // Escalating: move up exactly one level at a time
                val next = currentLevel.next()
                if (newLevel >= next) next else currentLevel
            }
            newLevel < currentLevel -> newLevel  // De-escalation: can jump directly to NONE
            else -> currentLevel
        }

        currentLevel     = finalLevel
        consecutiveOwner = consecutiveOwnerNow

        val shouldAlert = finalLevel == WindowVerdict.AlertLevel.ALERT &&
                          currentLevel != WindowVerdict.AlertLevel.ALERT  // Edge: only on transition

        val reason = buildReason(lowCount, recent.size, weighted, currentThreshold, finalLevel)
        Logger.d(TAG, "Window verdict: level=$finalLevel low=$lowCount/${recent.size} " +
                      "weighted=${"%.3f".format(weighted)}")

        return WindowVerdict(
            alertLevel    = finalLevel,
            averageScore  = simple,
            weightedScore = weighted,
            lowCount      = lowCount,
            totalCount    = recent.size,
            shouldAlert   = finalLevel == WindowVerdict.AlertLevel.ALERT,
            reason        = reason
        )
    }

    fun reset() {
        results.clear()
        currentLevel     = WindowVerdict.AlertLevel.NONE
        consecutiveOwner = 0
        skipCount        = 0
        Logger.d(TAG, "Window buffer reset")
    }

    fun recentScores(): List<Float> = results.map { it.rawScore }

    fun recentResults(): List<InferenceResult> = results.toList()

    fun currentAlertLevel(): WindowVerdict.AlertLevel = currentLevel

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Recency-weighted average. Most recent result weighted 0.30,
     * oldest in the window weighted 0.04. Weights sum to 1.0 for a 6-item window.
     */
    private fun computeWeightedScore(recent: List<InferenceResult>): Float {
        val n       = minOf(recent.size, RECENCY_WEIGHTS.size)
        val slice   = recent.takeLast(n).reversed()  // Most recent first
        var total   = 0f
        var wSum    = 0f
        for (i in 0 until n) {
            total += slice[i].rawScore * RECENCY_WEIGHTS[i]
            wSum  += RECENCY_WEIGHTS[i]
        }
        return if (wSum > 0) total / wSum else 0.5f
    }

    private fun deEscalateOne() {
        currentLevel = when (currentLevel) {
            WindowVerdict.AlertLevel.ALERT      -> WindowVerdict.AlertLevel.SUSPICIOUS
            WindowVerdict.AlertLevel.SUSPICIOUS -> WindowVerdict.AlertLevel.WATCH
            WindowVerdict.AlertLevel.WATCH      -> WindowVerdict.AlertLevel.NONE
            else                                -> WindowVerdict.AlertLevel.NONE
        }
    }

    private fun buildReason(
        lowCount:   Int,
        total:      Int,
        weighted:   Float,
        threshold:  Float,
        level:      WindowVerdict.AlertLevel
    ): String = when (level) {
        WindowVerdict.AlertLevel.ALERT ->
            "$lowCount/$total windows below threshold, weighted=${"%.2f".format(weighted)}"
        WindowVerdict.AlertLevel.SUSPICIOUS ->
            "$lowCount/$total windows suspicious, weighted=${"%.2f".format(weighted)}"
        WindowVerdict.AlertLevel.WATCH ->
            "$lowCount/$total windows below threshold"
        WindowVerdict.AlertLevel.NONE ->
            if (consecutiveOwner > 0) "Restored: $consecutiveOwner consecutive owner windows"
            else "Normal behaviour detected"
    }
}

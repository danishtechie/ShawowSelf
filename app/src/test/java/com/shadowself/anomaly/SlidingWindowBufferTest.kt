package com.shadowself.anomaly

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the core SlidingWindowBuffer escalation logic.
 * No Android dependencies — runs on JVM.
 */
class SlidingWindowBufferTest {

    private lateinit var buffer: SlidingWindowBuffer
    private val THRESHOLD = 0.65f
    private var ts = 0L

    @Before
    fun setUp() {
        buffer = SlidingWindowBuffer()
        ts = System.currentTimeMillis()
    }

    private fun makeResult(score: Float): InferenceResult {
        ts += 15_000L
        val verdict = when {
            score >= THRESHOLD + 0.15f -> WindowVerdict.ScoreClass.CONFIDENT_OWNER
            score >= THRESHOLD         -> WindowVerdict.ScoreClass.LIKELY_OWNER
            score >= THRESHOLD - 0.10f -> WindowVerdict.ScoreClass.UNCERTAIN
            else                       -> WindowVerdict.ScoreClass.LIKELY_INTRUDER
        }
        val quality = SignalQualityGate.QualityAssessment(
            score = 0.8f, isAcceptable = true, reason = "test",
            typingScore = 0.9f, touchScore = 0.8f, motionScore = 0.7f,
            nonZeroScore = 0.9f, diversityScore = 0.8f
        )
        return InferenceResult(ts, score, THRESHOLD, quality, verdict)
    }

    @Test fun `normal owner usage stays at NONE`() {
        repeat(6) { buffer.add(makeResult(0.85f)) }
        val verdict = buffer.computeVerdict(THRESHOLD)
        assertEquals(WindowVerdict.AlertLevel.NONE, verdict.alertLevel)
        assertFalse(verdict.shouldAlert)
    }

    @Test fun `two low windows reaches WATCH`() {
        repeat(4) { buffer.add(makeResult(0.85f)) }
        buffer.add(makeResult(0.40f))
        buffer.add(makeResult(0.38f))
        val verdict = buffer.computeVerdict(THRESHOLD)
        assertEquals(WindowVerdict.AlertLevel.WATCH, verdict.alertLevel)
    }

    @Test fun `four consecutive low windows escalates to ALERT`() {
        repeat(2) { buffer.add(makeResult(0.80f)) }
        repeat(4) { buffer.add(makeResult(0.30f)) }
        // Ratchet: escalates one level per evaluation cycle
        // After 2 lows: WATCH, 3 lows: SUSPICIOUS, 4 lows: ALERT
        // We need to call computeVerdict at each step to ratchet properly
        val b2 = SlidingWindowBuffer()
        b2.add(makeResult(0.80f))
        b2.add(makeResult(0.80f))
        b2.add(makeResult(0.30f)); b2.add(makeResult(0.30f))
        b2.computeVerdict(THRESHOLD)  // → WATCH
        b2.add(makeResult(0.28f))
        b2.computeVerdict(THRESHOLD)  // → SUSPICIOUS
        b2.add(makeResult(0.25f))
        val v = b2.computeVerdict(THRESHOLD)
        assertEquals(WindowVerdict.AlertLevel.ALERT, v.alertLevel)
    }

    @Test fun `single low window among many highs stays NONE`() {
        repeat(5) { buffer.add(makeResult(0.88f)) }
        buffer.add(makeResult(0.20f))  // One anomaly
        val verdict = buffer.computeVerdict(THRESHOLD)
        assertEquals(WindowVerdict.AlertLevel.NONE, verdict.alertLevel)
    }

    @Test fun `de-escalates to NONE after three consecutive owner windows`() {
        val b = SlidingWindowBuffer()
        // Escalate to ALERT
        repeat(2) { b.add(makeResult(0.80f)) }
        b.add(makeResult(0.30f)); b.computeVerdict(THRESHOLD)
        b.add(makeResult(0.28f)); b.computeVerdict(THRESHOLD)
        b.add(makeResult(0.25f)); b.computeVerdict(THRESHOLD)  // ALERT

        // Now recover
        b.add(makeResult(0.90f)); b.computeVerdict(THRESHOLD)
        b.add(makeResult(0.88f)); b.computeVerdict(THRESHOLD)
        b.add(makeResult(0.85f))
        val v = b.computeVerdict(THRESHOLD)
        assertEquals(WindowVerdict.AlertLevel.NONE, v.alertLevel)
    }

    @Test fun `reset clears all state`() {
        repeat(6) { buffer.add(makeResult(0.30f)) }
        buffer.computeVerdict(THRESHOLD)
        buffer.reset()
        val v = buffer.computeVerdict(THRESHOLD)
        assertEquals(WindowVerdict.AlertLevel.NONE, v.alertLevel)
        assertTrue(buffer.recentScores().isEmpty())
    }

    @Test fun `weighted score weights recent results more heavily`() {
        // Add alternating: old=high, recent=low
        buffer.add(makeResult(0.90f))
        buffer.add(makeResult(0.90f))
        buffer.add(makeResult(0.90f))
        buffer.add(makeResult(0.20f))
        buffer.add(makeResult(0.20f))
        buffer.add(makeResult(0.20f))
        val verdict = buffer.computeVerdict(THRESHOLD)
        // Weighted score should be pulled down significantly more than simple avg
        // Simple avg = (0.9*3 + 0.2*3)/6 = 0.55
        // Weighted: recent 0.2 values carry 0.30+0.25+0.20 = 0.75 weight
        assertTrue("Weighted score should reflect recent low values",
            verdict.weightedScore < verdict.averageScore)
    }
}

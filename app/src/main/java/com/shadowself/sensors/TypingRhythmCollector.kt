package com.shadowself.sensors

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.shadowself.domain.model.TypingEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures inter-key timing without reading actual key content.
 *
 * Uses an AccessibilityService hook to intercept KeyEvents globally.
 * Only timing metadata is stored — never the keyCode itself in raw form.
 * Keys are grouped into 5 positional buckets (top-row, home-row, etc.)
 * to preserve biometric signal while discarding text content.
 *
 * The service must be declared in AndroidManifest.xml:
 *   <service android:name=".sensors.ShadowSelfAccessibilityService"
 *            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *     <intent-filter>
 *       <action android:name="android.accessibilityservice.AccessibilityService"/>
 *     </intent-filter>
 *     <meta-data android:name="android.accessibilityservice"
 *                android:resource="@xml/accessibility_service_config"/>
 *   </service>
 */
@Singleton
class TypingRhythmCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val buffer = ConcurrentLinkedQueue<TypingEvent>()
    private val MAX_BUFFER = 500

    // Key-down timestamps for dwell time calculation
    private val keyDownTimes = HashMap<Int, Long>()
    private var lastKeyUpTimeMs = 0L

    // Rolling window for WPM estimation (last 5 key timings)
    private val recentFlightTimes = ArrayDeque<Long>(5)

    private val _dataFlow = MutableStateFlow<List<TypingEvent>>(emptyList())
    val dataFlow: StateFlow<List<TypingEvent>> = _dataFlow.asStateFlow()

    private var isActive = false

    fun start() {
        isActive = true
        // Actual event delivery comes via ShadowSelfAccessibilityService.onKeyEvent()
        // which calls this collector's onKeyEvent() directly
    }

    fun stop() {
        isActive = false
        keyDownTimes.clear()
        recentFlightTimes.clear()
    }

    /** Called by ShadowSelfAccessibilityService for every KeyEvent */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isActive) return false

        val nowMs = System.currentTimeMillis()
        val groupedCode = groupKeyCode(event.keyCode)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyDownTimes[event.keyCode] = nowMs

            KeyEvent.ACTION_UP -> {
                val downTime  = keyDownTimes.remove(event.keyCode) ?: return false
                val dwellMs   = nowMs - downTime
                val flightMs  = if (lastKeyUpTimeMs > 0) downTime - lastKeyUpTimeMs else 0L

                // Maintain rolling window of 5 flight times
                if (recentFlightTimes.size >= 5) recentFlightTimes.removeFirst()
                recentFlightTimes.addLast(flightMs)

                val wpm = estimateWpm(recentFlightTimes)

                if (buffer.size < MAX_BUFFER) {
                    buffer.add(TypingEvent(
                        timestampMs    = nowMs,
                        keyCode        = groupedCode,
                        dwellTimeMs    = dwellMs,
                        flightTimeMs   = flightMs,
                        typingSpeedWpm = wpm
                    ))
                }

                lastKeyUpTimeMs = nowMs
                emitCurrent()
            }
        }
        return false  // Never consume — let the event pass through normally
    }

    fun drainBuffer(): List<TypingEvent> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    fun getCurrentBuffer(): List<TypingEvent> = buffer.toList()

    private fun emitCurrent() {
        _dataFlow.tryEmit(buffer.toList())
    }

    /**
     * Maps raw Android keycodes to 5 positional groups.
     * Preserves enough signal for biometric profiling without
     * storing which specific keys were pressed.
     */
    private fun groupKeyCode(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_Q..KeyEvent.KEYCODE_P -> 1  // Top row
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_L -> 2  // Home row
        in KeyEvent.KEYCODE_Z..KeyEvent.KEYCODE_M -> 3  // Bottom row
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 4  // Numbers
        else -> 5                                        // Special / punctuation
    }

    private fun estimateWpm(flightTimes: ArrayDeque<Long>): Float {
        if (flightTimes.size < 2) return 0f
        val avgFlightMs = flightTimes.average()
        if (avgFlightMs <= 0) return 0f
        // Standard: 5 chars per word, convert ms/char to words/min
        return (60_000f / (avgFlightMs.toFloat() * 5f)).coerceIn(0f, 300f)
    }
}

/**
 * AccessibilityService that bridges system KeyEvents to TypingRhythmCollector.
 * Declared separately so Hilt can inject into the collector without the
 * service needing to hold collector state itself.
 */
class ShadowSelfAccessibilityService : AccessibilityService() {

    // Retrieved from the Hilt component at runtime
    private var collector: TypingRhythmCollector? = null

    override fun onServiceConnected() {
        // Hilt entry point injection for AccessibilityService
        val entryPoint = dagger.hilt.android.EntryPointAccessors
            .fromApplication(applicationContext, AccessibilityEntryPoint::class.java)
        collector = entryPoint.typingCollector()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return collector?.onKeyEvent(event) ?: false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { /* not used */ }
    override fun onInterrupt() { collector?.stop() }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface AccessibilityEntryPoint {
    fun typingCollector(): TypingRhythmCollector
}

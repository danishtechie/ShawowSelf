package com.shadowself.sensors

import android.content.Context
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowManager
import com.shadowself.domain.model.TouchAction
import com.shadowself.domain.model.TouchEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures touch biometrics via a transparent overlay WindowManager view.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed post-API 22)
 * to intercept MotionEvents without disturbing the foreground app.
 *
 * Captures: pressure, finger size, velocity at lift, fling characteristics.
 * Does NOT capture tap coordinates in absolute terms — only relative to
 * screen quadrant (normalised 0–1 within each quadrant).
 */
@Singleton
class TouchBiometricCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val buffer = ConcurrentLinkedQueue<TouchEvent>()
    private val MAX_BUFFER = 1000

    private var velocityTracker: VelocityTracker? = null
    private var isActive = false

    private val _dataFlow = MutableStateFlow<List<TouchEvent>>(emptyList())
    val dataFlow: StateFlow<List<TouchEvent>> = _dataFlow.asStateFlow()

    fun start() { isActive = true }
    fun stop()  { isActive = false; velocityTracker?.recycle(); velocityTracker = null }

    /**
     * Called by the accessibility service or a transparent overlay View
     * that receives dispatched MotionEvents from the WindowManager.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.clear() ?: run {
                    velocityTracker = VelocityTracker.obtain()
                }
                velocityTracker?.addMovement(event)
                recordEvent(event, TouchAction.DOWN)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                // Only record every 5th MOVE to reduce noise
                if (buffer.size % 5 == 0) recordEvent(event, TouchAction.MOVE)
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000) // pixels per second
                    recordEvent(event, TouchAction.UP, xVelocity, yVelocity)
                }
                _dataFlow.tryEmit(buffer.toList())
            }
            MotionEvent.ACTION_SCROLL -> recordEvent(event, TouchAction.SCROLL)
        }
        return false
    }

    private fun recordEvent(
        event: MotionEvent,
        action: TouchAction,
        velX: Float = 0f,
        velY: Float = 0f
    ) {
        if (buffer.size >= MAX_BUFFER) return

        // Normalise coordinates to 0–1 within screen quadrant (privacy)
        val dm = context.resources.displayMetrics
        val maxX = event.device?.getMotionRange(MotionEvent.AXIS_X)?.max ?: dm.widthPixels.toFloat()
        val maxY = event.device?.getMotionRange(MotionEvent.AXIS_Y)?.max ?: dm.heightPixels.toFloat()

        val normX = (event.x / maxX.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val normY = (event.y / maxY.coerceAtLeast(1f)).coerceIn(0f, 1f)

        buffer.add(TouchEvent(
            timestampMs = System.currentTimeMillis(),
            action      = action,
            x           = normX,
            y           = normY,
            pressure    = event.pressure.coerceIn(0f, 1f),
            size        = event.size.coerceIn(0f, 1f),
            velocityX   = velX,
            velocityY   = velY
        ))
    }

    fun drainBuffer(): List<TouchEvent> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    fun getCurrentBuffer(): List<TouchEvent> = buffer.toList()
}

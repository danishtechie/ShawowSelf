package com.shadowself.model

import android.content.Context
import com.shadowself.anomaly.AdaptiveThreshold
import com.shadowself.anomaly.AnomalyRecord
import com.shadowself.anomaly.AnomalyRecordStore
import com.shadowself.anomaly.EngineState
import com.shadowself.anomaly.InferenceResult
import com.shadowself.anomaly.SignalQualityGate
import com.shadowself.anomaly.SlidingWindowBuffer
import com.shadowself.anomaly.WindowVerdict
import com.shadowself.response.AlertDispatcher
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AnomalyEngine
 *
 * The inference core of ShadowSelf. Receives a 64-float feature vector
 * every 15 seconds, runs TFLite inference, and makes alert decisions.
 *
 * Key design decisions:
 *
 *   Sliding window buffer  — maintains a ring of the last N inference
 *   results. Alert decisions are made on the WINDOW, not individual
 *   results, preventing single-event false positives.
 *
 *   Signal quality gate  — rejects windows that don't have enough sensor
 *   data to produce a reliable inference. A nearly-idle phone at 3am
 *   should not trigger an alert just because there's no typing data.
 *
 *   Adaptive threshold  — starts at 0.65, drifts based on owner feedback.
 *   Uses exponential moving average of recent scores to detect gradual
 *   drift (owner behaviour changing over time) vs sudden drop (intruder).
 *
 *   Graduated alert levels  — WATCH → SUSPICIOUS → ALERT. Escalates
 *   across consecutive low-confidence windows, de-escalates when owner
 *   scores improve. Prevents both false positives (too trigger-happy)
 *   and false negatives (too slow to react).
 *
 *   State persistence  — engine state (threshold, history) survives
 *   app restarts via EncryptedSharedPreferences.
 */
@Singleton
class AnomalyEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertDispatcher:  AlertDispatcher,
    private val slidingWindow:    SlidingWindowBuffer,
    val adaptiveThreshold: AdaptiveThreshold,
    private val qualityGate:      SignalQualityGate,
    private val recordStore:      AnomalyRecordStore
) {
    companion object {
        private const val TAG               = "AnomalyEngine"
        private const val MODEL_FILE_ASSETS = "shadowself_trainable.tflite"
        private const val MODEL_FILE_LOCAL  = "shadowself_model.tflite"
        private const val INFER_SIGNATURE   = "infer"
    }

    // Public state — observed by UI and WorkManager
    private val _engineState = MutableStateFlow<EngineState>(EngineState.ColdStart)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _lastResult = MutableStateFlow<InferenceResult?>(null)
    val lastResult: StateFlow<InferenceResult?> = _lastResult.asStateFlow()

    private var interpreter: Interpreter? = null
    private var isModelLoaded = false

    // Pre-allocated inference buffers — avoid GC pressure on every call
    private val inputBuffer  = ByteBuffer.allocateDirect(4 * 64).order(ByteOrder.nativeOrder())
    private val outputBuffer = Array(1) { FloatArray(1) }

    init {
        tryLoadModel()
        adaptiveThreshold.restoreFromPrefs()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Entry point called by FeatureExtractionPipeline every 15 seconds.
     * Full pipeline: quality gate → inference → window verdict → alert decision.
     */
    suspend fun runInference(features: FloatArray, windowTimestampMs: Long) {
        if (!isModelLoaded) {
            _engineState.value = EngineState.ColdStart
            return
        }

        // 1. Signal quality gate — skip inference on low-data windows
        val quality = qualityGate.assess(features)
        if (!quality.isAcceptable) {
            Logger.d(TAG, "Window skipped — quality too low: ${quality.reason}")
            slidingWindow.addSkipped(windowTimestampMs)
            return
        }

        // 2. TFLite inference
        val rawScore = runTFLiteInference(features)
        val result   = InferenceResult(
            timestampMs = windowTimestampMs,
            rawScore    = rawScore,
            threshold   = adaptiveThreshold.current,
            quality     = quality,
            verdict     = classifyScore(rawScore)
        )
        _lastResult.value = result
        Logger.d(TAG, "Inference: score=${"%.3f".format(rawScore)} " +
                      "threshold=${"%.3f".format(adaptiveThreshold.current)} " +
                      "verdict=${result.verdict}")

        // 3. Update adaptive threshold's short-term EMA (owner baseline drift)
        adaptiveThreshold.observeScore(rawScore)

        // 4. Push into sliding window
        slidingWindow.add(result)

        // 5. Window-level verdict
        val windowVerdict = slidingWindow.computeVerdict(adaptiveThreshold.current)
        updateEngineState(windowVerdict)

        // 6. Persist to anomaly log
        recordStore.append(AnomalyRecord(
            timestampMs   = windowTimestampMs,
            rawScore      = rawScore,
            threshold     = adaptiveThreshold.current,
            windowVerdict = windowVerdict,
            quality       = quality.score
        ))

        // 7. Alert if warranted
        if (windowVerdict.shouldAlert) {
            alertDispatcher.triggerAlert(
                confidence    = windowVerdict.averageScore,
                timestampMs   = windowTimestampMs,
                alertLevel    = windowVerdict.alertLevel,
                windowHistory = slidingWindow.recentScores()
            )
        }
    }

    // ── Feedback API (called from UI after user responds to alert) ────────────

    /**
     * Owner confirmed alert was a FALSE ALARM — their own behaviour triggered it.
     * Relaxes the threshold and schedules a fine-tuning pass.
     */
    fun onFalseAlarmFeedback(timestampMs: Long) {
        slidingWindow.reset()
        adaptiveThreshold.onFalseAlarm()
        recordStore.markFeedback(timestampMs, isTruePositive = false)
        _engineState.value = EngineState.Monitoring
        Logger.d(TAG, "False alarm feedback — threshold now ${adaptiveThreshold.current}")
    }

    /**
     * Owner confirmed alert was a TRUE POSITIVE — the phone was actually taken.
     * Tightens the threshold and marks this window for training negatives.
     */
    fun onTruePositiveFeedback(timestampMs: Long) {
        adaptiveThreshold.onTruePositive()
        recordStore.markFeedback(timestampMs, isTruePositive = true)
        Logger.d(TAG, "True positive feedback — threshold now ${adaptiveThreshold.current}")
    }

    /**
     * Owner is about to hand phone to someone else deliberately (demo, etc).
     * Suppresses alerts for the given duration.
     */
    fun suppressAlertsFor(durationMs: Long) {
        alertDispatcher.suppressFor(durationMs)
        _engineState.value = EngineState.Suppressed(
            until = System.currentTimeMillis() + durationMs
        )
        Logger.d(TAG, "Alerts suppressed for ${durationMs / 1000}s")
    }

    // ── Model management ──────────────────────────────────────────────────────

    fun reloadModel() {
        val old = interpreter
        interpreter   = null
        isModelLoaded = false
        old?.close()
        tryLoadModel()
        Logger.d(TAG, "Model reloaded — isLoaded=$isModelLoaded")
    }

    val modelVersion: Int get() =
        context.getSharedPreferences("shadowself_model_prefs", Context.MODE_PRIVATE)
            .getInt("model_version", 0)

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun runTFLiteInference(features: FloatArray): Float {
        val interp = interpreter ?: return 0f
        inputBuffer.rewind()
        features.forEach { inputBuffer.putFloat(it) }
        outputBuffer[0][0] = 0f
        try {
            // Try named signature first (trainable model)
            val inputs  = mapOf("x" to inputBuffer)
            val outputs = mutableMapOf<String, Any>("output" to outputBuffer)
            interp.runSignature(inputs, outputs, INFER_SIGNATURE)
        } catch (e: Exception) {
            // Fall back to standard run() for inference-only models
            inputBuffer.rewind()
            interp.run(inputBuffer, outputBuffer)
        }
        return outputBuffer[0][0].coerceIn(0f, 1f)
    }

    private fun classifyScore(score: Float): WindowVerdict.ScoreClass = when {
        score >= adaptiveThreshold.current + 0.15f -> WindowVerdict.ScoreClass.CONFIDENT_OWNER
        score >= adaptiveThreshold.current         -> WindowVerdict.ScoreClass.LIKELY_OWNER
        score >= adaptiveThreshold.current - 0.10f -> WindowVerdict.ScoreClass.UNCERTAIN
        else                                        -> WindowVerdict.ScoreClass.LIKELY_INTRUDER
    }

    private fun updateEngineState(verdict: WindowVerdict) {
        _engineState.value = when (verdict.alertLevel) {
            WindowVerdict.AlertLevel.NONE       -> EngineState.Monitoring
            WindowVerdict.AlertLevel.WATCH      -> EngineState.Watch(verdict.averageScore)
            WindowVerdict.AlertLevel.SUSPICIOUS -> EngineState.Suspicious(verdict.averageScore)
            WindowVerdict.AlertLevel.ALERT      -> EngineState.Alert(verdict.averageScore)
        }
    }

    private fun tryLoadModel() {
        // Prefer trained model in internal storage; fall back to asset bundle
        interpreter = tryLoadFromInternalStorage()
            ?: tryLoadFromAssets()
        isModelLoaded = interpreter != null
        if (isModelLoaded) {
            _engineState.value = EngineState.Monitoring
            Logger.d(TAG, "Model loaded — version $modelVersion")
        } else {
            Logger.d(TAG, "No model found — cold start mode")
        }
    }

    private fun tryLoadFromInternalStorage(): Interpreter? {
        val file = File(context.filesDir, MODEL_FILE_LOCAL)
        if (!file.exists()) return null
        return try {
            Interpreter(file, Interpreter.Options().setNumThreads(2).setUseXNNPACK(true))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load from internal storage: ${e.message}")
            null
        }
    }

    private fun tryLoadFromAssets(): Interpreter? {
        return try {
            val fd    = context.assets.openFd(MODEL_FILE_ASSETS)
            val model = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
            Interpreter(model, buildInterpreterOptions())
        } catch (e: Exception) {
            Logger.d(TAG, "No asset model found: ${e.message}")
            null
        }
    }
    /**
     * Builds TFLite interpreter options with the Flex delegate enabled.
     * The Flex delegate is required because the "train" signature contains
     * gradient ops (ReluGrad, BroadcastGradientArgs) that are only available
     * via SELECT_TF_OPS. The "infer" signature uses only TFLITE_BUILTINS.
     */
    private fun buildInterpreterOptions(): Interpreter.Options {
        val options = Interpreter.Options()
            .setNumThreads(2)
            .setUseXNNPACK(true)
        try {
            // FlexDelegate enables SELECT_TF_OPS needed for training ops
            val flexDelegate = org.tensorflow.lite.flex.FlexDelegate()
            options.addDelegate(flexDelegate)
        } catch (e: Exception) {
            Logger.d(TAG, "FlexDelegate not available — inference-only mode: ${e.message}")
        }
        return options
    }

}

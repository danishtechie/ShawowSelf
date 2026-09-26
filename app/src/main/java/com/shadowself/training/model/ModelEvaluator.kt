package com.shadowself.training.model

import com.shadowself.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ModelEvaluator
 *
 * Computes classification metrics on the validation set:
 *   AUC (ROC)   — primary quality gate. Target > 0.80.
 *   Precision   — of flagged intrusions, how many are real?
 *   Recall      — of real intrusions, how many did we catch?
 *   F1 Score    — harmonic mean of precision and recall
 *   FPR at 95%  — false positive rate when recall is 95% (EER proxy)
 *
 * AUC is the primary metric because it measures discrimination ability
 * independent of the chosen threshold. A model with AUC 0.85 will work
 * well regardless of whether we later tune the threshold for low FPR or
 * high recall — it has the underlying discriminative power.
 *
 * Implementation: trapezoid AUC using 100-point ROC curve sweep.
 * No external library needed — pure Kotlin.
 */
object ModelEvaluator {

    private const val TAG          = "ModelEvaluator"
    private const val ROC_STEPS    = 100    // Threshold sweep resolution
    private const val INFER_SIGN   = "infer"

    fun evaluate(
        interpreter: org.tensorflow.lite.Interpreter,
        features:    Array<FloatArray>,
        labels:      FloatArray
    ): ModelMetrics {
        if (features.isEmpty()) return ModelMetrics.empty()

        // Get raw confidence scores for all samples
        val scores = getScores(interpreter, features)

        val auc       = computeAUC(scores, labels)
        val fprAt95   = computeFprAtRecall(scores, labels, targetRecall = 0.95f)
        val (prec, rec, f1) = computePrecRecF1(scores, labels, threshold = 0.5f)

        val metrics = ModelMetrics(
            auc       = auc,
            precision = prec,
            recall    = rec,
            f1        = f1,
            fprAt95Recall = fprAt95,
            sampleCount   = features.size
        )

        Logger.d(TAG, "Metrics: AUC=${"%.3f".format(auc)} " +
                      "P=${"%.3f".format(prec)} R=${"%.3f".format(rec)} " +
                      "F1=${"%.3f".format(f1)} FPR@95=${"%.3f".format(fprAt95)}")
        return metrics
    }

    // ── Inference pass ────────────────────────────────────────────────────────

    private fun getScores(
        interpreter: org.tensorflow.lite.Interpreter,
        features:    Array<FloatArray>
    ): FloatArray {
        val scores = FloatArray(features.size)
        val inputSize = features[0].size
        features.forEachIndexed { idx, vec ->
            val inputBuf  = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
            vec.forEach { inputBuf.putFloat(it) }
            inputBuf.rewind()
            val outputBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            try {
                val inputs  = mapOf("x" to inputBuf)
                val outputs = mutableMapOf<String, Any>("output" to outputBuf)
                interpreter.runSignature(inputs, outputs, INFER_SIGN)
                outputBuf.rewind()
                scores[idx] = outputBuf.float
            } catch (e: Exception) {
                // Fall back to standard run() if signature not available
                val outputArr = Array(1) { FloatArray(1) }
                interpreter.run(inputBuf, outputArr)
                scores[idx] = outputArr[0][0]
            }
        }
        return scores
    }

    // ── AUC (trapezoid rule over 100-point ROC) ───────────────────────────────

    private fun computeAUC(scores: FloatArray, labels: FloatArray): Float {
        val positives = labels.count { it >= 0.5f }.toFloat()
        val negatives = (labels.size - positives).toFloat()
        if (positives == 0f || negatives == 0f) return 0.5f

        // Build ROC curve: (FPR, TPR) for each threshold
        val roc = mutableListOf<Pair<Float, Float>>()
        for (step in 0..ROC_STEPS) {
            val threshold = step.toFloat() / ROC_STEPS
            var tp = 0f; var fp = 0f
            scores.forEachIndexed { i, s ->
                val predicted = s >= threshold
                val actual    = labels[i] >= 0.5f
                if (predicted && actual)  tp++
                if (predicted && !actual) fp++
            }
            roc.add(Pair(fp / negatives, tp / positives))
        }
        roc.sortBy { it.first }

        // Trapezoid rule AUC
        var auc = 0f
        for (i in 1 until roc.size) {
            val (x1, y1) = roc[i - 1]
            val (x2, y2) = roc[i]
            auc += (x2 - x1) * (y1 + y2) / 2f
        }
        return auc.coerceIn(0f, 1f)
    }

    // ── FPR at target recall ──────────────────────────────────────────────────

    private fun computeFprAtRecall(
        scores:        FloatArray,
        labels:        FloatArray,
        targetRecall:  Float
    ): Float {
        val positives = labels.count { it >= 0.5f }.toFloat()
        val negatives = (labels.size - positives).toFloat()
        if (positives == 0f || negatives == 0f) return 1f

        // Find the highest threshold that still achieves targetRecall
        for (step in ROC_STEPS downTo 0) {
            val threshold = step.toFloat() / ROC_STEPS
            var tp = 0f; var fp = 0f
            scores.forEachIndexed { i, s ->
                if (s >= threshold) {
                    if (labels[i] >= 0.5f) tp++ else fp++
                }
            }
            val recall = tp / positives
            if (recall >= targetRecall) return fp / negatives
        }
        return 1f
    }

    // ── Precision / Recall / F1 at threshold ─────────────────────────────────

    private fun computePrecRecF1(
        scores:    FloatArray,
        labels:    FloatArray,
        threshold: Float
    ): Triple<Float, Float, Float> {
        var tp = 0f; var fp = 0f; var fn = 0f
        scores.forEachIndexed { i, s ->
            val predicted = s >= threshold
            val actual    = labels[i] >= 0.5f
            when {
                predicted && actual  -> tp++
                predicted && !actual -> fp++
                !predicted && actual -> fn++
            }
        }
        val precision = if (tp + fp > 0) tp / (tp + fp) else 0f
        val recall    = if (tp + fn > 0) tp / (tp + fn) else 0f
        val f1        = if (precision + recall > 0)
            2 * precision * recall / (precision + recall) else 0f
        return Triple(precision, recall, f1)
    }
}

data class ModelMetrics(
    val auc:           Float,
    val precision:     Float,
    val recall:        Float,
    val f1:            Float,
    val fprAt95Recall: Float,
    val sampleCount:   Int
) {
    val isAcceptable: Boolean get() = auc >= 0.80f

    fun summary(): String = buildString {
        append("AUC: ${"%.3f".format(auc)}")
        append(" | P: ${"%.3f".format(precision)}")
        append(" | R: ${"%.3f".format(recall)}")
        append(" | F1: ${"%.3f".format(f1)}")
        append(" | FPR@95: ${"%.3f".format(fprAt95Recall)}")
        append(" | n=$sampleCount")
    }

    companion object {
        fun empty() = ModelMetrics(0f, 0f, 0f, 0f, 1f, 0)
    }
}

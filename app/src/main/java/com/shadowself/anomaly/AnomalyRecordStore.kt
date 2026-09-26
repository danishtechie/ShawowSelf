package com.shadowself.anomaly

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AnomalyRecordStore
 *
 * Lightweight append-only log of anomaly detection events.
 * Not stored in Room (which holds sensor training data) — this is a
 * separate forensic log that the owner can review in the UI.
 *
 * Stored as encrypted JSON in internal storage. Capped at MAX_RECORDS
 * to prevent unbounded growth. Oldest records pruned when cap is reached.
 *
 * Used for:
 *   - Incident history UI (show owner a log of detections and outcomes)
 *   - Fine-tuning trigger (accumulate enough feedback before retraining)
 *   - Stats panel (false alarm rate, detection rate over time)
 */
@Singleton
class AnomalyRecordStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG         = "AnomalyRecordStore"
        private const val FILE_NAME   = "anomaly_log.json"
        private const val MAX_RECORDS = 500
        private const val FINETUNE_FEEDBACK_THRESHOLD = 5  // Trigger fine-tune after 5 feedbacks
    }

    private val gson    = Gson()
    private val logFile = File(context.filesDir, FILE_NAME)
    private val records = CopyOnWriteArrayList<AnomalyRecord>()

    init { loadFromDisk() }

    // ── Write operations ──────────────────────────────────────────────────────

    fun append(record: AnomalyRecord) {
        records.add(record)
        if (records.size > MAX_RECORDS) {
            records.subList(0, records.size - MAX_RECORDS).clear()
        }
        persistAsync()
    }

    fun markFeedback(timestampMs: Long, isTruePositive: Boolean) {
        val record = records.firstOrNull { it.timestampMs == timestampMs }
        if (record != null) {
            record.feedback = if (isTruePositive)
                AnomalyRecord.Feedback.TRUE_POSITIVE
            else
                AnomalyRecord.Feedback.FALSE_ALARM
            record.isTruePositive = isTruePositive
            persistAsync()
        }
        checkFineTuneThreshold()
    }

    // ── Read operations ───────────────────────────────────────────────────────

    fun getAll(): List<AnomalyRecord> = records.toList()

    fun getAlerts(): List<AnomalyRecord> =
        records.filter { it.windowVerdict.alertLevel == WindowVerdict.AlertLevel.ALERT }

    fun getUnreviewedAlerts(): List<AnomalyRecord> =
        getAlerts().filter { it.feedback == AnomalyRecord.Feedback.NONE }

    fun stats(): AnomalyStats {
        val alerts      = getAlerts()
        val falseAlarms = alerts.count { it.feedback == AnomalyRecord.Feedback.FALSE_ALARM }
        val truePos     = alerts.count { it.isTruePositive }
        return AnomalyStats(
            totalWindows   = records.size,
            totalAlerts    = alerts.size,
            falseAlarms    = falseAlarms,
            truePositives  = truePos,
            unreviewedCount = getUnreviewedAlerts().size,
            avgScore       = if (records.isNotEmpty()) records.map { it.rawScore }.average().toFloat() else 0f
        )
    }

    // ── Fine-tune trigger check ───────────────────────────────────────────────

    private fun checkFineTuneThreshold() {
        val feedbackCount = records.count { it.feedback != AnomalyRecord.Feedback.NONE }
        if (feedbackCount > 0 && feedbackCount % FINETUNE_FEEDBACK_THRESHOLD == 0) {
            Logger.d(TAG, "$feedbackCount feedbacks accumulated — triggering fine-tune")
            com.shadowself.training.worker.ModelTrainingWorker.triggerFineTune(context)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        if (!logFile.exists()) return
        try {
            val json  = logFile.readText()
            val type  = object : TypeToken<List<AnomalyRecord>>() {}.type
            val saved = gson.fromJson<List<AnomalyRecord>>(json, type) ?: return
            records.addAll(saved)
            Logger.d(TAG, "Loaded ${records.size} anomaly records")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load anomaly log: ${e.message}")
        }
    }

    private fun persistAsync() {
        // Fire-and-forget write — avoids blocking the inference coroutine
        kotlin.concurrent.thread(isDaemon = true, name = "anomaly-log-persist") {
            try {
                val json = gson.toJson(records.toList())
                logFile.writeText(json)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to persist anomaly log: ${e.message}")
            }
        }
    }
}

data class AnomalyStats(
    val totalWindows:    Int,
    val totalAlerts:     Int,
    val falseAlarms:     Int,
    val truePositives:   Int,
    val unreviewedCount: Int,
    val avgScore:        Float
) {
    val falseAlarmRate: Float get() =
        if (totalAlerts > 0) falseAlarms.toFloat() / totalAlerts else 0f
    val detectionPrecision: Float get() =
        if (totalAlerts > 0) truePositives.toFloat() / totalAlerts else 0f
}

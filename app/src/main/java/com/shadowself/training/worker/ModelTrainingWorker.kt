package com.shadowself.training.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shadowself.training.data.TrainingDataset
import com.shadowself.training.model.OnDeviceTrainer
import com.shadowself.training.model.TrainingState
import com.shadowself.training.repository.TrainingRepository
import com.shadowself.model.AnomalyEngine
import com.shadowself.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * ModelTrainingWorker
 *
 * WorkManager worker that orchestrates the full training pipeline.
 * Runs as a long-running worker with foreground notification.
 *
 * Triggered three ways:
 *   1. Automatic weekly schedule (PeriodicWorkRequest, every 7 days)
 *   2. Manual trigger from settings UI (OneTimeWorkRequest)
 *   3. After significant false-alarm feedback (fine-tune path)
 *
 * Output data keys (readable by UI via WorkManager.getWorkInfoById):
 *   KEY_AUC        — final model AUC as Float
 *   KEY_PRECISION  — precision as Float
 *   KEY_RECALL     — recall as Float
 *   KEY_EPOCHS     — epochs run as Int
 *   KEY_SAVED      — whether model was saved (Boolean)
 *   KEY_ERROR      — error message if failed (String)
 *
 * Constraints: only runs on unmetered network (for FCM if needed) +
 * sufficient battery (> 20%) + device idle.
 */
@HiltWorker
class ModelTrainingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams:        WorkerParameters,
    private val dataset:           TrainingDataset,
    private val trainer:           OnDeviceTrainer,
    private val anomalyEngine:     AnomalyEngine,
    private val trainingRepo:      TrainingRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME_PERIODIC = "shadowself_model_training_weekly"
        const val WORK_NAME_MANUAL   = "shadowself_model_training_manual"
        const val WORK_NAME_FINETUNE = "shadowself_model_finetune"

        const val KEY_AUC            = "auc"
        const val KEY_PRECISION      = "precision"
        const val KEY_RECALL         = "recall"
        const val KEY_F1             = "f1"
        const val KEY_EPOCHS         = "epochs_run"
        const val KEY_SAVED          = "model_saved"
        const val KEY_ERROR          = "error"
        const val KEY_MODE           = "training_mode"

        const val MODE_FULL          = "full"
        const val MODE_FINETUNE      = "finetune"

        private const val NOTIF_ID   = 2001
        private const val CHANNEL_ID = "shadowself_training"
        private const val TAG        = "ModelTrainingWorker"

        /** Schedule the weekly automatic training job */
        fun scheduleWeekly(context: Context) {
            val request = PeriodicWorkRequestBuilder<ModelTrainingWorker>(
                7, TimeUnit.DAYS,
                1, TimeUnit.HOURS    // Flex window: fire any time within the last hour
            )
            .setConstraints(buildConstraints())
            .setInputData(workDataOf(KEY_MODE to MODE_FULL))
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,   // Don't reset if already scheduled
                request
            )
            Logger.d(TAG, "Weekly training scheduled")
        }

        /** Trigger an immediate one-time training run (e.g. from settings) */
        fun triggerNow(context: Context): androidx.work.Operation {
            val request = OneTimeWorkRequestBuilder<ModelTrainingWorker>()
                .setConstraints(buildConstraints())
                .setInputData(workDataOf(KEY_MODE to MODE_FULL))
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_MANUAL,
                    androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        /** Trigger fine-tuning after feedback accumulation */
        fun triggerFineTune(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelTrainingWorker>()
                .setInputData(workDataOf(KEY_MODE to MODE_FINETUNE))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_FINETUNE,
                    androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        private fun buildConstraints() = androidx.work.Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()
    }

    override suspend fun doWork(): Result {
        setForeground(buildForegroundInfo("Preparing training data…"))

        val mode = inputData.getString(KEY_MODE) ?: MODE_FULL

        return try {
            when (mode) {
                MODE_FINETUNE -> runFineTune()
                else          -> runFullTraining()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Worker failed: ${e.message}", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    // ── Full training ─────────────────────────────────────────────────────────

    private suspend fun runFullTraining(): Result {
        setForeground(buildForegroundInfo("Checking data availability…"))

        // Guard: need minimum data before training
        val dataCount = trainingRepo.getTrainingDataCount()
        if (dataCount < TrainingDataset.MIN_POSITIVE_SAMPLES) {
            Logger.d(TAG, "Not enough data: $dataCount < ${TrainingDataset.MIN_POSITIVE_SAMPLES}")
            return Result.failure(workDataOf(
                KEY_ERROR to "Need ${TrainingDataset.MIN_POSITIVE_SAMPLES} samples, have $dataCount. " +
                             "Keep using your phone for more data."
            ))
        }

        setForeground(buildForegroundInfo("Building dataset…"))
        val datasetResult = dataset.build(sourceDays = 7)
        if (datasetResult.isFailure) {
            return Result.failure(workDataOf(KEY_ERROR to datasetResult.exceptionOrNull()?.message))
        }
        val ds = datasetResult.getOrThrow()
        Logger.d(TAG, "Dataset: ${ds.positivesCount} pos + ${ds.negativesCount} neg")

        setForeground(buildForegroundInfo("Training model (0/${ModelArchitecture.EPOCHS_FULL})…"))

        // Observe training progress to update notification
        val trainingJob = CoroutineScope(Dispatchers.Default).launch {
            trainer.trainingState.collect { state ->
                if (state is TrainingState.Training) {
                    setForeground(buildForegroundInfo(
                        "Training epoch ${state.epoch}/${state.totalEpochs} " +
                        "— loss ${"%.3f".format(state.trainLoss)}"
                    ))
                }
            }
        }

        val result = trainer.trainFull(ds)
        trainingJob.cancel()

        return when {
            result.isSuccess -> {
                val r = result.getOrThrow()
                // Hot-swap the model in the running AnomalyEngine
                if (r.modelSaved) anomalyEngine.reloadModel()
                // Record training event
                trainingRepo.recordTrainingRun(r.metrics, ds)
                setForeground(buildForegroundInfo("Training complete — AUC ${"%.2f".format(r.metrics.auc)}"))

                Result.success(workDataOf(
                    KEY_AUC       to r.metrics.auc,
                    KEY_PRECISION to r.metrics.precision,
                    KEY_RECALL    to r.metrics.recall,
                    KEY_F1        to r.metrics.f1,
                    KEY_EPOCHS    to r.epochsRun,
                    KEY_SAVED     to r.modelSaved
                ))
            }
            else -> Result.failure(workDataOf(
                KEY_ERROR to result.exceptionOrNull()?.message
            ))
        }
    }

    // ── Fine-tune ─────────────────────────────────────────────────────────────

    private suspend fun runFineTune(): Result {
        setForeground(buildForegroundInfo("Preparing fine-tune data…"))
        val (positives, negatives) = trainingRepo.getFeedbackVectors()
        if (positives.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "No feedback data available for fine-tuning"))
        }

        val result = trainer.fineTune(positives, negatives)
        return if (result.isSuccess) {
            val r = result.getOrThrow()
            anomalyEngine.reloadModel()
            Result.success(workDataOf(
                KEY_AUC to r.metrics.auc,
                KEY_SAVED to true
            ))
        } else {
            Result.failure(workDataOf(KEY_ERROR to result.exceptionOrNull()?.message))
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildForegroundInfo(status: String): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model Training",
                    NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("ShadowSelf — Learning your behaviour")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIF_ID, notification)
    }
}

// Re-export for convenience
private typealias ModelArchitecture = com.shadowself.training.model.ModelArchitecture

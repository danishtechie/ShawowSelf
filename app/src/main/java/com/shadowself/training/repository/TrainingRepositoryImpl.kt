package com.shadowself.training.repository

import android.content.Context
import com.shadowself.data.repository.SensorDataRepository
import com.shadowself.training.data.FeatureVectorExtractor
import com.shadowself.training.data.TrainingDataset
import com.shadowself.training.model.ModelArchitecture
import com.shadowself.training.model.ModelMetrics
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorRepository: SensorDataRepository
) : TrainingRepository {

    private val prefs get() = context.getSharedPreferences("shadowself_training", Context.MODE_PRIVATE)
    private val extractor = FeatureVectorExtractor()

    override suspend fun getTrainingDataCount(): Int =
        sensorRepository.getTrainingDataCount()

    override suspend fun recordTrainingRun(metrics: ModelMetrics, dataset: TrainingDataset.Dataset) {
        val version = prefs.getInt(ModelArchitecture.MODEL_VERSION_KEY, 0) + 1
        prefs.edit()
            .putInt(ModelArchitecture.MODEL_VERSION_KEY, version)
            .putFloat("last_auc", metrics.auc)
            .putFloat("last_precision", metrics.precision)
            .putFloat("last_recall", metrics.recall)
            .putFloat("last_f1", metrics.f1)
            .putInt("last_positives", dataset.positivesCount)
            .putInt("last_negatives", dataset.negativesCount)
            .putLong("last_trained_ms", System.currentTimeMillis())
            .apply()
        Logger.d("TrainingRepo", "Recorded training v$version: AUC=${metrics.auc}")
    }

    override suspend fun getFeedbackVectors(): Pair<List<FloatArray>, List<FloatArray>> {
        val allWindows = sensorRepository.getRecentWindows(500)
        val positives = allWindows
            .filter { it.hasMinimumData }
            .mapNotNull { extractor.extractOrNull(it) }
            .take(100)
        return Pair(positives, emptyList())
    }

    override suspend fun getLastTrainingMetrics(): ModelMetrics? {
        val auc = prefs.getFloat("last_auc", -1f)
        if (auc < 0) return null
        return ModelMetrics(
            auc           = auc,
            precision     = prefs.getFloat("last_precision", 0f),
            recall        = prefs.getFloat("last_recall", 0f),
            f1            = prefs.getFloat("last_f1", 0f),
            fprAt95Recall = 0f,
            sampleCount   = prefs.getInt("last_positives", 0) + prefs.getInt("last_negatives", 0)
        )
    }

    override suspend fun getModelVersion(): Int =
        prefs.getInt(ModelArchitecture.MODEL_VERSION_KEY, 0)
}

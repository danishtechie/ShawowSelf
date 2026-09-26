package com.shadowself.training.repository

import com.shadowself.training.model.ModelMetrics
import com.shadowself.training.data.TrainingDataset

interface TrainingRepository {
    suspend fun getTrainingDataCount(): Int
    suspend fun recordTrainingRun(metrics: ModelMetrics, dataset: TrainingDataset.Dataset)
    suspend fun getFeedbackVectors(): Pair<List<FloatArray>, List<FloatArray>>
    suspend fun getLastTrainingMetrics(): ModelMetrics?
    suspend fun getModelVersion(): Int
}

package com.shadowself.data.repository

import com.shadowself.domain.model.SensorWindow

interface SensorDataRepository {
    suspend fun saveRawWindow(window: SensorWindow)
    suspend fun getTrainingData(sinceDays: Int): List<SensorWindow>
    suspend fun getRecentWindows(limit: Int): List<SensorWindow>
    suspend fun purgeOldData()
    suspend fun getTrainingDataCount(): Int
}

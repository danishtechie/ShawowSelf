package com.shadowself.data.repository

import com.google.gson.Gson
import com.shadowself.data.local.SensorWindowDao
import com.shadowself.data.local.SensorWindowEntity
import com.shadowself.domain.model.SensorWindow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorDataRepositoryImpl @Inject constructor(
    private val dao: SensorWindowDao
) : SensorDataRepository {

    private val gson = Gson()
    private val RETENTION_DAYS = 7L

    override suspend fun saveRawWindow(window: SensorWindow) {
        dao.insert(window.toEntity())
    }

    override suspend fun getTrainingData(sinceDays: Int): List<SensorWindow> {
        val sinceMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(sinceDays.toLong())
        return dao.getWindowsSince(sinceMs).map { it.toDomain() }
    }

    override suspend fun getRecentWindows(limit: Int): List<SensorWindow> =
        dao.getRecent(limit).map { it.toDomain() }

    override suspend fun purgeOldData() {
        val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        dao.purgeOlderThan(cutoffMs)
    }

    override suspend fun getTrainingDataCount(): Int = dao.trainingDataCount()

    private fun SensorWindow.toEntity() = SensorWindowEntity(
        timestampMs        = timestampMs,
        typingEventsJson   = gson.toJson(typingEvents),
        touchEventsJson    = gson.toJson(touchEvents),
        motionSamplesJson  = gson.toJson(motionSamples),
        appUsageEventsJson = gson.toJson(appUsageEvents),
        ambientDataJson    = gson.toJson(ambientData),
        unlockEventsJson   = gson.toJson(unlockEvents)
    )

    private fun SensorWindowEntity.toDomain() = SensorWindow(
        timestampMs    = timestampMs,
        typingEvents   = gson.fromJson(typingEventsJson,   Array<com.shadowself.domain.model.TypingEvent>::class.java).toList(),
        touchEvents    = gson.fromJson(touchEventsJson,    Array<com.shadowself.domain.model.TouchEvent>::class.java).toList(),
        motionSamples  = gson.fromJson(motionSamplesJson,  Array<com.shadowself.domain.model.MotionSample>::class.java).toList(),
        appUsageEvents = gson.fromJson(appUsageEventsJson, Array<com.shadowself.domain.model.AppUsageEvent>::class.java).toList(),
        ambientData    = gson.fromJson(ambientDataJson,    com.shadowself.domain.model.AmbientSnapshot::class.java),
        unlockEvents   = gson.fromJson(unlockEventsJson,   Array<com.shadowself.domain.model.UnlockEvent>::class.java).toList()
    )
}

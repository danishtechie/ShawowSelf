package com.shadowself.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SensorWindowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SensorWindowEntity)

    @Query("SELECT * FROM sensor_windows ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SensorWindowEntity>

    /** Returns windows in the last N days — used for model training */
    @Query("SELECT * FROM sensor_windows WHERE timestampMs > :sinceMs ORDER BY timestampMs ASC")
    suspend fun getWindowsSince(sinceMs: Long): List<SensorWindowEntity>

    /** Purge records older than the retention window (7 days) */
    @Query("DELETE FROM sensor_windows WHERE timestampMs < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long): Int

    @Query("UPDATE sensor_windows SET ownerFeedback = :feedback WHERE timestampMs = :ts")
    suspend fun updateFeedback(ts: Long, feedback: Int)

    /** Count of windows available for training */
    @Query("SELECT COUNT(*) FROM sensor_windows WHERE ownerFeedback != 1")
    suspend fun trainingDataCount(): Int
}

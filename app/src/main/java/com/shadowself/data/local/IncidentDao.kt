package com.shadowself.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IncidentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(entity: IncidentEntity): Long

    @Query("SELECT * FROM incidents ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<IncidentEntity>

    @Query("SELECT * FROM incidents WHERE id = :id")
    suspend fun getById(id: Long): IncidentEntity?

    @Query("UPDATE incidents SET feedback = 1 WHERE id = :id")
    suspend fun markAsFalseAlarm(id: Long)

    @Query("UPDATE incidents SET feedback = 2 WHERE id = :id")
    suspend fun markAsTruePositive(id: Long)

    @Query("UPDATE incidents SET notificationSent = 1 WHERE id = :id")
    suspend fun markNotificationSent(id: Long)

    @Query("UPDATE incidents SET lockdownTriggered = 1 WHERE id = :id")
    suspend fun markLockdownTriggered(id: Long)

    @Query("SELECT COUNT(*) FROM incidents WHERE feedback = 0")
    fun observeUnreviewedCount(): Flow<Int>

    @Query("DELETE FROM incidents WHERE timestampMs < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM incidents")
    suspend fun totalCount(): Int
}

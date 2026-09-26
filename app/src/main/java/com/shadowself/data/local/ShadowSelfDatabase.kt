package com.shadowself.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities     = [SensorWindowEntity::class, IncidentEntity::class],
    version      = 1,
    exportSchema = false
)
@TypeConverters(SensorConverters::class)
abstract class ShadowSelfDatabase : RoomDatabase() {
    abstract fun sensorWindowDao(): SensorWindowDao
    abstract fun incidentDao():     IncidentDao
}

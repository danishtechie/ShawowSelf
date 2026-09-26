package com.shadowself.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted representation of a SensorWindow.
 * Feature vectors are stored as serialised JSON blobs — lightweight
 * and avoids a proliferation of join tables for training data.
 *
 * Retention: 7-day rolling window. Older records purged by WorkManager nightly.
 */
@Entity(tableName = "sensor_windows")
data class SensorWindowEntity(
    @PrimaryKey
    val timestampMs:         Long,
    val typingEventsJson:    String,
    val touchEventsJson:     String,
    val motionSamplesJson:   String,
    val appUsageEventsJson:  String,
    val ambientDataJson:     String,
    val unlockEventsJson:    String,
    val featureVectorJson:   String = "",   // Populated after extraction
    val inferenceScore:      Float  = -1f,  // -1 = not yet inferred
    val isAnomalous:         Boolean = false,
    val ownerFeedback:       Int    = 0     // 0=none, 1=false alarm, 2=confirmed
)

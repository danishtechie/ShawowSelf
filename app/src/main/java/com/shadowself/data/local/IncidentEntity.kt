package com.shadowself.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * IncidentEntity — persisted record of one alert event.
 *
 * Stored in Room DB inside the app's private storage.
 * Photo path points to an AES-256-encrypted JPEG in filesDir/incidents/.
 * GPS coordinates are stored as raw doubles — they are only accessible
 * to the app itself (no cloud sync, no external exposure).
 *
 * feedback values:
 *   0 = owner has not reviewed yet
 *   1 = owner confirmed FALSE ALARM
 *   2 = owner confirmed TRUE POSITIVE (real intrusion)
 */
@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true)
    val id:               Long    = 0,
    val timestampMs:      Long,
    val confidenceScore:  Float,
    val alertLevel:       String  = "ALERT",
    val photoPath:        String? = null,   // Path to encrypted selfie JPEG
    val photoEncrypted:   Boolean = false,
    val latitude:         Double? = null,
    val longitude:        Double? = null,
    val addressLine:      String? = null,
    val windowScoresJson: String  = "[]",   // JSON array of last 6 window scores
    val qualityScore:     Float   = 0f,
    val feedback:         Int     = 0,      // 0=none, 1=false alarm, 2=confirmed
    val notificationSent: Boolean = false,
    val lockdownTriggered:Boolean = false
)

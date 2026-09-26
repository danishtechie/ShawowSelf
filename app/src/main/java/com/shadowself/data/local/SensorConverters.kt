package com.shadowself.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.shadowself.domain.model.*

class SensorConverters {
    private val gson = Gson()

    @TypeConverter fun typingToJson(v: List<TypingEvent>): String = gson.toJson(v)
    @TypeConverter fun jsonToTyping(v: String): List<TypingEvent> =
        gson.fromJson(v, Array<TypingEvent>::class.java).toList()

    @TypeConverter fun touchToJson(v: List<TouchEvent>): String = gson.toJson(v)
    @TypeConverter fun jsonToTouch(v: String): List<TouchEvent> =
        gson.fromJson(v, Array<TouchEvent>::class.java).toList()

    @TypeConverter fun motionToJson(v: List<MotionSample>): String = gson.toJson(v)
    @TypeConverter fun jsonToMotion(v: String): List<MotionSample> =
        gson.fromJson(v, Array<MotionSample>::class.java).toList()

    @TypeConverter fun appToJson(v: List<AppUsageEvent>): String = gson.toJson(v)
    @TypeConverter fun jsonToApp(v: String): List<AppUsageEvent> =
        gson.fromJson(v, Array<AppUsageEvent>::class.java).toList()

    @TypeConverter fun ambientToJson(v: AmbientSnapshot): String = gson.toJson(v)
    @TypeConverter fun jsonToAmbient(v: String): AmbientSnapshot =
        gson.fromJson(v, AmbientSnapshot::class.java)

    @TypeConverter fun unlockToJson(v: List<UnlockEvent>): String = gson.toJson(v)
    @TypeConverter fun jsonToUnlock(v: String): List<UnlockEvent> =
        gson.fromJson(v, Array<UnlockEvent>::class.java).toList()
}

package com.shadowself.response

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shadowself.anomaly.AnomalyRecordStore
import com.shadowself.model.AnomalyEngine
import com.shadowself.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the "False alarm" action from the alert notification.
 * Wires back into AnomalyEngine and AnomalyRecordStore.
 */
@AndroidEntryPoint
class FalseAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var anomalyEngine:      AnomalyEngine
    @Inject lateinit var alertDispatcher:    AlertDispatcher
    @Inject lateinit var anomalyRecordStore: AnomalyRecordStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.shadowself.FALSE_ALARM") return
        val incidentId  = intent.getLongExtra("incidentId", -1L)
        val timestampMs = intent.getLongExtra("timestampMs", System.currentTimeMillis())

        Logger.d("FalseAlarmReceiver", "False alarm for incident #$incidentId")

        CoroutineScope(Dispatchers.IO).launch {
            anomalyEngine.onFalseAlarmFeedback(timestampMs)
            alertDispatcher.onFalseAlarm(incidentId)
            anomalyRecordStore.markFeedback(timestampMs, isTruePositive = false)
        }
    }
}

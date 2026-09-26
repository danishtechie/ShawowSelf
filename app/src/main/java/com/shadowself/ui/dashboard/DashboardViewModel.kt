package com.shadowself.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shadowself.anomaly.AnomalyRecordStore
import com.shadowself.anomaly.AnomalyStats
import com.shadowself.anomaly.EngineState
import com.shadowself.anomaly.ThresholdStats
import com.shadowself.model.AnomalyEngine
import com.shadowself.training.repository.TrainingRepository
import com.shadowself.training.model.TrainingState
import com.shadowself.training.model.OnDeviceTrainer
import com.shadowself.training.worker.ModelTrainingWorker
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DashboardViewModel
 *
 * Single source of truth for the main monitoring screen.
 * Aggregates engine state, threshold stats, anomaly history,
 * training progress, and model version into one UiState.
 *
 * The UI observes [uiState] and renders accordingly — no direct
 * calls from Compose into engine internals.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val anomalyEngine:   AnomalyEngine,
    private val recordStore:     AnomalyRecordStore,
    private val trainingRepo:    TrainingRepository,
    private val trainer:         OnDeviceTrainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeEngineState()
        observeTrainingState()
        refreshStats()
    }

    // ── State observation ──────────────────────────────────────────────────────

    private fun observeEngineState() {
        viewModelScope.launch {
            anomalyEngine.engineState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    engineState     = state,
                    lastScore       = anomalyEngine.lastResult.value?.rawScore,
                    thresholdStats  = anomalyEngine.adaptiveThreshold.stats(),
                    modelVersion    = anomalyEngine.modelVersion
                )
            }
        }
        viewModelScope.launch {
            anomalyEngine.lastResult.collect { result ->
                result?.let {
                    _uiState.value = _uiState.value.copy(lastScore = it.rawScore)
                }
            }
        }
    }

    private fun observeTrainingState() {
        viewModelScope.launch {
            trainer.trainingState.collect { state ->
                _uiState.value = _uiState.value.copy(trainingState = state)
            }
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    fun refreshStats() {
        viewModelScope.launch {
            val anomalyStats    = recordStore.stats()
            val dataCount       = trainingRepo.getTrainingDataCount()
            val lastMetrics     = trainingRepo.getLastTrainingMetrics()
            _uiState.value = _uiState.value.copy(
                anomalyStats    = anomalyStats,
                trainingDataCount = dataCount,
                lastModelAuc    = lastMetrics?.auc,
                isReady         = anomalyEngine.modelVersion > 0
            )
        }
    }

    fun triggerManualTraining() {
        ModelTrainingWorker.triggerNow(context)
    }

    fun suppressAlerts(durationMs: Long) {
        anomalyEngine.suppressAlertsFor(durationMs)
    }

    fun onFalseAlarm(timestampMs: Long) {
        viewModelScope.launch {
            anomalyEngine.onFalseAlarmFeedback(timestampMs)
            refreshStats()
        }
    }

    fun onTruePositive(timestampMs: Long) {
        viewModelScope.launch {
            anomalyEngine.onTruePositiveFeedback(timestampMs)
            refreshStats()
        }
    }
}

// ── UI State ──────────────────────────────────────────────────────────────────

data class DashboardUiState(
    val engineState:       EngineState     = EngineState.ColdStart,
    val lastScore:         Float?          = null,
    val thresholdStats:    ThresholdStats? = null,
    val anomalyStats:      AnomalyStats?   = null,
    val trainingState:     TrainingState   = TrainingState.Idle,
    val trainingDataCount: Int             = 0,
    val lastModelAuc:      Float?          = null,
    val modelVersion:      Int             = 0,
    val isReady:           Boolean         = false
) {
    val trainingProgress: Int get() = when (trainingState) {
        is TrainingState.Training ->
            (trainingState.epoch * 100 / trainingState.totalEpochs)
        is TrainingState.Complete -> 100
        else                      -> 0
    }

    val statusLabel: String get() = when (engineState) {
        is EngineState.ColdStart   -> "Collecting data — model not yet trained"
        is EngineState.Monitoring  -> "Monitoring active"
        is EngineState.Watch       -> "Unusual behaviour detected"
        is EngineState.Suspicious  -> "Possible intrusion — watching closely"
        is EngineState.Alert       -> "ALERT — Intruder detected!"
        is EngineState.Suppressed  -> "Paused"
    }

    val statusColor: StatusColor get() = when (engineState) {
        is EngineState.ColdStart,
        is EngineState.Monitoring  -> StatusColor.GREEN
        is EngineState.Watch       -> StatusColor.YELLOW
        is EngineState.Suspicious  -> StatusColor.ORANGE
        is EngineState.Alert       -> StatusColor.RED
        is EngineState.Suppressed  -> StatusColor.GREY
    }

    enum class StatusColor { GREEN, YELLOW, ORANGE, RED, GREY }
}

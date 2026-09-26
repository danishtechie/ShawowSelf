package com.shadowself.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shadowself.anomaly.AdaptiveThreshold
import com.shadowself.anomaly.ThresholdStats
import com.shadowself.model.AnomalyEngine
import com.shadowself.training.repository.TrainingRepository
import com.shadowself.training.worker.ModelTrainingWorker
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val anomalyEngine:     AnomalyEngine,
    private val adaptiveThreshold: AdaptiveThreshold,
    private val trainingRepo:      TrainingRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch {
            _state.value = SettingsUiState(
                thresholdStats   = adaptiveThreshold.stats(),
                modelVersion     = anomalyEngine.modelVersion,
                trainingDataCount = trainingRepo.getTrainingDataCount(),
                lastAuc          = trainingRepo.getLastTrainingMetrics()?.auc
            )
        }
    }

    fun setThreshold(value: Float) {
        adaptiveThreshold.manuallySet(value)
        refresh()
    }

    fun resetThreshold() {
        adaptiveThreshold.reset()
        refresh()
    }

    fun triggerTraining() {
        ModelTrainingWorker.triggerNow(context)
    }
}

data class SettingsUiState(
    val thresholdStats:    ThresholdStats? = null,
    val modelVersion:      Int             = 0,
    val trainingDataCount: Int             = 0,
    val lastAuc:           Float?          = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var sliderValue by remember(state.thresholdStats) {
        mutableFloatStateOf(state.thresholdStats?.current ?: 0.65f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ── Threshold tuning ────────────────────────────────────────────────
            SectionCard(title = "Detection Sensitivity") {
                Text("Threshold: ${"%.3f".format(sliderValue)}",
                    style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value        = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { viewModel.setThreshold(sliderValue) },
                    valueRange   = AdaptiveThreshold.MIN_THRESHOLD..AdaptiveThreshold.MAX_THRESHOLD,
                    steps        = 56
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lenient", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Strict", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.thresholdStats?.let { stats ->
                    Spacer(Modifier.height(4.dp))
                    Text("EMA: ${"%.3f".format(stats.ema)}  " +
                         "| FA: ${stats.falseAlarmCount}  | TP: ${stats.truePosCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Weekly drift used: ${"%.1f".format(stats.driftBudgetPct * 100)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { viewModel.resetThreshold() }) {
                    Text("Reset to default (0.65)")
                }
            }

            // ── Model info ──────────────────────────────────────────────────────
            SectionCard(title = "Behaviour Model") {
                Text("Version: ${state.modelVersion}",
                    style = MaterialTheme.typography.bodyMedium)
                state.lastAuc?.let {
                    Text("Last AUC: ${"%.3f".format(it)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("Training data: ${state.trainingDataCount} windows",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { viewModel.triggerTraining() }) {
                    Text("Retrain model now")
                }
                Text("Retraining also runs automatically every 7 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Privacy ─────────────────────────────────────────────────────────
            SectionCard(title = "Privacy") {
                Text("All data stays on this device. No sensor data, " +
                     "photos, or location is ever sent to any server. " +
                     "FCM alerts send only a timestamp and confidence score.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

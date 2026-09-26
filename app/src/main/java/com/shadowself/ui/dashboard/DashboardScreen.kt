package com.shadowself.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shadowself.anomaly.EngineState
import com.shadowself.training.model.TrainingState

/**
 * DashboardScreen
 *
 * Main monitoring screen. Shows:
 *   - Pulsing status orb (green/yellow/orange/red)
 *   - Current identity confidence score
 *   - Threshold and model version
 *   - Anomaly stats (total alerts, false alarms, true positives)
 *   - Training progress bar (visible when training is running)
 *   - Quick actions: Pause, History, Settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToHistory:  () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ShadowSelf", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // ── Status Orb ────────────────────────────────────────────────────
            StatusOrb(
                state    = state,
                onPause  = { viewModel.suppressAlerts(30 * 60 * 1000L) }
            )

            Spacer(Modifier.height(24.dp))

            // ── Status label ──────────────────────────────────────────────────
            Text(
                text  = state.statusLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(32.dp))

            // ── Score card ────────────────────────────────────────────────────
            if (state.isReady && state.lastScore != null) {
                ScoreCard(state)
                Spacer(Modifier.height(16.dp))
            }

            // ── Training progress ─────────────────────────────────────────────
            if (state.trainingState is TrainingState.Training || state.trainingState is TrainingState.Preparing) {
                TrainingCard(state)
                Spacer(Modifier.height(16.dp))
            }

            // ── Cold start card ────────────────────────────────────────────────
            if (!state.isReady) {
                ColdStartCard(state.trainingDataCount)
                Spacer(Modifier.height(16.dp))
            }

            // ── Stats card ─────────────────────────────────────────────────────
            state.anomalyStats?.let {
                StatsCard(it)
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Pulsing status orb ─────────────────────────────────────────────────────────

@Composable
private fun StatusOrb(
    state:   DashboardUiState,
    onPause: () -> Unit
) {
    val targetColor = when (state.statusColor) {
        DashboardUiState.StatusColor.GREEN  -> Color(0xFF4CAF50)
        DashboardUiState.StatusColor.YELLOW -> Color(0xFFFFC107)
        DashboardUiState.StatusColor.ORANGE -> Color(0xFFFF9800)
        DashboardUiState.StatusColor.RED    -> Color(0xFFF44336)
        DashboardUiState.StatusColor.GREY   -> Color(0xFF9E9E9E)
    }
    val animColor by animateColorAsState(targetColor, tween(600), label = "orb_color")

    // Pulse animation for alert state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (state.engineState is EngineState.Alert) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "orb_scale"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale)
                .background(animColor.copy(alpha = 0.15f), CircleShape)
        )
        // Main orb
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .background(animColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (state.engineState is EngineState.Suppressed) {
                Icon(Icons.Default.Pause, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(40.dp))
            } else {
                Text(
                    text  = state.lastScore?.let { "${"%.0f".format(it * 100)}%" } ?: "—",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Score card ─────────────────────────────────────────────────────────────────

@Composable
private fun ScoreCard(state: DashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Identity Confidence", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            state.lastScore?.let { score ->
                LinearProgressIndicator(
                    progress = { score },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }

            state.thresholdStats?.let { stats ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Threshold", "${"%.2f".format(stats.current)}")
                    StatItem("EMA", "${"%.2f".format(stats.ema)}")
                    StatItem("Model v${state.modelVersion}", "AUC ${"%.2f".format(state.lastModelAuc ?: 0f)}")
                }
            }
        }
    }
}

// ── Training progress card ─────────────────────────────────────────────────────

@Composable
private fun TrainingCard(state: DashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Training model on your behaviour…",
                style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(
                progress = { state.trainingProgress / 100f },
                modifier  = Modifier.fillMaxWidth().height(6.dp)
            )
            if (state.trainingState is TrainingState.Training) {
                Text(
                    "Epoch ${state.trainingState.epoch}/${state.trainingState.totalEpochs} " +
                    "— loss ${"%.4f".format(state.trainingState.trainLoss)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Cold start card ────────────────────────────────────────────────────────────

@Composable
private fun ColdStartCard(dataCount: Int) {
    val needed = 100
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Building your behavioural profile",
                style = MaterialTheme.typography.titleSmall)
            Text("Use your phone normally for a few days. ShadowSelf will " +
                 "train a model unique to you once enough data is collected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { minOf(dataCount.toFloat() / needed, 1f) },
                modifier  = Modifier.fillMaxWidth().height(6.dp)
            )
            Text("$dataCount / $needed data windows collected",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Stats card ─────────────────────────────────────────────────────────────────

@Composable
private fun StatsCard(stats: com.shadowself.anomaly.AnomalyStats) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("Total windows",  stats.totalWindows.toString())
            StatItem("Alerts",         stats.totalAlerts.toString())
            StatItem("False alarms",   stats.falseAlarms.toString())
            StatItem("Confirmed",      stats.truePositives.toString())
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

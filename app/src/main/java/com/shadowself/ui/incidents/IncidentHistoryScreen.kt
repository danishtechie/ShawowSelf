package com.shadowself.ui.incidents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shadowself.anomaly.AnomalyRecord
import com.shadowself.anomaly.AnomalyRecordStore
import com.shadowself.data.local.IncidentDao
import com.shadowself.data.local.IncidentEntity
import com.shadowself.model.AnomalyEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Incident history — shows every ALERT event, photo thumbnail,
 * location, and confidence score. Owner can mark each as
 * false alarm or confirm true positive directly from this screen.
 */
@HiltViewModel
class IncidentHistoryViewModel @Inject constructor(
    private val incidentDao:  IncidentDao,
    private val recordStore:  AnomalyRecordStore,
    private val anomalyEngine: AnomalyEngine
) : ViewModel() {

    private val _incidents = MutableStateFlow<List<IncidentEntity>>(emptyList())
    val incidents: StateFlow<List<IncidentEntity>> = _incidents.asStateFlow()

    init {
        viewModelScope.launch {
            incidentDao.observeAll().collect { _incidents.value = it }
        }
    }

    fun markFalseAlarm(incident: IncidentEntity) {
        viewModelScope.launch {
            incidentDao.markAsFalseAlarm(incident.id)
            anomalyEngine.onFalseAlarmFeedback(incident.timestampMs)
            recordStore.markFeedback(incident.timestampMs, isTruePositive = false)
        }
    }

    fun markTruePositive(incident: IncidentEntity) {
        viewModelScope.launch {
            incidentDao.markAsTruePositive(incident.id)
            anomalyEngine.onTruePositiveFeedback(incident.timestampMs)
            recordStore.markFeedback(incident.timestampMs, isTruePositive = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentHistoryScreen(
    onBack:    () -> Unit,
    viewModel: IncidentHistoryViewModel = hiltViewModel()
) {
    val incidents by viewModel.incidents.collectAsState()
    val fmt = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Incident History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (incidents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("No incidents recorded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(incidents, key = { it.id }) { incident ->
                    IncidentCard(
                        incident    = incident,
                        fmt         = fmt,
                        onFalseAlarm = { viewModel.markFalseAlarm(incident) },
                        onTruePositive = { viewModel.markTruePositive(incident) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IncidentCard(
    incident:       IncidentEntity,
    fmt:            SimpleDateFormat,
    onFalseAlarm:   () -> Unit,
    onTruePositive: () -> Unit
) {
    val feedbackColor = when (incident.feedback) {
        1 -> Color(0xFF4CAF50)   // false alarm — green
        2 -> Color(0xFFF44336)   // true positive — red
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    fmt.format(Date(incident.timestampMs)),
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                // Feedback badge
                when (incident.feedback) {
                    1 -> Text("False alarm", color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.labelMedium)
                    2 -> Text("Confirmed", color = Color(0xFFF44336),
                        style = MaterialTheme.typography.labelMedium)
                    else -> Text("Unreviewed", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
            }

            Text(
                "Confidence: ${"%.1f".format(incident.confidenceScore * 100)}%  " +
                "| Level: ${incident.alertLevel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            incident.addressLine?.let {
                Text("📍 $it", style = MaterialTheme.typography.bodySmall)
            }

            incident.photoPath?.let {
                Text("📷 Encrypted photo captured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            // Feedback buttons — only shown if not yet reviewed
            if (incident.feedback == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick   = onFalseAlarm,
                        modifier  = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("False alarm")
                    }
                    Button(
                        onClick  = onTruePositive,
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Confirmed")
                    }
                }
            }
        }
    }
}

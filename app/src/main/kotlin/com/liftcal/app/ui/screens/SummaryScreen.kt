package com.liftcal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liftcal.app.ui.app
import com.liftcal.app.ui.dateTime
import com.liftcal.app.ui.duration
import com.liftcal.app.ui.exerciseName
import com.liftcal.app.ui.factory
import com.liftcal.app.ui.kcal
import com.liftcal.app.ui.num
import com.liftcal.data.LiftCalData
import com.liftcal.data.db.WorkoutStatus
import com.liftcal.data.db.WorkoutWithSets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SummaryViewModel(private val data: LiftCalData, private val id: Long) : ViewModel() {
    val workout: StateFlow<WorkoutWithSets?> =
        data.workouts.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val refreshing = MutableStateFlow(false)

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            runCatching { data.finalizer.finalize(id) }
            refreshing.value = false
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            data.workouts.delete(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    workoutId: Long,
    onBack: () -> Unit,
    vm: SummaryViewModel = viewModel(key = "summary-$workoutId", factory = factory { SummaryViewModel(app().liftCalData, workoutId) }),
) {
    val w by vm.workout.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workout summary") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (refreshing) CircularProgressIndicator(Modifier.padding(12.dp))
                    else IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Recompute") }
                    IconButton(onClick = { vm.delete(onBack) }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        val ww = w ?: return@Scaffold
        val e = ww.workout
        val sets = ww.sets.sortedBy { it.startMs }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dateTime(e.startMs), style = MaterialTheme.typography.bodyMedium)
                        Text("${kcal(e.totalKcal)} kcal", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        if (e.low90Kcal != null) {
                            Text("90% range ${kcal(e.low90Kcal)} - ${kcal(e.high90Kcal)} kcal")
                        }
                        if (e.status == WorkoutStatus.PENDING_SYNC) {
                            Text(
                                "Provisional: waiting for your Fitbit Air to sync heart rate for the end of this " +
                                    "session. The estimate updates automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stat("Duration", duration((e.endMs ?: e.startMs) - e.startMs))
                        Stat("Active", "${kcal(e.activeKcal)} kcal")
                        Stat("Resting (RMR)", "${kcal(e.restingKcal)} kcal")
                        Stat("Afterburn (EPOC)", "${kcal(e.epocKcal)} kcal")
                        HorizontalDivider()
                        Stat("Heart-rate source", when (e.hrSource) {
                            "CHEST_STRAP" -> "Chest strap"
                            "WRIST" -> "Fitbit Air"
                            else -> "None (model only)"
                        })
                        Stat("Heart-rate quality", e.hrQuality?.let { "${(it * 100).toInt()}%" } ?: "-")
                        e.fitbitKcal?.let { Stat("Fitbit's own estimate", "${kcal(it)} kcal") }
                        Stat("Saved to Health Connect", if (e.writtenToHealthConnect) "Yes" else "Not yet")
                    }
                }
            }
            item { Text("Per set (active kcal incl. recovery)", style = MaterialTheme.typography.titleSmall) }
            items(sets, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(exerciseName(s.exerciseId) + if (s.isWarmup) " (warm-up)" else "")
                        Text("${num(s.loadKg)} kg x ${s.reps}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${kcal(s.attributedKcal)} kcal", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

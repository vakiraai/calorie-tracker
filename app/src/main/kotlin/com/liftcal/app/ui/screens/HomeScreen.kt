package com.liftcal.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.liftcal.app.ui.factory
import com.liftcal.app.ui.kcal
import com.liftcal.data.LiftCalData
import com.liftcal.data.db.ProfileEntity
import com.liftcal.data.db.WorkoutStatus
import com.liftcal.data.db.WorkoutWithSets
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val data: LiftCalData) : ViewModel() {
    val workouts: StateFlow<List<WorkoutWithSets>> =
        data.workouts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profile: StateFlow<ProfileEntity?> =
        data.profile.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    suspend fun startWorkout(): Long = data.workouts.start()

    fun refreshPending() {
        viewModelScope.launch { runCatching { data.finalizer.finalizePending() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartWorkout: (Long) -> Unit,
    onOpenWorkout: (Long, Boolean) -> Unit,
    onProfile: () -> Unit,
    onConnections: () -> Unit,
    onCalibration: () -> Unit,
    vm: HomeViewModel = viewModel(factory = factory { HomeViewModel(app().liftCalData) }),
) {
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val active = workouts.firstOrNull { it.workout.status == WorkoutStatus.ACTIVE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LiftCal") },
                actions = {
                    IconButton(onClick = onConnections) { Icon(Icons.Default.Bluetooth, "Connections") }
                    IconButton(onClick = onCalibration) { Icon(Icons.Default.Tune, "Calibration") }
                    IconButton(onClick = onProfile) { Icon(Icons.Default.Person, "Profile") }
                },
            )
        },
        floatingActionButton = {
            if (profile != null && active == null) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { onStartWorkout(vm.startWorkout()) } },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Start workout") },
                )
            }
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profile == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Set up your profile", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Age, weight, height and heart-rate data personalise the calorie model. " +
                                    "Measured max HR and VO2max make it markedly more accurate.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onProfile) { Text("Create profile") }
                        }
                    }
                }
            }
            if (active != null) {
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenWorkout(active.workout.id, true) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Workout in progress", style = MaterialTheme.typography.titleMedium)
                            Text("${active.sets.size} sets logged - tap to continue")
                        }
                    }
                }
            }
            if (workouts.any { it.workout.status == WorkoutStatus.PENDING_SYNC }) {
                item {
                    TextButton(onClick = vm::refreshPending) { Text("Check for Fitbit sync now") }
                }
            }
            items(workouts.filter { it.workout.status != WorkoutStatus.ACTIVE }, key = { it.workout.id }) { w ->
                WorkoutCard(w) { onOpenWorkout(w.workout.id, false) }
            }
        }
    }
}

@Composable
private fun WorkoutCard(w: WorkoutWithSets, onClick: () -> Unit) {
    val e = w.workout
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dateTime(e.startMs), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${w.sets.size} sets - ${duration((e.endMs ?: e.startMs) - e.startMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (e.status == WorkoutStatus.PENDING_SYNC) {
                    AssistChip(onClick = onClick, label = { Text("Waiting for band sync") })
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${kcal(e.totalKcal)} kcal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (e.low90Kcal != null) {
                    Text("${kcal(e.low90Kcal)}-${kcal(e.high90Kcal)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

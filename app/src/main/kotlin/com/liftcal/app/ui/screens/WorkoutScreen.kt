package com.liftcal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liftcal.app.LiftCalApp
import com.liftcal.app.LiveHrState
import com.liftcal.app.StrapStatus
import com.liftcal.app.ui.app
import com.liftcal.app.ui.duration
import com.liftcal.app.ui.exerciseName
import com.liftcal.app.ui.factory
import com.liftcal.app.ui.num
import com.liftcal.data.db.SetEntity
import com.liftcal.data.db.WorkoutWithSets
import com.liftcal.data.sync.FinalizeWorker
import com.liftcal.engine.ExerciseCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SetForm(
    val exerciseId: String = ExerciseCatalog.all.first().id,
    val load: String = "",
    val reps: String = "",
    val rpe: Float? = null,
    val warmup: Boolean = false,
)

class WorkoutViewModel(private val app: LiftCalApp, val workoutId: Long) : ViewModel() {
    private val data = app.liftCalData
    val workout: StateFlow<WorkoutWithSets?> =
        data.workouts.observe(workoutId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val liveHr: StateFlow<LiveHrState> = app.liveHr.state
    val form = MutableStateFlow(SetForm())
    val setStartedAt = MutableStateFlow<Long?>(null)
    val error = MutableStateFlow<String?>(null)

    init {
        app.liveHr.recordingWorkoutId = workoutId
    }

    fun selectExercise(id: String) {
        val last = workout.value?.sets?.filter { it.exerciseId == id }?.maxByOrNull { it.startMs }
        form.value = form.value.copy(
            exerciseId = id,
            load = last?.let { num(it.loadKg) } ?: "",
            reps = last?.reps?.toString() ?: "",
        )
    }

    fun startSet() {
        setStartedAt.value = System.currentTimeMillis()
    }

    fun cancelSet() {
        setStartedAt.value = null
    }

    /** Finishes a timed set. Without a running set, logs one ending now with ~3 s per rep. */
    fun logSet() {
        val f = form.value
        val load = f.load.toDoubleOrNull()
        val reps = f.reps.toIntOrNull()
        if (load == null || reps == null) {
            error.value = "Enter load and reps"
            return
        }
        if (load > MAX_LOAD_KG || reps !in 1..MAX_REPS) {
            error.value = "Check the numbers: load up to ${MAX_LOAD_KG.toInt()} kg, 1-$MAX_REPS reps"
            return
        }
        val now = System.currentTimeMillis()
        val w = workout.value
        val earliest = maxOf(w?.workout?.startMs ?: 0L, w?.sets?.maxOfOrNull { it.endMs } ?: 0L)
        val start = setStartedAt.value ?: maxOf(now - reps * 3_000L, earliest).coerceAtMost(now - 1000)
        viewModelScope.launch {
            data.workouts.addSet(
                SetEntity(
                    workoutId = workoutId, exerciseId = f.exerciseId, loadKg = load, reps = reps,
                    startMs = start, endMs = maxOf(now, start + 1000), rpe = f.rpe?.toDouble(), isWarmup = f.warmup,
                ),
            )
            setStartedAt.value = null
            error.value = null
        }
    }

    fun deleteSet(s: SetEntity) {
        viewModelScope.launch { data.workouts.deleteSet(s) }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            app.liveHr.flush()
            app.liveHr.recordingWorkoutId = null
            data.workouts.end(workoutId)
            runCatching { data.finalizer.finalize(workoutId) }
            FinalizeWorker.schedule(app)
            onDone()
        }
    }

    fun discard(onDone: () -> Unit) {
        viewModelScope.launch {
            app.liveHr.recordingWorkoutId = null
            data.workouts.delete(workoutId)
            onDone()
        }
    }

    companion object {
        const val MAX_LOAD_KG = 500.0
        const val MAX_REPS = 100
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    workoutId: Long,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    vm: WorkoutViewModel = viewModel(key = "workout-$workoutId", factory = factory { WorkoutViewModel(app(), workoutId) }),
) {
    val w by vm.workout.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val setStart by vm.setStartedAt.collectAsStateWithLifecycle()
    val hr by vm.liveHr.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var restTargetSec by remember { mutableStateOf(120) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val sets = w?.sets.orEmpty().sortedBy { it.startMs }
    val lastEnd = sets.maxOfOrNull { it.endMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workout ${w?.let { duration(now - it.workout.startMs) } ?: ""}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { LiveHrChip(hr) },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (setStart != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Set in progress", style = MaterialTheme.typography.titleMedium)
                            Text(duration(now - setStart!!), style = MaterialTheme.typography.displayMedium)
                        }
                    }
                } else if (lastEnd != null) {
                    val restSec = ((now - lastEnd) / 1000).toInt()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Rest", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(duration(restSec * 1000L), style = MaterialTheme.typography.headlineMedium,
                                    color = if (restSec >= restTargetSec) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                            }
                            LinearProgressIndicator(
                                progress = { (restSec.toFloat() / restTargetSec).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(60, 90, 120, 180, 240).forEach { s ->
                                    FilterChip(selected = restTargetSec == s, onClick = { restTargetSec = s }, label = { Text("${s}s") })
                                }
                            }
                        }
                    }
                }
            }
            item { SetEntry(form, vm, setStart != null, error) }
            itemsIndexed(sets.reversed(), key = { _, s -> s.id }) { i, s ->
                SetRow(sets.size - i, s) { vm.deleteSet(s) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.finish(onFinished) },
                        enabled = sets.isNotEmpty() && setStart == null,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Finish workout") }
                    TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Discard workout", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard workout?") },
            text = { Text("All logged sets will be deleted.") },
            confirmButton = { TextButton(onClick = { vm.discard(onBack) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LiveHrChip(hr: LiveHrState) {
    if (hr.status == StrapStatus.DISCONNECTED) return
    AssistChip(
        onClick = {},
        leadingIcon = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.error) },
        label = { Text(hr.bpm?.let { "$it bpm" } ?: "...") },
        modifier = Modifier.padding(end = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetEntry(form: SetForm, vm: WorkoutViewModel, running: Boolean, error: String?) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = exerciseName(form.exerciseId),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Exercise") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ExerciseCatalog.all.forEach { ex ->
                        DropdownMenuItem(text = { Text(ex.name) }, onClick = {
                            vm.selectExercise(ex.id)
                            expanded = false
                        })
                    }
                }
            }
            val perHand = ExerciseCatalog[form.exerciseId].loadIsPerHand
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(if (perHand) "Load per hand (kg)" else "Load (kg)", form.load, Modifier.weight(1f)) {
                    vm.form.value = form.copy(load = it)
                }
                NumberField("Reps", form.reps, Modifier.weight(1f), decimal = false) { vm.form.value = form.copy(reps = it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).toggleable(
                        value = form.rpe != null,
                        role = Role.Checkbox,
                        onValueChange = { vm.form.value = form.copy(rpe = if (it) 8f else null) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = form.rpe != null, onCheckedChange = null)
                    Text(form.rpe?.let { "RPE ${(it * 2).roundToInt() / 2.0}" } ?: "Rate effort (RPE)")
                }
                Row(
                    Modifier.toggleable(
                        value = form.warmup,
                        role = Role.Checkbox,
                        onValueChange = { vm.form.value = form.copy(warmup = it) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = form.warmup, onCheckedChange = null)
                    Text("Warm-up")
                }
            }
            form.rpe?.let { r ->
                Slider(value = r, onValueChange = { vm.form.value = form.copy(rpe = it) }, valueRange = 5f..10f, steps = 9)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = vm::cancelSet, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = vm::logSet,
                        modifier = Modifier.weight(2f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    ) { Text("Finish set") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = vm::logSet, modifier = Modifier.weight(1f)) { Text("Quick log") }
                    Button(onClick = vm::startSet, modifier = Modifier.weight(2f).height(52.dp)) { Text("Start set") }
                }
                Text(
                    "Tap Start set when you unrack and Finish set when you rack: exact timing lets the model " +
                        "line your sets up with the band's heart rate.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SetRow(n: Int, s: SetEntity, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$n", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(exerciseName(s.exerciseId) + if (s.isWarmup) " (warm-up)" else "")
                Text(
                    "${num(s.loadKg)} kg x ${s.reps}" + (s.rpe?.let { " @ RPE ${num(it)}" } ?: "") +
                        " - ${duration(s.endMs - s.startMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete set") }
        }
    }
}

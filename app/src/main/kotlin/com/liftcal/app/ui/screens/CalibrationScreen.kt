package com.liftcal.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liftcal.app.LiftCalApp
import com.liftcal.app.ui.app
import com.liftcal.app.ui.dateTime
import com.liftcal.app.ui.factory
import com.liftcal.data.db.ProfileEntity
import com.liftcal.data.db.WorkoutStatus
import com.liftcal.data.db.WorkoutWithSets
import com.liftcal.engine.Calibration
import com.liftcal.data.withCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CalibrationViewModel(private val app: LiftCalApp) : ViewModel() {
    private val data = app.liftCalData
    val profile: StateFlow<ProfileEntity?> = data.profile.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val workouts: StateFlow<List<WorkoutWithSets>> = data.workouts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val referenceIds: StateFlow<List<Long>> = data.calibration.observeReferenceWorkoutIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun importCart(workoutId: Long, uri: Uri) = work {
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: error("could not read file")
        val n = data.calibration.importMetabolicCart(workoutId, text)
        "Imported $n reference windows. Tap Refit to update your model."
    }

    fun refit() = work {
        val fit = withContext(Dispatchers.Default) { data.calibration.refit() } ?: error("no reference sessions yet")
        data.finalizer.let { f -> workouts.value.filter { it.workout.status != WorkoutStatus.ACTIVE }.forEach { f.finalize(it.workout.id) } }
        String.format(
            Locale.getDefault(),
            "Model refitted on %d session(s). Window error %.0f%% -> %.0f%%. All workouts recomputed.",
            fit.calibration.referenceSessions, fit.rmsRelErrorBefore * 100, fit.rmsRelErrorAfter * 100,
        )
    }

    fun learnWristReliability(workoutId: Long) = work {
        val r = data.calibration.updateWristReliability(workoutId)
            ?: error("this workout needs at least a minute of overlapping chest-strap and band heart rate")
        String.format(Locale.getDefault(), "Wrist HR reliability during lifting: %.0f%%", r * 100)
    }

    fun reset() = work {
        val p = data.profile.get() ?: error("no profile")
        data.profile.save(p.withCalibration(Calibration.PRIOR))
        "Calibration reset to population defaults"
    }

    private fun work(block: suspend () -> String) {
        viewModelScope.launch {
            busy.value = true
            message.value = runCatching { block() }.getOrElse { "Error: ${it.message}" }
            busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onBack: () -> Unit,
    onProfile: () -> Unit,
    vm: CalibrationViewModel = viewModel(factory = factory { CalibrationViewModel(app()) }),
) {
    val p by vm.profile.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val refIds by vm.referenceIds.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val finished = workouts.filter { it.workout.status != WorkoutStatus.ACTIVE }
    var selected by remember { mutableStateOf<Long?>(null) }
    val cartPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = selected
        if (uri != null && id != null) vm.importCart(id, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Your model", "Fitted personal parameters. Defaults are population averages.") {
                p?.let {
                    Param("HR-to-VO2 scale", it.hrVo2Scale)
                    Param("In-set HR weight", it.inSetHrWeight)
                    Param("Work cost scale", it.workCostScale)
                    Param("EPOC scale", it.epocScale)
                    Param("Wrist HR reliability", it.wristHrReliability)
                    Text("Reference sessions: ${it.referenceSessions}")
                    TextButton(onClick = vm::reset) { Text("Reset to defaults") }
                } ?: Text("Create a profile first.")
            }
            Section(
                "1. Lab or field test values",
                "A measured max HR and VO2max (from a lab, or a max-effort field test) give the largest single accuracy gain.",
            ) {
                OutlinedButton(onClick = onProfile) { Text("Enter in profile") }
            }

            WorkoutPicker(finished, selected) { selected = it }

            Section(
                "2. Metabolic cart session",
                "Wear the band during a lifting session measured by indirect calorimetry, then import the export as CSV " +
                    "with columns time_s (from workout start), vo2_ml_min, vco2_ml_min.",
            ) {
                Button(onClick = { cartPicker.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = selected != null && !busy) {
                    Text("Import CSV for selected workout")
                }
                Text("Workouts with reference data: ${refIds.size}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::refit, enabled = refIds.isNotEmpty() && !busy) { Text("Refit model") }
            }
            Section(
                "3. Chest strap comparison",
                "Log a workout while wearing both the band and a chest strap (connected under Connections). " +
                    "This measures how well wrist HR tracks during lifts.",
            ) {
                Button(onClick = { selected?.let(vm::learnWristReliability) }, enabled = selected != null && !busy) {
                    Text("Learn from selected workout")
                }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

@Composable
private fun Param(label: String, v: Double) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(String.format(Locale.getDefault(), "%.2f", v))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutPicker(workouts: List<WorkoutWithSets>, selected: Long?, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = workouts.firstOrNull { it.workout.id == selected }?.let { dateTime(it.workout.startMs) } ?: "Select a workout"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true, label = { Text("Workout") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            workouts.forEach { w ->
                DropdownMenuItem(
                    text = { Text("${dateTime(w.workout.startMs)} - ${w.sets.size} sets") },
                    onClick = { onSelect(w.workout.id); expanded = false },
                )
            }
        }
    }
}

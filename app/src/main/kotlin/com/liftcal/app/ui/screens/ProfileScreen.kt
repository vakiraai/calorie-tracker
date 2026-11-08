package com.liftcal.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liftcal.app.ui.app
import com.liftcal.app.ui.factory
import com.liftcal.app.ui.num
import com.liftcal.data.LiftCalData
import com.liftcal.data.db.ProfileEntity
import com.liftcal.data.toUserProfile
import com.liftcal.engine.Sex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class ProfileForm(
    val age: String = "",
    val sex: Sex = Sex.MALE,
    val weight: String = "",
    val height: String = "",
    val bodyFat: String = "",
    val restingHr: String = "",
    val maxHr: String = "",
    val vo2Max: String = "",
) {
    fun toEntity(base: ProfileEntity?): ProfileEntity? {
        val a = age.toIntOrNull() ?: return null
        val w = weight.toDoubleOrNull() ?: return null
        val h = height.toDoubleOrNull() ?: return null
        val e = (base ?: ProfileEntity(ageYears = a, sex = sex.name, weightKg = w, heightCm = h)).copy(
            ageYears = a, sex = sex.name, weightKg = w, heightCm = h,
            bodyFatPct = bodyFat.toDoubleOrNull(), restingHr = restingHr.toDoubleOrNull(),
            maxHr = maxHr.toDoubleOrNull(), vo2Max = vo2Max.toDoubleOrNull(),
        )
        return runCatching { e.toUserProfile() }.map { e }.getOrNull()
    }

    companion object {
        fun from(p: ProfileEntity) = ProfileForm(
            p.ageYears.toString(), Sex.valueOf(p.sex), num(p.weightKg), num(p.heightCm),
            num(p.bodyFatPct), num(p.restingHr), num(p.maxHr), num(p.vo2Max),
        )
    }
}

class ProfileViewModel(private val data: LiftCalData) : ViewModel() {
    val form = MutableStateFlow(ProfileForm())
    val message = MutableStateFlow<String?>(null)
    private var existing: ProfileEntity? = null

    val hcPermissions get() = data.healthConnect.readPermissions
    val hcAvailable get() = data.healthConnect.isAvailable()

    init {
        viewModelScope.launch {
            existing = data.profile.get()
            existing?.let { form.value = ProfileForm.from(it) }
        }
    }

    fun importFromHealthConnect() {
        viewModelScope.launch {
            val hints = runCatching { data.healthConnect.readProfileHints() }.getOrElse {
                message.value = "Could not read Health Connect: ${it.message}"
                return@launch
            }
            val f = form.value
            form.value = f.copy(
                restingHr = f.restingHr.ifBlank { num(hints.restingHr?.let { Math.round(it).toDouble() }) },
                vo2Max = f.vo2Max.ifBlank { num(hints.vo2Max) },
                weight = hints.weightKg?.let { num(Math.round(it * 10) / 10.0) } ?: f.weight,
                height = f.height.ifBlank { num(hints.heightCm?.let { Math.round(it).toDouble() }) },
                bodyFat = f.bodyFat.ifBlank { num(hints.bodyFatPct) },
            )
            message.value = "Imported available values from Health Connect"
        }
    }

    fun save(onDone: () -> Unit) {
        val e = form.value.toEntity(existing)
        if (e == null) {
            message.value = "Please enter a valid age, weight and height"
            return
        }
        viewModelScope.launch {
            data.profile.save(e)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onDone: () -> Unit,
    vm: ProfileViewModel = viewModel(factory = factory { ProfileViewModel(app().liftCalData) }),
) {
    val f by vm.form.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(vm.hcPermissions)) vm.importFromHealthConnect()
        else vm.message.value = "Health Connect permissions were not granted"
    }

    fun update(transform: ProfileForm.() -> ProfileForm) {
        vm.form.value = vm.form.value.transform()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.hcAvailable) {
                OutlinedButton(onClick = { hcLauncher.launch(vm.hcPermissions) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import from Health Connect (Fitbit)")
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Sex.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = f.sex == s,
                        onClick = { update { copy(sex = s) } },
                        shape = SegmentedButtonDefaults.itemShape(i, Sex.entries.size),
                    ) { Text(s.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Age", f.age, Modifier.weight(1f), decimal = false) { update { copy(age = it) } }
                NumberField("Weight (kg)", f.weight, Modifier.weight(1f)) { update { copy(weight = it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Height (cm)", f.height, Modifier.weight(1f)) { update { copy(height = it) } }
                NumberField("Body fat %", f.bodyFat, Modifier.weight(1f)) { update { copy(bodyFat = it) } }
            }
            Text("Heart rate and fitness", style = MaterialTheme.typography.titleSmall)
            Text(
                "Leave blank to estimate. Values from a lab or field test (e.g. a max-effort ramp) " +
                    "remove the largest source of error in HR-based calorie estimates.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Resting HR", f.restingHr, Modifier.weight(1f)) { update { copy(restingHr = it) } }
                NumberField("Max HR", f.maxHr, Modifier.weight(1f)) { update { copy(maxHr = it) } }
            }
            NumberField("VO2max (ml/kg/min)", f.vo2Max, Modifier.fillMaxWidth()) { update { copy(vo2Max = it) } }

            f.toEntity(null)?.toUserProfile()?.let { p ->
                Text(
                    "Model will use: max HR %.0f, resting HR %.0f, VO2max %.1f".format(
                        p.effectiveMaxHr, p.effectiveRestingHr, p.effectiveVo2Max,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.isEmpty() || v.matches(Regex(if (decimal) "\\d*[.,]?\\d*" else "\\d*"))) onChange(v.replace(',', '.')) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

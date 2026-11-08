package com.liftcal.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liftcal.app.LiftCalApp
import com.liftcal.app.LiveHrState
import com.liftcal.app.StrapStatus
import com.liftcal.app.ui.app
import com.liftcal.app.ui.factory
import com.liftcal.data.ble.BleHrDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ConnectionsState(
    val hcStatus: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val hcGranted: Int = 0,
    val hcTotal: Int = 0,
    val fitbitConfigured: Boolean = false,
    val fitbitAuthorized: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<BleHrDevice> = emptyList(),
    val message: String? = null,
)

class ConnectionsViewModel(private val app: LiftCalApp) : ViewModel() {
    private val data = app.liftCalData
    val state = MutableStateFlow(ConnectionsState())
    val liveHr: StateFlow<LiveHrState> = app.liveHr.state
    val hcPermissions get() = data.healthConnect.allPermissions
    val blePermissions get() = data.ble.requiredPermissions()
    private var scanJob: Job? = null

    fun refresh() {
        viewModelScope.launch {
            val granted = runCatching { data.healthConnect.grantedPermissions() }.getOrDefault(emptySet())
            state.value = state.value.copy(
                hcStatus = data.healthConnect.sdkStatus(),
                hcGranted = granted.intersect(hcPermissions).size,
                hcTotal = hcPermissions.size,
                fitbitConfigured = data.fitbitAuth.isConfigured,
                fitbitAuthorized = data.fitbitAuth.isAuthorized,
            )
        }
    }

    fun fitbitIntent(): Intent? = runCatching { data.fitbitAuth.authorizationIntent() }
        .onFailure { state.value = state.value.copy(message = it.message) }
        .getOrNull()

    fun onFitbitResult(intent: Intent?) {
        if (intent == null) return
        viewModelScope.launch {
            runCatching { data.fitbitAuth.handleAuthorizationResult(intent) }
                .onFailure { state.value = state.value.copy(message = "Fitbit sign-in failed: ${it.message}") }
            refresh()
        }
    }

    fun fitbitSignOut() {
        data.fitbitAuth.signOut()
        refresh()
    }

    fun scan() {
        if (!data.ble.isBluetoothOn) {
            state.value = state.value.copy(message = "Turn on Bluetooth to find a chest strap")
            return
        }
        scanJob?.cancel()
        state.value = state.value.copy(scanning = true, devices = emptyList(), message = null)
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(15_000) {
                data.ble.scan()
                    .catch { state.value = state.value.copy(message = it.message) }
                    .collect { d ->
                        val list = state.value.devices.filterNot { it.address == d.address } + d
                        state.value = state.value.copy(devices = list.sortedByDescending { it.rssi })
                    }
            }
            state.value = state.value.copy(scanning = false)
        }
    }

    fun connect(d: BleHrDevice) {
        scanJob?.cancel()
        state.value = state.value.copy(scanning = false)
        app.liveHr.connect(d)
    }

    fun disconnect() = app.liveHr.disconnect()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    vm: ConnectionsViewModel = viewModel(factory = factory { ConnectionsViewModel(app()) }),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val hr by vm.liveHr.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refresh() }

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refresh() }
    val fitbitLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onFitbitResult(it.data) }
    val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) vm.scan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Health Connect", "Primary source of Fitbit Air heart rate. In the Fitbit app, enable sharing heart rate with Health Connect.") {
                    when (s.hcStatus) {
                        HealthConnectClient.SDK_AVAILABLE -> {
                            Text("Permissions: ${s.hcGranted}/${s.hcTotal} granted")
                            Button(onClick = { hcLauncher.launch(vm.hcPermissions) }) { Text("Grant access") }
                        }
                        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                            Text("Health Connect needs to be installed or updated.")
                            Button(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                            }) { Text("Open Play Store") }
                        }
                        else -> Text("Health Connect is not available on this device.")
                    }
                }
            }
            item {
                Section("Fitbit Web API", "Fallback that provides 1-second intraday heart rate when Health Connect data is sparse, plus Fitbit's own estimate for comparison.") {
                    when {
                        !s.fitbitConfigured -> Text("Not configured: add fitbit.clientId to local.properties and rebuild.")
                        s.fitbitAuthorized -> {
                            Text("Connected")
                            OutlinedButton(onClick = vm::fitbitSignOut) { Text("Disconnect") }
                        }
                        else -> Button(onClick = { vm.fitbitIntent()?.let(fitbitLauncher::launch) }) { Text("Connect Fitbit account") }
                    }
                }
            }
            item {
                Section("Chest strap (optional)", "Any Bluetooth heart-rate strap (e.g. Polar H10). Shows live HR, and recording a session with both strap and band teaches the model how far to trust wrist HR during lifts.") {
                    when (hr.status) {
                        StrapStatus.DISCONNECTED -> Button(onClick = { bleLauncher.launch(vm.blePermissions) }, enabled = !s.scanning) {
                            Text(if (s.scanning) "Scanning..." else "Scan for straps")
                        }
                        else -> {
                            Text("${hr.deviceName ?: "Strap"}: ${if (hr.status == StrapStatus.CONNECTED) hr.bpm?.let { "$it bpm" } ?: "no contact" else "connecting..."}")
                            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                        }
                    }
                    hr.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (hr.status == StrapStatus.DISCONNECTED) {
                items(s.devices, key = { it.address }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { vm.connect(d) }) {
                        Row(Modifier.padding(16.dp)) {
                            Text(d.name ?: d.address, Modifier.weight(1f))
                            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            s.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

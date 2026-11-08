package com.liftcal.app

import com.liftcal.data.LiftCalData
import com.liftcal.data.ble.BleHrDevice
import com.liftcal.data.ble.BleHrEvent
import com.liftcal.data.db.HrSampleEntity
import com.liftcal.engine.HrSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class StrapStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class LiveHrState(
    val status: StrapStatus = StrapStatus.DISCONNECTED,
    val deviceName: String? = null,
    val bpm: Int? = null,
    val error: String? = null,
)

/**
 * Holds the optional chest-strap connection for the whole app so it survives navigation,
 * and records samples against the active workout as CHEST_STRAP reference data.
 */
class LiveHeartRate(private val data: LiftCalData, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(LiveHrState())
    val state: StateFlow<LiveHrState> = _state.asStateFlow()

    @Volatile
    var recordingWorkoutId: Long? = null

    private var job: Job? = null
    private val buffer = ArrayList<HrSampleEntity>()

    fun connect(device: BleHrDevice) {
        job?.cancel()
        _state.value = LiveHrState(StrapStatus.CONNECTING, device.name ?: device.address)
        job = scope.launch {
            data.ble.connect(device.address)
                .catch { e -> _state.value = _state.value.copy(status = StrapStatus.DISCONNECTED, error = e.message) }
                .collect { ev ->
                    when (ev) {
                        BleHrEvent.Connecting -> _state.value = _state.value.copy(status = StrapStatus.CONNECTING)
                        BleHrEvent.Connected -> _state.value = _state.value.copy(status = StrapStatus.CONNECTED, error = null)
                        is BleHrEvent.Disconnected -> _state.value = _state.value.copy(status = StrapStatus.CONNECTING, bpm = null, error = ev.reason)
                        is BleHrEvent.Measurement -> onMeasurement(ev)
                    }
                }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        flush()
        _state.value = LiveHrState()
    }

    private fun onMeasurement(ev: BleHrEvent.Measurement) {
        if (ev.value.sensorContact == false) {
            _state.value = _state.value.copy(status = StrapStatus.CONNECTED, bpm = null)
            return
        }
        _state.value = _state.value.copy(status = StrapStatus.CONNECTED, bpm = ev.value.bpm)
        val wid = recordingWorkoutId ?: return
        synchronized(buffer) {
            buffer += HrSampleEntity(workoutId = wid, epochMs = ev.epochMs, bpm = ev.value.bpm.toDouble(), source = HrSource.CHEST_STRAP.name)
        }
        if (buffer.size >= 15) flush()
    }

    fun flush() {
        val batch = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (batch.isNotEmpty()) scope.launch { data.workouts.recordLiveHr(batch) }
    }
}

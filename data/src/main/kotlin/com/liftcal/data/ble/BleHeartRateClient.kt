package com.liftcal.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class BleHrDevice(val name: String?, val address: String, val rssi: Int)

sealed interface BleHrEvent {
    data object Connecting : BleHrEvent
    data object Connected : BleHrEvent
    data class Measurement(val epochMs: Long, val value: HeartRateMeasurement) : BleHrEvent
    data class Disconnected(val reason: String) : BleHrEvent
}

/**
 * Client for any standard BLE Heart Rate Service sensor (Polar H10, Garmin HRM, Wahoo TICKR...).
 * Used for live HR on screen and as a high-quality reference to calibrate wrist HR.
 */
class BleHeartRateClient(context: Context) {
    private val appContext = context.applicationContext
    private val adapter get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleHrDevice> = callbackFlow {
        check(hasPermissions()) { "Bluetooth permissions not granted" }
        val scanner = adapter?.bluetoothLeScanner ?: run { close(IllegalStateException("Bluetooth unavailable")); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(BleHrDevice(result.device.name ?: result.scanRecord?.deviceName, result.device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Flow<BleHrEvent> = callbackFlow {
        check(hasPermissions()) { "Bluetooth permissions not granted" }
        val device: BluetoothDevice = adapter?.getRemoteDevice(address)
            ?: run { close(IllegalStateException("Bluetooth unavailable")); return@callbackFlow }
        trySend(BleHrEvent.Connecting)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> trySend(BleHrEvent.Disconnected("status $status"))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val ch = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (ch == null) {
                    trySend(BleHrEvent.Disconnected("device has no heart rate service"))
                    return
                }
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                trySend(BleHrEvent.Connected)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                emit(characteristic, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                emit(characteristic, characteristic.value ?: return)
            }

            private fun emit(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != HR_MEASUREMENT) return
                HeartRateMeasurementParser.parse(value)?.let {
                    trySend(BleHrEvent.Measurement(System.currentTimeMillis(), it))
                }
            }
        }

        val gatt = device.connectGatt(appContext, true, callback, BluetoothDevice.TRANSPORT_LE)
        awaitClose {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

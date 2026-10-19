package com.example.disasterconnect.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide BLE advertisement of this phone's node id, so other phones can find it
 * from any screen, not only while Nearby Devices is open.
 *
 * Payload: service data [PROTOCOL_VERSION] + 3 node-id bytes (see rules.md §13).
 */
object BleAdvertiser {
    private const val PROTOCOL_VERSION: Byte = 1
    private val serviceUuid = ParcelUuid(DisasterConnectGatt.SERVICE_UUID)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var starting = false
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    /** Last AdvertiseCallback error code, or null. Many low-end phones cannot advertise at all. */
    private val _lastError = MutableStateFlow<Int?>(null)
    val lastError: StateFlow<Int?> = _lastError

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(context: Context, nodeBytes: ByteArray) {
        if (_isAdvertising.value || starting) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        if (adapter?.isEnabled != true) return
        val resolved = adapter.bluetoothLeAdvertiser ?: run {
            _lastError.value = AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(serviceUuid, byteArrayOf(PROTOCOL_VERSION) + nodeBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        try {
            starting = true
            advertiser = resolved
            resolved.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {
            starting = false
            advertiser = null
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
            // Bluetooth is already off.
        }
        advertiser = null
        starting = false
        _isAdvertising.value = false
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            synchronized(this@BleAdvertiser) { starting = false }
            _lastError.value = null
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            synchronized(this@BleAdvertiser) { starting = false }
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                _isAdvertising.value = true
                return
            }
            _isAdvertising.value = false
            _lastError.value = errorCode
        }
    }
}

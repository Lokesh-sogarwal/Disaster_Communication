package com.example.disasterconnect.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

enum class NearbyNodeState { NEARBY, CONNECTING, CONNECTED, LOST }

data class NetworkNearbyNode(
    val nodeId: String,
    val device: BluetoothDevice,
    val deviceName: String?,
    val rssi: Int,
    val lastSeenAt: Long,
    val state: NearbyNodeState = NearbyNodeState.NEARBY
)

/** One process-wide scanner and one observable node list for every screen. */
object NearbyNetworkRepository {
    private const val PROTOCOL_VERSION: Byte = 1
    private const val NODE_BYTES = 3
    private const val STALE_MILLIS = 15_000L
    private val serviceUuid = ParcelUuid.fromString("c7f9f1b5-0ce1-4dbe-9c75-c2d7dc5e9b0a")
    private val consumers = ConcurrentHashMap.newKeySet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false
    private var appContext: Context? = null
    private val _nodes = MutableStateFlow<Map<String, NetworkNearbyNode>>(emptyMap())
    val nodes: StateFlow<Map<String, NetworkNearbyNode>> = _nodes
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    @SuppressLint("MissingPermission")
    fun start(context: Context, consumer: String) {
        consumers.add(consumer)
        if (scanning || !hasPermissions(context)) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) return
        val resolvedScanner = adapter.bluetoothLeScanner ?: return
        appContext = context.applicationContext
        scanner = resolvedScanner
        try {
            resolvedScanner.startScan(
                listOf(ScanFilter.Builder().setServiceData(serviceUuid, byteArrayOf(PROTOCOL_VERSION), byteArrayOf(0xFF.toByte())).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(1_000L).build(),
                scanCallback
            )
            scanning = true
            _isScanning.value = true
            handler.post(presenceTicker)
        } catch (_: SecurityException) { stopInternal() }
    }

    @SuppressLint("MissingPermission")
    fun stop(consumer: String) {
        consumers.remove(consumer)
        if (consumers.isEmpty()) stopInternal()
    }

    fun updateConnection(nodeId: String, state: NearbyNodeState) {
        _nodes.value[nodeId]?.let { node -> _nodes.value = _nodes.value + (nodeId to node.copy(state = state)) }
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        handler.removeCallbacks(presenceTicker)
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanner = null; scanning = false; _isScanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
        override fun onScanFailed(errorCode: Int) = stopInternal()
    }

    private fun record(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(serviceUuid) ?: return
        if (data.size != NODE_BYTES + 1 || data.first() != PROTOCOL_VERSION) return
        val id = "DC-" + data.copyOfRange(1, data.size).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val old = _nodes.value[id]
        val name = try { result.device.name?.trim()?.takeIf(String::isNotEmpty) } catch (_: SecurityException) { null }
        _nodes.value = _nodes.value + (id to NetworkNearbyNode(id, result.device, name ?: old?.deviceName, result.rssi, System.currentTimeMillis(), old?.state?.takeIf { it == NearbyNodeState.CONNECTED || it == NearbyNodeState.CONNECTING } ?: NearbyNodeState.NEARBY))
    }

    private val presenceTicker = object : Runnable {
        override fun run() {
            if (!scanning) return
            val now = System.currentTimeMillis()
            _nodes.value = _nodes.value.mapValues { (_, node) -> if (node.state != NearbyNodeState.CONNECTED && now - node.lastSeenAt > STALE_MILLIS) node.copy(state = NearbyNodeState.LOST) else node }
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun hasPermissions(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
}

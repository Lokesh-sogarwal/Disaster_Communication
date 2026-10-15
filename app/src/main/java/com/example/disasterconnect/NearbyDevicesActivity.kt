package com.example.disasterconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.disasterconnect.ui.theme.DisasterConnectTheme
import com.example.disasterconnect.ble.DisasterConnectGattClient
import com.example.disasterconnect.ble.DisasterConnectGattServer
import com.example.disasterconnect.ble.BleConnectionManager
import com.example.disasterconnect.ble.GattConnectionEvent
import com.example.disasterconnect.ble.GattConnectionEventType
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NearbyNodeState
import androidx.compose.runtime.collectAsState

import java.security.SecureRandom


// =========================================================
// NEARBY DEVICE DISCOVERY ACTIVITY
// =========================================================

class NearbyDevicesActivity : ComponentActivity() {

    // =========================================================
    // BLUETOOTH
    // =========================================================

    private val bluetoothManager by lazy {

        getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    private val mainHandler =
        Handler(Looper.getMainLooper())


    // =========================================================
    // TEMPORARY NODE ID
    // =========================================================

    private val temporaryNodeBytes =
        ByteArray(TEMPORARY_NODE_ID_BYTES).also {

            SecureRandom().nextBytes(it)
        }

    private val temporaryNodeId =
        BleConnectionManager.nodeId(
            this,
            "DC-${temporaryNodeBytes.toHexId()}"
        )

    private var advertiseRetryAttempted = false


    // =========================================================
    // UI STATE
    // =========================================================

    private var discoveredNodes by mutableStateOf(
        emptyMap<String, NearbyNode>()
    )

    private var statusMessage by mutableStateOf(
        "Preparing nearby-device discovery..."
    )

    private var isScanning by mutableStateOf(false)

    // A manual stop remains stopped across a short background/foreground cycle.
    private var scanningRequested = true

    private var bluetoothStateReceiverRegistered = false

    private var isAdvertising by mutableStateOf(false)

    private var bluetoothUnavailable by mutableStateOf(false)

    private var bluetoothDisabled by mutableStateOf(false)

    private var connectedNodeId by mutableStateOf<String?>(null)

    private var connectionStatus by mutableStateOf("Not connected")

    private var handshakeSuccessful by mutableStateOf(false)

    // Drives relative "last seen" labels and marks stale nodes without
    // stopping the low-power scanner.
    private var discoveryClockMillis by mutableStateOf(System.currentTimeMillis())

    private val bluetoothStateReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                when (
                    intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                ) {
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> {

                        bluetoothDisabled = true

                        stopScanning(
                            "Bluetooth was turned off. Nearby scanning stopped."
                        )
                    }

                    BluetoothAdapter.STATE_ON -> {

                        bluetoothDisabled = false

                        if (scanningRequested) {

                            prepareDiscovery()
                        }
                    }
                }
            }
        }


    // =========================================================
    // ACTIVITY RESULT LAUNCHERS
    // =========================================================

    private val bluetoothPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                requiredBluetoothPermissions().all { permission ->

                    permissions[permission] == true ||
                            checkSelfPermission(permission) ==
                            PackageManager.PERMISSION_GRANTED
                }

            if (granted) {

                requestLocationIfRequired()

            } else {

                statusMessage =
                    "Nearby-device permission is required for BLE discovery."
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {

            if (hasLocationPermission()) {

                prepareDiscovery()

            } else {

                statusMessage =
                    "Location permission is required for BLE scanning on this Android version."
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {

            prepareDiscovery()
        }


    // =========================================================
    // BLE CALLBACKS
    // =========================================================

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) {

                isAdvertising = true

                if (!isScanning) {

                    statusMessage =
                        "Advertising as node $temporaryNodeId"
                }
            }

            override fun onStartFailure(
                errorCode: Int
            ) {

                isAdvertising = false

                if (
                    errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED &&
                    !advertiseRetryAttempted
                ) {

                    advertiseRetryAttempted = true

                    startAdvertising(
                        connectable = true
                    )

                    return
                }

                statusMessage =
                    advertisingErrorMessage(errorCode)
            }
        }

    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                recordDiscovery(result)
            }

            override fun onBatchScanResults(
                results: MutableList<ScanResult>
            ) {

                results.forEach(::recordDiscovery)
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                isScanning = false

                scanningRequested = false

                mainHandler.removeCallbacks(
                    presenceRefreshRunnable
                )

                statusMessage =
                    "BLE scan failed (error $errorCode). Tap Start Scanning to retry."
            }
        }


    // =========================================================
    // ACTIVITY LIFECYCLE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            DisasterConnectTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    val sharedNodes by NearbyNetworkRepository.nodes.collectAsState()

                    val sharedScanning by NearbyNetworkRepository.isScanning.collectAsState()

                    NearbyDevicesScreen(
                        temporaryNodeId = temporaryNodeId,
                        statusMessage = statusMessage,
                        isScanning = sharedScanning,
                        isAdvertising = isAdvertising,
                        bluetoothUnavailable = bluetoothUnavailable,
                        bluetoothDisabled = bluetoothDisabled,
                        connectedNodeId = connectedNodeId,
                        connectionStatus = connectionStatus,
                        handshakeSuccessful = handshakeSuccessful,
                        discoveryClockMillis = discoveryClockMillis,
                        nodes = sharedNodes.values.map { node ->
                            NearbyNode(
                                temporaryId = node.nodeId,
                                device = node.device,
                                deviceName = node.deviceName,
                                rssi = node.rssi,
                                lastSeenAt = node.lastSeenAt,
                                connectionState = NodeConnectionState.valueOf(node.state.name)
                            )
                        }.sortedByDescending {
                            it.rssi
                        },
                        onToggleScanning = ::toggleScanning,
                        onEnableBluetooth = ::requestBluetoothEnable,
                        onConnect = ::connectToNode,
                        onBack = ::finish
                    )
                }
            }
        }
    }

    override fun onResume() {

        super.onResume()

        registerBluetoothStateReceiver()

        prepareDiscovery()
    }

    override fun onPause() {

        unregisterBluetoothStateReceiver()

        stopScanning("Nearby-device scanning paused while this screen is inactive.")

        stopAdvertising()

        super.onPause()
    }

    override fun onDestroy() {

        mainHandler.removeCallbacksAndMessages(null)

        unregisterBluetoothStateReceiver()

        stopScanning("Nearby-device scanning stopped.")

        stopAdvertising()

        super.onDestroy()
    }


    // =========================================================
    // DISCOVERY SETUP
    // =========================================================

    private fun prepareDiscovery() {

        val adapter =
            bluetoothAdapter

        if (adapter == null || !packageManager.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE
            )
        ) {

            bluetoothUnavailable = true

            bluetoothDisabled = false

            statusMessage =
                "Bluetooth Low Energy is not available on this phone."

            return
        }

        bluetoothUnavailable = false

        if (!hasBluetoothPermissions()) {

            bluetoothPermissionLauncher.launch(
                requiredBluetoothPermissions()
            )

            return
        }

        if (requiresLocationPermission() && !hasLocationPermission()) {

            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )

            return
        }

        if (!adapter.isEnabled) {

            bluetoothDisabled = true

            statusMessage =
                "Turn on Bluetooth to discover DisasterConnect nodes."

            return
        }

        bluetoothDisabled = false

        BleConnectionManager.start(this, temporaryNodeId)

        startAdvertising()

        if (scanningRequested) {

            startScanning()
        }
    }

    private fun requestBluetoothEnable() {

        if (!hasBluetoothPermissions()) {

            bluetoothPermissionLauncher.launch(
                requiredBluetoothPermissions()
            )

            return
        }

        if (requiresLocationPermission() && !hasLocationPermission()) {

            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )

            return
        }

        enableBluetoothLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        )
    }


    // =========================================================
    // ADVERTISING
    // =========================================================

    private fun startAdvertising(
        connectable: Boolean = true
    ) {

        if (isAdvertising || !hasBluetoothPermissions()) {

            return
        }

        bluetoothLeAdvertiser =
            bluetoothAdapter?.bluetoothLeAdvertiser

        val advertiser =
            bluetoothLeAdvertiser
                ?: run {

                    statusMessage =
                        "This phone cannot advertise over BLE."

                    return
                }

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                )
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                )
                .setConnectable(connectable)
                .build()

        val data =
            AdvertiseData.Builder()
                .addServiceData(
                    DISASTER_CONNECT_SERVICE_UUID,
                    byteArrayOf(PROTOCOL_VERSION) + temporaryNodeBytes
                )
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

        try {

            advertiser.startAdvertising(
                settings,
                data,
                advertiseCallback
            )

        } catch (exception: SecurityException) {

            statusMessage =
                "Bluetooth permission was removed. Please allow it and retry."
        }
    }

    private fun stopAdvertising() {

        if (!isAdvertising || !hasBluetoothPermissions()) {

            isAdvertising = false

            return
        }

        try {

            bluetoothLeAdvertiser?.stopAdvertising(
                advertiseCallback
            )

        } catch (_: SecurityException) {

            // Permission was removed while the activity was stopping.
        }

        isAdvertising = false
    }


    // =========================================================
    // SCANNING
    // =========================================================

    private fun startScanning() {

        NearbyNetworkRepository.start(this, SCAN_CONSUMER)

        isScanning = NearbyNetworkRepository.isScanning.value

        statusMessage = "Continuously scanning for nearby DisasterConnect nodes."

        return

        if (isScanning) {

            return
        }

        if (!hasBluetoothPermissions()) {

            isScanning = false

            statusMessage =
                "Bluetooth permission is required for nearby-device scanning."

            return
        }

        bluetoothLeScanner =
            bluetoothAdapter?.bluetoothLeScanner

        val scanner =
            bluetoothLeScanner
                ?: run {

                    statusMessage =
                        "BLE scanning is unavailable on this phone."

                    return
                }

        val filters =
            listOf(
                ScanFilter.Builder()
                    .setServiceData(
                        DISASTER_CONNECT_SERVICE_UUID,
                        byteArrayOf(PROTOCOL_VERSION),
                        byteArrayOf(0xFF.toByte())
                    )
                    .build()
            )

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_POWER
                )
                .setReportDelay(SCAN_REPORT_DELAY_MILLIS)
                .build()

        try {

            scanner.startScan(
                filters,
                settings,
                scanCallback
            )

            isScanning = true

            statusMessage =
                "Continuously scanning for nearby DisasterConnect nodes."

            startPresenceTracking()

        } catch (exception: SecurityException) {

            isScanning = false

            statusMessage =
                "Bluetooth permission was removed. Please allow it and retry."
        }
    }

    private fun stopScanning(
        reason: String = "Nearby-device scanning paused."
    ) {

        NearbyNetworkRepository.stop(SCAN_CONSUMER)

        isScanning = false

        statusMessage = reason

        return

        mainHandler.removeCallbacks(presenceRefreshRunnable)

        if (!isScanning || !hasBluetoothPermissions()) {

            isScanning = false

            statusMessage = reason

            return
        }

        try {

            bluetoothLeScanner?.stopScan(scanCallback)

        } catch (_: SecurityException) {

            // Permission was removed while the activity was stopping.
        }

        isScanning = false

        statusMessage = reason
    }

    private fun toggleScanning() {

        if (isScanning) {

            scanningRequested = false

            stopScanning("Nearby-device scanning stopped by user.")

        } else {

            scanningRequested = true

            prepareDiscovery()
        }
    }

    private fun recordDiscovery(
        result: ScanResult
    ) {

        val serviceData =
            result.scanRecord
                ?.getServiceData(
                    DISASTER_CONNECT_SERVICE_UUID
                )
                ?: return

        if (
            serviceData.size != EXPECTED_SERVICE_DATA_LENGTH ||
            serviceData.first() != PROTOCOL_VERSION
        ) {

            return
        }

        val nodeId =
            "DC-" +
                    serviceData
                        .copyOfRange(
                            1,
                            serviceData.size
                        )
                        .toHexId()

        if (nodeId == temporaryNodeId) {

            return
        }

        discoveredNodes =
            discoveredNodes +
                    (
                        nodeId to
                                NearbyNode(
                                    temporaryId = nodeId,
                                    device = result.device,
                                    deviceName = safeDeviceName(result.device),
                                    rssi = result.rssi,
                                    lastSeenAt =
                                        System.currentTimeMillis(),
                                    connectionState =
                                        discoveredNodes[nodeId]
                                            ?.connectionState
                                            ?.takeIf {
                                                it == NodeConnectionState.CONNECTED ||
                                                        it == NodeConnectionState.CONNECTING
                                            }
                                            ?: NodeConnectionState.NEARBY
                                )
                    )
    }

    private fun startPresenceTracking() {

        mainHandler.removeCallbacks(presenceRefreshRunnable)

        mainHandler.post(presenceRefreshRunnable)
    }

    private val presenceRefreshRunnable =
        object : Runnable {

            override fun run() {

                if (!isScanning) {

                    return
                }

                if (!hasBluetoothPermissions()) {

                    scanningRequested = false

                    stopScanning(
                        "Bluetooth permission was revoked. Nearby scanning stopped."
                    )

                    return
                }

                if (bluetoothAdapter?.isEnabled != true) {

                    stopScanning(
                        "Bluetooth is off. Nearby scanning stopped."
                    )

                    return
                }

                if (bluetoothAdapter?.bluetoothLeScanner == null) {

                    stopScanning(
                        "BLE scanner is unavailable on this device."
                    )

                    return
                }

                val now = System.currentTimeMillis()

                discoveryClockMillis = now

                discoveredNodes =
                    discoveredNodes.mapValues { (_, node) ->

                        if (
                            node.connectionState != NodeConnectionState.CONNECTED &&
                            now - node.lastSeenAt > NODE_STALE_MILLIS
                        ) {

                            node.copy(
                                connectionState = NodeConnectionState.LOST
                            )

                        } else {

                            node
                        }
                    }

                mainHandler.postDelayed(
                    this,
                    PRESENCE_REFRESH_MILLIS
                )
            }
        }

    private fun safeDeviceName(
        device: BluetoothDevice
    ): String? {

        return try {

            device.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        } catch (_: SecurityException) {

            null
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothStateReceiver() {

        if (bluetoothStateReceiverRegistered) {

            return
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            registerReceiver(
                bluetoothStateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                bluetoothStateReceiver,
                filter
            )
        }

        bluetoothStateReceiverRegistered = true
    }

    private fun unregisterBluetoothStateReceiver() {

        if (!bluetoothStateReceiverRegistered) {

            return
        }

        try {

            unregisterReceiver(bluetoothStateReceiver)

        } catch (_: IllegalArgumentException) {

            // Already unregistered by the platform.
        }

        bluetoothStateReceiverRegistered = false
    }

    private fun connectToNode(node: NearbyNode) {

        if (!hasBluetoothPermissions()) {

            bluetoothPermissionLauncher.launch(
                requiredBluetoothPermissions()
            )

            return
        }

        if (node.temporaryId == connectedNodeId) {

            return
        }

        val connectionMessage = BleConnectionManager.connect(
            context = this,
            nodeId = node.temporaryId,
            device = node.device
        )

        connectionMessage?.let { message ->

            connectionStatus = message
        }
    }

    private fun updateConnection(
        nodeId: String?,
        status: String,
        handshakeSuccess: Boolean
    ) {

        runOnUiThread {

            connectedNodeId = nodeId

            connectionStatus = status

            handshakeSuccessful = handshakeSuccess
        }
    }

    /** Maps only real GATT callbacks/message events into observable Compose state. */
    private fun onGattEvent(event: GattConnectionEvent) {

        runOnUiThread {

            when (event.type) {

                GattConnectionEventType.CONNECTION_ESTABLISHED -> {

                    if (event.nodeId != null) {

                        connectedNodeId = event.nodeId
                    }

                    connectionStatus = event.detail ?: "GATT connection established."

                    handshakeSuccessful = false

                    event.nodeId?.let {

                        updateNodeConnectionState(
                            it,
                            NodeConnectionState.CONNECTING
                        )
                    }
                }

                GattConnectionEventType.SERVICES_DISCOVERED -> {

                    connectedNodeId = event.nodeId ?: connectedNodeId

                    connectionStatus = event.detail ?: "GATT services discovered."
                }

                GattConnectionEventType.HELLO_SENT,
                GattConnectionEventType.HELLO_RECEIVED,
                GattConnectionEventType.ACK_SENT,
                GattConnectionEventType.ACK_RECEIVED -> {

                    connectedNodeId = event.nodeId ?: connectedNodeId

                    connectionStatus = event.detail ?: "Handshake in progress."
                }

                GattConnectionEventType.HANDSHAKE_COMPLETED -> {

                    connectedNodeId = event.nodeId ?: connectedNodeId

                    connectionStatus = "CONNECTED"

                    handshakeSuccessful = true

                    event.nodeId?.let {

                        updateNodeConnectionState(
                            it,
                            NodeConnectionState.CONNECTED
                        )
                    }
                }

                GattConnectionEventType.CONNECTION_LOST,
                GattConnectionEventType.CONNECTION_FAILED -> {

                    val nodeLabel = event.nodeId?.let { " $it" } ?: ""

                    connectedNodeId = null

                    connectionStatus =
                        event.detail ?: "Connection$nodeLabel ended."

                    handshakeSuccessful = false

                    event.nodeId?.let {

                        updateNodeConnectionState(
                            it,
                            NodeConnectionState.LOST
                        )
                    }
                }
            }
        }
    }

    private fun updateNodeConnectionState(
        nodeId: String,
        state: NodeConnectionState
    ) {

        NearbyNetworkRepository.updateConnection(
            nodeId,
            NearbyNodeState.valueOf(state.name)
        )

        val node = discoveredNodes[nodeId] ?: return

        discoveredNodes =
            discoveredNodes +
                    (nodeId to node.copy(connectionState = state))
    }


    // =========================================================
    // PERMISSIONS
    // =========================================================

    private fun hasBluetoothPermissions(): Boolean {

        return requiredBluetoothPermissions().all { permission ->

            checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

        } else {

            emptyArray()
        }
    }

    private fun requiresLocationPermission(): Boolean {

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    }

    private fun hasLocationPermission(): Boolean {

        return checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationIfRequired() {

        if (requiresLocationPermission() && !hasLocationPermission()) {

            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )

        } else {

            prepareDiscovery()
        }
    }

    private fun advertisingErrorMessage(
        errorCode: Int
    ): String {

        val reason =
            when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "the BLE advertisement is too large"

                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "the phone has no advertiser slot available"

                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                    "advertising is already active"

                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                    "the Bluetooth chipset returned an internal error"

                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "this phone does not support BLE advertising"

                else ->
                    "the Bluetooth chipset rejected the request"
            }

        return "BLE advertising unavailable: $reason. " +
                "BLE scanning remains available when supported."
    }


    companion object {

        private const val PROTOCOL_VERSION: Byte = 1

        private const val TEMPORARY_NODE_ID_BYTES = 3

        private const val EXPECTED_SERVICE_DATA_LENGTH =
            TEMPORARY_NODE_ID_BYTES + 1

        private const val SCAN_REPORT_DELAY_MILLIS = 1_000L

        private const val PRESENCE_REFRESH_MILLIS = 1_000L

        private const val NODE_STALE_MILLIS = 15_000L

        private const val SCAN_CONSUMER = "nearby-devices-screen"

        private val DISASTER_CONNECT_SERVICE_UUID =
            ParcelUuid.fromString(
                "c7f9f1b5-0ce1-4dbe-9c75-c2d7dc5e9b0a"
            )

    }
}


// =========================================================
// DISCOVERED NODE MODEL
// =========================================================

data class NearbyNode(
    val temporaryId: String,
    val device: BluetoothDevice,
    val deviceName: String? = null,
    val rssi: Int,
    val lastSeenAt: Long,
    val connectionState: NodeConnectionState = NodeConnectionState.NEARBY
)

enum class NodeConnectionState {
    NEARBY,
    CONNECTING,
    CONNECTED,
    LOST
}


// =========================================================
// COMPOSE SCREEN
// =========================================================

@Composable
fun NearbyDevicesScreen(
    temporaryNodeId: String,
    statusMessage: String,
    isScanning: Boolean,
    isAdvertising: Boolean,
    bluetoothUnavailable: Boolean,
    bluetoothDisabled: Boolean,
    connectedNodeId: String?,
    connectionStatus: String,
    handshakeSuccessful: Boolean,
    discoveryClockMillis: Long,
    nodes: List<NearbyNode>,
    onToggleScanning: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onConnect: (NearbyNode) -> Unit,
    onBack: () -> Unit
) {

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "NEARBY DEVICES",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            TextButton(onClick = onBack) {

                Text("BACK")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {

            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "YOUR TEMPORARY NODE ID",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = temporaryNodeId,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text =
                        "Advertising: " +
                                if (isAdvertising) "ON" else "OFF",
                    color =
                        if (isAdvertising) Color(0xFF2E7D32) else Color.Red
                )

                Text(
                    text = statusMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (bluetoothUnavailable) {

            BluetoothUnavailableContent()

        } else if (bluetoothDisabled) {

            Button(
                onClick = onEnableBluetooth,
                modifier = Modifier.fillMaxWidth()
            ) {

                Text("TURN ON BLUETOOTH")
            }

        } else {

            Button(
                onClick = onToggleScanning,
                modifier = Modifier.fillMaxWidth()
            ) {

                Text(
                    if (isScanning)
                        "STOP SCANNING"
                    else
                        "START SCANNING"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {

            Column(modifier = Modifier.padding(14.dp)) {

                Text(
                    text = "GATT CONNECTION",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = connectionStatus,
                    color = if (handshakeSuccessful) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (connectedNodeId != null) {

                    Text(text = "Node: $connectedNodeId")

                    if (handshakeSuccessful) {

                        Text(
                            text = "Handshake: SUCCESS",
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "DISCOVERED NODES (${nodes.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (nodes.isEmpty()) {

            Text(
                text =
                    "No DisasterConnect nodes found yet. Keep another " +
                            "phone nearby with this screen open.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

        } else {

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                items(
                    items = nodes,
                    key = { it.temporaryId }
                ) { node ->

                    NearbyNodeCard(
                        node = node,
                        now = discoveryClockMillis,
                        onConnect = { onConnect(node) }
                    )
                }
            }
        }
    }
}


@Composable
private fun BluetoothUnavailableContent() {

    Text(
        text =
            "This device does not support Bluetooth Low Energy, so nearby " +
                    "DisasterConnect discovery cannot run here.",
        color = Color.Red
    )
}


@Composable
private fun NearbyNodeCard(
    node: NearbyNode,
    now: Long,
    onConnect: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled =
                    node.connectionState != NodeConnectionState.CONNECTED &&
                            node.connectionState != NodeConnectionState.CONNECTING,
                onClick = onConnect
            ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {

        Column(modifier = Modifier.padding(16.dp)) {

            Text(
                text = "DISASTERCONNECT NODE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Temporary ID: ${node.temporaryId}",
                fontWeight = FontWeight.Bold
            )

            node.deviceName?.let { name ->

                Text(
                    text = "Device: $name",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "RSSI: ${node.rssi} dBm (${node.rssi.signalStrengthLabel()})",
                color = node.rssi.signalStrengthColor()
            )

            Text(
                text = "Status: ${node.connectionState.displayLabel()}",
                color = node.connectionState.displayColor()
            )

            Text(
                text = "Last seen: ${node.lastSeenAt.lastSeenLabel(now)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    node.connectionState == NodeConnectionState.CONNECTED ->
                        "CONNECTED • Handshake: SUCCESS"
                    node.connectionState == NodeConnectionState.CONNECTING -> "CONNECTING…"
                    node.connectionState == NodeConnectionState.LOST -> "NODE NOT CURRENTLY VISIBLE"
                    else -> "TAP TO CONNECT"
                },
                color =
                    if (node.connectionState == NodeConnectionState.CONNECTED)
                        Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


// =========================================================
// DISPLAY HELPERS
// =========================================================

private fun ByteArray.toHexId(): String {

    return joinToString(separator = "") { byte ->

        "%02X".format(byte.toInt() and 0xFF)
    }
}

private fun Int.signalStrengthLabel(): String {

    return when {
        this >= -60 -> "Strong"
        this >= -75 -> "Medium"
        else -> "Weak"
    }
}

private fun Int.signalStrengthColor(): Color {

    return when {
        this >= -60 -> Color(0xFF2E7D32)
        this >= -75 -> Color(0xFFF57C00)
        else -> Color.Red
    }
}

private fun NodeConnectionState.displayLabel(): String {

    return when (this) {
        NodeConnectionState.NEARBY -> "NEARBY"
        NodeConnectionState.CONNECTING -> "CONNECTING"
        NodeConnectionState.CONNECTED -> "CONNECTED"
        NodeConnectionState.LOST -> "NOT CURRENTLY VISIBLE"
    }
}

private fun NodeConnectionState.displayColor(): Color {

    return when (this) {
        NodeConnectionState.CONNECTED -> Color(0xFF2E7D32)
        NodeConnectionState.CONNECTING -> Color(0xFFF57C00)
        NodeConnectionState.LOST -> Color.Red
        NodeConnectionState.NEARBY -> Color(0xFF2E7D32)
    }
}

private fun Long.lastSeenLabel(
    now: Long
): String {

    val elapsedSeconds = ((now - this).coerceAtLeast(0L)) / 1_000L

    return when {
        elapsedSeconds < 2 -> "Just now"
        elapsedSeconds < 60 -> "$elapsedSeconds sec ago"
        else -> "${elapsedSeconds / 60} min ago"
    }
}

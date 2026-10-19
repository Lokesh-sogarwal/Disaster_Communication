package com.example.disasterconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
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

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items


import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.example.disasterconnect.ui.theme.DisasterConnectTheme
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.disasterconnect.ble.ManagedConnection
import com.example.disasterconnect.nearby.ConnectionSuccessTracker
import com.example.disasterconnect.nearby.DeviceStatus
import com.example.disasterconnect.nearby.deviceStatus
import com.example.disasterconnect.ui.components.CardTone
import com.example.disasterconnect.ui.components.DisasterCard
import com.example.disasterconnect.ui.components.NoticeCard
import com.example.disasterconnect.ui.components.PrimaryButton
import com.example.disasterconnect.ui.components.ScreenHeader
import com.example.disasterconnect.ui.components.SecondaryButton
import com.example.disasterconnect.ui.components.StatusBadge
import com.example.disasterconnect.ui.components.StatusType
import com.example.disasterconnect.ui.theme.DisasterTheme
import com.example.disasterconnect.ui.theme.Sizes
import com.example.disasterconnect.ui.theme.Spacing
import kotlinx.coroutines.delay
import com.example.disasterconnect.ble.BleAdvertiser
import com.example.disasterconnect.ble.BleConnectionManager
import com.example.disasterconnect.messaging.MessagingCoordinator
import com.example.disasterconnect.ble.GattConnectionEvent
import com.example.disasterconnect.ble.GattConnectionEventType
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NearbyNodeState
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Lock
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.security.KeyTrustStatus
import com.example.disasterconnect.security.keyTrustStatus
import com.example.disasterconnect.ui.components.TertiaryButton
import kotlinx.coroutines.flow.MutableStateFlow



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

    private val mainHandler =
        Handler(Looper.getMainLooper())


    // =========================================================
    // TEMPORARY NODE ID
    // =========================================================

    // Lazy: the Activity context is not usable until onCreate.
    private val temporaryNodeId by lazy {

        BleConnectionManager.nodeId(this)
    }

    // Derived from the persisted id so the advertised id matches the GATT id.
    private val temporaryNodeBytes by lazy {

        BleConnectionManager.nodeIdBytes(temporaryNodeId)
    }


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

    private var bluetoothUnavailable by mutableStateOf(false)

    private var bluetoothDisabled by mutableStateOf(false)

    private var bluetoothPermissionMissing by mutableStateOf(false)

    // Why BleConnectionManager.connect() declined a tap, shown on that device's card.
    private var connectNotice by mutableStateOf<Pair<String, String>?>(null)

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

                    val advertising by BleAdvertiser.isAdvertising.collectAsState()

                    val advertiseError by BleAdvertiser.lastError.collectAsState()

                    val connections by BleConnectionManager.connections.collectAsState()

                    // Personal-contact verification status. Display only; connecting, messaging and SOS ignore it.
                    val trustedKeys by remember { AppDatabase.getDatabase(this).trustedKeyDao().observeAll() }
                        .collectAsState(initial = emptyList())

                    val keyExchange by MessagingCoordinator.keyExchange.collectAsState()

                    val keyConflicts by remember(keyExchange) { keyExchange?.conflicts ?: MutableStateFlow(emptySet()) }
                        .collectAsState()

                    NearbyDevicesScreen(
                        localNodeId = temporaryNodeId,
                        bluetooth =
                            when {
                                bluetoothUnavailable -> BluetoothReadiness.UNSUPPORTED
                                bluetoothPermissionMissing -> BluetoothReadiness.PERMISSION_NEEDED
                                bluetoothDisabled -> BluetoothReadiness.OFF
                                else -> BluetoothReadiness.READY
                            },
                        isScanning = sharedScanning,
                        isAdvertising = advertising,
                        advertisingProblem = advertiseError?.let(::advertisingErrorMessage),
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
                        connections = connections,
                        outgoingLinkNodeId = BleConnectionManager.outgoingLinkNodeId,
                        connectNotice = connectNotice,
                        onStartScanning = {
                            scanningRequested = true
                            prepareDiscovery()
                        },
                        onStopScanning = {
                            scanningRequested = false
                            stopScanning("Nearby-device scanning stopped by user.")
                        },
                        onRequestPermission = ::prepareDiscovery,
                        onOpenSettings = ::openAppSettings,
                        onEnableBluetooth = ::requestBluetoothEnable,
                        onConnect = ::connectToNode,
                        onDisconnect = { node ->
                            BleConnectionManager.disconnect(node.temporaryId)
                        },
                        onMessage = { node ->
                            startActivity(ChatActivity.intent(this, node.temporaryId))
                        },
                        onBack = ::finish,
                        keyTrust = { nodeId -> keyTrustStatus(nodeId, trustedKeys, keyConflicts) },
                        onVerify = { node ->
                            startActivity(VerificationActivity.intent(this, node.temporaryId))
                        }
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

        super.onPause()
    }

    override fun onDestroy() {

        mainHandler.removeCallbacksAndMessages(null)

        unregisterBluetoothStateReceiver()

        stopScanning("Nearby-device scanning stopped.")

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

        bluetoothPermissionMissing =
            !hasBluetoothPermissions() ||
                    (requiresLocationPermission() && !hasLocationPermission())

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

        // Starts the GATT server and advertising for the whole app, not just this screen.
        MessagingCoordinator.start(this)

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

        // null means the manager accepted and is now CONNECTING; otherwise it explains why not.
        connectNotice =
            connectionMessage?.let { message ->
                node.temporaryId to message
            }
    }


    private fun openAppSettings() {

        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
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
// Every status shown here comes from NearbyNetworkRepository (scanning),
// BleAdvertiser (visibility) and BleConnectionManager (connections).

/** Bluetooth readiness as checked by prepareDiscovery(). */
enum class BluetoothReadiness {
    READY,
    UNSUPPORTED,
    PERMISSION_NEEDED,
    OFF
}

@Composable
fun NearbyDevicesScreen(

    localNodeId: String,

    bluetooth: BluetoothReadiness,

    isScanning: Boolean,

    isAdvertising: Boolean,

    advertisingProblem: String?,

    nodes: List<NearbyNode>,

    connections: Map<String, ManagedConnection>,

    outgoingLinkNodeId: String?,

    connectNotice: Pair<String, String>?,

    onStartScanning: () -> Unit,

    onStopScanning: () -> Unit,

    onRequestPermission: () -> Unit,

    onOpenSettings: () -> Unit,

    onEnableBluetooth: () -> Unit,

    onConnect: (NearbyNode) -> Unit,

    onDisconnect: (NearbyNode) -> Unit,

    onMessage: (NearbyNode) -> Unit,

    onBack: () -> Unit,

    keyTrust: (String) -> KeyTrustStatus? = { null },

    onVerify: (NearbyNode) -> Unit = {}
) {

    // ---------------------------------------------------------
    // Success animation events (one per observed, completed handshake)
    // ---------------------------------------------------------

    val tracker = remember { ConnectionSuccessTracker() }

    val successEvents = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(connections) {

        tracker.onConnections(connections).forEach { nodeId ->
            successEvents[nodeId] = System.nanoTime()
        }
    }

    // Keeps "last seen" labels current; the scanner itself is not touched.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {

        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }

    val reduceMotion = rememberReduceMotion()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        contentPadding =
            PaddingValues(
                horizontal = Spacing.screen,
                vertical = Spacing.lg
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {

        // =====================================================
        // HEADER
        // =====================================================

        item {

            ScreenHeader(
                title = "Nearby Devices",
                subtitle = "Connect with nearby devices to exchange emergency messages.",
                onBack = onBack
            )
        }


        // =====================================================
        // YOUR DEVICE
        // =====================================================

        item {

            YourDeviceCard(
                localNodeId = localNodeId,
                bluetooth = bluetooth,
                isAdvertising = isAdvertising,
                advertisingProblem = advertisingProblem
            )
        }


        // =====================================================
        // DISCOVERY CONTROLS
        // =====================================================

        item {

            DiscoverySection(
                bluetooth = bluetooth,
                isScanning = isScanning,
                reduceMotion = reduceMotion,
                onStartScanning = onStartScanning,
                onStopScanning = onStopScanning,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                onEnableBluetooth = onEnableBluetooth
            )
        }


        // =====================================================
        // DEVICE LIST
        // =====================================================

        item {

            Text(
                text = "Devices found (${nodes.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
        }

        if (nodes.isEmpty()) {

            item {

                Text(
                    text =
                        when {
                            bluetooth != BluetoothReadiness.READY ->
                                "Devices will appear here once Bluetooth is ready."
                            isScanning ->
                                "Looking for devices… This can take up to a minute."
                            else ->
                                "No nearby devices found. Keep Bluetooth enabled and try scanning again."
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(
            items = nodes,
            key = { it.temporaryId }
        ) { node ->

            val connection = connections[node.temporaryId]

            val status =
                deviceStatus(
                    connection = connection,
                    visible = node.connectionState != NodeConnectionState.LOST
                )

            val successEvent = successEvents[node.temporaryId]

            // Drop the celebration as soon as it no longer matches reality.
            if (successEvent != null && status != DeviceStatus.CONNECTED) {

                SideEffect { successEvents.remove(node.temporaryId) }
            }

            NearbyDeviceCard(
                node = node,
                status = status,
                connection = connection,
                now = now,
                successEvent = successEvent?.takeIf { status == DeviceStatus.CONNECTED },
                onSuccessShown = { successEvents.remove(node.temporaryId, successEvent) },
                canDisconnect = outgoingLinkNodeId == node.temporaryId,
                // A refused connect attempt is stale once the device is connected (e.g. it connected to this phone).
                notice = connectNotice?.takeIf { it.first == node.temporaryId && status != DeviceStatus.CONNECTED }?.second,
                reduceMotion = reduceMotion,
                onConnect = { onConnect(node) },
                onDisconnect = { onDisconnect(node) },
                onMessage = { onMessage(node) },
                keyTrust = keyTrust(node.temporaryId),
                onVerify = { onVerify(node) }
            )
        }
    }
}


// =========================================================
// YOUR DEVICE
// =========================================================

@Composable
private fun YourDeviceCard(

    localNodeId: String,

    bluetooth: BluetoothReadiness,

    isAdvertising: Boolean,

    advertisingProblem: String?
) {

    DisasterCard {

        Text(
            text = "Your Device",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = localNodeId,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { contentDescription = "Your device ID $localNodeId" }
        )

        Text(
            text = "Nearby phones see this ID.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        LabelledStatus(label = "Bluetooth") {

            when (bluetooth) {
                BluetoothReadiness.READY -> StatusBadge(StatusType.CONNECTED, label = "On")
                BluetoothReadiness.OFF -> StatusBadge(StatusType.DISCONNECTED, label = "Off")
                BluetoothReadiness.PERMISSION_NEEDED -> StatusBadge(StatusType.WARNING, label = "Permission needed")
                BluetoothReadiness.UNSUPPORTED -> StatusBadge(StatusType.WARNING, label = "Not supported")
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        LabelledStatus(label = "Visible to others") {

            when {
                isAdvertising -> StatusBadge(StatusType.CONNECTED, label = "Yes")
                advertisingProblem != null -> StatusBadge(StatusType.WARNING, label = "No")
                else -> StatusBadge(StatusType.DISCONNECTED, label = "Not yet")
            }
        }

        if (!isAdvertising && advertisingProblem != null) {

            Spacer(modifier = Modifier.height(Spacing.xs))

            Text(
                text = advertisingProblem,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun LabelledStatus(

    label: String,

    badge: @Composable () -> Unit
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(Spacing.sm))

        badge()
    }
}


// =========================================================
// DISCOVERY CONTROLS
// =========================================================

@Composable
private fun DiscoverySection(

    bluetooth: BluetoothReadiness,

    isScanning: Boolean,

    reduceMotion: Boolean,

    onStartScanning: () -> Unit,

    onStopScanning: () -> Unit,

    onRequestPermission: () -> Unit,

    onOpenSettings: () -> Unit,

    onEnableBluetooth: () -> Unit
) {

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        when (bluetooth) {

            BluetoothReadiness.UNSUPPORTED ->
                NoticeCard(
                    title = "Bluetooth is not supported",
                    message =
                        "This phone cannot use Bluetooth Low Energy, so it cannot find " +
                                "nearby devices. Messages you write are kept on this phone.",
                    tone = CardTone.WARNING
                )

            BluetoothReadiness.PERMISSION_NEEDED -> {

                NoticeCard(
                    title = "Permission needed",
                    message =
                        "Allow the Nearby devices permission so DisasterConnect can find phones " +
                                "around you. Until then, messages wait on this phone.",
                    tone = CardTone.WARNING
                )

                PrimaryButton(
                    text = "Allow permission",
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth()
                )

                SecondaryButton(
                    text = "Open settings",
                    onClick = onOpenSettings,
                    icon = Icons.Filled.Settings,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            BluetoothReadiness.OFF -> {

                NoticeCard(
                    title = "Bluetooth is off",
                    message = "Turn on Bluetooth to find nearby devices and send messages.",
                    tone = CardTone.WARNING
                )

                PrimaryButton(
                    text = "Turn on Bluetooth",
                    onClick = onEnableBluetooth,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            BluetoothReadiness.READY ->
                if (isScanning) {

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {
                                    liveRegion = LiveRegionMode.Polite
                                },
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        ActivityIndicator(
                            reduceMotion = reduceMotion,
                            description = "Scanning"
                        )

                        Spacer(modifier = Modifier.width(Spacing.md))

                        Text(
                            text = "Scanning for nearby devices…",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SecondaryButton(
                        text = "Stop scanning",
                        onClick = onStopScanning,
                        modifier = Modifier.fillMaxWidth()
                    )

                } else {

                    Text(
                        text = "Scanning is off.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    PrimaryButton(
                        text = "Scan for Devices",
                        onClick = onStartScanning,
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
        }
    }
}


// =========================================================
// DEVICE CARD
// =========================================================

@Composable
private fun NearbyDeviceCard(

    node: NearbyNode,

    status: DeviceStatus,

    connection: ManagedConnection?,

    now: Long,

    successEvent: Long?,

    onSuccessShown: () -> Unit,

    canDisconnect: Boolean,

    notice: String?,

    reduceMotion: Boolean,

    onConnect: () -> Unit,

    onDisconnect: () -> Unit,

    onMessage: () -> Unit,

    keyTrust: KeyTrustStatus?,

    onVerify: () -> Unit
) {

    DisasterCard {

        // ---------------------------------------------------------
        // Identity and status
        // ---------------------------------------------------------

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = node.temporaryId,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                node.deviceName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            StatusBadge(
                status = status.badgeType(),
                label = status.badgeLabel()
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        Text(
            text =
                "Signal: ${node.rssi.signalStrengthLabel()} • Seen ${node.lastSeenAt.lastSeenLabel(now).lowercase()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (status == DeviceStatus.OUT_OF_RANGE) {

            Text(
                text = "Not seen recently. It may be out of range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }


        // ---------------------------------------------------------
        // Live connection feedback
        // ---------------------------------------------------------

        ConnectionFeedback(
            nodeId = node.temporaryId,
            status = status,
            detail = connection?.detail,
            successEvent = successEvent,
            onSuccessShown = onSuccessShown,
            reduceMotion = reduceMotion
        )

        notice?.let {

            Spacer(modifier = Modifier.height(Spacing.sm))

            StatusBadge(
                status = StatusType.WARNING,
                label = it
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))


        // ---------------------------------------------------------
        // Actions
        // ---------------------------------------------------------

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {

            when (status) {

                DeviceStatus.AVAILABLE,
                DeviceStatus.OUT_OF_RANGE ->
                    PrimaryButton(
                        text = "Connect",
                        onClick = onConnect,
                        modifier = Modifier.weight(1f)
                    )

                DeviceStatus.FAILED ->
                    PrimaryButton(
                        text = "Try again",
                        onClick = onConnect,
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.weight(1f)
                    )

                DeviceStatus.DISCONNECTED ->
                    PrimaryButton(
                        text = "Reconnect",
                        onClick = onConnect,
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.weight(1f)
                    )

                // Disabled while the manager is working, so repeated taps cannot start a second attempt.
                DeviceStatus.CONNECTING ->
                    PrimaryButton(
                        text = "Connecting…",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f)
                    )

                DeviceStatus.DISCONNECTING ->
                    PrimaryButton(
                        text = "Disconnecting…",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f)
                    )

                DeviceStatus.CONNECTED ->
                    if (canDisconnect) {
                        SecondaryButton(
                            text = "Disconnect",
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f)
                        )
                    }
            }

            // Messages are stored and sent when a link exists, so this is always available.
            if (status == DeviceStatus.CONNECTED) {
                PrimaryButton(
                    text = "Message",
                    onClick = onMessage,
                    icon = Icons.Filled.Email,
                    modifier = Modifier.weight(1f)
                )
            } else {
                SecondaryButton(
                    text = "Message",
                    onClick = onMessage,
                    icon = Icons.Filled.Email,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (status != DeviceStatus.CONNECTED) {

            Spacer(modifier = Modifier.height(Spacing.xs))

            Text(
                text = "Messages wait on this phone and send once you are connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }


        // ---------------------------------------------------------
        // Contact verification (personal messages only)
        // ---------------------------------------------------------

        keyTrust?.let { trust ->

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                StatusBadge(
                    status = trust.badgeType(),
                    label = trust.label
                )

                Spacer(modifier = Modifier.weight(1f))

                TertiaryButton(
                    text = if (trust == KeyTrustStatus.VERIFIED) "Safety code" else "Verify",
                    onClick = onVerify,
                    icon = Icons.Filled.Lock
                )
            }
        }
    }
}


@Composable
private fun ConnectionFeedback(

    nodeId: String,

    status: DeviceStatus,

    detail: String?,

    successEvent: Long?,

    onSuccessShown: () -> Unit,

    reduceMotion: Boolean
) {

    // Status text is announced by TalkBack when it changes.
    val liveModifier =
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }

    when {

        status == DeviceStatus.CONNECTING ->
            Row(
                modifier = liveModifier,
                verticalAlignment = Alignment.CenterVertically
            ) {

                ActivityIndicator(
                    reduceMotion = reduceMotion,
                    description = "Connecting to $nodeId"
                )

                Spacer(modifier = Modifier.width(Spacing.md))

                Column(modifier = Modifier.weight(1f)) {

                    Text(
                        text = "Connecting to device…",
                        style = MaterialTheme.typography.titleSmall
                    )

                    Text(
                        text = detail?.takeIf { it.isNotBlank() } ?: nodeId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        successEvent != null ->
            SuccessBanner(
                nodeId = nodeId,
                event = successEvent,
                onShown = onSuccessShown,
                reduceMotion = reduceMotion,
                modifier = liveModifier
            )

        status == DeviceStatus.FAILED ->
            FeedbackRow(
                icon = Icons.Outlined.Warning,
                title = "Connection failed. Try again.",
                detail = detail,
                modifier = liveModifier
            )

        status == DeviceStatus.DISCONNECTED ->
            FeedbackRow(
                icon = Icons.Outlined.Close,
                title = "Disconnected",
                detail = "The connection to $nodeId ended.",
                modifier = liveModifier
            )

        status == DeviceStatus.DISCONNECTING ->
            FeedbackRow(
                icon = Icons.Outlined.Close,
                title = "Disconnecting…",
                detail = null,
                modifier = liveModifier
            )
    }
}


/** Checkmark that scales and fades in after a confirmed handshake, then gives way to the normal card. */
@Composable
private fun SuccessBanner(

    nodeId: String,

    event: Long,

    onShown: () -> Unit,

    reduceMotion: Boolean,

    modifier: Modifier = Modifier
) {

    val visible = remember(event) { MutableTransitionState(reduceMotion) }

    LaunchedEffect(event) {

        visible.targetState = true

        delay(SUCCESS_VISIBLE_MILLIS)

        onShown()
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = DisasterTheme.colors.info,
        contentColor = DisasterTheme.colors.onInfo
    ) {

        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {

            AnimatedVisibility(
                visibleState = visible,
                enter =
                    if (reduceMotion) EnterTransition.None
                    else fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.6f)
            ) {

                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Connected",
                    modifier = Modifier.size(Sizes.icon + Spacing.sm)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column {

                Text(
                    text = "Successfully connected!",
                    style = MaterialTheme.typography.titleSmall
                )

                Text(
                    text = "Connected to $nodeId",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}


@Composable
private fun FeedbackRow(

    icon: ImageVector,

    title: String,

    detail: String?,

    modifier: Modifier = Modifier
) {

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(Sizes.icon)
        )

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )

            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/** Small spinner; a still icon when the user has turned animations off. */
@Composable
private fun ActivityIndicator(

    reduceMotion: Boolean,

    description: String
) {

    val modifier =
        Modifier
            .size(Sizes.icon)
            .semantics { contentDescription = description }

    if (reduceMotion) {

        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = modifier
        )

    } else {

        CircularProgressIndicator(
            modifier = modifier,
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}


/** True when the system animator duration scale is 0 (animations turned off). */
@Composable
private fun rememberReduceMotion(): Boolean {

    val resolver = LocalContext.current.contentResolver

    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private const val SUCCESS_VISIBLE_MILLIS = 2_500L

private fun DeviceStatus.badgeType(): StatusType = when (this) {
    DeviceStatus.CONNECTED -> StatusType.CONNECTED
    DeviceStatus.CONNECTING, DeviceStatus.DISCONNECTING -> StatusType.PENDING
    DeviceStatus.FAILED -> StatusType.WARNING
    DeviceStatus.AVAILABLE, DeviceStatus.OUT_OF_RANGE, DeviceStatus.DISCONNECTED -> StatusType.DISCONNECTED
}

private fun DeviceStatus.badgeLabel(): String = when (this) {
    DeviceStatus.AVAILABLE -> "Not connected"
    DeviceStatus.OUT_OF_RANGE -> "Out of range"
    DeviceStatus.CONNECTING -> "Connecting"
    DeviceStatus.CONNECTED -> "Connected"
    DeviceStatus.DISCONNECTING -> "Disconnecting"
    DeviceStatus.FAILED -> "Failed"
    DeviceStatus.DISCONNECTED -> "Disconnected"
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

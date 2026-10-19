package com.example.disasterconnect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding


import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.example.disasterconnect.ble.BleConnectionManager
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.data.SosPacket
import com.example.disasterconnect.messaging.MessagingCoordinator
import com.example.disasterconnect.network.NetworkDecisionEngine
import com.example.disasterconnect.network.OfflineRelayQueue
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NetworkNearbyNode
import com.example.disasterconnect.network.NearbyNodeState
import com.example.disasterconnect.ui.theme.DisasterConnectTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.map.LocationMap
import com.example.disasterconnect.map.MapLoadState
import com.example.disasterconnect.map.UserLocationLegendDot
import com.example.disasterconnect.ui.components.CardTone
import com.example.disasterconnect.ui.components.DisasterCard
import com.example.disasterconnect.ui.components.EmergencyButton
import com.example.disasterconnect.ui.components.NoticeCard
import com.example.disasterconnect.ui.components.PrimaryButton
import com.example.disasterconnect.ui.components.ScreenHeader
import com.example.disasterconnect.ui.components.SecondaryButton
import com.example.disasterconnect.ui.components.StatusBadge
import com.example.disasterconnect.ui.components.StatusType
import com.example.disasterconnect.ui.components.TertiaryButton
import com.example.disasterconnect.ui.theme.Sizes
import com.example.disasterconnect.ui.theme.Spacing

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID


class MainActivity : ComponentActivity() {

    private val nearbyDiscoveryPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            // Android returns an empty result when another permission request (location) is
            // already showing. Treat that as "not yet"; onResume asks again afterwards.
            // Without this check, empty == "all granted" and the request loops until a crash.
            if (permissions.isNotEmpty() && permissions.values.all { it }) startDashboardDiscovery()
        }

    // =========================================================
    // DATABASE
    // =========================================================

    private lateinit var database: AppDatabase


    // =========================================================
    // LOCATION
    // =========================================================

    private lateinit var fusedLocationClient:
            FusedLocationProviderClient

    private var permissionGranted by mutableStateOf(false)

    private var permissionDenied by mutableStateOf(false)

    private var latitude by mutableStateOf<Double?>(null)

    private var longitude by mutableStateOf<Double?>(null)

    private var accuracy by mutableStateOf<Float?>(null)

    private var locationMessage by mutableStateOf(
        "Getting your location..."
    )


    // =========================================================
    // NETWORK
    // =========================================================

    private var internetAvailable by mutableStateOf(false)

    private var networkType by mutableStateOf("Unknown")


    // =========================================================
    // SOS
    // =========================================================

    private var showSosDialog by mutableStateOf(false)


    // =========================================================
    // LOCATION PERMISSION RESULT
    // =========================================================

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineLocation =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true

            val coarseLocation =
                permissions[
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ] == true

            if (fineLocation || coarseLocation) {

                permissionGranted = true

                permissionDenied = false

                getCurrentLocation()

            } else {

                permissionGranted = false

                permissionDenied = true

                locationMessage =
                    "Location permission is required"
            }
        }


    // =========================================================
    // ACTIVITY CREATE
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Database
        database =
            AppDatabase.getDatabase(this)

        // Location
        fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(this)

        // Network
        updateNetworkStatus()

        // Permission
        permissionGranted =
            hasLocationPermission()

        // =====================================================
        // UI
        // =====================================================

        setContent {

            val nearbyNodes by NearbyNetworkRepository.nodes.collectAsState()

            val nearbyScanning by NearbyNetworkRepository.isScanning.collectAsState()

            // Read-only view of the existing message table, used for the "waiting to send" count.
            val storedMessages by remember {
                database.emergencyMessageDao().observeAll()
            }.collectAsState(initial = emptyList())

            val coroutineScope =
                rememberCoroutineScope()

            DisasterConnectTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color =
                        MaterialTheme
                            .colorScheme
                            .background
                ) {

                    // The dashboard is always shown so SOS is never hidden behind a permission screen.
                    DisasterConnectHome(

                        locationState = currentLocationState(),

                        latitude = latitude,

                        longitude = longitude,

                        accuracy = accuracy,

                        internetAvailable =
                            internetAvailable,

                        networkType =
                            networkType,

                        nearbyNodes = nearbyNodes.values.toList(),

                        nearbyScanning = nearbyScanning,

                        messagesWaiting = storedMessages.count { it.isWaitingToSend() },

                        onRefreshLocation = {

                            getCurrentLocation()

                            updateNetworkStatus()
                        },

                        onAllowLocation = {

                            requestLocationPermission()
                        },

                        onOpenSettings = {

                            openAppSettings()
                        },

                        onOpenMap = {

                            val intent =
                                Intent(
                                    this@MainActivity,
                                    MapActivity::class.java
                                )

                            latitude?.let {

                                intent.putExtra(
                                    "latitude",
                                    it
                                )
                            }

                            longitude?.let {

                                intent.putExtra(
                                    "longitude",
                                    it
                                )
                            }

                            startActivity(intent)
                        },

                        onOpenNearbyDevices = {

                            startActivity(
                                Intent(
                                    this@MainActivity,
                                    NearbyDevicesActivity::class.java
                                )
                            )
                        },

                        onOpenMessages = {

                            startActivity(
                                ChatActivity.intent(
                                    this@MainActivity,
                                    peerNodeId = null
                                )
                            )
                        },

                        onSendSos = {

                            showSosDialog = true
                        }
                    )


                    // =================================================
                    // SOS DIALOG
                    // =================================================

                    if (showSosDialog) {

                        SosConfirmationDialog(

                            onConfirm = { message ->

                                showSosDialog = false

                                coroutineScope.launch {

                                    triggerSos(message)
                                }
                            },

                            onDismiss = {

                                showSosDialog = false
                            }
                        )
                    }
                }
            }
        }


        // =====================================================
        // INITIAL PERMISSION CHECK
        // =====================================================

        if (!permissionGranted) {

            requestLocationPermission()

        } else {

            getCurrentLocation()
        }
    }


    // =========================================================
    // APP RESUME
    // =========================================================

    override fun onResume() {

        super.onResume()

        // Picks up a permission granted from system Settings while the app was in the background.
        permissionGranted =
            hasLocationPermission()

        if (permissionGranted) {

            permissionDenied = false
        }

        updateNetworkStatus()

        startDashboardDiscovery()

        if (hasLocationPermission()) {

            getCurrentLocation()
        }
    }

    override fun onPause() {

        NearbyNetworkRepository.stop("main-dashboard")

        super.onPause()
    }

    private fun startDashboardDiscovery() {

        val bluetoothPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            bluetoothPermissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        ) {
            nearbyDiscoveryPermissionLauncher.launch(bluetoothPermissions)
            return
        }

        NearbyNetworkRepository.start(this, "main-dashboard")

        // Keeps this phone discoverable and delivering queued messages from the dashboard too.
        MessagingCoordinator.start(this)
    }


    // =========================================================
    // NETWORK STATUS
    // =========================================================

    private fun updateNetworkStatus() {

        internetAvailable =
            isInternetAvailable()

        networkType =
            getActiveNetworkType()
    }


    private fun isInternetAvailable(): Boolean {

        val connectivityManager =
            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            connectivityManager.activeNetwork
                ?: return false

        val capabilities =
            connectivityManager.getNetworkCapabilities(
                network
            )
                ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) &&
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
    }


    private fun getActiveNetworkType(): String {

        val connectivityManager =
            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            connectivityManager.activeNetwork
                ?: return "OFFLINE"

        val capabilities =
            connectivityManager
                .getNetworkCapabilities(network)
                ?: return "UNKNOWN"

        return when {

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) -> {

                "Wi-Fi"
            }

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) -> {

                getCellularNetworkType()
            }

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_ETHERNET
            ) -> {

                "Ethernet"
            }

            else -> {

                "Unknown"
            }
        }
    }


    private fun getCellularNetworkType(): String {

        if (
            checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return "Mobile"
        }

        return try {

            val telephonyManager =
                getSystemService(
                    Context.TELEPHONY_SERVICE
                ) as TelephonyManager

            when (telephonyManager.dataNetworkType) {

                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE -> {

                    "2G"
                }

                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> {

                    "3G"
                }

                TelephonyManager.NETWORK_TYPE_LTE -> {

                    "4G"
                }

                TelephonyManager.NETWORK_TYPE_NR -> {

                    "5G"
                }

                else -> {

                    "Mobile"
                }
            }

        } catch (e: Exception) {

            "Mobile"
        }
    }


    // =========================================================
    // LOCATION PERMISSION
    // =========================================================

    private fun hasLocationPermission(): Boolean {

        val fineLocation =
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation =
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }


    private fun requestLocationPermission() {

        locationPermissionLauncher.launch(

            arrayOf(

                Manifest.permission.ACCESS_FINE_LOCATION,

                Manifest.permission.ACCESS_COARSE_LOCATION,

                Manifest.permission.READ_PHONE_STATE
            )
        )
    }


    private fun openAppSettings() {

        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }


    // ---------------------------------------------------------
    // Home location state (derived only; location logic unchanged)
    // ---------------------------------------------------------

    private fun currentLocationState(): HomeLocationState {

        return when {

            permissionGranted && latitude != null && longitude != null ->
                HomeLocationState.AVAILABLE

            permissionDenied ->
                HomeLocationState.PERMISSION_DENIED

            !permissionGranted ->
                HomeLocationState.WAITING_FOR_PERMISSION

            // Same text getCurrentLocation() shows while a request is running.
            locationMessage == "Getting your location..." ->
                HomeLocationState.LOCATING

            else ->
                HomeLocationState.UNAVAILABLE
        }
    }


    // =========================================================
    // GET CURRENT LOCATION
    // =========================================================

    private fun getCurrentLocation() {

        if (!hasLocationPermission()) {

            locationMessage =
                "Location permission not granted"

            return
        }

        locationMessage =
            "Getting your location..."


        val priority =

            if (
                checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                Priority.PRIORITY_HIGH_ACCURACY

            } else {

                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }


        fusedLocationClient

            .getCurrentLocation(
                priority,
                null
            )

            .addOnSuccessListener { location: Location? ->

                if (location != null) {

                    latitude =
                        location.latitude

                    longitude =
                        location.longitude

                    accuracy =
                        location.accuracy

                    locationMessage =
                        "Location detected"

                } else {

                    locationMessage =
                        "Unable to get current location"
                }
            }

            .addOnFailureListener {

                locationMessage =
                    "Location service failed: ${it.message}"
            }
    }


    // =========================================================
    // TRIGGER SOS
    // =========================================================

    private suspend fun triggerSos(
        message: String
    ) {

        if (!hasLocationPermission()) {

            showToast(
                "Location permission is required"
            )

            return
        }


        showToast(
            "Collecting emergency information..."
        )


        try {

            fusedLocationClient
                .getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                )
                .addOnSuccessListener { location ->

                    val sosPacket =
                        collectSosData(
                            location,
                            message
                        )

                    saveSosPacket(
                        sosPacket
                    )
                }
                .addOnFailureListener {

                    val sosPacket =
                        collectSosData(
                            null,
                            message
                        )

                    saveSosPacket(
                        sosPacket
                    )
                }

        } catch (e: SecurityException) {

            val sosPacket =
                collectSosData(
                    null,
                    message
                )

            saveSosPacket(
                sosPacket
            )
        }
    }


    // =========================================================
    // COLLECT SOS INFORMATION
    // =========================================================

    private fun collectSosData(
        location: Location?,
        message: String
    ): SosPacket {

        // -----------------------------------------------------
        // BATTERY
        // -----------------------------------------------------

        val batteryStatus: Intent? =

            IntentFilter(
                Intent.ACTION_BATTERY_CHANGED
            ).let { filter ->

                baseContext.registerReceiver(
                    null,
                    filter
                )
            }


        val batteryPercentage =

            batteryStatus?.let { intent ->

                val level =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_LEVEL,
                        -1
                    )

                val scale =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_SCALE,
                        -1
                    )

                if (
                    level >= 0 &&
                    scale > 0
                ) {

                    (level * 100 / scale.toFloat())
                        .toInt()

                } else {

                    -1
                }

            } ?: -1


        // -----------------------------------------------------
        // CHARGING STATUS
        // -----------------------------------------------------

        val chargingStatus =

            batteryStatus?.let { intent ->

                val status =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_STATUS,
                        -1
                    )

                when (status) {

                    BatteryManager.BATTERY_STATUS_CHARGING ->
                        "Charging"

                    BatteryManager.BATTERY_STATUS_FULL ->
                        "Full"

                    else ->
                        "Not Charging"
                }

            } ?: "Unknown"


        // -----------------------------------------------------
        // CHARGING TYPE
        // -----------------------------------------------------

        val chargingType =

            batteryStatus?.let { intent ->

                val chargePlug =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_PLUGGED,
                        -1
                    )

                when (chargePlug) {

                    BatteryManager.BATTERY_PLUGGED_USB ->
                        "USB"

                    BatteryManager.BATTERY_PLUGGED_AC ->
                        "AC"

                    BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                        "Wireless"

                    else ->
                        "None"
                }

            } ?: "Unknown"


        // -----------------------------------------------------
        // TELEPHONY
        // -----------------------------------------------------

        val telephonyManager =

            getSystemService(
                Context.TELEPHONY_SERVICE
            ) as TelephonyManager


        val carrierName =

            try {

                telephonyManager.networkOperatorName
                    .ifBlank {
                        "Unknown"
                    }

            } catch (e: Exception) {

                "Unknown"
            }


        // -----------------------------------------------------
        // CELLULAR NETWORK
        // -----------------------------------------------------

        val cellularType =
            getCellularNetworkType()


        // -----------------------------------------------------
        // SIM
        // -----------------------------------------------------

        val simAvailable =

            try {

                telephonyManager.simState ==
                        TelephonyManager.SIM_STATE_READY

            } catch (e: Exception) {

                false
            }


        // -----------------------------------------------------
        // INTERNET / WIFI
        // -----------------------------------------------------

        val connectivityManager =

            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager


        val activeNetwork =
            connectivityManager.activeNetwork


        val capabilities =

            connectivityManager
                .getNetworkCapabilities(
                    activeNetwork
                )


        val isWifiActive =

            capabilities?.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) == true


        val isMobileDataActive =

            capabilities?.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) == true


        val online =
            isInternetAvailable()


        // -----------------------------------------------------
        // SIGNAL STRENGTH
        // -----------------------------------------------------

        val signalStrength =

            if (
                checkSelfPermission(
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                try {

                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.Q
                    ) {

                        telephonyManager
                            .signalStrength
                            ?.cellSignalStrengths
                            ?.firstOrNull()
                            ?.dbm
                            ?.toString()
                            ?: "N/A"

                    } else {

                        "N/A"
                    }

                } catch (e: Exception) {

                    "N/A"
                }

            } else {

                "Permission Denied"
            }


        // -----------------------------------------------------
        // CREATE SOS PACKET
        // -----------------------------------------------------

        return SosPacket(

            sosId =
                UUID.randomUUID().toString(),

            timestamp =
                System.currentTimeMillis(),

            latitude =
                location?.latitude
                    ?: latitude
                    ?: 0.0,

            longitude =
                location?.longitude
                    ?: longitude
                    ?: 0.0,

            accuracy =
                location?.accuracy
                    ?: accuracy
                    ?: 0f,

            batteryPercentage =
                batteryPercentage,

            chargingStatus =
                chargingStatus,

            chargingType =
                chargingType,

            cellularCarrier =
                carrierName,

            cellularNetworkType =
                cellularType,

            signalStrength =
                signalStrength,

            isSimAvailable =
                simAvailable,

            internetStatus =
                if (online)
                    "ONLINE"
                else
                    "OFFLINE",

            isWifiActive =
                isWifiActive,

            isMobileDataActive =
                isMobileDataActive,

            deviceModel =
                Build.MODEL,

            androidVersion =
                Build.VERSION.RELEASE,

            emergencyMessage =
                message,

            isSynced =
                false
        )
    }


    // =========================================================
    // SAVE SOS
    // =========================================================

    private fun saveSosPacket(
        sosPacket: SosPacket
    ) {

        kotlinx.coroutines.MainScope().launch(
            Dispatchers.IO
        ) {

            try {

                database
                    .sosDao()
                    .insertSos(sosPacket)


                // =================================================
                // OFFLINE RELAY QUEUE
                // =================================================

                val now =
                    System.currentTimeMillis()

                val relayQueue =
                    OfflineRelayQueue(
                        messageDao =
                            database
                                .emergencyMessageDao(),
                        decisionEngine =
                            NetworkDecisionEngine()
                    )

                relayQueue.enqueue(
                    EmergencyMessage(
                        messageId = sosPacket.sosId,
                        senderId = getOrCreateNodeId(),
                        messageType = "SOS",
                        priority = MessagePriority.CRITICAL,
                        content = sosPacket.emergencyMessage,
                        createdAt = now,
                        expiresAt =
                            now +
                                    SOS_MESSAGE_TTL_MILLIS,
                        latitude = sosPacket.latitude,
                        longitude = sosPacket.longitude
                    )
                )


                launch(Dispatchers.Main) {

                    android.widget.Toast.makeText(

                        this@MainActivity,

                        "🚨 SOS CREATED\nStored in emergency relay queue",

                        android.widget.Toast.LENGTH_LONG

                    ).show()
                }

            } catch (e: Exception) {

                launch(Dispatchers.Main) {

                    android.widget.Toast.makeText(

                        this@MainActivity,

                        "SOS database error: ${e.message}",

                        android.widget.Toast.LENGTH_LONG

                    ).show()
                }
            }
        }
    }


    // =========================================================
    // TOAST HELPER
    // =========================================================

    private fun showToast(
        message: String
    ) {

        runOnUiThread {

            android.widget.Toast.makeText(
                this,
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }


    // =========================================================
    // PERSISTENT NODE ID
    // =========================================================

    // Same identity the phone advertises over BLE, so relays can attribute messages.
    private fun getOrCreateNodeId(): String {

        return BleConnectionManager.nodeId(
            this
        )
    }


    companion object {

        private const val SOS_MESSAGE_TTL_MILLIS =
            24L * 60L * 60L * 1000L
    }
}


// =================================================================
// SOS CONFIRMATION DIALOG
// =================================================================

@Composable
fun SosConfirmationDialog(

    onConfirm: (String) -> Unit,

    onDismiss: () -> Unit

) {

    var message by remember {

        mutableStateOf(
            "I need immediate assistance!"
        )
    }


    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {

            Text(

                text =
                    "🚨 CONFIRM SOS",

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color.Red
            )
        },

        text = {

            Column {

                Text(
                    "Are you sure you want to trigger an emergency SOS? " +
                            "Your location and device status will be collected."
                )


                Spacer(
                    modifier =
                        Modifier.height(16.dp)
                )


                OutlinedTextField(

                    value =
                        message,

                    onValueChange = {
                        message = it
                    },

                    label = {
                        Text(
                            "Emergency Message"
                        )
                    },

                    modifier =
                        Modifier.fillMaxWidth()
                )
            }
        },

        confirmButton = {

            Button(

                onClick = {

                    onConfirm(
                        message
                    )
                },

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color.Red
                    )
            ) {

                Text(
                    "SEND SOS",
                    color = Color.White
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {

                Text("CANCEL")
            }
        }
    )
}


// =================================================================
// HOME DASHBOARD
// =================================================================
// Answers three questions, top to bottom: Where am I? (map) How do I get help?
// (Emergency Actions) Where is the nearest shelter? (Nearby Shelters).
// Only real data is shown; missing data gets an honest message.

enum class HomeLocationState {
    WAITING_FOR_PERMISSION,
    PERMISSION_DENIED,
    LOCATING,
    AVAILABLE,
    UNAVAILABLE
}

@Composable
fun DisasterConnectHome(

    locationState: HomeLocationState,

    latitude: Double?,

    longitude: Double?,

    accuracy: Float?,

    internetAvailable: Boolean,

    networkType: String,

    nearbyNodes: List<NetworkNearbyNode>,

    nearbyScanning: Boolean,

    messagesWaiting: Int,

    onRefreshLocation: () -> Unit,

    onAllowLocation: () -> Unit,

    onOpenSettings: () -> Unit,

    onOpenMap: () -> Unit,

    onOpenNearbyDevices: () -> Unit,

    onOpenMessages: () -> Unit,

    onSendSos: () -> Unit
) {

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Spacing.screen,
                    vertical = Spacing.lg
                ),
        verticalArrangement =
            Arrangement.spacedBy(Spacing.xl)
    ) {

        // =====================================================
        // HEADER
        // =====================================================

        ScreenHeader(
            title = "DisasterConnect",
            subtitle = locationState.headerLabel()
        )


        // =====================================================
        // MAP
        // =====================================================

        HomeMapCard(
            locationState = locationState,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            internetAvailable = internetAvailable,
            onRefreshLocation = onRefreshLocation,
            onAllowLocation = onAllowLocation,
            onOpenSettings = onOpenSettings
        )


        // =====================================================
        // EMERGENCY ACTIONS
        // =====================================================

        EmergencyActionsSection(
            locationState = locationState,
            messagesWaiting = messagesWaiting,
            onSendSos = onSendSos,
            onOpenNearbyDevices = onOpenNearbyDevices,
            onOpenMessages = onOpenMessages,
            onOpenMap = onOpenMap
        )


        // =====================================================
        // NEARBY SHELTERS
        // =====================================================

        NearbySheltersSection()


        // =====================================================
        // CONNECTION STATUS
        // =====================================================

        ConnectionStatusSection(
            internetAvailable = internetAvailable,
            networkType = networkType,
            nearbyNodes = nearbyNodes,
            nearbyScanning = nearbyScanning,
            messagesWaiting = messagesWaiting
        )
    }
}


// =================================================================
// MAP CARD
// =================================================================

@Composable
private fun HomeMapCard(

    locationState: HomeLocationState,

    latitude: Double?,

    longitude: Double?,

    accuracy: Float?,

    internetAvailable: Boolean,

    onRefreshLocation: () -> Unit,

    onAllowLocation: () -> Unit,

    onOpenSettings: () -> Unit
) {

    // Large enough to be useful, small enough to keep SOS near the first screen.
    val windowHeight =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    val mapHeight =
        (windowHeight * 0.32f).coerceIn(200.dp, 320.dp)

    var mapLoadState by remember { mutableStateOf(MapLoadState.LOADING) }

    var recenterRequest by remember { mutableIntStateOf(0) }

    var reloadRequest by remember { mutableIntStateOf(0) }

    val showMap =
        locationState == HomeLocationState.AVAILABLE &&
                latitude != null &&
                longitude != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(Sizes.border, MaterialTheme.colorScheme.outlineVariant)
    ) {

        if (showMap) {

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {

                LocationMap(
                    latitude = latitude!!,
                    longitude = longitude!!,
                    recenterRequest = recenterRequest,
                    reloadRequest = reloadRequest,
                    onLoadStateChange = { mapLoadState = it },
                    modifier = Modifier.fillMaxSize()
                )

                when (mapLoadState) {

                    MapLoadState.LOADING ->
                        MapMessagePanel(
                            title = "Loading map…",
                            message = "The map needs Internet the first time an area is shown.",
                            loading = true,
                            scrollable = true,
                            modifier = Modifier.fillMaxSize()
                        )

                    MapLoadState.FAILED ->
                        MapMessagePanel(
                            title = "The map couldn't load",
                            message = "Your location is still known. Check your connection and try again.",
                            scrollable = true,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            PrimaryButton(
                                text = "Try again",
                                onClick = { reloadRequest++ },
                                icon = Icons.Filled.Refresh
                            )
                        }

                    MapLoadState.READY ->
                        PrimaryButton(
                            text = "Recenter",
                            onClick = { recenterRequest++ },
                            icon = Icons.Filled.LocationOn,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(Spacing.md)
                        )
                }
            }

        } else {

            LocationStatePanel(
                locationState = locationState,
                onAllowLocation = onAllowLocation,
                onOpenSettings = onOpenSettings,
                onRefreshLocation = onRefreshLocation,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = mapHeight)
            )
        }


        // ---------------------------------------------------------
        // Legend, coordinates and offline note
        // ---------------------------------------------------------

        if (showMap) {

            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    UserLocationLegendDot()

                    Spacer(modifier = Modifier.width(Spacing.sm))

                    Text(
                        text = "My location",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Text(
                    text =
                        "%.5f, %.5f".format(latitude, longitude) +
                                (accuracy?.let { " • accurate to about %.0f m".format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!internetAvailable) {

                    StatusBadge(
                        status = StatusType.WARNING,
                        label = "Offline: map may only show areas viewed before"
                    )
                }

                TertiaryButton(
                    text = "Refresh location",
                    onClick = onRefreshLocation,
                    icon = Icons.Filled.Refresh
                )
            }
        }
    }
}


@Composable
private fun LocationStatePanel(

    locationState: HomeLocationState,

    onAllowLocation: () -> Unit,

    onOpenSettings: () -> Unit,

    onRefreshLocation: () -> Unit,

    modifier: Modifier = Modifier
) {

    when (locationState) {

        HomeLocationState.WAITING_FOR_PERMISSION ->
            MapMessagePanel(
                title = "Waiting for permission",
                message = "Choose Allow when your phone asks to use your location.",
                loading = true,
                modifier = modifier
            ) {
                PrimaryButton(
                    text = "Allow location",
                    onClick = onAllowLocation,
                    icon = Icons.Filled.LocationOn
                )
            }

        HomeLocationState.PERMISSION_DENIED ->
            MapMessagePanel(
                title = "Location access is off",
                message =
                    "Allow location so the map can show where you are. " +
                            "Sending an SOS also needs location access.",
                icon = Icons.Filled.LocationOn,
                modifier = modifier
            ) {
                PrimaryButton(
                    text = "Allow location",
                    onClick = onAllowLocation,
                    icon = Icons.Filled.LocationOn
                )

                SecondaryButton(
                    text = "Open settings",
                    onClick = onOpenSettings,
                    icon = Icons.Filled.Settings
                )
            }

        HomeLocationState.LOCATING ->
            MapMessagePanel(
                title = "Finding your location…",
                message = "This can take a minute. Being outdoors or near a window helps.",
                loading = true,
                modifier = modifier
            )

        HomeLocationState.UNAVAILABLE,
        HomeLocationState.AVAILABLE ->
            MapMessagePanel(
                title = "Can't find your location",
                message = "Check that Location is switched on in your phone settings, then try again.",
                icon = Icons.Outlined.Warning,
                modifier = modifier
            ) {
                PrimaryButton(
                    text = "Try again",
                    onClick = onRefreshLocation,
                    icon = Icons.Filled.Refresh
                )
            }
    }
}


/** Solid panel used for every map state that is not the map itself. */
@Composable
private fun MapMessagePanel(

    title: String,

    message: String,

    modifier: Modifier = Modifier,

    loading: Boolean = false,

    icon: ImageVector? = null,

    // Only for panels with a fixed height (over the map). Inside the scrolling Home
    // page an unbounded scrollable panel crashes Compose, so it grows instead.
    scrollable: Boolean = false,

    actions: @Composable ColumnScope.() -> Unit = {}
) {

    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (scrollable) Modifier.verticalScroll(rememberScrollState())
                    else Modifier
                )
                .padding(Spacing.xl)
                .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.spacedBy(
                Spacing.md,
                Alignment.CenterVertically
            )
    ) {

        if (loading) {

            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )

        } else if (icon != null) {

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Sizes.icon + Spacing.sm)
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        actions()
    }
}


// =================================================================
// EMERGENCY ACTIONS
// =================================================================

private class HomeAction(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

@Composable
private fun EmergencyActionsSection(

    locationState: HomeLocationState,

    messagesWaiting: Int,

    onSendSos: () -> Unit,

    onOpenNearbyDevices: () -> Unit,

    onOpenMessages: () -> Unit,

    onOpenMap: () -> Unit
) {

    val locationKnown =
        locationState == HomeLocationState.AVAILABLE

    val actions =
        listOf(
            HomeAction(
                label = "Nearby Devices",
                description = "Find phones close to you",
                icon = Icons.Filled.Phone,
                onClick = onOpenNearbyDevices
            ),
            HomeAction(
                label = "Messages",
                description =
                    if (messagesWaiting > 0) "$messagesWaiting waiting to send"
                    else "Read and send messages",
                icon = Icons.Filled.Email,
                onClick = onOpenMessages
            ),
            HomeAction(
                label = "Full map",
                description =
                    if (locationKnown) "Open a larger map"
                    else "Available once your location is found",
                icon = Icons.Filled.Place,
                onClick = onOpenMap,
                // The full map needs a real position to centre on.
                enabled = locationKnown
            )
        )

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        SectionTitle("Emergency Actions")

        EmergencyButton(
            text = "Send SOS",
            onClick = onSendSos,
            icon = Icons.Filled.Warning,
            modifier = Modifier.fillMaxWidth()
        )

        if (
            locationState == HomeLocationState.WAITING_FOR_PERMISSION ||
            locationState == HomeLocationState.PERMISSION_DENIED
        ) {

            Text(
                text = "SOS needs location access. Allow location in the map section above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Two columns on normal phones; one column on narrow screens or with large text.
        BoxWithConstraints {

            val columns =
                if (maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f) 2 else 1

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {

                actions.chunked(columns).forEach { row ->

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {

                        row.forEach { action ->

                            HomeActionTile(
                                action = action,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                            )
                        }

                        repeat(columns - row.size) {

                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun HomeActionTile(

    action: HomeAction,

    modifier: Modifier = Modifier
) {

    DisasterCard(
        modifier = modifier,
        onClick = action.onClick,
        enabled = action.enabled
    ) {

        Icon(
            imageVector = action.icon,
            contentDescription = null,
            modifier = Modifier.size(Sizes.icon)
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Text(
            text = action.label,
            style = MaterialTheme.typography.titleSmall
        )

        Text(
            text = action.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


// =================================================================
// NEARBY SHELTERS
// =================================================================

@Composable
private fun NearbySheltersSection() {

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        SectionTitle("Nearby Shelters")

        // The app has no real shelter data yet. Never show made-up shelters here.
        NoticeCard(
            title = "No shelter information yet",
            message =
                "DisasterConnect does not have shelter locations for your area, " +
                        "so it can't show the nearest shelter. " +
                        "Follow instructions from local authorities.",
            tone = CardTone.INFO
        )
    }
}


// =================================================================
// CONNECTION STATUS
// =================================================================

@Composable
private fun ConnectionStatusSection(

    internetAvailable: Boolean,

    networkType: String,

    nearbyNodes: List<NetworkNearbyNode>,

    nearbyScanning: Boolean,

    messagesWaiting: Int
) {

    val visibleNodes =
        nearbyNodes.filter { it.state != NearbyNodeState.LOST }

    val connectedNodes =
        nearbyNodes.count { it.state == NearbyNodeState.CONNECTED }

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        SectionTitle("Connection Status")

        DisasterCard {

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {

                StatusRow(
                    label = "Internet",
                    detail =
                        if (internetAvailable) "Connected through $networkType"
                        else "No internet. Nearby phones can still pass on messages.",
                    status =
                        if (internetAvailable) StatusType.CONNECTED else StatusType.DISCONNECTED,
                    statusLabel =
                        if (internetAvailable) "Online" else "Offline"
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StatusRow(
                    label = "Nearby phones",
                    detail =
                        when {
                            visibleNodes.isNotEmpty() -> "${visibleNodes.size} found nearby"
                            nearbyScanning -> "Searching… none found yet"
                            else -> "Search is off. Open Nearby Devices to start it."
                        },
                    status =
                        if (connectedNodes > 0) StatusType.CONNECTED else StatusType.DISCONNECTED,
                    statusLabel =
                        if (connectedNodes > 0) "$connectedNodes connected" else "Not connected"
                )

                nearbyNodes
                    .sortedByDescending { it.rssi }
                    .take(4)
                    .forEach { node ->

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            Text(
                                text = node.nodeId,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                text = node.state.dashboardLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StatusRow(
                    label = "Messages",
                    detail =
                        if (messagesWaiting > 0) "They send automatically when another phone is in range."
                        else "Nothing waiting to send",
                    status =
                        if (messagesWaiting > 0) StatusType.PENDING else StatusType.DELIVERED,
                    statusLabel =
                        if (messagesWaiting > 0) "$messagesWaiting waiting" else "None waiting"
                )
            }
        }
    }
}


@Composable
private fun StatusRow(

    label: String,

    detail: String,

    status: StatusType,

    statusLabel: String
) {

    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

            StatusBadge(
                status = status,
                label = statusLabel
            )
        }

        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


// =================================================================
// SHARED HOME HELPERS
// =================================================================

@Composable
private fun SectionTitle(
    text: String
) {

    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() }
    )
}

private fun HomeLocationState.headerLabel(): String = when (this) {
    HomeLocationState.AVAILABLE -> "Your location is shown on the map"
    HomeLocationState.LOCATING -> "Finding your location…"
    HomeLocationState.WAITING_FOR_PERMISSION -> "Waiting for location permission"
    HomeLocationState.PERMISSION_DENIED -> "Location access is off"
    HomeLocationState.UNAVAILABLE -> "Location not available right now"
}

private fun NearbyNodeState.dashboardLabel(): String = when (this) {
    NearbyNodeState.NEARBY -> "Nearby"
    NearbyNodeState.CONNECTING -> "Connecting"
    NearbyNodeState.CONNECTED -> "Connected"
    NearbyNodeState.LOST -> "Out of range"
}

// Outgoing messages (including SOS) not yet handed to another phone. Delivery receipts are not messages.
private fun EmergencyMessage.isWaitingToSend(): Boolean =
    !isIncoming && messageType != MessageType.RECEIPT && deliveryState in setOf(
        DeliveryState.CREATED,
        DeliveryState.QUEUED,
        DeliveryState.SENT
    )

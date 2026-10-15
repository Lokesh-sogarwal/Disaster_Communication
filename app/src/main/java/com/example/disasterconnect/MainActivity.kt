package com.example.disasterconnect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.clickable
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

import androidx.compose.foundation.shape.RoundedCornerShape

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
import androidx.compose.ui.unit.sp

import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.SosPacket
import com.example.disasterconnect.network.NetworkDecisionEngine
import com.example.disasterconnect.network.OfflineRelayQueue
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NetworkNearbyNode
import com.example.disasterconnect.network.NearbyNodeState
import com.example.disasterconnect.ui.theme.DisasterConnectTheme

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
            if (permissions.values.all { it }) startDashboardDiscovery()
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

                    if (permissionGranted) {

                        DisasterConnectHome(

                            latitude = latitude,

                            longitude = longitude,

                            accuracy = accuracy,

                            locationMessage =
                                locationMessage,

                            internetAvailable =
                                internetAvailable,

                            networkType =
                                networkType,

                            nearbyNodes = nearbyNodes.values.toList(),

                            onRefreshLocation = {

                                getCurrentLocation()

                                updateNetworkStatus()
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

                    } else {

                        PermissionScreen(

                            permissionDenied =
                                permissionDenied,

                            onRequestPermission = {

                                requestLocationPermission()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyDiscoveryPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
            return
        }

        NearbyNetworkRepository.start(this, "main-dashboard")
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

    private fun getOrCreateNodeId(): String {

        val preferences =
            getSharedPreferences(
                "disaster_connect_preferences",
                MODE_PRIVATE
            )

        return preferences.getString(
            "node_id",
            null
        ) ?: UUID.randomUUID()
            .toString()
            .also { nodeId ->

                preferences
                    .edit()
                    .putString(
                        "node_id",
                        nodeId
                    )
                    .apply()
            }
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
// PERMISSION SCREEN
// =================================================================

@Composable
fun PermissionScreen(

    permissionDenied: Boolean,

    onRequestPermission: () -> Unit

) {

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .padding(30.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center

    ) {

        Text(
            text = "📍",
            fontSize = 60.sp
        )


        Spacer(
            modifier =
                Modifier.height(20.dp)
        )


        Text(

            text =
                "Location Permission Required",

            fontSize =
                24.sp,

            fontWeight =
                FontWeight.Bold,

            textAlign =
                TextAlign.Center
        )


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        Text(

            text =
                "DisasterConnect needs your location and phone state " +
                        "for emergency communication, nearby shelters and SOS services.",

            fontSize =
                16.sp,

            textAlign =
                TextAlign.Center,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )


        if (permissionDenied) {

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )


            Text(

                text =
                    "Permission denied. Please enable permissions in settings.",

                color =
                    Color.Red,

                fontSize =
                    14.sp
            )
        }


        Spacer(
            modifier =
                Modifier.height(30.dp)
        )


        Button(
            onClick =
                onRequestPermission
        ) {

            Text(
                "ALLOW PERMISSIONS"
            )
        }
    }
}


// =================================================================
// MAIN HOME SCREEN
// =================================================================

@Composable
fun DisasterConnectHome(

    latitude: Double?,

    longitude: Double?,

    accuracy: Float?,

    locationMessage: String,

    internetAvailable: Boolean,

    networkType: String,

    nearbyNodes: List<NetworkNearbyNode>,

    onRefreshLocation: () -> Unit,

    onOpenMap: () -> Unit,

    onOpenNearbyDevices: () -> Unit,

    onSendSos: () -> Unit

) {

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally

    ) {

        // =====================================================
        // TITLE
        // =====================================================

        Text(

            text =
                "DISASTER CONNECT",

            fontSize =
                26.sp,

            fontWeight =
                FontWeight.Bold,

            textAlign =
                TextAlign.Center
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(

            text =
                "Emergency Communication Network",

            fontSize =
                14.sp,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )


        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "NEARBY DISASTER NODES",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                nearbyNodes.sortedByDescending { it.rssi }.take(4).forEach { node ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("● ${node.nodeId}")
                        Text(
                            text = node.state.dashboardLabel(),
                            color = node.state.dashboardColor(),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (nearbyNodes.isEmpty()) {
                    Text("No nearby DisasterConnect nodes detected.")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Nearby Nodes: ${nearbyNodes.size}")
                Text("Connected Nodes: ${nearbyNodes.count { it.state == NearbyNodeState.CONNECTED }}")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))


        // =====================================================
        // NETWORK STATUS
        // =====================================================

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(16.dp),

            elevation =
                CardDefaults.cardElevation(
                    defaultElevation = 4.dp
                )

        ) {

            Column(

                modifier =
                    Modifier.padding(18.dp)

            ) {

                Text(

                    text =
                        "NETWORK STATUS",

                    fontSize =
                        14.sp,

                    fontWeight =
                        FontWeight.Bold
                )


                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )


                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween

                ) {

                    Text(
                        "Internet"
                    )


                    Text(

                        text =

                            if (internetAvailable)
                                "ONLINE"
                            else
                                "OFFLINE",

                        fontWeight =
                            FontWeight.Bold,

                        color =

                            if (internetAvailable)
                                Color(0xFF2E7D32)
                            else
                                Color.Red
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )


                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween

                ) {

                    Text(
                        "Network"
                    )


                    Text(

                        text =
                            networkType,

                        fontWeight =
                            FontWeight.Bold
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )


                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween

                ) {

                    Text(
                        "Nearby Devices"
                    )


                    Text(

                        text =
                            "0",

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        // =====================================================
        // LOCATION
        // =====================================================

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(16.dp),

            elevation =
                CardDefaults.cardElevation(
                    defaultElevation = 4.dp
                )

        ) {

            Column(

                modifier =
                    Modifier.padding(18.dp)

            ) {

                Text(

                    text =
                        "📍 LOCATION",

                    fontSize =
                        17.sp,

                    fontWeight =
                        FontWeight.Bold
                )


                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )


                if (
                    latitude != null &&
                    longitude != null
                ) {

                    Text(

                        text =
                            "Latitude: %.6f"
                                .format(latitude)
                    )


                    Text(

                        text =
                            "Longitude: %.6f"
                                .format(longitude)
                    )


                    if (accuracy != null) {

                        Text(

                            text =
                                "Accuracy: %.1f meters"
                                    .format(accuracy)
                        )
                    }


                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )


                    Text(
                        locationMessage
                    )

                } else {

                    Text(
                        locationMessage
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )


                Button(

                    onClick =
                        onRefreshLocation,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "REFRESH LOCATION"
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(24.dp)
        )


        // =====================================================
        // SOS
        // =====================================================

        Button(

            onClick =
                onSendSos,

            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),

            shape =
                RoundedCornerShape(16.dp),

            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        Color.Red
                )

        ) {

            Text(

                text =
                    "🚨  SEND SOS",

                fontSize =
                    20.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color.White
            )
        }


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        // =====================================================
        // MAP
        // =====================================================

        Button(

            onClick =
                onOpenMap,

            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),

            shape =
                RoundedCornerShape(16.dp)

        ) {

            Text(

                text =
                    "🗺️  VIEW MAP",

                fontSize =
                    20.sp,

                fontWeight =
                    FontWeight.Bold
            )
        }


        Spacer(
            modifier =
                Modifier.height(20.dp)
        )


        // =====================================================
        // MENU
        // =====================================================

        Text(
            "Messages",
            fontSize = 17.sp
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        Text(
            text = "Nearby Devices",
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClick = onOpenNearbyDevices
                    )
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        Text(
            "Shelters",
            fontSize = 17.sp
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        Text(
            "Network Status",
            fontSize = 17.sp
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        Text(
            "Settings",
            fontSize = 17.sp
        )
    }
}

private fun NearbyNodeState.dashboardLabel(): String = when (this) {
    NearbyNodeState.NEARBY -> "NEARBY"
    NearbyNodeState.CONNECTING -> "CONNECTING"
    NearbyNodeState.CONNECTED -> "CONNECTED"
    NearbyNodeState.LOST -> "OUT OF RANGE"
}

private fun NearbyNodeState.dashboardColor(): Color = when (this) {
    NearbyNodeState.CONNECTED, NearbyNodeState.NEARBY -> Color(0xFF2E7D32)
    NearbyNodeState.CONNECTING -> Color(0xFFF57C00)
    NearbyNodeState.LOST -> Color.Red
}

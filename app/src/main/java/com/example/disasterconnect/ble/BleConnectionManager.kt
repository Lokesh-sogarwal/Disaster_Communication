package com.example.disasterconnect.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NearbyNodeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED }
enum class HandshakeState { NOT_STARTED, HELLO_SENT, HELLO_RECEIVED, ACK_SENT, ACK_RECEIVED, SUCCESS, FAILED }

data class ManagedConnection(
    val nodeId: String,
    val connectionState: ConnectionState,
    val handshakeState: HandshakeState = HandshakeState.NOT_STARTED,
    val detail: String = ""
)

/** Receives link-level events. Callbacks arrive on Bluetooth binder threads. */
interface BleLinkListener {
    fun onLinkReady(nodeId: String)
    fun onLinkClosed(nodeId: String)
    fun onPacketReceived(nodeId: String, packet: ByteArray)
}

/**
 * Process-wide owner of the GATT server, advertising and the active client connection.
 * Nothing here depends on an Activity being open.
 */
object BleConnectionManager {
    private var localNodeId: String? = null
    private var server: DisasterConnectGattServer? = null
    private var client: DisasterConnectGattClient? = null
    private var stateReceiverRegistered = false
    private val _connections = MutableStateFlow<Map<String, ManagedConnection>>(emptyMap())
    val connections: StateFlow<Map<String, ManagedConnection>> = _connections

    @Volatile
    var linkListener: BleLinkListener? = null

    /** The single persistent identity of this phone, shared by BLE advertising, GATT and relayed messages. */
    @Synchronized
    fun nodeId(context: Context, proposedId: String = randomNodeId()): String {
        val preferences = context.applicationContext.getSharedPreferences("disaster_connect_ble", Context.MODE_PRIVATE)
        return preferences.getString("temporary_node_id", null)?.takeIf(NODE_ID_PATTERN::matches) ?: proposedId.also {
            preferences.edit().putString("temporary_node_id", it).apply()
        }
    }

    /** The 3 node-id bytes carried in the BLE advertisement for [nodeId]. */
    fun nodeIdBytes(nodeId: String): ByteArray =
        nodeId.removePrefix("DC-").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val NODE_ID_PATTERN = Regex("DC-[0-9A-F]{6}")

    private fun randomNodeId(): String =
        "DC-" + ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    // ---------------------------------------------------------
    // Node lifecycle
    // ---------------------------------------------------------

    /**
     * Starts the GATT server and advertising if permissions and Bluetooth allow it.
     * Safe to call repeatedly from any screen. Returns false when this phone cannot take
     * part yet (missing permission, Bluetooth off or no BLE).
     */
    @Synchronized
    fun startNode(context: Context): Boolean {
        val app = context.applicationContext
        registerBluetoothStateReceiver(app)
        if (!hasConnectPermission(app)) return false
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val id = nodeId(app)
        start(app, id)
        BleAdvertiser.start(app, nodeIdBytes(id))
        return server?.isRunning == true
    }

    fun start(context: Context, nodeId: String) {
        if (server?.isRunning == true) return
        localNodeId = nodeId
        server = DisasterConnectGattServer(
            context.applicationContext,
            nodeId,
            { handleEvent(it, fromClient = false) },
            ::handlePacket
        ).also { it.start() }
    }

    /** Bluetooth turned off: every link and the advertisement are gone. */
    @Synchronized
    private fun stopNode() {
        client?.close()
        client = null
        server?.stop()
        server = null
        BleAdvertiser.stop()
    }

    // ---------------------------------------------------------
    // Links
    // ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(context: Context, nodeId: String, device: BluetoothDevice): String? {
        val current = _connections.value[nodeId]?.connectionState
        if (current == ConnectionState.CONNECTING || current == ConnectionState.CONNECTED || current == ConnectionState.DISCONNECTING) {
            return "Already ${current.name.lowercase()} to $nodeId."
        }
        if (server?.isConnected(nodeId) == true) return "$nodeId is already connected to this phone."
        if (client != null) return "Another DisasterConnect GATT connection is active."
        update(nodeId, ConnectionState.CONNECTING, HandshakeState.NOT_STARTED, "Connecting…")
        client = DisasterConnectGattClient(
            context.applicationContext,
            localNodeId ?: nodeId(context),
            { handleEvent(it, fromClient = true) },
            ::handlePacket
        )
        client?.connect(nodeId, device)
        return null
    }

    @SuppressLint("MissingPermission")
    fun disconnect(nodeId: String) {
        val record = _connections.value[nodeId] ?: return
        if (client?.connectedNodeId != nodeId) return
        update(nodeId, ConnectionState.DISCONNECTING, record.handshakeState, "Disconnecting…")
        client?.disconnect()
        client = null
    }

    /** True when [nodeId] finished the handshake over either an outgoing or an incoming link. */
    fun isLinkReady(nodeId: String): Boolean =
        client?.connectedNodeId == nodeId || server?.isConnected(nodeId) == true

    /** The node this phone is connected to as a client, if any. Only one at a time. */
    val outgoingLinkNodeId: String?
        get() = client?.connectedNodeId

    val hasOutgoingLink: Boolean
        get() = client != null

    /** Sends one packet over whichever link reaches [nodeId]. [onComplete] is always called once. */
    fun send(nodeId: String, packet: ByteArray, onComplete: (Boolean) -> Unit) {
        val currentClient = client
        val currentServer = server
        when {
            currentClient != null && currentClient.connectedNodeId == nodeId -> currentClient.sendPacket(packet, onComplete)
            currentServer != null && currentServer.isConnected(nodeId) -> currentServer.sendPacket(nodeId, packet, onComplete)
            else -> onComplete(false)
        }
    }

    private fun handlePacket(nodeId: String, packet: ByteArray) {
        linkListener?.onPacketReceived(nodeId, packet)
    }

    private fun handleEvent(event: GattConnectionEvent, fromClient: Boolean) {
        val nodeId = event.nodeId ?: return
        when (event.type) {
            GattConnectionEventType.CONNECTION_ESTABLISHED,
            GattConnectionEventType.SERVICES_DISCOVERED -> update(nodeId, ConnectionState.CONNECTING, HandshakeState.NOT_STARTED, event.detail.orEmpty())
            GattConnectionEventType.HELLO_SENT -> update(nodeId, ConnectionState.CONNECTING, HandshakeState.HELLO_SENT, event.detail.orEmpty())
            GattConnectionEventType.HELLO_RECEIVED -> update(nodeId, ConnectionState.CONNECTING, HandshakeState.HELLO_RECEIVED, event.detail.orEmpty())
            GattConnectionEventType.ACK_SENT -> update(nodeId, ConnectionState.CONNECTING, HandshakeState.ACK_SENT, event.detail.orEmpty())
            GattConnectionEventType.ACK_RECEIVED -> update(nodeId, ConnectionState.CONNECTING, HandshakeState.ACK_RECEIVED, event.detail.orEmpty())
            GattConnectionEventType.HANDSHAKE_COMPLETED -> {
                update(nodeId, ConnectionState.CONNECTED, HandshakeState.SUCCESS, event.detail.orEmpty())
                NearbyNetworkRepository.updateConnection(nodeId, NearbyNodeState.CONNECTED)
                linkListener?.onLinkReady(nodeId)
            }
            GattConnectionEventType.CONNECTION_LOST,
            GattConnectionEventType.CONNECTION_FAILED -> {
                // Only the client's own events may drop the client; server peers come and go independently.
                if (fromClient) client = null
                if (isLinkReady(nodeId)) return
                val failed = event.type == GattConnectionEventType.CONNECTION_FAILED
                update(
                    nodeId,
                    if (failed) ConnectionState.FAILED else ConnectionState.DISCONNECTED,
                    if (failed) HandshakeState.FAILED else HandshakeState.NOT_STARTED,
                    event.detail.orEmpty()
                )
                NearbyNetworkRepository.updateConnection(nodeId, NearbyNodeState.LOST)
                linkListener?.onLinkClosed(nodeId)
            }
        }
    }

    private fun update(nodeId: String, connection: ConnectionState, handshake: HandshakeState, detail: String) {
        _connections.update { it + (nodeId to ManagedConnection(nodeId, connection, handshake, detail)) }
        if (connection == ConnectionState.CONNECTING) NearbyNetworkRepository.updateConnection(nodeId, NearbyNodeState.CONNECTING)
    }

    // ---------------------------------------------------------
    // Bluetooth on/off
    // ---------------------------------------------------------

    private fun registerBluetoothStateReceiver(app: Context) {
        if (stateReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopNode()
                    BluetoothAdapter.STATE_ON -> startNode(context)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        stateReceiverRegistered = true
    }

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

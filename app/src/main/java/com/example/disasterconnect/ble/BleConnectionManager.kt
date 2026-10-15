package com.example.disasterconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NearbyNodeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED }
enum class HandshakeState { NOT_STARTED, HELLO_SENT, HELLO_RECEIVED, ACK_SENT, ACK_RECEIVED, SUCCESS, FAILED }

data class ManagedConnection(
    val nodeId: String,
    val connectionState: ConnectionState,
    val handshakeState: HandshakeState = HandshakeState.NOT_STARTED,
    val detail: String = ""
)

/** Process-wide owner of the GATT server and active client connection state. */
object BleConnectionManager {
    private var localNodeId: String? = null
    private var server: DisasterConnectGattServer? = null
    private var client: DisasterConnectGattClient? = null
    private val _connections = MutableStateFlow<Map<String, ManagedConnection>>(emptyMap())
    val connections: StateFlow<Map<String, ManagedConnection>> = _connections

    fun nodeId(context: Context, proposedId: String): String {
        val preferences = context.applicationContext.getSharedPreferences("disaster_connect_ble", Context.MODE_PRIVATE)
        return preferences.getString("temporary_node_id", null) ?: proposedId.also {
            preferences.edit().putString("temporary_node_id", it).apply()
        }
    }

    fun start(context: Context, nodeId: String) {
        if (server != null) return
        localNodeId = nodeId
        server = DisasterConnectGattServer(context.applicationContext, nodeId, ::handleEvent).also { it.start() }
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, nodeId: String, device: BluetoothDevice): String? {
        val current = _connections.value[nodeId]?.connectionState
        if (current == ConnectionState.CONNECTING || current == ConnectionState.CONNECTED || current == ConnectionState.DISCONNECTING) {
            return "Already ${current.name.lowercase()} to $nodeId."
        }
        if (client != null) return "Another DisasterConnect GATT connection is active."
        update(nodeId, ConnectionState.CONNECTING, HandshakeState.NOT_STARTED, "Connecting…")
        client = DisasterConnectGattClient(
            context.applicationContext,
            localNodeId ?: nodeId,
            ::handleEvent
        )
        client?.connect(nodeId, device)
        return null
    }

    @SuppressLint("MissingPermission")
    fun disconnect(nodeId: String) {
        val record = _connections.value[nodeId] ?: return
        update(nodeId, ConnectionState.DISCONNECTING, record.handshakeState, "Disconnecting…")
        client?.disconnect()
        client = null
    }

    private fun handleEvent(event: GattConnectionEvent) {
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
            }
            GattConnectionEventType.CONNECTION_LOST -> {
                update(nodeId, ConnectionState.DISCONNECTED, HandshakeState.NOT_STARTED, event.detail.orEmpty())
                NearbyNetworkRepository.updateConnection(nodeId, NearbyNodeState.LOST)
                client = null
            }
            GattConnectionEventType.CONNECTION_FAILED -> {
                update(nodeId, ConnectionState.FAILED, HandshakeState.FAILED, event.detail.orEmpty())
                NearbyNetworkRepository.updateConnection(nodeId, NearbyNodeState.LOST)
                client = null
            }
        }
    }

    private fun update(nodeId: String, connection: ConnectionState, handshake: HandshakeState, detail: String) {
        _connections.value = _connections.value + (nodeId to ManagedConnection(nodeId, connection, handshake, detail))
        if (connection == ConnectionState.CONNECTING) NearbyNetworkRepository.updateConnection(nodeId, NearbyNodeState.CONNECTING)
    }
}

package com.example.disasterconnect.nearby

import com.example.disasterconnect.ble.ConnectionState
import com.example.disasterconnect.ble.HandshakeState
import com.example.disasterconnect.ble.ManagedConnection

// =========================================================
// DEVICE STATUS FOR THE NEARBY DEVICES SCREEN
// =========================================================
// Read-only view of BleConnectionManager state. Nothing here starts, stops or
// times out a connection; the UI only reflects what the manager reports.

enum class DeviceStatus {
    AVAILABLE,
    OUT_OF_RANGE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED,
    DISCONNECTED
}

/**
 * [connection] is the manager's record for this node (null if never connected).
 * [visible] is false when the scanner has not seen the node recently.
 */
fun deviceStatus(
    connection: ManagedConnection?,
    visible: Boolean
): DeviceStatus =
    when (connection?.connectionState) {
        ConnectionState.CONNECTING -> DeviceStatus.CONNECTING
        // The manager only reports CONNECTED after HANDSHAKE_COMPLETED; check both anyway.
        ConnectionState.CONNECTED ->
            if (connection.handshakeState == HandshakeState.SUCCESS) DeviceStatus.CONNECTED
            else DeviceStatus.CONNECTING
        ConnectionState.DISCONNECTING -> DeviceStatus.DISCONNECTING
        ConnectionState.FAILED -> DeviceStatus.FAILED
        ConnectionState.DISCONNECTED -> DeviceStatus.DISCONNECTED
        null -> if (visible) DeviceStatus.AVAILABLE else DeviceStatus.OUT_OF_RANGE
    }

// =========================================================
// SUCCESS ANIMATION TRIGGER
// =========================================================

/**
 * Decides when to play the "Successfully connected!" animation.
 *
 * A node qualifies only when this screen saw it CONNECTING and then saw it
 * CONNECTED with a successful handshake. So:
 * - discovering a device, or opening the screen while already connected, plays nothing;
 * - each connection plays once, even if the state is re-emitted;
 * - a failure, disconnect or new attempt in between cancels a pending success.
 */
class ConnectionSuccessTracker {

    private val attempting = mutableSetOf<String>()

    /** Returns the node ids whose handshake has just succeeded. */
    fun onConnections(connections: Map<String, ManagedConnection>): Set<String> {

        val succeeded = mutableSetOf<String>()

        connections.forEach { (nodeId, connection) ->
            when (deviceStatus(connection, visible = true)) {
                DeviceStatus.CONNECTING -> attempting += nodeId
                DeviceStatus.CONNECTED -> if (attempting.remove(nodeId)) succeeded += nodeId
                else -> attempting -= nodeId
            }
        }

        attempting.retainAll(connections.keys)

        return succeeded
    }
}

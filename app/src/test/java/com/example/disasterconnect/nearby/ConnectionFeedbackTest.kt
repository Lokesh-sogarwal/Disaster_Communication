package com.example.disasterconnect.nearby

import com.example.disasterconnect.ble.ConnectionState
import com.example.disasterconnect.ble.HandshakeState
import com.example.disasterconnect.ble.ManagedConnection
import org.junit.Assert.assertEquals
import org.junit.Test


class ConnectionFeedbackTest {

    private val a = "DC-AAAAAA"
    private val b = "DC-BBBBBB"

    private fun connecting(id: String, step: HandshakeState = HandshakeState.NOT_STARTED) =
        ManagedConnection(id, ConnectionState.CONNECTING, step)

    private fun connected(id: String) =
        ManagedConnection(id, ConnectionState.CONNECTED, HandshakeState.SUCCESS)

    private fun failed(id: String) =
        ManagedConnection(id, ConnectionState.FAILED, HandshakeState.FAILED, "Connection timed out before the handshake completed.")

    private fun disconnected(id: String) =
        ManagedConnection(id, ConnectionState.DISCONNECTED)


    @Test
    fun discoveredDeviceWithoutAttemptHasNoConnectionState() {

        assertEquals(DeviceStatus.AVAILABLE, deviceStatus(null, visible = true))
        assertEquals(DeviceStatus.OUT_OF_RANGE, deviceStatus(null, visible = false))
    }


    @Test
    fun gattConnectedWithoutHandshakeIsStillConnecting() {

        val gattOnly = ManagedConnection(a, ConnectionState.CONNECTED, HandshakeState.ACK_SENT)

        assertEquals(DeviceStatus.CONNECTING, deviceStatus(gattOnly, visible = true))
    }


    @Test
    fun successPlaysOnceAfterAnObservedAttemptAndHandshake() {

        val tracker = ConnectionSuccessTracker()

        assertEquals(emptySet<String>(), tracker.onConnections(mapOf(a to connecting(a))))
        assertEquals(emptySet<String>(), tracker.onConnections(mapOf(a to connecting(a, HandshakeState.HELLO_RECEIVED))))
        assertEquals(setOf(a), tracker.onConnections(mapOf(a to connected(a))))

        // Re-emitting the same CONNECTED state must not replay the animation.
        assertEquals(emptySet<String>(), tracker.onConnections(mapOf(a to connected(a))))
    }


    @Test
    fun alreadyConnectedWhenScreenOpensPlaysNothing() {

        assertEquals(emptySet<String>(), ConnectionSuccessTracker().onConnections(mapOf(a to connected(a))))
    }


    @Test
    fun failureOrDisconnectCancelsAPendingSuccess() {

        val tracker = ConnectionSuccessTracker()

        tracker.onConnections(mapOf(a to connecting(a)))
        tracker.onConnections(mapOf(a to failed(a)))

        // A stale CONNECTED after the failure must not show success without a new attempt.
        assertEquals(emptySet<String>(), tracker.onConnections(mapOf(a to connected(a))))

        tracker.onConnections(mapOf(a to connecting(a)))
        tracker.onConnections(mapOf(a to disconnected(a)))
        assertEquals(emptySet<String>(), tracker.onConnections(mapOf(a to connected(a))))
    }


    @Test
    fun eachDeviceIsTrackedIndependently() {

        val tracker = ConnectionSuccessTracker()

        tracker.onConnections(mapOf(a to connecting(a), b to failed(b)))

        assertEquals(setOf(a), tracker.onConnections(mapOf(a to connected(a), b to failed(b))))
        assertEquals(DeviceStatus.FAILED, deviceStatus(failed(b), visible = true))
    }
}

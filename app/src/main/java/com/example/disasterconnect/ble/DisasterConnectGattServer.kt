package com.example.disasterconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.disasterconnect.messaging.OutgoingFrameQueue
import com.example.disasterconnect.messaging.PacketFraming
import com.example.disasterconnect.messaging.PacketReassembler
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Peripheral-side service. Every event originates from a real GATT callback.
 *
 * Several phones may connect at once. Data frames arrive as writes on the message
 * characteristic and leave as notifications, one notification in flight at a time.
 */
class DisasterConnectGattServer(
    private val context: Context,
    private val nodeId: String,
    private val onEvent: (GattConnectionEvent) -> Unit,
    private val onPacket: (String, ByteArray) -> Unit = { _, _ -> }
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val handler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    // Keyed by Bluetooth address, except peerDevices which is keyed by node id.
    private val peerNodeIds = ConcurrentHashMap<String, String>()
    private val peerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val reassemblers = ConcurrentHashMap<String, PacketReassembler>()
    private val outgoing = OutgoingFrameQueue<BluetoothDevice>()
    private val transferIds = AtomicInteger()

    private val notifyTimeout = Runnable { pump(outgoing.onFrameResult(false)) }

    val isRunning: Boolean
        get() = gattServer != null

    /** True when [nodeId] completed the handshake over an incoming connection. */
    fun isConnected(nodeId: String): Boolean = peerDevices.containsKey(nodeId)

    @SuppressLint("MissingPermission")
    fun start() {
        if (gattServer != null) return
        val server = try {
            bluetoothManager.openGattServer(context, callback)
        } catch (_: SecurityException) {
            null
        }
        if (server == null) {
            onEvent(GattConnectionEvent(GattConnectionEventType.CONNECTION_FAILED, detail = "This phone cannot host a GATT service."))
            return
        }
        val service = DisasterConnectGatt.createService()
        messageCharacteristic = service.getCharacteristic(DisasterConnectGatt.MESSAGE_UUID)
        if (!server.addService(service)) {
            server.close()
            onEvent(GattConnectionEvent(GattConnectionEventType.CONNECTION_FAILED, detail = "Unable to start the GATT service."))
            return
        }
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(notifyTimeout)
        try {
            gattServer?.close()
        } catch (_: SecurityException) { }
        gattServer = null
        messageCharacteristic = null
        val peers = peerNodeIds.values.toSet()
        peerNodeIds.clear()
        peerDevices.clear()
        mtus.clear()
        reassemblers.clear()
        outgoing.failWhere { true }.completions.forEach { it() }
        peers.forEach { onEvent(GattConnectionEvent(GattConnectionEventType.CONNECTION_LOST, it, "GATT server stopped.")) }
    }

    /** Queues [packet] for [nodeId]. [onComplete] reports whether every frame was sent. */
    fun sendPacket(nodeId: String, packet: ByteArray, onComplete: (Boolean) -> Unit) {
        val device = peerDevices[nodeId] ?: run { onComplete(false); return }
        val frames = try {
            PacketFraming.split(packet, transferIds.getAndIncrement() and 0xFFFF, mtus[device.address] ?: PacketFraming.DEFAULT_MTU)
        } catch (_: IllegalArgumentException) {
            onComplete(false)
            return
        }
        pump(outgoing.enqueue(device, frames, onComplete))
    }

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when {
                status != BluetoothGatt.GATT_SUCCESS -> onEvent(
                    GattConnectionEvent(GattConnectionEventType.CONNECTION_FAILED, peerNodeIds[address], "Incoming connection failed (status $status).")
                )
                newState == BluetoothGatt.STATE_CONNECTED -> onEvent(
                    GattConnectionEvent(GattConnectionEventType.CONNECTION_ESTABLISHED, detail = "Incoming GATT connection established.")
                )
                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    val peerNodeId = peerNodeIds.remove(address)
                    if (peerNodeId != null && peerDevices[peerNodeId]?.address == address) peerDevices.remove(peerNodeId)
                    mtus.remove(address)
                    reassemblers.remove(address)
                    pump(outgoing.failWhere { it.address == address })
                    onEvent(GattConnectionEvent(GattConnectionEventType.CONNECTION_LOST, peerNodeId, "Peer disconnected."))
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // The HELLO notification also lands here; only frames from our queue advance it.
            if (outgoing.inFlightTarget()?.address != device.address) return
            handler.removeCallbacks(notifyTimeout)
            pump(outgoing.onFrameResult(status == BluetoothGatt.GATT_SUCCESS))
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != DisasterConnectGatt.NODE_INFORMATION_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            val value = nodeId.toByteArray(StandardCharsets.UTF_8)
            val response = if (offset <= value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (descriptor.uuid == DisasterConnectGatt.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else null
            gattServer?.sendResponse(device, requestId, if (value == null) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val validSubscription = descriptor.uuid == DisasterConnectGatt.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && !preparedWrite && offset == 0
            val status = if (validSubscription) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, offset, null)
            if (validSubscription && notify(device, DisasterConnectGatt.HELLO)) {
                onEvent(GattConnectionEvent(GattConnectionEventType.HELLO_SENT, detail = "HELLO sent to connected node."))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (PacketFraming.isFrame(value)) {
                onFrameWrite(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
                return
            }
            val peerNodeId = DisasterConnectGatt.nodeIdFromAcknowledgement(String(value, StandardCharsets.UTF_8))
            val validAck = characteristic.uuid == DisasterConnectGatt.MESSAGE_UUID && !preparedWrite && offset == 0 && peerNodeId != null
            val status = if (validAck) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, offset, null)
            if (validAck) {
                peerNodeIds[device.address] = peerNodeId!!
                peerDevices[peerNodeId] = device
                onEvent(GattConnectionEvent(GattConnectionEventType.ACK_RECEIVED, peerNodeId, "ACK received."))
                onEvent(GattConnectionEvent(GattConnectionEventType.HANDSHAKE_COMPLETED, peerNodeId, "Handshake completed."))
            }
        }
    }

    // ---------------------------------------------------------
    // Data frames
    // ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun onFrameWrite(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
        // Frames are accepted only after the handshake identified the peer.
        val peerNodeId = peerNodeIds[device.address]
        val accepted = peerNodeId != null && characteristic.uuid == DisasterConnectGatt.MESSAGE_UUID && !preparedWrite && offset == 0
        if (responseNeeded) gattServer?.sendResponse(device, requestId, if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, null)
        if (!accepted) return
        reassemblers.getOrPut(device.address) { PacketReassembler() }
            .accept(value, System.currentTimeMillis())
            ?.let { onPacket(peerNodeId!!, it) }
    }

    private fun pump(outcome: OutgoingFrameQueue.Outcome<BluetoothDevice>) {
        outcome.completions.forEach { it() }
        val (device, frame) = outcome.next ?: return
        if (notifyFrame(device, frame)) {
            handler.removeCallbacks(notifyTimeout)
            handler.postDelayed(notifyTimeout, NOTIFY_TIMEOUT_MILLIS)
        } else {
            pump(outgoing.onFrameResult(false))
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyFrame(device: BluetoothDevice, frame: ByteArray): Boolean {
        val characteristic = messageCharacteristic ?: return false
        val server = gattServer ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, frame) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // A value the stack refuses (e.g. over the attribute limit) fails this transfer, never the process.
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, value: String): Boolean {
        val characteristic = messageCharacteristic ?: return false
        characteristic.value = value.toByteArray(StandardCharsets.UTF_8)
        return gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
    }

    private companion object {
        const val NOTIFY_TIMEOUT_MILLIS = 5_000L
    }
}

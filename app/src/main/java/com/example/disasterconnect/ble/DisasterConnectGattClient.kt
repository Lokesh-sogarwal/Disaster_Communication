package com.example.disasterconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.disasterconnect.messaging.OutgoingFrameQueue
import com.example.disasterconnect.messaging.PacketFraming
import com.example.disasterconnect.messaging.PacketReassembler
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central-side GATT controller with callback-backed, observable connection events.
 *
 * After the HELLO / ACK handshake the same message characteristic carries data frames:
 * writes (with response) towards the server and notifications back from it.
 */
class DisasterConnectGattClient(
    private val context: Context,
    private val localNodeId: String,
    private val onEvent: (GattConnectionEvent) -> Unit,
    private val onPacket: (String, ByteArray) -> Unit = { _, _ -> }
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var targetNodeId: String? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var handshakeComplete = false

    private val handshake = HandshakeSequencer()
    @Volatile private var mtu = PacketFraming.DEFAULT_MTU
    private var servicesRequested = false
    private var reassembler = PacketReassembler()
    private val outgoing = OutgoingFrameQueue<Unit>()
    private val transferIds = AtomicInteger()

    private val handshakeTimeout = Runnable {
        if (!handshakeComplete && gatt != null) fail("Connection timed out before the handshake completed.")
    }
    private val mtuFallback = Runnable { gatt?.let(::discoverServices) }
    private val writeTimeout = Runnable { fail("Timed out while sending data.") }

    /** The remote node id once the handshake has completed and data can flow. */
    val connectedNodeId: String?
        get() = targetNodeId.takeIf { handshakeComplete }

    @SuppressLint("MissingPermission")
    fun connect(nodeId: String, device: BluetoothDevice) {
        if (gatt != null) {
            if (targetNodeId == nodeId) return
            emit(GattConnectionEventType.CONNECTION_FAILED, nodeId, "Another DisasterConnect connection is already active.")
            return
        }
        targetNodeId = nodeId
        handshakeComplete = false
        handshake.reset()
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
        if (gatt == null) fail("Unable to create a Bluetooth GATT connection.") else startTimeout()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val nodeId = targetNodeId
        handler.removeCallbacksAndMessages(null)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) { }
        clear()
        if (nodeId != null) emit(GattConnectionEventType.CONNECTION_LOST, nodeId, "Connection closed.")
    }

    fun close() = disconnect()

    /** Queues [packet] for the connected node. [onComplete] reports whether every frame was accepted. */
    fun sendPacket(packet: ByteArray, onComplete: (Boolean) -> Unit) {
        if (!handshakeComplete || gatt == null) {
            onComplete(false)
            return
        }
        val frames = try {
            PacketFraming.split(packet, transferIds.getAndIncrement() and 0xFFFF, mtu)
        } catch (_: IllegalArgumentException) {
            onComplete(false)
            return
        }
        pump(outgoing.enqueue(Unit, frames, onComplete))
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != this@DisasterConnectGattClient.gatt) return
            when {
                status != BluetoothGatt.GATT_SUCCESS -> fail("Connection failed (status $status).")
                newState == BluetoothGatt.STATE_CONNECTED -> {
                    emit(GattConnectionEventType.CONNECTION_ESTABLISHED, targetNodeId, "GATT connection established.")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // A larger MTU means fewer frames. Some phones never answer, so fall back to 23 bytes.
                    if (gatt.requestMtu(REQUESTED_MTU)) handler.postDelayed(mtuFallback, MTU_TIMEOUT_MILLIS)
                    else discoverServices(gatt)
                }
                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    val nodeId = targetNodeId
                    val completed = handshakeComplete
                    cleanupGatt()
                    if (nodeId != null) {
                        emit(
                            if (completed) GattConnectionEventType.CONNECTION_LOST else GattConnectionEventType.CONNECTION_FAILED,
                            nodeId,
                            if (completed) "Connection lost." else "Disconnected before the handshake completed."
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt != this@DisasterConnectGattClient.gatt) return
            handler.removeCallbacks(mtuFallback)
            if (status == BluetoothGatt.GATT_SUCCESS) this@DisasterConnectGattClient.mtu = mtu
            discoverServices(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT service discovery failed (status $status).")
                return
            }
            val service = gatt.getService(DisasterConnectGatt.SERVICE_UUID)
                ?: run { fail("DisasterConnect GATT service was not found."); return }
            messageCharacteristic = service.getCharacteristic(DisasterConnectGatt.MESSAGE_UUID)
                ?: run { fail("DisasterConnect message characteristic was not found."); return }
            val nodeCharacteristic = service.getCharacteristic(DisasterConnectGatt.NODE_INFORMATION_UUID)
                ?: run { fail("DisasterConnect node-information characteristic was not found."); return }
            emit(GattConnectionEventType.SERVICES_DISCOVERED, targetNodeId, "DisasterConnect service and characteristics discovered.")
            if (!gatt.readCharacteristic(nodeCharacteristic)) fail("Could not read the remote DisasterConnect node id.")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != DisasterConnectGatt.NODE_INFORMATION_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not read the remote DisasterConnect node id (status $status).")
                return
            }
            val remoteId = String(characteristic.value, StandardCharsets.UTF_8)
            if (!remoteId.startsWith("DC-")) {
                fail("Remote GATT server returned an invalid node id.")
                return
            }
            targetNodeId = remoteId
            enableHelloNotifications(gatt)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onMessageChanged(gatt, characteristic, characteristic.value?.copyOf() ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onMessageChanged(gatt, characteristic, value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != DisasterConnectGatt.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not subscribe to the handshake notification (status $status).")
                return
            }
            // HELLO may have overtaken this callback; the ACK could not be written until now.
            if (handshake.onSubscribed()) sendAck(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != DisasterConnectGatt.MESSAGE_UUID) return
            if (handshakeComplete) {
                handler.removeCallbacks(writeTimeout)
                pump(outgoing.onFrameResult(status == BluetoothGatt.GATT_SUCCESS))
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handshakeComplete = true
                handler.removeCallbacks(handshakeTimeout)
                emit(GattConnectionEventType.ACK_SENT, targetNodeId, "ACK accepted by the remote GATT server.")
                emit(GattConnectionEventType.HANDSHAKE_COMPLETED, targetNodeId, "Handshake completed.")
            } else fail("Handshake ACK was rejected (status $status).")
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(gatt: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        if (!gatt.discoverServices()) fail("Could not start GATT service discovery.")
    }

    @SuppressLint("MissingPermission")
    private fun enableHelloNotifications(gatt: BluetoothGatt) {
        val characteristic = messageCharacteristic ?: run { fail("Message characteristic is unavailable."); return }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail("Could not enable DisasterConnect handshake notifications.")
            return
        }
        val descriptor = characteristic.getDescriptor(DisasterConnectGatt.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
            ?: run { fail("Message notification descriptor was not found."); return }
        handshake.onSubscribeStarted()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                fail("Could not subscribe to the handshake notification.")
            }
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) fail("Could not subscribe to the handshake notification.")
        }
    }

    private fun onMessageChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != DisasterConnectGatt.MESSAGE_UUID) return
        if (PacketFraming.isFrame(value)) {
            val nodeId = connectedNodeId ?: return
            reassembler.accept(value, System.currentTimeMillis())?.let { onPacket(nodeId, it) }
            return
        }
        if (String(value, StandardCharsets.UTF_8) == DisasterConnectGatt.HELLO) {
            emit(GattConnectionEventType.HELLO_RECEIVED, targetNodeId, "HELLO received from remote node.")
            // Android runs one GATT operation at a time: wait for the subscription write to finish.
            if (handshake.onHello()) sendAck(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(gatt: BluetoothGatt) {
        val characteristic = messageCharacteristic ?: run { fail("Message characteristic is unavailable."); return }
        val payload = DisasterConnectGatt.acknowledgeFrom(localNodeId).toByteArray(StandardCharsets.UTF_8)
        if (!writeValue(gatt, characteristic, payload)) fail("Could not send the handshake ACK.")
    }

    // ---------------------------------------------------------
    // Data frames
    // ---------------------------------------------------------

    private fun pump(outcome: OutgoingFrameQueue.Outcome<Unit>) {
        outcome.completions.forEach { it() }
        val frame = outcome.next?.second ?: return
        val currentGatt = gatt
        val characteristic = messageCharacteristic
        if (currentGatt != null && characteristic != null && writeValue(currentGatt, characteristic, frame)) {
            handler.removeCallbacks(writeTimeout)
            handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MILLIS)
        } else {
            pump(outgoing.onFrameResult(false))
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeValue(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        // A value the stack refuses (e.g. over the attribute limit) fails this transfer, never the process.
        false
    }

    private fun startTimeout() {
        handler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun fail(reason: String) {
        val nodeId = targetNodeId ?: return
        handler.removeCallbacksAndMessages(null)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) { }
        clear()
        emit(GattConnectionEventType.CONNECTION_FAILED, nodeId, reason)
    }

    private fun cleanupGatt() {
        handler.removeCallbacksAndMessages(null)
        try {
            gatt?.close()
        } catch (_: SecurityException) { }
        clear()
    }

    private fun clear() {
        gatt = null
        targetNodeId = null
        messageCharacteristic = null
        handshakeComplete = false
        handshake.reset()
        mtu = PacketFraming.DEFAULT_MTU
        servicesRequested = false
        reassembler = PacketReassembler()
        // Queued transfers can never complete on a closed link.
        outgoing.failWhere { true }.completions.forEach { it() }
    }

    private fun emit(type: GattConnectionEventType, nodeId: String?, detail: String) {
        onEvent(GattConnectionEvent(type, nodeId, detail))
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MILLIS = 15_000L
        const val MTU_TIMEOUT_MILLIS = 3_000L
        const val WRITE_TIMEOUT_MILLIS = 5_000L
        const val REQUESTED_MTU = 185
    }
}

package com.example.disasterconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets

/** Central-side GATT controller with callback-backed, observable connection events. */
class DisasterConnectGattClient(
    private val context: Context,
    private val localNodeId: String,
    private val onEvent: (GattConnectionEvent) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var targetNodeId: String? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var handshakeComplete = false

    @SuppressLint("MissingPermission")
    fun connect(nodeId: String, device: BluetoothDevice) {
        if (gatt != null) {
            if (targetNodeId == nodeId) return
            emit(GattConnectionEventType.CONNECTION_FAILED, nodeId, "Another DisasterConnect connection is already active.")
            return
        }
        targetNodeId = nodeId
        handshakeComplete = false
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail("Unable to create a Bluetooth GATT connection.") else startTimeout()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val nodeId = targetNodeId
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        clear()
        if (nodeId != null) emit(GattConnectionEventType.CONNECTION_LOST, nodeId, "Connection closed.")
    }

    fun close() = disconnect()

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != this@DisasterConnectGattClient.gatt) return
            when {
                status != BluetoothGatt.GATT_SUCCESS -> fail("Connection failed (status $status).")
                newState == BluetoothGatt.STATE_CONNECTED -> {
                    emit(GattConnectionEventType.CONNECTION_ESTABLISHED, targetNodeId, "GATT connection established.")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.discoverServices()) fail("Could not start GATT service discovery.")
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
            onMessageChanged(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onMessageChanged(gatt, characteristic, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != DisasterConnectGatt.MESSAGE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handshakeComplete = true
                handler.removeCallbacksAndMessages(null)
                emit(GattConnectionEventType.ACK_SENT, targetNodeId, "ACK accepted by the remote GATT server.")
                emit(GattConnectionEventType.HANDSHAKE_COMPLETED, targetNodeId, "Handshake completed.")
            } else fail("Handshake ACK was rejected (status $status).")
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not subscribe to the handshake notification.")
            }
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) fail("Could not subscribe to the handshake notification.")
        }
    }

    private fun onMessageChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != DisasterConnectGatt.MESSAGE_UUID) return
        if (String(value, StandardCharsets.UTF_8) == DisasterConnectGatt.HELLO) {
            emit(GattConnectionEventType.HELLO_RECEIVED, targetNodeId, "HELLO received from remote node.")
            sendAck(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(gatt: BluetoothGatt) {
        val characteristic = messageCharacteristic ?: run { fail("Message characteristic is unavailable."); return }
        val payload = DisasterConnectGatt.acknowledgeFrom(localNodeId).toByteArray(StandardCharsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not send the handshake ACK.")
            }
        } else {
            characteristic.value = payload
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!gatt.writeCharacteristic(characteristic)) fail("Could not send the handshake ACK.")
        }
    }

    private fun startTimeout() {
        handler.postDelayed({
            if (!handshakeComplete && gatt != null) fail("Connection timed out before the handshake completed.")
        }, HANDSHAKE_TIMEOUT_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun fail(reason: String) {
        val nodeId = targetNodeId ?: return
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        clear()
        emit(GattConnectionEventType.CONNECTION_FAILED, nodeId, reason)
    }

    private fun cleanupGatt() {
        handler.removeCallbacksAndMessages(null)
        gatt?.close()
        clear()
    }

    private fun clear() {
        gatt = null
        targetNodeId = null
        messageCharacteristic = null
        handshakeComplete = false
    }

    private fun emit(type: GattConnectionEventType, nodeId: String?, detail: String) {
        onEvent(GattConnectionEvent(type, nodeId, detail))
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MILLIS = 15_000L
    }
}

package com.example.disasterconnect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.content.Context
import java.nio.charset.StandardCharsets

/** Peripheral-side service. Every event originates from a real GATT callback. */
class DisasterConnectGattServer(
    private val context: Context,
    private val nodeId: String,
    private val onEvent: (GattConnectionEvent) -> Unit
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private val peerNodeIds = mutableMapOf<String, String>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (gattServer != null) return
        val server = bluetoothManager.openGattServer(context, callback)
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
        gattServer?.close()
        gattServer = null
        messageCharacteristic = null
        peerNodeIds.clear()
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
                newState == BluetoothGatt.STATE_DISCONNECTED -> onEvent(
                    GattConnectionEvent(GattConnectionEventType.CONNECTION_LOST, peerNodeIds.remove(address), "Peer disconnected.")
                )
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != DisasterConnectGatt.NODE_INFORMATION_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            val value = nodeId.toByteArray(StandardCharsets.UTF_8)
            val response = if (offset <= value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

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
            val peerNodeId = DisasterConnectGatt.nodeIdFromAcknowledgement(String(value, StandardCharsets.UTF_8))
            val validAck = characteristic.uuid == DisasterConnectGatt.MESSAGE_UUID && !preparedWrite && offset == 0 && peerNodeId != null
            val status = if (validAck) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, offset, null)
            if (validAck) {
                peerNodeIds[device.address] = peerNodeId!!
                onEvent(GattConnectionEvent(GattConnectionEventType.ACK_RECEIVED, peerNodeId, "ACK received."))
                onEvent(GattConnectionEvent(GattConnectionEventType.HANDSHAKE_COMPLETED, peerNodeId, "Handshake completed."))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, value: String): Boolean {
        val characteristic = messageCharacteristic ?: return false
        characteristic.value = value.toByteArray(StandardCharsets.UTF_8)
        return gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
    }
}

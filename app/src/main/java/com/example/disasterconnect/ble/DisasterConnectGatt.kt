package com.example.disasterconnect.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import java.util.UUID

/** Fixed protocol identifiers shared by every DisasterConnect device. */
object DisasterConnectGatt {
    val SERVICE_UUID: UUID = UUID.fromString("c7f9f1b5-0ce1-4dbe-9c75-c2d7dc5e9b0a")
    val NODE_INFORMATION_UUID: UUID = UUID.fromString("c7f9f1b5-0ce1-4dbe-9c75-c2d7dc5e9b0b")
    val MESSAGE_UUID: UUID = UUID.fromString("c7f9f1b5-0ce1-4dbe-9c75-c2d7dc5e9b0c")
    val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HELLO = "HELLO"
    const val ACK = "ACK"
    private const val ACK_NODE_SEPARATOR = "|"

    fun acknowledgeFrom(nodeId: String): String =
        "$ACK$ACK_NODE_SEPARATOR$nodeId"

    fun nodeIdFromAcknowledgement(message: String): String? {
        val prefix = "$ACK$ACK_NODE_SEPARATOR"
        return message.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.startsWith("DC-") && it.length > 3 }
    }

    fun createService(): BluetoothGattService {
        val nodeInformation = BluetoothGattCharacteristic(
            NODE_INFORMATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val message = BluetoothGattCharacteristic(
            MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        message.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        return BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(nodeInformation)
            addCharacteristic(message)
        }
    }
}

enum class GattConnectionEventType {
    CONNECTION_ESTABLISHED,
    SERVICES_DISCOVERED,
    HELLO_SENT,
    HELLO_RECEIVED,
    ACK_SENT,
    ACK_RECEIVED,
    HANDSHAKE_COMPLETED,
    CONNECTION_LOST,
    CONNECTION_FAILED
}

/** A BLE-originated event that the Activity maps directly into Compose state. */
data class GattConnectionEvent(
    val type: GattConnectionEventType,
    val nodeId: String? = null,
    val detail: String? = null
)

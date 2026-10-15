package com.example.disasterconnect.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


// =========================================================
// DELIVERY CONSTANTS
// =========================================================

object MessagePriority {

    const val CRITICAL = "CRITICAL"

    const val HIGH = "HIGH"

    const val NORMAL = "NORMAL"
}


object DeliveryState {

    const val CREATED = "CREATED"

    const val QUEUED = "QUEUED"

    const val FORWARDED = "FORWARDED"

    const val GATEWAY_REACHED = "GATEWAY_REACHED"

    const val SERVER_RECEIVED = "SERVER_RECEIVED"

    const val DELIVERED = "DELIVERED"

    const val EXPIRED = "EXPIRED"
}


// =========================================================
// OFFLINE-FIRST MESSAGE QUEUE
// =========================================================

@Entity(
    tableName = "emergency_messages",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["deliveryState"]),
        Index(value = ["expiresAt"])
    ]
)
data class EmergencyMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageId: String,
    val senderId: String,
    val receiverId: String? = null,
    val messageType: String,
    val priority: String,
    val content: String,
    val createdAt: Long,
    val expiresAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hopCount: Int = 0,
    val currentRelayId: String? = null,
    val deliveryState: String = DeliveryState.CREATED,
    val lastAttemptAt: Long? = null
)

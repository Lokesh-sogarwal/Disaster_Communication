package com.example.disasterconnect.data

import androidx.room.ColumnInfo
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


object MessageType {

    const val TEXT = "TEXT"

    const val SOS = "SOS"

    const val STATUS = "STATUS"

    const val LOCATION = "LOCATION"

    // Reserved for signed delivery receipts (rules.md §13, §15). Not accepted on the wire yet.
    const val RECEIPT = "RECEIPT"

    // Reserved for direct-link public key exchange (rules.md §13, §15). Not accepted on the wire yet.
    const val KEY = "KEY"
}


object DeliveryState {

    const val CREATED = "CREATED"

    // Shown to users as "Pending".
    const val QUEUED = "QUEUED"

    // Every BLE frame was accepted by the peer; waiting for its application-level ACK.
    const val SENT = "SENT"

    const val FORWARDED = "FORWARDED"

    const val GATEWAY_REACHED = "GATEWAY_REACHED"

    const val SERVER_RECEIVED = "SERVER_RECEIVED"

    const val DELIVERED = "DELIVERED"

    const val EXPIRED = "EXPIRED"

    // Gave up after the retry limit or the receiver rejected it. Can be retried manually.
    const val FAILED = "FAILED"
}


/** What proves an outgoing direct message reached its recipient (rules.md §11). Not a delivery state. */
object DeliveryProof {

    // Nothing from the recipient yet.
    const val NONE = "NONE"

    // The recipient's own BLE ACK. Unauthenticated: anyone can claim a node ID.
    const val LINK_ACK = "LINK_ACK"

    // A signed receipt that verified against the recipient's VERIFIED key.
    const val SIGNED_RECEIPT = "SIGNED_RECEIPT"
}


// =========================================================
// OFFLINE-FIRST MESSAGE QUEUE
// =========================================================

@Entity(
    tableName = "emergency_messages",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["deliveryState"]),
        Index(value = ["expiresAt"]),
        Index(value = ["receiptFor"])
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
    val lastAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val isIncoming: Boolean = false,
    @ColumnInfo(defaultValue = DeliveryProof.NONE)
    val deliveryProof: String = DeliveryProof.NONE,
    // RECEIPT rows only: the messageId of the message this receipt confirms.
    val receiptFor: String? = null
)


// =========================================================
// PER-PEER HANDOFF LOG
// =========================================================

/** A broadcast message (e.g. SOS) is handed to each peer at most once. */
@Entity(
    tableName = "message_handoffs",
    primaryKeys = ["messageId", "peerId"]
)
data class MessageHandoff(
    val messageId: String,
    val peerId: String,
    val handedOffAt: Long
)

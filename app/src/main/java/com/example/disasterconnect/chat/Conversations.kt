package com.example.disasterconnect.chat

import com.example.disasterconnect.data.DeliveryProof
import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType

// =========================================================
// INBOX GROUPING
// =========================================================
// Pure view logic over stored EmergencyMessage rows. It never changes a message;
// it only decides how existing data is grouped and labelled on screen.

/** Who a conversation is with. Outgoing broadcasts (SOS) have no single recipient. */
sealed interface ConversationKey {

    data class Peer(val nodeId: String) : ConversationKey

    data object OwnBroadcasts : ConversationKey
}

data class Conversation(
    val key: ConversationKey,
    val latest: EmergencyMessage,
    val messageCount: Int,
    val failedCount: Int,
    val hasEmergency: Boolean
)

/** The conversation a stored message belongs to, from the message model only. */
fun EmergencyMessage.conversationKey(): ConversationKey =
    when {
        isIncoming -> ConversationKey.Peer(senderId)
        receiverId != null -> ConversationKey.Peer(receiverId)
        else -> ConversationKey.OwnBroadcasts
    }

/**
 * A relay copy is a direct message this phone only carries for two other phones
 * (received, but addressed to someone else). It is stored for forwarding, never shown.
 */
fun EmergencyMessage.isRelayCopy(localNodeId: String): Boolean =
    isIncoming && receiverId != null && receiverId != localNodeId

/** Delivery receipts and key messages are protocol traffic, never chat messages. */
fun EmergencyMessage.isProtocolMessage(): Boolean =
    messageType == MessageType.RECEIPT || messageType == MessageType.KEY

/** Messages the user of this phone should see: everything except relay copies and protocol traffic. */
fun List<EmergencyMessage>.visibleTo(localNodeId: String): List<EmergencyMessage> =
    filterNot { it.isRelayCopy(localNodeId) || it.isProtocolMessage() }

fun EmergencyMessage.isEmergency(): Boolean =
    messageType == MessageType.SOS || priority == MessagePriority.CRITICAL

/**
 * Groups messages into conversations, newest activity first. "Newest" uses the local
 * row id (arrival order on this phone), because createdAt on received messages comes
 * from the sender's clock.
 */
fun buildConversations(messages: List<EmergencyMessage>): List<Conversation> =
    messages
        .groupBy { it.conversationKey() }
        .map { (key, group) ->
            Conversation(
                key = key,
                latest = group.maxBy { it.id },
                messageCount = group.size,
                failedCount = group.count { !it.isIncoming && it.deliveryState == DeliveryState.FAILED },
                hasEmergency = group.any { it.isEmergency() }
            )
        }
        .sortedByDescending { it.latest.id }


// =========================================================
// DELIVERY LABELS
// =========================================================

/** What the user is told about one message. Each value maps to a real stored state. */
enum class DeliveryLabel(
    val text: String,
    val explanation: String
) {
    RECEIVED("Received", "This message reached your phone."),
    WAITING_FOR_CONNECTION("Waiting for connection", "It will send automatically when the other phone is connected."),
    PENDING("Pending", "Connected. It will be sent in a moment."),
    SENT("Sent", "Sent. Waiting for the other phone to confirm."),
    DELIVERED("Delivered · unverified", "A phone using this ID confirmed it. Its identity was not verified with a signed receipt."),
    DELIVERED_VERIFIED("Delivered · verified by recipient", "The recipient's verified security key signed a receipt for this exact message."),
    AWAITING_RECEIPT("Reached recipient · awaiting verified receipt", "The recipient's phone acknowledged it, but its signed receipt has not arrived yet."),
    PASSED_ON("Passed to nearby phone", "A nearby phone received this and will pass it on."),
    FAILED("Failed", "It could not be delivered after several tries."),
    EXPIRED("Expired", "It was not delivered in time and will not be sent."),
    REACHED_GATEWAY("Reached gateway", "It reached a phone with Internet."),
    REACHED_SERVER("Reached server", "It reached the emergency server.")
}

/**
 * [peerLinked] is whether BleConnectionManager reports the recipient as connected.
 * It only splits QUEUED into "Pending" vs "Waiting for connection"; it never upgrades a state.
 */
fun EmergencyMessage.deliveryLabel(peerLinked: Boolean): DeliveryLabel {

    if (isIncoming) return DeliveryLabel.RECEIVED

    return when (deliveryState) {
        DeliveryState.CREATED, DeliveryState.QUEUED ->
            if (peerLinked) DeliveryLabel.PENDING else DeliveryLabel.WAITING_FOR_CONNECTION
        DeliveryState.SENT -> DeliveryLabel.SENT
        // Only a stored SIGNED_RECEIPT proof is shown as verified.
        DeliveryState.DELIVERED ->
            if (deliveryProof == DeliveryProof.SIGNED_RECEIPT) DeliveryLabel.DELIVERED_VERIFIED else DeliveryLabel.DELIVERED
        DeliveryState.FORWARDED ->
            if (deliveryProof == DeliveryProof.LINK_ACK) DeliveryLabel.AWAITING_RECEIPT else DeliveryLabel.PASSED_ON
        DeliveryState.FAILED -> DeliveryLabel.FAILED
        DeliveryState.EXPIRED -> DeliveryLabel.EXPIRED
        DeliveryState.GATEWAY_REACHED -> DeliveryLabel.REACHED_GATEWAY
        DeliveryState.SERVER_RECEIVED -> DeliveryLabel.REACHED_SERVER
        // Unknown future state: show it as not yet sent rather than claiming success.
        else -> DeliveryLabel.PENDING
    }
}

/** Broadcasts (SOS) have no single recipient, so their waiting text differs. */
fun EmergencyMessage.deliveryExplanation(label: DeliveryLabel): String =
    when {
        !isIncoming && receiverId == null && label == DeliveryLabel.WAITING_FOR_CONNECTION ->
            "It will be passed to nearby phones as soon as one is in range."
        // A relay ACK is hop-level only: be explicit that the recipient has not confirmed it.
        !isIncoming && receiverId != null && label == DeliveryLabel.PASSED_ON ->
            "A nearby phone has it and will pass it on to $receiverId. Delivery is not confirmed yet."
        !isIncoming && label == DeliveryLabel.EXPIRED && deliveryProof == DeliveryProof.LINK_ACK ->
            "$receiverId's phone acknowledged it, but no verified receipt arrived before it expired."
        else -> label.explanation
    }

/** Retry is offered exactly where MessagingEngine.retry() would act. */
fun EmergencyMessage.canRetry(): Boolean =
    !isIncoming && deliveryState == DeliveryState.FAILED

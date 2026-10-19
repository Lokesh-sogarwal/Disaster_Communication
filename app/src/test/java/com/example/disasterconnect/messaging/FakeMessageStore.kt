package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType


/** Mirrors the Room queries: unique messageId, candidate states, expiry and priority ordering. */
internal class FakeMessageStore : MessageStore {

    private val messages = linkedMapOf<String, EmergencyMessage>()
    private val handoffs = mutableSetOf<Pair<String, String>>()

    fun get(messageId: String) = messages.getValue(messageId)
    fun all() = messages.values.toList()

    override suspend fun insert(message: EmergencyMessage): Boolean =
        messages.putIfAbsent(message.messageId, message) == null

    override suspend fun update(message: EmergencyMessage) { messages[message.messageId] = message }

    override suspend fun find(messageId: String) = messages[messageId]

    override suspend fun relayCandidates(now: Long) = messages.values
        .filter { it.deliveryState in setOf(DeliveryState.CREATED, DeliveryState.QUEUED, DeliveryState.FORWARDED) && it.expiresAt > now }
        .sortedWith(compareBy({ listOf(MessagePriority.CRITICAL, MessagePriority.HIGH).indexOf(it.priority).let { p -> if (p < 0) 2 else p } }, { it.createdAt }))

    override suspend fun awaitingAck() = messages.values.filter { it.deliveryState == DeliveryState.SENT }

    override suspend fun expire(now: Long) {
        messages.replaceAll { _, m ->
            if (m.expiresAt <= now && m.deliveryState !in setOf(DeliveryState.DELIVERED, DeliveryState.EXPIRED)) m.copy(deliveryState = DeliveryState.EXPIRED) else m
        }
    }

    override suspend fun recordHandoff(messageId: String, peerId: String, at: Long) { handoffs += messageId to peerId }

    override suspend fun hasHandoff(messageId: String, peerId: String) = (messageId to peerId) in handoffs

    override suspend fun handoffCount(messageId: String) = handoffs.count { it.first == messageId }

    /** Set to simulate a crash between the two inserts (Room inserts both in one transaction). */
    var failReceiptInsert = false

    override suspend fun insertWithReceipt(message: EmergencyMessage, receipt: EmergencyMessage): Boolean {
        if (messages.containsKey(message.messageId)) return false
        if (failReceiptInsert) throw IllegalStateException("simulated crash before commit")
        messages[message.messageId] = message
        messages.putIfAbsent(receipt.messageId, receipt)
        return true
    }

    override suspend fun receiptCount(messageId: String) =
        messages.values.count { it.messageType == MessageType.RECEIPT && it.receiptFor == messageId }

    override suspend fun pendingRelayReceipts(now: Long) = messages.values.count {
        it.messageType == MessageType.RECEIPT && it.isIncoming && it.expiresAt > now &&
            it.deliveryState in setOf(DeliveryState.CREATED, DeliveryState.QUEUED, DeliveryState.SENT, DeliveryState.FORWARDED)
    }

    override suspend fun messagesMissingReceipt(localNodeId: String, now: Long, limit: Int) = messages.values
        .filter { m ->
            m.isIncoming && m.receiverId == localNodeId && m.expiresAt > now &&
                m.messageType in setOf(MessageType.TEXT, MessageType.STATUS, MessageType.LOCATION) &&
                messages.values.none { it.messageType == MessageType.RECEIPT && it.receiptFor == m.messageId && it.senderId == localNodeId }
        }
        .take(limit)
}

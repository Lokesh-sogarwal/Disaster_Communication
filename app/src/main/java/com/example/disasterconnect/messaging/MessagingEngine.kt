package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.DeliveryProof
import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.EmergencyMessageDao
import com.example.disasterconnect.data.MessageHandoff
import com.example.disasterconnect.data.MessageHandoffDao
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID


// =========================================================
// STORAGE PORT
// =========================================================

/** What the engine needs from persistence. Room in the app, in-memory in unit tests. */
interface MessageStore {

    /** Returns false when a message with the same messageId already exists (deduplication). */
    suspend fun insert(message: EmergencyMessage): Boolean

    suspend fun update(message: EmergencyMessage)

    suspend fun find(messageId: String): EmergencyMessage?

    /** Not expired and in CREATED / QUEUED / FORWARDED, highest priority and oldest first. */
    suspend fun relayCandidates(now: Long): List<EmergencyMessage>

    suspend fun awaitingAck(): List<EmergencyMessage>

    suspend fun expire(now: Long)

    suspend fun recordHandoff(messageId: String, peerId: String, at: Long)

    suspend fun hasHandoff(messageId: String, peerId: String): Boolean

    suspend fun handoffCount(messageId: String): Int

    /** Inserts [message] and [receipt] atomically. Returns false (nothing stored) if [message] already exists. */
    suspend fun insertWithReceipt(message: EmergencyMessage, receipt: EmergencyMessage): Boolean

    /** RECEIPT rows of any origin that confirm [messageId]. */
    suspend fun receiptCount(messageId: String): Int

    /** Receipts this phone carries for others and has not finished passing on. */
    suspend fun pendingRelayReceipts(now: Long): Int

    /** Personal messages addressed to [localNodeId] that have no receipt from this phone yet. */
    suspend fun messagesMissingReceipt(localNodeId: String, now: Long, limit: Int): List<EmergencyMessage>
}


/**
 * Which keys may prove delivery (rules.md §11, §15). Personal direct messages only; never consulted
 * for SOS or broadcasts. Production: VERIFIED rows of the trust store; tests use fakes.
 */
interface ReceiptTrust {

    /** True if [nodeId] has a VERIFIED key: then only a signed receipt proves delivery to it. */
    suspend fun requiresSignedReceipt(nodeId: String): Boolean

    /** The VERIFIED key allowed to sign receipts for [nodeId]; null if none or a key conflict is active. */
    suspend fun receiptKey(nodeId: String): ByteArray?

    object None : ReceiptTrust {

        override suspend fun requiresSignedReceipt(nodeId: String) = false

        override suspend fun receiptKey(nodeId: String): ByteArray? = null
    }
}


class RoomMessageStore(
    private val messages: EmergencyMessageDao,
    private val handoffs: MessageHandoffDao
) : MessageStore {

    override suspend fun insert(message: EmergencyMessage) = messages.insert(message) != -1L
    override suspend fun update(message: EmergencyMessage) = messages.update(message)
    override suspend fun find(messageId: String) = messages.findByMessageId(messageId)
    override suspend fun relayCandidates(now: Long) = messages.getRelayCandidates(now)
    override suspend fun awaitingAck() = messages.getAwaitingAck()
    override suspend fun expire(now: Long) { messages.expireMessages(now) }
    override suspend fun recordHandoff(messageId: String, peerId: String, at: Long) =
        handoffs.insert(MessageHandoff(messageId, peerId, at))
    override suspend fun hasHandoff(messageId: String, peerId: String) = handoffs.count(messageId, peerId) > 0
    override suspend fun handoffCount(messageId: String) = handoffs.countForMessage(messageId)
    override suspend fun insertWithReceipt(message: EmergencyMessage, receipt: EmergencyMessage) =
        messages.insertAll(listOf(message, receipt)).first() != -1L
    override suspend fun receiptCount(messageId: String) = messages.countReceiptsFor(messageId)
    override suspend fun pendingRelayReceipts(now: Long) = messages.countPendingRelayReceipts(now)
    override suspend fun messagesMissingReceipt(localNodeId: String, now: Long, limit: Int) =
        messages.getMissingReceipts(localNodeId, now, limit)
}


// =========================================================
// RETRY POLICY
// =========================================================

data class RetryPolicy(
    val baseDelayMillis: Long = 5_000L,
    val maxDelayMillis: Long = 60_000L,
    // Direct messages become FAILED after this many unacknowledged attempts. Broadcasts never fail.
    val maxAttempts: Int = 5,
    val ackTimeoutMillis: Long = 15_000L
) {

    /** Exponential backoff after [attempts] failed attempts. */
    fun delayAfter(attempts: Int): Long =
        if (attempts <= 0) 0L
        else (baseDelayMillis shl (attempts - 1).coerceAtMost(10)).coerceAtMost(maxDelayMillis)
}


// =========================================================
// DELIVERY RULES
// =========================================================

/**
 * Store-and-forward rules for one phone. Pure Kotlin, so every rule is unit-tested.
 *
 * - Direct message (receiverId set): QUEUED → SENT → DELIVERED only when the receiver ACKs.
 * - Broadcast (receiverId null, e.g. SOS): handed to every peer once; each ACK records a
 *   handoff and the message becomes FORWARDED. Received broadcasts are relayed further
 *   until [maxHops].
 * - Multi-hop direct message: when the receiver is not linked, the message may be handed
 *   to up to [maxRelayCopies] relays. A relay ACK only means FORWARDED (hop-level);
 *   DELIVERED still requires an ACK from the receiver itself. Relays keep a hidden
 *   "relay copy" (isIncoming, addressed to someone else) and forward it the same way.
 * - Signed receipts (personal direct messages only, never SOS or broadcasts): the recipient stores
 *   each new message together with a receipt signed by its identity key; the receipt travels back
 *   like a direct message and relays pass it on unchanged. For a recipient with a VERIFIED key,
 *   only a receipt that verifies against that key makes the message DELIVERED (SIGNED_RECEIPT);
 *   its plain ACK only records LINK_ACK. Other recipients keep the ACK rule (DELIVERED, LINK_ACK).
 */
class MessagingEngine(
    private val store: MessageStore,
    private val localNodeId: String,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val maxHops: Int = DEFAULT_MAX_HOPS,
    private val maxRelayCopies: Int = DEFAULT_MAX_RELAY_COPIES,
    private val receiptTrust: ReceiptTrust = ReceiptTrust.None,
    // This phone's identity signer; null while the Keystore key is unavailable (receipts are then recovered later).
    private val receiptSigner: () -> ReceiptSigner? = { null }
) {

    data class Outbound(val messageId: String, val bytes: ByteArray)

    /** [message] is non-null only when the packet was new and stored. */
    data class ReceiveResult(val ack: AckPacket, val message: EmergencyMessage?)

    private val mutex = Mutex()


    // ---------------------------------------------------------
    // Outgoing
    // ---------------------------------------------------------

    suspend fun createText(receiverId: String, text: String, now: Long): EmergencyMessage {
        val content = text.trim()
        require(content.isNotEmpty()) { "Message is empty." }
        require(MessagePacketCodec.payloadFits(content)) { "Message is too long." }
        require(receiverId != localNodeId) { "Cannot message this phone." }
        val message = EmergencyMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = localNodeId,
            receiverId = receiverId,
            messageType = MessageType.TEXT,
            priority = MessagePriority.NORMAL,
            content = content,
            createdAt = now,
            expiresAt = now + TEXT_TTL_MILLIS,
            deliveryState = DeliveryState.QUEUED
        )
        mutex.withLock { store.insert(message) }
        return message
    }

    suspend fun hasPendingFor(peerId: String, now: Long): Boolean = mutex.withLock {
        store.relayCandidates(now).any { isEligible(it, peerId, now) }
    }

    /** Picks the next message for [peerId] and counts the attempt. Null when nothing is due. */
    suspend fun nextOutgoing(peerId: String, now: Long): Outbound? = mutex.withLock {
        store.expire(now)
        for (message in store.relayCandidates(now)) {
            if (!isEligible(message, peerId, now)) continue
            val bytes = try {
                MessagePacketCodec.encode(message.toPacket(now))
            } catch (_: IllegalArgumentException) {
                store.update(message.copy(deliveryState = DeliveryState.FAILED))
                continue
            }
            store.update(message.copy(attemptCount = message.attemptCount + 1, lastAttemptAt = now, currentRelayId = peerId))
            return@withLock Outbound(message.messageId, bytes)
        }
        null
    }

    /** Called when the BLE stack accepted (or failed to send) every frame of the message. */
    suspend fun onTransmitted(messageId: String, peerId: String, success: Boolean, now: Long) = mutex.withLock {
        val message = store.find(messageId) ?: return@withLock
        if (message.deliveryState !in RETRYABLE_STATES) return@withLock
        if (success) {
            // The ACK can overtake the final write confirmation; never downgrade in that case.
            if (store.hasHandoff(messageId, peerId)) return@withLock
            // Once a relay holds a direct message it stays FORWARDED until the receiver itself ACKs.
            val state = if (message.isDirectForwarded()) DeliveryState.FORWARDED else DeliveryState.SENT
            store.update(message.copy(deliveryState = state, lastAttemptAt = now, currentRelayId = peerId))
        } else if (message.isOwnDirect() && !message.isDirectForwarded() && message.attemptCount >= retryPolicy.maxAttempts) {
            store.update(message.copy(deliveryState = DeliveryState.FAILED))
        }
    }

    suspend fun onAck(ack: AckPacket, peerId: String, now: Long) = mutex.withLock {
        // Only the phone we are linked with may acknowledge (the ACK format carries no relay path).
        if (ack.fromNodeId != peerId) return@withLock
        val message = store.find(ack.messageId) ?: return@withLock
        // A message that was addressed to this phone is never acknowledged by others.
        if (message.receiverId == localNodeId) return@withLock
        if (message.deliveryState in FINAL_STATES) return@withLock

        // Only the intended receiver's own ACK proves delivery. Any other ACK is hop-level.
        val fromFinalRecipient = message.receiverId != null && message.receiverId == peerId

        // A relay ACK for a direct message counts only from the phone it was actually sent to.
        if (message.receiverId != null && !fromFinalRecipient && message.currentRelayId != peerId) return@withLock

        when (ack.status) {
            AckStatus.ACCEPTED, AckStatus.DUPLICATE ->
                if (fromFinalRecipient && message.isPersonalOwnDirect() && receiptTrust.requiresSignedReceipt(peerId)) {
                    // Verified contact: its ACK proves nothing cryptographically. The recipient has it, so it is
                    // never sent again; DELIVERED waits for the signed receipt.
                    store.recordHandoff(message.messageId, peerId, now)
                    store.update(
                        message.copy(
                            deliveryState = DeliveryState.FORWARDED,
                            deliveryProof = DeliveryProof.LINK_ACK,
                            attemptCount = 0,
                            currentRelayId = peerId
                        )
                    )
                } else if (fromFinalRecipient) {
                    store.update(
                        message.copy(deliveryState = DeliveryState.DELIVERED, deliveryProof = DeliveryProof.LINK_ACK, currentRelayId = peerId)
                    )
                } else {
                    store.recordHandoff(message.messageId, peerId, now)
                    store.update(message.copy(deliveryState = DeliveryState.FORWARDED, attemptCount = 0, currentRelayId = peerId))
                }

            AckStatus.REJECTED ->
                if (fromFinalRecipient && message.isOwnDirect()) {
                    store.update(message.copy(deliveryState = DeliveryState.FAILED))
                } else {
                    // A relay (or older app) that cannot carry it is simply not used again.
                    // The record only blocks that peer; it never counts as a successful relay.
                    store.recordHandoff(message.messageId, peerId, now)
                    val state = if (message.isDirectForwarded()) DeliveryState.FORWARDED else requeueState(message)
                    store.update(message.copy(deliveryState = state))
                }

            AckStatus.EXPIRED ->
                store.update(message.copy(deliveryState = DeliveryState.EXPIRED))
        }
    }

    /** SENT messages without an ACK in time go back to the queue (or FAILED at the limit). */
    suspend fun requeueTimedOut(now: Long) = mutex.withLock {
        store.awaitingAck()
            .filter { now - (it.lastAttemptAt ?: 0L) >= retryPolicy.ackTimeoutMillis }
            .forEach {
                // Older apps drop RECEIPT packets without any ACK: each phone gets one try per receipt.
                val peer = it.currentRelayId
                if (it.messageType == MessageType.RECEIPT && peer != null) store.recordHandoff(it.messageId, peer, now)
                store.update(it.copy(deliveryState = requeueState(it)))
            }
    }

    /** The link to [peerId] dropped: anything still waiting for its ACK is retried later. */
    suspend fun requeueForPeer(peerId: String) = mutex.withLock {
        store.awaitingAck()
            .filter { it.currentRelayId == peerId }
            .forEach { store.update(it.copy(deliveryState = requeueState(it))) }
    }

    suspend fun retry(messageId: String) = mutex.withLock {
        val message = store.find(messageId) ?: return@withLock
        if (message.deliveryState == DeliveryState.FAILED && !message.isIncoming) {
            store.update(message.copy(deliveryState = DeliveryState.QUEUED, attemptCount = 0))
        }
    }


    // ---------------------------------------------------------
    // Incoming
    // ---------------------------------------------------------

    suspend fun onDataReceived(packet: DataPacket, peerId: String, now: Long): ReceiveResult = mutex.withLock {
        fun ack(status: AckStatus, message: EmergencyMessage? = null) =
            ReceiveResult(AckPacket(packet.messageId, localNodeId, status), message)

        when {
            // Key exchange is handled by KeyExchange; KEY packets are never stored, shown or relayed.
            packet.messageType == MessageType.KEY -> ack(AckStatus.REJECTED)
            packet.ttlSeconds <= 0 -> ack(AckStatus.EXPIRED)
            packet.senderId == localNodeId -> {
                // Our own message came back: that peer already has it, so never send it there again.
                if (packet.receiverId != localNodeId) store.recordHandoff(packet.messageId, peerId, now)
                ack(AckStatus.DUPLICATE)
            }
            // Receipts have their own checks and never reach the inbox.
            packet.messageType == MessageType.RECEIPT -> ack(onReceipt(packet, peerId, now))
            // A relay copy this phone could never pass on is refused, so the sender keeps trying elsewhere.
            packet.receiverId != null && packet.receiverId != localNodeId && packet.hopCount + 1 >= maxHops ->
                ack(AckStatus.REJECTED)
            else -> {
                val message = EmergencyMessage(
                    messageId = packet.messageId,
                    senderId = packet.senderId,
                    receiverId = packet.receiverId,
                    messageType = packet.messageType,
                    priority = packet.priority,
                    content = packet.payload,
                    createdAt = packet.createdAt,
                    expiresAt = now + packet.ttlSeconds * 1_000L,
                    latitude = packet.latitude,
                    longitude = packet.longitude,
                    hopCount = packet.hopCount + 1,
                    currentRelayId = peerId,
                    // Messages for this phone have arrived; broadcasts and relay copies stay queued to be passed on.
                    deliveryState = if (packet.receiverId == localNodeId) DeliveryState.DELIVERED else DeliveryState.QUEUED,
                    isIncoming = true
                )
                // A new personal message for this phone is stored together with its signed receipt.
                val receipt = if (message.needsReceiptFrom(localNodeId) && store.find(message.messageId) == null) {
                    createReceipt(message, now)
                } else null
                // Stored before any forwarding is attempted, so a restart or disconnect cannot lose it.
                val inserted = if (receipt != null) store.insertWithReceipt(message, receipt) else store.insert(message)
                // Never send it back to the phone it came from.
                if (packet.receiverId != localNodeId) store.recordHandoff(packet.messageId, peerId, now)
                if (inserted) ack(AckStatus.ACCEPTED, message) else ack(AckStatus.DUPLICATE)
            }
        }
    }


    // ---------------------------------------------------------
    // Signed receipts
    // ---------------------------------------------------------

    /** Creates receipts this phone owes but could not sign earlier (e.g. Keystore not ready). Bounded per call. */
    suspend fun recoverReceipts(now: Long): Int = mutex.withLock {
        if (receiptSigner() == null) return@withLock 0
        store.messagesMissingReceipt(localNodeId, now, RECOVERY_BATCH)
            .mapNotNull { createReceipt(it, now) }
            .count { store.insert(it) }
    }

    private fun EmergencyMessage.needsReceiptFrom(localNodeId: String): Boolean =
        isIncoming && receiverId == localNodeId && messageType in RECEIPTABLE_TYPES

    /** Null when this phone cannot sign (no usable identity key): never a receipt without a real signature. */
    private fun createReceipt(message: EmergencyMessage, now: Long): EmergencyMessage? {
        val subject = message.receiptSubject() ?: return null
        val signer = receiptSigner() ?: return null
        val encoded = runCatching { DeliveryReceiptCodec.create(subject, localNodeId, signer) }.getOrNull() ?: return null
        return EmergencyMessage(
            messageId = DeliveryReceiptCodec.receiptMessageId(encoded),
            senderId = localNodeId,
            receiverId = message.senderId,
            messageType = MessageType.RECEIPT,
            priority = MessagePriority.NORMAL,
            content = encoded,
            createdAt = now,
            // Never outlives the message it confirms.
            expiresAt = message.expiresAt,
            deliveryState = DeliveryState.QUEUED,
            receiptFor = message.messageId
        )
    }

    private suspend fun onReceipt(packet: DataPacket, peerId: String, now: Long): AckStatus {
        val receiverId = packet.receiverId ?: return AckStatus.REJECTED
        if (packet.priority != MessagePriority.NORMAL || packet.latitude != null || packet.longitude != null) return AckStatus.REJECTED
        if (packet.ttlSeconds * 1_000L > TEXT_TTL_MILLIS) return AckStatus.REJECTED
        val receipt = DeliveryReceiptCodec.decode(packet.payload) ?: return AckStatus.REJECTED
        if (!DeliveryReceiptCodec.hasConsistentId(packet.messageId, packet.payload)) return AckStatus.REJECTED
        return if (receiverId == localNodeId) applyReceipt(packet, receipt) else relayReceipt(packet, receipt, peerId, now)
    }

    /** This phone sent the original. Only a receipt verified against the recipient's VERIFIED key counts. */
    private suspend fun applyReceipt(packet: DataPacket, receipt: WireReceipt): AckStatus {
        val original = store.find(receipt.originalMessageId)
            ?.takeIf { it.isPersonalOwnDirect() && it.receiverId == packet.senderId }
            ?: return AckStatus.REJECTED
        if (original.deliveryState == DeliveryState.DELIVERED && original.deliveryProof == DeliveryProof.SIGNED_RECEIPT) {
            return AckStatus.DUPLICATE
        }
        val subject = original.receiptSubject() ?: return AckStatus.REJECTED
        val key = receiptTrust.receiptKey(subject.receiverId) ?: return AckStatus.REJECTED
        if (DeliveryReceiptCodec.verify(receipt, subject, subject.receiverId, key) != ReceiptCheck.VALID) return AckStatus.REJECTED
        // Also resolves FAILED or EXPIRED: the recipient really has this exact message.
        store.update(original.copy(deliveryState = DeliveryState.DELIVERED, deliveryProof = DeliveryProof.SIGNED_RECEIPT))
        return AckStatus.ACCEPTED
    }

    /** Carried for someone else, unchanged. Relays cannot verify it, so storage is capped. */
    private suspend fun relayReceipt(packet: DataPacket, receipt: WireReceipt, peerId: String, now: Long): AckStatus {
        if (packet.hopCount + 1 >= maxHops) return AckStatus.REJECTED
        if (store.find(packet.messageId) != null) {
            store.recordHandoff(packet.messageId, peerId, now)
            return AckStatus.DUPLICATE
        }
        if (store.receiptCount(receipt.originalMessageId) >= MAX_RECEIPTS_PER_MESSAGE) return AckStatus.REJECTED
        if (store.pendingRelayReceipts(now) >= MAX_RELAY_RECEIPTS) return AckStatus.REJECTED
        val copy = EmergencyMessage(
            messageId = packet.messageId,
            senderId = packet.senderId,
            receiverId = packet.receiverId,
            messageType = MessageType.RECEIPT,
            priority = MessagePriority.NORMAL,
            content = packet.payload,
            createdAt = packet.createdAt,
            expiresAt = now + packet.ttlSeconds * 1_000L,
            hopCount = packet.hopCount + 1,
            currentRelayId = peerId,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = true,
            receiptFor = receipt.originalMessageId
        )
        val inserted = store.insert(copy)
        store.recordHandoff(packet.messageId, peerId, now)
        return if (inserted) AckStatus.ACCEPTED else AckStatus.DUPLICATE
    }


    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private suspend fun isEligible(message: EmergencyMessage, peerId: String, now: Long): Boolean {
        if (message.expiresAt <= now) return false
        // The recipient already acknowledged it; only its signed receipt is still awaited.
        if (message.deliveryProof == DeliveryProof.LINK_ACK) return false
        val lastAttempt = message.lastAttemptAt
        if (message.attemptCount > 0 && lastAttempt != null && now - lastAttempt < retryPolicy.delayAfter(message.attemptCount)) {
            return false
        }
        // Shared loop guards: hop limit, never back to the sender, never twice to the same peer.
        if (message.receiverId == localNodeId) return false
        if (message.hopCount >= maxHops) return false
        if (message.senderId == peerId) return false
        if (store.hasHandoff(message.messageId, peerId)) return false

        return when {
            // Broadcast: every peer once (unchanged).
            message.receiverId == null -> true
            // The intended receiver can always get it, even after relays already have it.
            message.receiverId == peerId -> true
            // Relay hop: only if the next phone could still pass it on, and within the copy limit.
            else -> message.hopCount + 1 < maxHops && relayHandoffs(message) < maxRelayCopies
        }
    }

    // For direct messages FORWARDED is never inferred from handoff records (they also hold
    // refusals); it is only set by an accepted relay ACK and then kept.
    private suspend fun requeueState(message: EmergencyMessage): String = when {
        message.isOwnDirect() && message.attemptCount >= retryPolicy.maxAttempts -> DeliveryState.FAILED
        message.receiverId == null && store.handoffCount(message.messageId) > 0 -> DeliveryState.FORWARDED
        else -> DeliveryState.QUEUED
    }

    private fun EmergencyMessage.isDirectForwarded(): Boolean =
        receiverId != null && deliveryState == DeliveryState.FORWARDED

    /** Handoffs this phone made for a direct message; a relay copy's own source is not counted. */
    private suspend fun relayHandoffs(message: EmergencyMessage): Int =
        store.handoffCount(message.messageId) - (if (message.isIncoming) 1 else 0)

    /** A direct message written on this phone (the only kind that can become FAILED). */
    private fun EmergencyMessage.isOwnDirect(): Boolean = !isIncoming && receiverId != null

    /** A personal direct message written on this phone: the only kind confirmed by signed receipts. */
    private fun EmergencyMessage.isPersonalOwnDirect(): Boolean = isOwnDirect() && messageType in RECEIPTABLE_TYPES

    private fun EmergencyMessage.toPacket(now: Long) = DataPacket(
        messageId = messageId,
        senderId = senderId,
        receiverId = receiverId,
        messageType = messageType,
        priority = priority,
        createdAt = createdAt,
        ttlSeconds = ((expiresAt - now) / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        hopCount = hopCount,
        latitude = latitude,
        longitude = longitude,
        payload = content
    )

    companion object {

        const val DEFAULT_MAX_HOPS = 8

        // Relays a direct message may be handed to by each phone, to avoid flooding everyone.
        const val DEFAULT_MAX_RELAY_COPIES = 2

        const val TEXT_TTL_MILLIS = 24L * 60L * 60L * 1000L

        // Personal message types confirmed with signed receipts. Never SOS, broadcasts, KEY or RECEIPT.
        val RECEIPTABLE_TYPES = setOf(MessageType.TEXT, MessageType.STATUS, MessageType.LOCATION)

        // Distinct receipts a phone stores for one original message (a forged one cannot crowd out the real one).
        const val MAX_RECEIPTS_PER_MESSAGE = 2

        // Receipts a relay carries for others at once.
        const val MAX_RELAY_RECEIPTS = 200

        private const val RECOVERY_BATCH = 20

        private val RETRYABLE_STATES = setOf(DeliveryState.CREATED, DeliveryState.QUEUED, DeliveryState.FORWARDED)

        private val FINAL_STATES = setOf(DeliveryState.DELIVERED, DeliveryState.EXPIRED)
    }
}

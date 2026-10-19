package com.example.disasterconnect.messaging

import com.example.disasterconnect.chat.visibleTo
import com.example.disasterconnect.data.DeliveryProof
import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64


/**
 * Signed receipts over the real codec, framing and engines on every phone; only the radio links,
 * the Keystore (software P-256 keys) and the trust store (a map of VERIFIED keys) are simulated.
 */
class SignedReceiptTransportTest {

    private val policy = RetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, maxAttempts = 5, ackTimeoutMillis = 15_000L)

    private inner class Phone(val id: String) {

        val store = FakeMessageStore()

        val identity = SoftwareReceiptSigner()

        var identityAvailable = true

        // This phone's VERIFIED contacts, and contacts currently presenting a conflicting key.
        val verified = mutableMapOf<String, ByteArray>()

        val conflicts = mutableSetOf<String>()

        private val trust = object : ReceiptTrust {
            override suspend fun requiresSignedReceipt(nodeId: String) = nodeId in verified
            override suspend fun receiptKey(nodeId: String) = verified[nodeId]?.takeIf { nodeId !in conflicts }
        }

        var engine = newEngine()
            private set

        private fun newEngine() =
            MessagingEngine(store, id, policy, receiptTrust = trust, receiptSigner = { identity.takeIf { identityAvailable } })

        fun restart() {
            engine = newEngine()
        }

        fun verifies(other: Phone) {
            verified[other.id] = other.identity.publicKey
        }

        fun receipts() = store.all().filter { it.messageType == MessageType.RECEIPT }
    }

    private val a = Phone("DC-AAAAAA")
    private val b = Phone("DC-BBBBBB")
    private val c = Phone("DC-CCCCCC")

    private fun carry(bytes: ByteArray): ByteArray {
        val reassembler = PacketReassembler()
        return PacketFraming.split(bytes, 0, PacketFraming.DEFAULT_MTU).mapNotNull { reassembler.accept(it, 0L) }.single()
    }

    /** One transfer the way MessagingCoordinator drives it; [alter] tampers with the packet in flight. */
    private suspend fun transfer(from: Phone, to: Phone, now: Long, alter: (DataPacket) -> DataPacket = { it }): AckPacket? {
        val outbound = from.engine.nextOutgoing(to.id, now) ?: return null
        val packet = alter(MessagePacketCodec.decode(carry(outbound.bytes)) as DataPacket)
        from.engine.onTransmitted(outbound.messageId, to.id, success = true, now)
        val result = to.engine.onDataReceived(packet, from.id, now)
        val ack = MessagePacketCodec.decode(carry(MessagePacketCodec.encode(result.ack))) as AckPacket
        from.engine.onAck(ack, to.id, now)
        return ack
    }

    private fun receiptPacket(encoded: String, from: String, to: String, hops: Int = 0, ttl: Int = 3_600) = DataPacket(
        messageId = DeliveryReceiptCodec.receiptMessageId(encoded),
        senderId = from,
        receiverId = to,
        messageType = MessageType.RECEIPT,
        priority = MessagePriority.NORMAL,
        createdAt = 0L,
        ttlSeconds = ttl,
        hopCount = hops,
        payload = encoded
    )

    private suspend fun deliver(packet: DataPacket, to: Phone, fromId: String, now: Long = 0L): AckStatus =
        to.engine.onDataReceived(MessagePacketCodec.decode(carry(MessagePacketCodec.encode(packet))) as DataPacket, fromId, now).ack.status

    /** A sends to C directly and C stores it; returns C's receipt (still queued at C). */
    private suspend fun sendDirect(text: String = "Are you safe?", now: Long = 0L): Pair<EmergencyMessage, EmergencyMessage> {
        val original = a.engine.createText(c.id, text, now)
        transfer(a, c, now)
        return original to c.receipts().single { it.receiptFor == original.messageId }
    }

    private fun EmergencyMessage.at(phone: Phone) = phone.store.get(messageId)


    // ---------------------------------------------------------
    // Verified contacts
    // ---------------------------------------------------------

    @Test
    fun directMessageToVerifiedContactIsNotDeliveredByAckOnlyByItsSignedReceipt() = runBlocking {

        a.verifies(c)
        val (original, receipt) = sendDirect()

        // C's own ACK arrived: hop-level proof only.
        assertEquals(DeliveryState.FORWARDED, original.at(a).deliveryState)
        assertEquals(DeliveryProof.LINK_ACK, original.at(a).deliveryProof)
        // C already has it, so it is never sent again.
        assertNull(a.engine.nextOutgoing(c.id, now = 60_000L))

        assertEquals(AckStatus.ACCEPTED, transfer(c, a, now = 1L)?.status)

        assertEquals(DeliveryState.DELIVERED, original.at(a).deliveryState)
        assertEquals(DeliveryProof.SIGNED_RECEIPT, original.at(a).deliveryProof)
        assertEquals(DeliveryState.DELIVERED, receipt.at(c).deliveryState)
        // Receipts are never stored at the original sender, never shown anywhere.
        assertTrue(a.receipts().isEmpty())
        assertTrue(c.store.all().visibleTo(c.id).none { it.messageType == MessageType.RECEIPT })
    }


    @Test
    fun multiHopReceiptIsCreatedAtCRelayedUnchangedByBAndVerifiedByA() = runBlocking {

        a.verifies(c)
        val original = a.engine.createText(c.id, "Bridge is down, use the north road", now = 0L)

        transfer(a, b, now = 0L)
        transfer(b, c, now = 1_000L)
        val atC = c.receipts().single()
        assertEquals(DeliveryState.FORWARDED, original.at(a).deliveryState)
        assertEquals(DeliveryProof.NONE, original.at(a).deliveryProof)

        transfer(c, b, now = 2_000L)
        val atB = b.receipts().single()
        assertEquals(atC.content, atB.content)
        assertEquals(atC.messageId, atB.messageId)
        assertEquals(c.id, atB.senderId)
        assertEquals(a.id, atB.receiverId)
        assertTrue(b.store.all().visibleTo(b.id).isEmpty())

        assertEquals(AckStatus.ACCEPTED, transfer(b, a, now = 3_000L)?.status)
        assertEquals(DeliveryState.DELIVERED, original.at(a).deliveryState)
        assertEquals(DeliveryProof.SIGNED_RECEIPT, original.at(a).deliveryProof)
    }


    @Test
    fun unverifiedContactKeepsAckDeliveryLabelledUnverifiedAndItsReceiptChangesNothing() = runBlocking {

        val (original, receipt) = sendDirect()

        assertEquals(DeliveryState.DELIVERED, original.at(a).deliveryState)
        assertEquals(DeliveryProof.LINK_ACK, original.at(a).deliveryProof)

        // A has no VERIFIED key for C, so the receipt cannot authorise anything.
        assertEquals(AckStatus.REJECTED, transfer(c, a, now = 1L)?.status)
        assertEquals(DeliveryProof.LINK_ACK, original.at(a).deliveryProof)
        // C's hidden receipt row ends; it is not retried.
        assertEquals(DeliveryState.FAILED, receipt.at(c).deliveryState)
    }


    @Test
    fun receiptsSignedByAnUnverifiedRelayOrConflictingKeyAreRejected() = runBlocking {

        // A verified B (a relay), not C.
        a.verifies(b)
        val (original, receipt) = sendDirect()
        assertEquals(AckStatus.REJECTED, deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id))

        // A verified C, but C currently presents a conflicting key.
        a.verifies(c)
        a.conflicts += c.id
        assertEquals(AckStatus.REJECTED, deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id))

        // B signs "C received it" with B's own key, with or without copying C's key prefix.
        a.conflicts.clear()
        val subject = original.at(a).receiptSubject()!!
        val forgedForB = DeliveryReceiptCodec.encode(
            WireReceipt(
                original.messageId,
                EcdsaP256.fingerprint(b.identity.publicKey).copyOf(DeliveryReceiptCodec.KEY_PREFIX_BYTES),
                EcdsaP256.normalizeLowS(b.identity.sign(DeliveryReceiptCodec.signedBytes(subject, EcdsaP256.fingerprint(b.identity.publicKey))))!!
            )
        )
        val forgedWithCPrefix = DeliveryReceiptCodec.encode(
            WireReceipt(
                original.messageId,
                EcdsaP256.fingerprint(c.identity.publicKey).copyOf(DeliveryReceiptCodec.KEY_PREFIX_BYTES),
                EcdsaP256.normalizeLowS(b.identity.sign(DeliveryReceiptCodec.signedBytes(subject, EcdsaP256.fingerprint(c.identity.publicKey))))!!
            )
        )
        assertEquals(AckStatus.REJECTED, deliver(receiptPacket(forgedForB, c.id, a.id), a, b.id))
        assertEquals(AckStatus.REJECTED, deliver(receiptPacket(forgedWithCPrefix, c.id, a.id), a, b.id))

        assertTrue(original.at(a).deliveryProof != DeliveryProof.SIGNED_RECEIPT)
    }


    // ---------------------------------------------------------
    // Tampering, forgery and malformed input
    // ---------------------------------------------------------

    @Test
    fun relayChangingTheMessageBodyMeansTheReceiptNeverVerifies() = runBlocking {

        a.verifies(c)
        val original = a.engine.createText(c.id, "Meet at the clinic", now = 0L)

        transfer(a, b, now = 0L)
        transfer(b, c, now = 1_000L) { it.copy(payload = "Meet at the river") }
        transfer(c, b, now = 2_000L)

        assertEquals(AckStatus.REJECTED, transfer(b, a, now = 3_000L)?.status)
        assertEquals(DeliveryState.FORWARDED, original.at(a).deliveryState)
        assertEquals(DeliveryProof.NONE, original.at(a).deliveryProof)
    }


    @Test
    fun modifiedReceiptFieldsAndEnvelopesAreRejected() = runBlocking {

        a.verifies(c)
        val (original, receipt) = sendDirect()
        val wire = DeliveryReceiptCodec.decode(receipt.content)!!
        val other = a.engine.createText(c.id, "second message", now = 5L)

        fun reEncoded(id: String = wire.originalMessageId, prefix: ByteArray = wire.keyPrefix) =
            DeliveryReceiptCodec.encode(WireReceipt(id, prefix, wire.signature))

        val changedPrefix = wire.keyPrefix.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val cases = listOf(
            // Message ID rewritten, with the receipt ID recomputed to match.
            receiptPacket(reEncoded(id = other.messageId), c.id, a.id),
            // Fingerprint prefix changed.
            receiptPacket(reEncoded(prefix = changedPrefix), c.id, a.id),
            // Receipt ID not matching its content.
            receiptPacket(receipt.content, c.id, a.id).copy(messageId = "r:" + "0".repeat(32)),
            // Envelope sender is not the original recipient.
            receiptPacket(receipt.content, b.id, a.id),
            // Priority, location or a lifetime beyond any message.
            receiptPacket(receipt.content, c.id, a.id).copy(priority = MessagePriority.CRITICAL),
            receiptPacket(receipt.content, c.id, a.id).copy(latitude = 1.0, longitude = 1.0),
            receiptPacket(receipt.content, c.id, a.id, ttl = 2 * 24 * 3_600),
            // Broadcast receipt.
            receiptPacket(receipt.content, c.id, a.id).copy(receiverId = null)
        )

        for (packet in cases) assertEquals(packet.toString(), AckStatus.REJECTED, deliver(packet, a, c.id))

        assertEquals(DeliveryProof.LINK_ACK, original.at(a).deliveryProof)
        assertEquals(DeliveryState.QUEUED, other.at(a).deliveryState)
        assertTrue(a.receipts().isEmpty())
    }


    @Test
    fun forgedHighSMalformedOversizedAndTruncatedReceiptsChangeNothing() = runBlocking {

        a.verifies(c)
        val (original, receipt) = sendDirect()
        val wire = DeliveryReceiptCodec.decode(receipt.content)!!
        val (r, s) = EcdsaP256.parseSignature(wire.signature)!!
        val highS = DeliveryReceiptCodec.encode(WireReceipt(wire.originalMessageId, wire.keyPrefix, EcdsaP256.encodeSignature(r, EcdsaP256.N - s)))
        val badSignature = DeliveryReceiptCodec.encode(
            WireReceipt(wire.originalMessageId, wire.keyPrefix, EcdsaP256.encodeSignature(r, s.subtract(java.math.BigInteger.ONE)))
        )
        val bytes = Base64.getDecoder().decode(receipt.content)

        // Well-formed but wrongly signed: relays cannot check signatures and carry it; A rejects it.
        assertEquals(AckStatus.REJECTED, deliver(receiptPacket(badSignature, c.id, a.id), a, c.id))
        assertEquals(AckStatus.ACCEPTED, deliver(receiptPacket(badSignature, c.id, "DC-DDDDDD"), b, c.id))
        b.store.all().forEach { assertEquals(badSignature, it.content) }

        for (payload in listOf(
            highS,
            "not a receipt",
            "A".repeat(DeliveryReceiptCodec.MAX_ENCODED_CHARS + 4),
            Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 3))
        )) {
            val packet = receiptPacket(payload, c.id, a.id)
            assertEquals(payload.take(20), AckStatus.REJECTED, deliver(packet, a, c.id))
            // Relays do not store them either.
            assertEquals(AckStatus.REJECTED, deliver(packet.copy(receiverId = "DC-DDDDDD"), b, c.id))
        }

        // A packet cut short on the wire never decodes at all.
        val encodedPacket = MessagePacketCodec.encode(receiptPacket(receipt.content, c.id, a.id))
        assertNull(MessagePacketCodec.decode(encodedPacket.copyOf(encodedPacket.size - 1)))

        assertEquals(DeliveryProof.LINK_ACK, original.at(a).deliveryProof)
        assertEquals(1, b.store.all().size)
    }


    // ---------------------------------------------------------
    // Duplicates and replay
    // ---------------------------------------------------------

    @Test
    fun duplicateMessagesGetOneReceiptAndDuplicateReceiptsAreIdempotent() = runBlocking {

        a.verifies(c)
        val original = a.engine.createText(c.id, "Water at the school", now = 0L)

        // The same message reaches C directly and through B.
        transfer(a, b, now = 0L)
        transfer(b, c, now = 1_000L)
        val viaA = MessagePacketCodec.decode(carry(MessagePacketCodec.encode(
            DataPacket(original.messageId, a.id, c.id, MessageType.TEXT, MessagePriority.NORMAL, 0L, 3_600, 0, payload = original.content)
        ))) as DataPacket
        assertEquals(AckStatus.DUPLICATE, c.engine.onDataReceived(viaA, a.id, now = 2_000L).ack.status)
        assertEquals(1, c.receipts().size)

        val receipt = c.receipts().single()
        assertEquals(AckStatus.ACCEPTED, deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id, now = 3_000L))
        val delivered = original.at(a)

        // The same receipt again, and a second, independently signed valid receipt.
        val second = DeliveryReceiptCodec.create(c.store.get(original.messageId).receiptSubject()!!, c.id, c.identity)
        assertEquals(AckStatus.DUPLICATE, deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id, now = 4_000L))
        assertEquals(AckStatus.DUPLICATE, deliver(receiptPacket(second, c.id, a.id), a, c.id, now = 5_000L))
        assertEquals(delivered, original.at(a))
    }


    @Test
    fun replayedReceiptNeverConfirmsAnotherMessage() = runBlocking {

        a.verifies(c)
        val (first, receipt) = sendDirect("first")
        deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id)
        val second = a.engine.createText(c.id, "second", now = 10L)

        assertEquals(AckStatus.DUPLICATE, deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id, now = 11L))

        assertEquals(DeliveryProof.SIGNED_RECEIPT, first.at(a).deliveryProof)
        assertEquals(DeliveryState.QUEUED, second.at(a).deliveryState)
        assertEquals(DeliveryProof.NONE, second.at(a).deliveryProof)
    }


    // ---------------------------------------------------------
    // Loss, restart, crash and an unavailable key
    // ---------------------------------------------------------

    @Test
    fun lostReceiptTransferIsRetriedAfterReconnectAndRestart() = runBlocking {

        a.verifies(c)
        val (original, _) = sendDirect()

        // The link drops halfway through the receipt's frames.
        val outbound = c.engine.nextOutgoing(a.id, now = 1L)!!
        c.engine.onTransmitted(outbound.messageId, a.id, success = false, now = 1L)

        c.restart()
        a.restart()

        assertEquals(AckStatus.ACCEPTED, transfer(c, a, now = 10_000L)?.status)
        assertEquals(DeliveryProof.SIGNED_RECEIPT, original.at(a).deliveryProof)
    }


    @Test
    fun crashBeforeMessageAndReceiptAreCommittedLeavesNothingAndTheResendStoresBoth() = runBlocking {

        val original = a.engine.createText(c.id, "Are you safe?", now = 0L)
        c.store.failReceiptInsert = true

        assertTrue(runCatching { transfer(a, c, now = 0L) }.isFailure)
        assertTrue(c.store.all().isEmpty())

        // C restarts; A's ACK timeout puts the message back in the queue.
        c.store.failReceiptInsert = false
        c.restart()
        a.engine.requeueTimedOut(now = 20_000L)
        transfer(a, c, now = 20_000L)

        assertNotNull(c.store.find(original.messageId))
        assertEquals(1, c.receipts().count { it.receiptFor == original.messageId })
    }


    @Test
    fun withoutAUsableIdentityKeyNoReceiptIsMadeUntilTheKeyIsBackThenExactlyOne() = runBlocking {

        c.identityAvailable = false
        val original = a.engine.createText(c.id, "Are you safe?", now = 0L)

        assertEquals(AckStatus.ACCEPTED, transfer(a, c, now = 0L)?.status)
        assertTrue(c.receipts().isEmpty())
        assertEquals(0, c.engine.recoverReceipts(now = 1L))

        c.identityAvailable = true
        assertEquals(1, c.engine.recoverReceipts(now = 2L))
        assertEquals(0, c.engine.recoverReceipts(now = 3L))
        assertEquals(original.messageId, c.receipts().single().receiptFor)
    }


    // ---------------------------------------------------------
    // Older apps, limits and loops
    // ---------------------------------------------------------

    @Test
    fun olderPhoneThatDropsReceiptsGetsOneTryNotEndlessRetries() = runBlocking {

        val (_, receipt) = sendDirect()

        // A is an older build: the frames are written but no ACK ever comes back.
        val outbound = c.engine.nextOutgoing(a.id, now = 1L)!!
        c.engine.onTransmitted(outbound.messageId, a.id, success = true, now = 1L)
        c.engine.requeueTimedOut(now = 20_000L)

        for (now in listOf(30_000L, 120_000L, 3_600_000L)) assertNull(c.engine.nextOutgoing(a.id, now))
        assertEquals(DeliveryState.QUEUED, receipt.at(c).deliveryState)
    }


    @Test
    fun receiptLifetimeHopLimitDestinationAndNoReceiptForReceipts() = runBlocking {

        val (original, receipt) = sendDirect()

        // Never outlives the message it confirms.
        assertEquals(c.store.get(original.messageId).expiresAt, receipt.expiresAt)

        // At the hop limit a relay refuses it.
        assertEquals(
            AckStatus.REJECTED,
            deliver(receiptPacket(receipt.content, c.id, a.id, hops = MessagingEngine.DEFAULT_MAX_HOPS - 1), b, c.id)
        )

        // A receipt for someone else is only carried: never applied, never answered with a receipt,
        // never sent back to the phone it came from or to its signer.
        assertEquals(AckStatus.ACCEPTED, deliver(receiptPacket(receipt.content, c.id, a.id, hops = 1), b, "DC-EEEEEE"))
        assertEquals(1, b.receipts().size)
        assertNull(b.engine.nextOutgoing("DC-EEEEEE", now = 1L))
        assertNull(b.engine.nextOutgoing(c.id, now = 1L))
        assertNotNull(b.engine.nextOutgoing(a.id, now = 1L))
        assertTrue(b.store.all().none { it.senderId == b.id })
    }


    @Test
    fun relayStorageForReceiptsIsCapped() = runBlocking {

        // At most MAX_RECEIPTS_PER_MESSAGE distinct receipts per original message.
        val (original, _) = sendDirect()
        val subject = c.store.get(original.messageId).receiptSubject()!!
        val variants = List(MessagingEngine.MAX_RECEIPTS_PER_MESSAGE + 1) { DeliveryReceiptCodec.create(subject, c.id, c.identity) }
        val statuses = variants.map { deliver(receiptPacket(it, c.id, a.id, hops = 1), b, c.id) }
        assertEquals(List(MessagingEngine.MAX_RECEIPTS_PER_MESSAGE) { AckStatus.ACCEPTED } + AckStatus.REJECTED, statuses)

        // At most MAX_RELAY_RECEIPTS carried at once.
        val relay = Phone("DC-DDDDDD")
        repeat(MessagingEngine.MAX_RELAY_RECEIPTS) {
            val encoded = DeliveryReceiptCodec.create(subject.copy(messageId = "m-$it"), c.id, c.identity)
            assertEquals(AckStatus.ACCEPTED, deliver(receiptPacket(encoded, c.id, a.id, hops = 1), relay, c.id))
        }
        val oneMore = DeliveryReceiptCodec.create(subject.copy(messageId = "m-extra"), c.id, c.identity)
        assertEquals(AckStatus.REJECTED, deliver(receiptPacket(oneMore, c.id, a.id, hops = 1), relay, c.id))
    }


    @Test
    fun lateValidReceiptResolvesAFailedOrExpiredMessageOnly() = runBlocking {

        a.verifies(c)
        val (original, receipt) = sendDirect()
        val untouched = a.engine.createText(c.id, "other", now = 1L)
        a.store.update(original.at(a).copy(deliveryState = DeliveryState.EXPIRED))
        a.store.update(untouched.at(a).copy(deliveryState = DeliveryState.FAILED))

        assertEquals(AckStatus.ACCEPTED, deliver(receiptPacket(receipt.content, c.id, a.id), a, c.id))

        assertEquals(DeliveryState.DELIVERED, original.at(a).deliveryState)
        assertEquals(DeliveryState.FAILED, untouched.at(a).deliveryState)
    }
}

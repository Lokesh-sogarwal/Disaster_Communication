package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.DeliveryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Multi-hop store-and-forward for direct messages: A → B (relay) → C.
 * Real codec, framing and engines on every phone; only the radio links are simulated.
 */
class MultiHopRelayTest {

    private val policy = RetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, maxAttempts = 5, ackTimeoutMillis = 15_000L)

    private inner class Phone(val id: String) {

        val store = FakeMessageStore()

        var engine = MessagingEngine(store, id, policy)
            private set

        fun restart() {
            engine = MessagingEngine(store, id, policy)
        }
    }

    private val a = Phone("DC-AAAAAA")
    private val b = Phone("DC-BBBBBB")
    private val c = Phone("DC-CCCCCC")
    private val d = Phone("DC-DDDDDD")
    private val e = Phone("DC-EEEEEE")

    /**
     * One transfer over a fresh link, the way MessagingCoordinator drives it:
     * frames to [to], [to] stores and ACKs, the ACK frames go back to [from].
     * Returns null when [from] has nothing eligible for [to].
     */
    private suspend fun transfer(from: Phone, to: Phone, now: Long, mtu: Int = 185): AckPacket? {

        val outbound = from.engine.nextOutgoing(to.id, now) ?: return null
        val packet = carry(outbound.bytes, mtu)
        from.engine.onTransmitted(outbound.messageId, to.id, success = true, now)

        val result = to.engine.onDataReceived(MessagePacketCodec.decode(packet) as DataPacket, from.id, now)
        val ack = MessagePacketCodec.decode(carry(MessagePacketCodec.encode(result.ack), mtu)) as AckPacket

        from.engine.onAck(ack, to.id, now)
        return ack
    }

    private fun carry(bytes: ByteArray, mtu: Int): ByteArray {
        val reassembler = PacketReassembler()
        return PacketFraming.split(bytes, 0, mtu).mapNotNull { reassembler.accept(it, 0L) }.single()
    }


    // ---------------------------------------------------------
    // Forwarding and acknowledgement semantics
    // ---------------------------------------------------------

    @Test
    fun messageTravelsAThroughBToCWithOriginalIdAndSender() = runBlocking {

        val original = a.engine.createText(c.id, "A to C via B", now = 0L)

        // A and C cannot reach each other; A meets B.
        val relayAck = transfer(a, b, now = 0L)!!

        assertEquals(b.id, relayAck.fromNodeId)
        // A relay ACK is hop-level only: never DELIVERED.
        assertEquals(DeliveryState.FORWARDED, a.store.get(original.messageId).deliveryState)

        // B persisted a hidden relay copy before forwarding.
        val copy = b.store.get(original.messageId)
        assertTrue(copy.isIncoming)
        assertEquals(a.id, copy.senderId)
        assertEquals(c.id, copy.receiverId)
        assertEquals(1, copy.hopCount)

        // B later meets C.
        val finalAck = transfer(b, c, now = 1_000L)!!

        val received = c.store.all().single()
        assertEquals(original.messageId, received.messageId)
        assertEquals(a.id, received.senderId)
        assertEquals("A to C via B", received.content)
        assertEquals(DeliveryState.DELIVERED, received.deliveryState)
        assertEquals(AckStatus.ACCEPTED, finalAck.status)

        // B's copy is done; A still only knows it was passed on (no end-to-end receipt yet).
        assertEquals(DeliveryState.DELIVERED, b.store.get(original.messageId).deliveryState)
        assertNull(b.engine.nextOutgoing(c.id, now = 2_000L))
        assertEquals(DeliveryState.FORWARDED, a.store.get(original.messageId).deliveryState)
    }


    @Test
    fun senderBecomesDeliveredOnlyWhenTheRecipientItselfAcks() = runBlocking {

        val original = a.engine.createText(c.id, "hello", now = 0L)
        transfer(a, b, now = 0L)
        transfer(b, c, now = 1_000L)

        // A forged or relayed ACK claiming to be C, arriving over B's link, is ignored.
        a.engine.onAck(AckPacket(original.messageId, c.id, AckStatus.ACCEPTED), peerId = b.id, now = 2_000L)
        assertEquals(DeliveryState.FORWARDED, a.store.get(original.messageId).deliveryState)

        // Later A meets C directly: C already has it and answers DUPLICATE, which proves delivery.
        val ack = transfer(a, c, now = 3_000L)!!

        assertEquals(AckStatus.DUPLICATE, ack.status)
        assertEquals(DeliveryState.DELIVERED, a.store.get(original.messageId).deliveryState)
        assertEquals(1, c.store.all().size)
    }


    @Test
    fun forwardedStatusIsKeptThroughLaterTimeoutsRefusalsAndFailedRetries() = runBlocking {

        val original = a.engine.createText(c.id, "stay honest", now = 0L)
        transfer(a, b, now = 0L)
        assertEquals(DeliveryState.FORWARDED, a.store.get(original.messageId).deliveryState)

        // Second relay D receives the frames but its ACK is lost, then the ACK timeout passes.
        val toD = a.engine.nextOutgoing(d.id, now = 1_000L)!!
        a.engine.onTransmitted(toD.messageId, d.id, success = true, now = 1_000L)
        a.engine.requeueTimedOut(now = 60_000L)
        assertEquals(DeliveryState.FORWARDED, a.store.get(original.messageId).deliveryState)

        // Direct attempts to C keep failing: a message a relay already holds is not FAILED.
        var now = 70_000L
        repeat(8) {
            val attempt = a.engine.nextOutgoing(c.id, now) ?: return@repeat
            a.engine.onTransmitted(attempt.messageId, c.id, success = false, now)
            now += 60_000L
        }
        assertEquals(DeliveryState.FORWARDED, a.store.get(original.messageId).deliveryState)
    }


    // ---------------------------------------------------------
    // Persistence and recovery
    // ---------------------------------------------------------

    @Test
    fun relayKeepsTheMessageWhileRecipientIsAwayAndAcrossARestart() = runBlocking {

        val original = a.engine.createText(c.id, "queued at B", now = 0L)
        transfer(a, b, now = 0L)

        // C is unavailable; B restarts while holding the relay copy.
        b.restart()
        assertEquals(DeliveryState.QUEUED, b.store.get(original.messageId).deliveryState)

        // A failed transfer to C (link dropped) keeps the copy queued, not deleted.
        val attempt = b.engine.nextOutgoing(c.id, now = 10_000L)!!
        b.engine.onTransmitted(attempt.messageId, c.id, success = false, now = 10_000L)
        assertEquals(DeliveryState.QUEUED, b.store.get(original.messageId).deliveryState)

        // C reconnects after the existing backoff.
        transfer(b, c, now = 11_000L)

        assertEquals(1, c.store.all().size)
        assertEquals(DeliveryState.DELIVERED, b.store.get(original.messageId).deliveryState)
    }


    @Test
    fun relayCopyIsNeverFailedByRetriesOnlyByExpiry() = runBlocking {

        val original = a.engine.createText(c.id, "keep trying", now = 0L)
        transfer(a, b, now = 0L)

        var now = 10_000L
        repeat(8) {
            val attempt = b.engine.nextOutgoing(c.id, now) ?: return@repeat
            b.engine.onTransmitted(attempt.messageId, c.id, success = false, now)
            now += 60_000L
        }

        assertEquals(DeliveryState.QUEUED, b.store.get(original.messageId).deliveryState)
    }


    // ---------------------------------------------------------
    // Loop and flood prevention
    // ---------------------------------------------------------

    @Test
    fun relayNeverSendsBackToTheSenderOrThePhoneItCameFrom() = runBlocking {

        a.engine.createText(c.id, "no loops", now = 0L)
        transfer(a, b, now = 0L)
        transfer(b, d, now = 1_000L)

        assertNull(b.engine.nextOutgoing(a.id, now = 2_000L))
        assertNull(d.engine.nextOutgoing(b.id, now = 2_000L))
        assertNull(d.engine.nextOutgoing(a.id, now = 2_000L))
    }


    @Test
    fun eachPhoneHandsADirectMessageToAtMostTwoRelaysButAlwaysToTheRecipient() = runBlocking {

        a.engine.createText(c.id, "limited copies", now = 0L)

        assertNotNull(transfer(a, b, now = 0L))
        assertNotNull(transfer(a, d, now = 1L))
        assertNull(a.engine.nextOutgoing(e.id, now = 2L))

        assertNotNull(transfer(a, c, now = 3L))
    }


    @Test
    fun duplicateCopiesFromSeveralRelaysAreStoredOnceEverywhere() = runBlocking {

        val original = a.engine.createText(c.id, "one copy only", now = 0L)
        transfer(a, b, now = 0L)
        transfer(a, d, now = 0L)

        // D also passes it to B, which already holds it.
        val relayDup = transfer(d, b, now = 1_000L)!!
        assertEquals(AckStatus.DUPLICATE, relayDup.status)
        assertEquals(1, b.store.all().size)

        // Both relays reach C.
        assertEquals(AckStatus.ACCEPTED, transfer(b, c, now = 2_000L)!!.status)
        assertEquals(AckStatus.DUPLICATE, transfer(d, c, now = 3_000L)!!.status)

        assertEquals(listOf(original.messageId), c.store.all().map { it.messageId })
    }


    // ---------------------------------------------------------
    // Hop limit and TTL
    // ---------------------------------------------------------

    @Test
    fun relayAtTheHopLimitRefusesAndTheSenderIsNotFailed() = runBlocking {

        val atLimit =
            DataPacket(
                messageId = "deep",
                senderId = a.id,
                receiverId = c.id,
                messageType = "TEXT",
                priority = "NORMAL",
                createdAt = 0L,
                ttlSeconds = 3_600,
                hopCount = MessagingEngine.DEFAULT_MAX_HOPS - 1,
                payload = "far"
            )

        // B could never pass it on, so it refuses and stores nothing.
        val refused = b.engine.onDataReceived(atLimit, peerId = d.id, now = 0L)
        assertEquals(AckStatus.REJECTED, refused.ack.status)
        assertTrue(b.store.all().isEmpty())

        // The real recipient still accepts the same packet.
        assertEquals(AckStatus.ACCEPTED, c.engine.onDataReceived(atLimit, peerId = d.id, now = 0L).ack.status)

        // A sender whose relay refuses keeps the message queued instead of FAILED.
        val original = a.engine.createText(c.id, "try elsewhere", now = 0L)
        val outbound = a.engine.nextOutgoing(b.id, now = 0L)!!
        a.engine.onAck(AckPacket(outbound.messageId, b.id, AckStatus.REJECTED), b.id, now = 0L)

        assertEquals(DeliveryState.QUEUED, a.store.get(original.messageId).deliveryState)
        assertNull(a.engine.nextOutgoing(b.id, now = 1L))
    }


    @Test
    fun expiredRelayCopiesAreNotForwarded() = runBlocking {

        val original = a.engine.createText(c.id, "too late", now = 0L)
        transfer(a, b, now = 0L)

        val afterTtl = MessagingEngine.TEXT_TTL_MILLIS + 1_000L

        assertNull(b.engine.nextOutgoing(c.id, now = afterTtl))
        assertEquals(DeliveryState.EXPIRED, b.store.get(original.messageId).deliveryState)
        assertFalse(c.store.all().any { it.messageId == original.messageId })
    }


    // ---------------------------------------------------------
    // Broadcasts are unchanged
    // ---------------------------------------------------------

    @Test
    fun sosBroadcastStillReachesEveryPeerOnce() = runBlocking {

        a.store.insert(
            com.example.disasterconnect.data.EmergencyMessage(
                messageId = "sos",
                senderId = a.id,
                messageType = "SOS",
                priority = "CRITICAL",
                content = "SOS",
                createdAt = 0L,
                expiresAt = 1_000_000L,
                deliveryState = DeliveryState.QUEUED
            )
        )

        assertNotNull(transfer(a, b, now = 0L))
        assertNotNull(transfer(a, d, now = 0L))
        assertNotNull(transfer(a, e, now = 0L))
        assertNull(a.engine.nextOutgoing(b.id, now = 1L))
        assertEquals(DeliveryState.FORWARDED, a.store.get("sos").deliveryState)
    }
}

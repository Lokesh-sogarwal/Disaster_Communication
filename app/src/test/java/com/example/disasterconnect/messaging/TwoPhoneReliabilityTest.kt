package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.DeliveryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * End-to-end reliability checks for two phones, using the real codec, framing,
 * reassembly and engine. Only the radio is simulated: a [Link] is one GATT
 * connection with its own transfer ids and reassembler, as in the BLE client/server.
 */
class TwoPhoneReliabilityTest {

    private val policy = RetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, maxAttempts = 5, ackTimeoutMillis = 15_000L)

    private class Phone(val id: String, private val policy: RetryPolicy) {

        val store = FakeMessageStore()

        var engine = MessagingEngine(store, id, policy)
            private set

        /** App restart: a new engine over the same persisted rows. */
        fun restart() {
            engine = MessagingEngine(store, id, policy)
        }
    }

    /** One direction of one connection. A reconnect is a new Link. */
    private class Link(private val mtu: Int) {

        private var transferId = 0

        private val reassembler = PacketReassembler()

        /** Returns every packet the receiver completes from the (possibly altered) frames. */
        fun carry(
            packet: ByteArray,
            alter: (List<ByteArray>) -> List<ByteArray> = { it }
        ): List<ByteArray> =
            alter(PacketFraming.split(packet, transferId++ and 0xFFFF, mtu))
                .mapNotNull { reassembler.accept(it, now = 0L) }
    }

    private val phoneA = Phone("DC-AAAAAA", policy)
    private val phoneB = Phone("DC-BBBBBB", policy)

    /** Long multi-byte text: needs many frames at MTU 23 and several at MTU 185. */
    private val longText = ("Water rising near the school gate. पानी बढ़ रहा है. 🚨 ").repeat(12).take(400)


    // ---------------------------------------------------------
    // Offline recovery
    // ---------------------------------------------------------

    @Test
    fun messageWrittenOfflineSurvivesRestartAndIsDeliveredOnceTheLinkReturns() = runBlocking {

        val message = phoneA.engine.createText(phoneB.id, "Are you safe?", now = 0L)

        // B is unreachable: the coordinator never asks for outgoing data, so the row just waits.
        phoneA.restart()
        assertEquals(DeliveryState.QUEUED, phoneA.store.get(message.messageId).deliveryState)

        // Bluetooth is back. MTU negotiation failed, so the 23-byte fallback is used.
        val ack = sendOverNewLink(now = 60_000L, mtu = 23)

        assertEquals(AckStatus.ACCEPTED, ack?.status)
        assertEquals(DeliveryState.DELIVERED, phoneA.store.get(message.messageId).deliveryState)
        assertEquals(listOf("Are you safe?"), phoneB.store.all().map { it.content })
        assertEquals(phoneA.id, phoneB.store.all().single().senderId)
    }


    @Test
    fun linkDroppingMidMessageStoresNothingAndTheRetryOnANewLinkDeliversExactlyOnce() = runBlocking {

        val message = phoneA.engine.createText(phoneB.id, longText, now = 0L)

        // First connection: only half the frames make it before B turns Bluetooth off.
        val outbound = phoneA.engine.nextOutgoing(phoneB.id, now = 0L)!!
        val completed = Link(mtu = 23).carry(outbound.bytes) { frames -> frames.take(frames.size / 2) }
        phoneA.engine.onTransmitted(outbound.messageId, phoneB.id, success = false, now = 0L)

        assertTrue(completed.isEmpty())
        assertTrue(phoneB.store.all().isEmpty())
        assertEquals(DeliveryState.QUEUED, phoneA.store.get(message.messageId).deliveryState)

        // Reconnect after the existing backoff (1 s after one failed attempt in this policy).
        assertNull(phoneA.engine.nextOutgoing(phoneB.id, now = 999L))
        val ack = sendOverNewLink(now = 1_000L, mtu = 185)

        assertEquals(AckStatus.ACCEPTED, ack?.status)
        assertEquals(DeliveryState.DELIVERED, phoneA.store.get(message.messageId).deliveryState)
        assertEquals(longText, phoneB.store.all().single().content)
    }


    @Test
    fun failedTransferRetriedOnTheSameLinkDoesNotMixOldAndNewFrames() = runBlocking {

        phoneA.engine.createText(phoneB.id, longText, now = 0L)

        val link = Link(mtu = 23)
        val first = phoneA.engine.nextOutgoing(phoneB.id, now = 0L)!!
        link.carry(first.bytes) { it.dropLast(3) }
        phoneA.engine.onTransmitted(first.messageId, phoneB.id, success = false, now = 0L)

        // The retry has a different remaining TTL, so its bytes differ from the stale partial.
        val retry = phoneA.engine.nextOutgoing(phoneB.id, now = 5_000L)!!
        val completed = link.carry(retry.bytes)

        assertEquals(1, completed.size)
        assertEquals(longText, (MessagePacketCodec.decode(completed.single()) as DataPacket).payload)
    }


    // ---------------------------------------------------------
    // Duplicate prevention
    // ---------------------------------------------------------

    @Test
    fun lostAckCausesAResendThatIsAckedAsDuplicateWithoutASecondCopy() = runBlocking {

        val message = phoneA.engine.createText(phoneB.id, "Meet at the clinic", now = 0L)

        // B stores the message, but its ACK never reaches A (link drops right after).
        val outbound = phoneA.engine.nextOutgoing(phoneB.id, now = 0L)!!
        val packet = Link(mtu = 185).carry(outbound.bytes).single()
        phoneA.engine.onTransmitted(outbound.messageId, phoneB.id, success = true, now = 0L)
        phoneB.engine.onDataReceived(MessagePacketCodec.decode(packet) as DataPacket, phoneA.id, now = 0L)
        assertEquals(DeliveryState.SENT, phoneA.store.get(message.messageId).deliveryState)

        // Existing ACK timeout puts it back in the queue; the resend reaches B again.
        phoneA.engine.requeueTimedOut(now = 15_000L)
        val ack = sendOverNewLink(now = 15_000L, mtu = 185)

        assertEquals(AckStatus.DUPLICATE, ack?.status)
        assertEquals(message.messageId, ack?.messageId)
        assertEquals(DeliveryState.DELIVERED, phoneA.store.get(message.messageId).deliveryState)
        assertEquals(1, phoneB.store.all().size)
    }


    @Test
    fun ackForOneMessageNeverDeliversAnotherMessage() = runBlocking {

        val first = phoneA.engine.createText(phoneB.id, "first", now = 0L)
        val second = phoneA.engine.createText(phoneB.id, "second", now = 1L)

        val ack = sendOverNewLink(now = 10L, mtu = 185)
        assertEquals(first.messageId, ack?.messageId)

        // Replaying the same ACK (e.g. a duplicate notification) changes nothing else.
        phoneA.engine.onAck(ack!!, phoneB.id, now = 20L)

        assertEquals(DeliveryState.DELIVERED, phoneA.store.get(first.messageId).deliveryState)
        assertEquals(DeliveryState.QUEUED, phoneA.store.get(second.messageId).deliveryState)
    }


    // ---------------------------------------------------------
    // Fragmentation
    // ---------------------------------------------------------

    @Test
    fun reorderedAndReplayedFramesAtNegotiatedMtuAreStoredExactlyOnce() = runBlocking {

        phoneA.engine.createText(phoneB.id, longText, now = 0L)
        val outbound = phoneA.engine.nextOutgoing(phoneB.id, now = 0L)!!

        // Frames arrive reversed, and then the whole set is replayed.
        val completed = Link(mtu = 185).carry(outbound.bytes) { frames -> frames.reversed() + frames }
        assertEquals(2, completed.size)

        val results = completed.map {
            phoneB.engine.onDataReceived(MessagePacketCodec.decode(it) as DataPacket, phoneA.id, now = 0L)
        }

        // The framing layer can complete a replayed packet twice; the engine stores it once.
        assertEquals(listOf(AckStatus.ACCEPTED, AckStatus.DUPLICATE), results.map { it.ack.status })
        assertEquals(longText, phoneB.store.all().single().content)
    }


    @Test
    fun shortMessageFitsOneFrameAtNegotiatedMtuAndManyAtFallback() = runBlocking {

        phoneA.engine.createText(phoneB.id, "ok", now = 0L)
        val bytes = phoneA.engine.nextOutgoing(phoneB.id, now = 0L)!!.bytes

        assertEquals(1, PacketFraming.split(bytes, 0, 185).size)
        assertTrue(PacketFraming.split(bytes, 0, 23).size > 1)
        assertNotNull(Link(mtu = 23).carry(bytes).singleOrNull())
    }


    // ---------------------------------------------------------
    // Helper: one full send over a fresh connection
    // ---------------------------------------------------------

    /** A → frames → B → ACK frames → A, the way MessagingCoordinator drives one transfer. */
    private suspend fun sendOverNewLink(now: Long, mtu: Int): AckPacket? {

        val toB = Link(mtu)
        val toA = Link(mtu)

        val outbound = phoneA.engine.nextOutgoing(phoneB.id, now) ?: return null
        val packet = toB.carry(outbound.bytes).single()
        phoneA.engine.onTransmitted(outbound.messageId, phoneB.id, success = true, now)

        val result = phoneB.engine.onDataReceived(MessagePacketCodec.decode(packet) as DataPacket, phoneA.id, now)
        val ack = MessagePacketCodec.decode(toA.carry(MessagePacketCodec.encode(result.ack)).single()) as AckPacket

        phoneA.engine.onAck(ack, phoneB.id, now)
        return ack
    }
}

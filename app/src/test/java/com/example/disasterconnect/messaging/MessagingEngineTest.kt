package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class MessagingEngineTest {

    private val phoneA = "DC-AAAAAA"
    private val phoneB = "DC-BBBBBB"
    private val phoneC = "DC-CCCCCC"

    private val policy = RetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, maxAttempts = 3, ackTimeoutMillis = 10_000L)


    // ---------------------------------------------------------
    // Packet + delivery
    // ---------------------------------------------------------

    @Test
    fun textTravelsFromAToBWithSenderIdentityAndAckMarksDelivered() = runBlocking {

        val storeA = FakeMessageStore()
        val storeB = FakeMessageStore()
        val engineA = MessagingEngine(storeA, phoneA, policy)
        val engineB = MessagingEngine(storeB, phoneB, policy)

        val created = engineA.createText(phoneB, "  Are you safe?  ", now = 0L)
        val outbound = engineA.nextOutgoing(phoneB, now = 0L)!!
        engineA.onTransmitted(outbound.messageId, phoneB, success = true, now = 10L)

        // Frames were accepted, but there is no receipt yet.
        assertEquals(DeliveryState.SENT, storeA.get(created.messageId).deliveryState)

        val packet = MessagePacketCodec.decode(outbound.bytes) as DataPacket
        val received = engineB.onDataReceived(packet, peerId = phoneA, now = 20L)

        assertEquals("Are you safe?", received.message!!.content)
        assertEquals(phoneA, received.message!!.senderId)
        assertTrue(received.message!!.isIncoming)
        assertEquals(AckStatus.ACCEPTED, received.ack.status)

        engineA.onAck(received.ack, peerId = phoneB, now = 30L)

        assertEquals(DeliveryState.DELIVERED, storeA.get(created.messageId).deliveryState)
    }


    @Test
    fun ackFromAnotherPhoneDoesNotMarkDelivered() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneA, policy)
        val created = engine.createText(phoneB, "hello", now = 0L)
        engine.nextOutgoing(phoneB, now = 0L)
        engine.onTransmitted(created.messageId, phoneB, success = true, now = 0L)

        engine.onAck(AckPacket(created.messageId, phoneC, AckStatus.ACCEPTED), peerId = phoneC, now = 1L)
        engine.onAck(AckPacket(created.messageId, phoneC, AckStatus.ACCEPTED), peerId = phoneB, now = 1L)

        assertEquals(DeliveryState.SENT, store.get(created.messageId).deliveryState)
    }


    @Test
    fun ackArrivingBeforeWriteConfirmationIsNotDowngraded() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneA, policy)
        val created = engine.createText(phoneB, "hello", now = 0L)
        engine.nextOutgoing(phoneB, now = 0L)

        engine.onAck(AckPacket(created.messageId, phoneB, AckStatus.ACCEPTED), peerId = phoneB, now = 1L)
        engine.onTransmitted(created.messageId, phoneB, success = true, now = 2L)

        assertEquals(DeliveryState.DELIVERED, store.get(created.messageId).deliveryState)
    }


    @Test
    fun directMessageGoesToItsReceiverOrToARelayButNeverTwiceToTheSameRelay() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneA, policy)
        val message = engine.createText(phoneB, "for B", now = 0L)

        // C may carry it towards B (multi-hop); once C has it, C is not offered it again.
        val toRelay = engine.nextOutgoing(phoneC, now = 0L)!!
        engine.onAck(AckPacket(toRelay.messageId, phoneC, AckStatus.ACCEPTED), phoneC, now = 0L)

        assertEquals(DeliveryState.FORWARDED, store.get(message.messageId).deliveryState)
        assertNull(engine.nextOutgoing(phoneC, now = 1L))
        assertNotNull(engine.nextOutgoing(phoneB, now = 1L))
    }


    // ---------------------------------------------------------
    // Deduplication
    // ---------------------------------------------------------

    @Test
    fun duplicatePacketIsStoredOnceAndAckedAsDuplicate() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneB, policy)
        val packet = dataPacket(receiverId = phoneB)

        val first = engine.onDataReceived(packet, peerId = phoneA, now = 0L)
        val second = engine.onDataReceived(packet, peerId = phoneA, now = 5L)

        assertNotNull(first.message)
        assertNull(second.message)
        assertEquals(AckStatus.DUPLICATE, second.ack.status)
        assertEquals(1, store.all().size)
    }


    @Test
    fun packetForAnotherReceiverIsStoredAsAHiddenRelayCopy() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneC, policy)

        val result = engine.onDataReceived(dataPacket(receiverId = phoneB), peerId = phoneA, now = 0L)

        assertEquals(AckStatus.ACCEPTED, result.ack.status)
        val copy = store.all().single()
        assertTrue(copy.isIncoming)
        assertEquals(phoneB, copy.receiverId)
        assertEquals(DeliveryState.QUEUED, copy.deliveryState)
    }


    // ---------------------------------------------------------
    // TTL
    // ---------------------------------------------------------

    @Test
    fun ttlIsSentAsRemainingTimeAndReceiverUsesItsOwnClock() = runBlocking {

        val engineA = MessagingEngine(FakeMessageStore(), phoneA, policy)
        engineA.createText(phoneB, "hello", now = 0L)

        val oneHourLater = 60L * 60L * 1000L
        val packet = MessagePacketCodec.decode(engineA.nextOutgoing(phoneB, now = oneHourLater)!!.bytes) as DataPacket

        assertEquals(23 * 60 * 60, packet.ttlSeconds)

        val receiverNow = 5_000_000L
        val stored = MessagingEngine(FakeMessageStore(), phoneB, policy).onDataReceived(packet, phoneA, receiverNow).message!!
        assertEquals(receiverNow + 23L * 60L * 60L * 1000L, stored.expiresAt)
    }


    @Test
    fun expiredMessagesAreNeitherSentNorAccepted() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneA, policy)
        val created = engine.createText(phoneB, "late", now = 0L)

        assertNull(engine.nextOutgoing(phoneB, now = MessagingEngine.TEXT_TTL_MILLIS + 1))
        assertEquals(DeliveryState.EXPIRED, store.get(created.messageId).deliveryState)

        val result = MessagingEngine(FakeMessageStore(), phoneB, policy)
            .onDataReceived(dataPacket(receiverId = phoneB, ttlSeconds = 0), phoneA, now = 0L)
        assertEquals(AckStatus.EXPIRED, result.ack.status)
        assertNull(result.message)
    }


    // ---------------------------------------------------------
    // Retry
    // ---------------------------------------------------------

    @Test
    fun failedTransmissionIsRetriedWithBackoffThenFails() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneA, policy)
        val created = engine.createText(phoneB, "retry me", now = 0L)

        engine.nextOutgoing(phoneB, now = 0L)
        engine.onTransmitted(created.messageId, phoneB, success = false, now = 0L)
        assertEquals(DeliveryState.QUEUED, store.get(created.messageId).deliveryState)

        // Backoff after 1 attempt is 1 s.
        assertNull(engine.nextOutgoing(phoneB, now = 999L))
        assertNotNull(engine.nextOutgoing(phoneB, now = 1_000L))
        engine.onTransmitted(created.messageId, phoneB, success = false, now = 1_000L)

        // Backoff after 2 attempts is 2 s.
        assertFalse(engine.hasPendingFor(phoneB, now = 2_999L))
        assertNotNull(engine.nextOutgoing(phoneB, now = 3_000L))
        engine.onTransmitted(created.messageId, phoneB, success = false, now = 3_000L)

        assertEquals(DeliveryState.FAILED, store.get(created.messageId).deliveryState)
        assertNull(engine.nextOutgoing(phoneB, now = 100_000L))

        engine.retry(created.messageId)
        assertEquals(DeliveryState.QUEUED, store.get(created.messageId).deliveryState)
        assertNotNull(engine.nextOutgoing(phoneB, now = 100_000L))
    }


    @Test
    fun missingAckRequeuesAfterTimeoutAndSurvivesRestart() = runBlocking {

        val store = FakeMessageStore()
        val created = MessagingEngine(store, phoneA, policy).let { engine ->
            val message = engine.createText(phoneB, "hello", now = 0L)
            engine.nextOutgoing(phoneB, now = 0L)
            engine.onTransmitted(message.messageId, phoneB, success = true, now = 0L)
            message
        }

        // A new engine over the same store models an app restart.
        val restarted = MessagingEngine(store, phoneA, policy)
        restarted.requeueTimedOut(now = 9_999L)
        assertEquals(DeliveryState.SENT, store.get(created.messageId).deliveryState)

        restarted.requeueTimedOut(now = 10_000L)
        assertEquals(DeliveryState.QUEUED, store.get(created.messageId).deliveryState)
        assertNotNull(restarted.nextOutgoing(phoneB, now = 10_000L))
    }


    @Test
    fun disconnectRequeuesOnlyThatPeersUnackedMessages() = runBlocking {

        val store = FakeMessageStore()
        val engine = MessagingEngine(store, phoneA, policy)
        val toB = engine.createText(phoneB, "b", now = 0L)
        val toC = engine.createText(phoneC, "c", now = 0L)
        engine.nextOutgoing(phoneB, now = 0L)
        engine.onTransmitted(toB.messageId, phoneB, true, now = 0L)
        engine.nextOutgoing(phoneC, now = 0L)
        engine.onTransmitted(toC.messageId, phoneC, true, now = 0L)

        engine.requeueForPeer(phoneB)

        assertEquals(DeliveryState.QUEUED, store.get(toB.messageId).deliveryState)
        assertEquals(DeliveryState.SENT, store.get(toC.messageId).deliveryState)
    }


    // ---------------------------------------------------------
    // Broadcast SOS relay
    // ---------------------------------------------------------

    @Test
    fun sosIsHandedToEachPeerOnceAndNeverBackToItsSource() = runBlocking {

        val storeA = FakeMessageStore()
        val engineA = MessagingEngine(storeA, phoneA, policy)
        storeA.insert(sos(senderId = phoneA))

        val toB = engineA.nextOutgoing(phoneB, now = 0L)!!
        engineA.onTransmitted(toB.messageId, phoneB, true, now = 0L)
        val engineB = MessagingEngine(FakeMessageStore(), phoneB, policy)
        val received = engineB.onDataReceived(MessagePacketCodec.decode(toB.bytes) as DataPacket, phoneA, now = 0L)
        engineA.onAck(received.ack, phoneB, now = 0L)

        assertEquals(DeliveryState.FORWARDED, storeA.get("sos-1").deliveryState)
        assertNull(engineA.nextOutgoing(phoneB, now = 1L))
        assertNotNull(engineA.nextOutgoing(phoneC, now = 1L))

        // B relays to C but not back to A, with the original sender and one more hop.
        assertNull(engineB.nextOutgoing(phoneA, now = 1L))
        val relayed = MessagePacketCodec.decode(engineB.nextOutgoing(phoneC, now = 1L)!!.bytes) as DataPacket
        assertEquals(phoneA, relayed.senderId)
        assertEquals(1, relayed.hopCount)
    }


    @Test
    fun broadcastStopsAtHopLimit() = runBlocking {

        val engine = MessagingEngine(FakeMessageStore(), phoneB, policy, maxHops = 2)

        engine.onDataReceived(dataPacket(receiverId = null, hopCount = 1), phoneA, now = 0L)

        assertNull(engine.nextOutgoing(phoneC, now = 0L))
    }


    // ---------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------

    private fun dataPacket(receiverId: String?, ttlSeconds: Int = 3_600, hopCount: Int = 0) =
        DataPacket(
            messageId = "msg-1",
            senderId = phoneA,
            receiverId = receiverId,
            messageType = if (receiverId == null) MessageType.SOS else MessageType.TEXT,
            priority = if (receiverId == null) MessagePriority.CRITICAL else MessagePriority.NORMAL,
            createdAt = 0L,
            ttlSeconds = ttlSeconds,
            hopCount = hopCount,
            payload = "help"
        )

    private fun sos(senderId: String) =
        EmergencyMessage(
            messageId = "sos-1",
            senderId = senderId,
            messageType = MessageType.SOS,
            priority = MessagePriority.CRITICAL,
            content = "SOS",
            createdAt = 0L,
            expiresAt = 1_000_000L,
            deliveryState = DeliveryState.QUEUED
        )
}

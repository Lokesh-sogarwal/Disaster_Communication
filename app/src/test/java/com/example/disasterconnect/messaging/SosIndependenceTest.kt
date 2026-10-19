package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.security.AnnouncedKey
import com.example.disasterconnect.security.FakeTrustedKeyRecords
import com.example.disasterconnect.security.KeyExchange
import com.example.disasterconnect.security.KeyPayloadCodec
import com.example.disasterconnect.security.KeyReceiveResult
import com.example.disasterconnect.security.TrustedKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec


/**
 * SOS must never depend on personal-contact verification: unknown, unverified and "key changed"
 * phones still have their SOS received, stored and relayed exactly as before. KEY packets never
 * enter message storage or relaying.
 */
class SosIndependenceTest {

    private val policy = RetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, maxAttempts = 5, ackTimeoutMillis = 15_000L)

    private inner class Phone(val id: String, verifiesEveryone: Boolean = false, signs: Boolean = false) {

        val store = FakeMessageStore()

        private val identity = SoftwareReceiptSigner()

        // A phone that treats every contact as VERIFIED and signs receipts: the strictest receipt setup.
        private val receiptTrust = object : ReceiptTrust {
            override suspend fun requiresSignedReceipt(nodeId: String) = verifiesEveryone
            override suspend fun receiptKey(nodeId: String): ByteArray? = null
        }

        val engine = MessagingEngine(store, id, policy, receiptTrust = receiptTrust, receiptSigner = { identity.takeIf { signs } })

        val trust = FakeTrustedKeyRecords()

        val keys = KeyExchange(TrustedKeyStore(trust), id, newKey())
    }

    private val a = Phone("DC-AAAAAA")
    private val b = Phone("DC-BBBBBB")
    private val c = Phone("DC-CCCCCC")

    /** An SOS exactly as MainActivity.saveSosPacket queues it (broadcast, CRITICAL, 24 h). */
    private suspend fun queueSos(phone: Phone, id: String = "sos-1"): EmergencyMessage {
        val sos = EmergencyMessage(
            messageId = id,
            senderId = phone.id,
            messageType = MessageType.SOS,
            priority = MessagePriority.CRITICAL,
            content = "Trapped on second floor",
            createdAt = 0L,
            expiresAt = MessagingEngine.TEXT_TTL_MILLIS,
            latitude = 28.6139,
            longitude = 77.209,
            deliveryState = DeliveryState.QUEUED
        )
        phone.store.insert(sos)
        return sos
    }

    private suspend fun transfer(from: Phone, to: Phone, now: Long): AckPacket? {
        val outbound = from.engine.nextOutgoing(to.id, now) ?: return null
        val reassembler = PacketReassembler()
        val packet = PacketFraming.split(outbound.bytes, 0, PacketFraming.DEFAULT_MTU).mapNotNull { reassembler.accept(it, 0L) }.single()
        from.engine.onTransmitted(outbound.messageId, to.id, success = true, now)
        val ack = to.engine.onDataReceived(MessagePacketCodec.decode(packet) as DataPacket, from.id, now).ack
        from.engine.onAck(ack, to.id, now)
        return ack
    }


    @Test
    fun sosFromAnUnknownUnverifiedPhoneIsReceivedAndRelayedWithNoKeysExchanged() = runBlocking {

        queueSos(a)

        assertEquals(AckStatus.ACCEPTED, transfer(a, b, now = 0L)?.status)
        assertEquals(AckStatus.ACCEPTED, transfer(b, c, now = 1_000L)?.status)

        assertTrue(b.trust.rows.isEmpty() && c.trust.rows.isEmpty())
        assertEquals(DeliveryState.FORWARDED, a.store.get("sos-1").deliveryState)
        val atC = c.store.get("sos-1")
        assertEquals(MessageType.SOS, atC.messageType)
        assertEquals(a.id, atC.senderId)
        assertEquals(2, atC.hopCount)
        assertEquals(28.6139, atC.latitude!!, 0.0)
    }


    @Test
    fun sosFromAPhoneFlaggedKeyChangedIsStillReceivedAndRelayed() = runBlocking {

        // B pinned A's key, then someone presented a different key for A: B shows "Key changed".
        b.keys.onKeyPacket(keyPacket(a.id, b.id, a.keys.localPublicKey), a.id, 0L)
        assertEquals(KeyReceiveResult.CONFLICT, b.keys.onKeyPacket(keyPacket(a.id, b.id, newKey()), a.id, 1L))

        queueSos(a)

        assertEquals(AckStatus.ACCEPTED, transfer(a, b, now = 2L)?.status)
        assertEquals(AckStatus.ACCEPTED, transfer(b, c, now = 1_002L)?.status)
        assertEquals(DeliveryState.QUEUED, c.store.get("sos-1").deliveryState)
    }


    @Test
    fun sosDeduplicationAndHopLimitAreUnchangedByKeyTraffic() = runBlocking {

        queueSos(a)
        transfer(a, b, now = 0L)

        // Key packets between the same phones change nothing in message storage.
        b.keys.onKeyPacket(keyPacket(a.id, b.id, a.keys.localPublicKey), a.id, 1L)

        // The same SOS arriving again (e.g. through C) is a duplicate, stored once.
        val again = b.engine.onDataReceived(
            MessagePacketCodec.decode(MessagePacketCodec.encode(sosPacket(hops = 1))) as DataPacket, c.id, now = 2L
        )
        assertEquals(AckStatus.DUPLICATE, again.ack.status)
        assertEquals(1, b.store.all().size)

        // An SOS that already used every hop is stored but never forwarded further (unchanged rule).
        val atLimit = c.engine.onDataReceived(
            MessagePacketCodec.decode(MessagePacketCodec.encode(sosPacket(id = "sos-old", hops = MessagingEngine.DEFAULT_MAX_HOPS - 1))) as DataPacket,
            b.id,
            now = 3L
        )
        assertEquals(AckStatus.ACCEPTED, atLimit.ack.status)
        // Asked for a fourth phone (not the sender, not the phone it came from).
        assertNull(c.engine.nextOutgoing("DC-DDDDDD", now = 4L))
    }


    @Test
    fun sosIsRelayedAndNeverReceiptedWhileReceiptTrafficAndVerifiedContactsAreActive() = runBlocking {

        val sender = Phone("DC-111111", verifiesEveryone = true, signs = true)
        val relay = Phone("DC-222222", verifiesEveryone = true, signs = true)
        val last = Phone("DC-333333", verifiesEveryone = true, signs = true)

        // A personal message first, so the relay also holds a receipt waiting to go back.
        sender.engine.createText(relay.id, "personal", now = 0L)
        transfer(sender, relay, now = 0L)
        assertEquals(1, relay.store.all().count { it.messageType == MessageType.RECEIPT })

        queueSos(sender)

        // CRITICAL SOS goes first and is relayed onward despite every peer being "verified".
        assertEquals(AckStatus.ACCEPTED, transfer(sender, relay, now = 10L)?.status)
        assertEquals(AckStatus.ACCEPTED, transfer(relay, last, now = 1_010L)?.status)

        val sos = sender.store.get("sos-1")
        assertEquals(DeliveryState.FORWARDED, sos.deliveryState)
        assertEquals(com.example.disasterconnect.data.DeliveryProof.NONE, sos.deliveryProof)
        assertEquals(MessageType.SOS, last.store.get("sos-1").messageType)
        // No receipt is ever made for an SOS.
        listOf(sender, relay, last).forEach { phone ->
            assertTrue(phone.store.all().none { it.messageType == MessageType.RECEIPT && it.receiptFor == "sos-1" })
        }
    }


    @Test
    fun sosStillFlowsWhenThisPhonesIdentityKeyIsUnavailable() = runBlocking {

        val sender = Phone("DC-111111", verifiesEveryone = true, signs = false)
        val relay = Phone("DC-222222", verifiesEveryone = true, signs = false)

        queueSos(sender)

        assertEquals(AckStatus.ACCEPTED, transfer(sender, relay, now = 0L)?.status)
        assertEquals(DeliveryState.QUEUED, relay.store.get("sos-1").deliveryState)
    }


    @Test
    fun keyPacketsNeverEnterMessageStorageOrRelaying() = runBlocking {

        val packet = MessagePacketCodec.decode(a.keys.keyPacketFor(b.id, 0L)!!) as DataPacket

        val result = b.engine.onDataReceived(packet, a.id, now = 0L)

        assertEquals(AckStatus.REJECTED, result.ack.status)
        assertNull(result.message)
        assertTrue(b.store.all().isEmpty())
        assertNull(b.engine.nextOutgoing(c.id, now = 1L))
    }


    private fun sosPacket(id: String = "sos-1", hops: Int) = DataPacket(
        messageId = id,
        senderId = a.id,
        receiverId = null,
        messageType = MessageType.SOS,
        priority = MessagePriority.CRITICAL,
        createdAt = 0L,
        ttlSeconds = 3_600,
        hopCount = hops,
        latitude = 28.6139,
        longitude = 77.209,
        payload = "Trapped on second floor"
    )

    private fun keyPacket(from: String, to: String, key: ByteArray) = DataPacket(
        messageId = "k:test",
        senderId = from,
        receiverId = to,
        messageType = MessageType.KEY,
        priority = MessagePriority.NORMAL,
        createdAt = 0L,
        ttlSeconds = 60,
        hopCount = 0,
        payload = KeyPayloadCodec.encode(AnnouncedKey(from, key))
    )

    private companion object {

        fun newKey(): ByteArray =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded
    }
}

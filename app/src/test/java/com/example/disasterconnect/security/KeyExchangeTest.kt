package com.example.disasterconnect.security

import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.messaging.DataPacket
import com.example.disasterconnect.messaging.EcdsaP256
import com.example.disasterconnect.messaging.MessagePacketCodec
import com.example.disasterconnect.messaging.PacketFraming
import com.example.disasterconnect.messaging.PacketReassembler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec


/** Two (or three) phones exchanging keys through the real packet codec and framing; only the radio is simulated. */
class KeyExchangeTest {

    private class Phone(val id: String) {

        val records = FakeTrustedKeyRecords()

        val store = TrustedKeyStore(records)

        val key: ByteArray = newKey()

        val exchange = KeyExchange(store, id, key)
    }

    private val a = Phone("DC-AAAAAA")
    private val b = Phone("DC-BBBBBB")

    /** Frames the packet at the fallback MTU, reassembles it and decodes it, as a BLE link would. */
    private fun overTheAir(bytes: ByteArray): DataPacket {
        val reassembler = PacketReassembler()
        val packet = PacketFraming.split(bytes, 0, PacketFraming.DEFAULT_MTU).mapNotNull { reassembler.accept(it, 0L) }.single()
        return MessagePacketCodec.decode(packet) as DataPacket
    }

    private suspend fun send(from: Phone, to: Phone, now: Long = 0L): KeyReceiveResult =
        to.exchange.onKeyPacket(overTheAir(from.exchange.keyPacketFor(to.id, now)!!), from.id, now)

    private fun keyPacket(from: String, to: String?, key: ByteArray, hops: Int = 0, payloadId: String = from) = DataPacket(
        messageId = "k:test",
        senderId = from,
        receiverId = to,
        messageType = MessageType.KEY,
        priority = "NORMAL",
        createdAt = 0L,
        ttlSeconds = 60,
        hopCount = hops,
        payload = KeyPayloadCodec.encode(AnnouncedKey(payloadId, key))
    )


    // ---------------------------------------------------------
    // Exchange
    // ---------------------------------------------------------

    @Test
    fun linkedPhonesExchangeKeysAndStoreThemOnlyAsSeen() = runBlocking {

        assertEquals(KeyReceiveResult.STORED, send(a, b))
        assertEquals(KeyReceiveResult.STORED, send(b, a))

        assertArrayEquals(a.key, b.store.find(a.id)!!.publicKey)
        assertArrayEquals(b.key, a.store.find(b.id)!!.publicKey)
        assertEquals(TrustState.SEEN, b.store.find(a.id)!!.trust)
        assertEquals(TrustState.SEEN, a.store.find(b.id)!!.trust)

        // Both phones show the same code, computed locally.
        assertEquals(a.exchange.safetyCode(b.id), b.exchange.safetyCode(a.id))
        assertNotNull(a.exchange.safetyCode(b.id))
    }


    @Test
    fun keyIsSentOncePerPeerPerRunUnlessExplicitlyAllowedAgain() {

        assertNotNull(a.exchange.keyPacketFor(b.id, 0L))

        // An older app silently drops KEY packets: no repeated sending.
        repeat(5) { assertNull(a.exchange.keyPacketFor(b.id, it.toLong())) }

        a.exchange.allowResend(b.id)
        assertNotNull(a.exchange.keyPacketFor(b.id, 10L))
        assertNull(a.exchange.keyPacketFor(b.id, 11L))

        assertNull(a.exchange.keyPacketFor(a.id, 0L))
    }


    @Test
    fun sentKeyPacketIsDirectAddressedAndSelfDescribing() {

        val packet = overTheAir(a.exchange.keyPacketFor(b.id, 0L)!!)

        assertEquals(MessageType.KEY, packet.messageType)
        assertEquals(0, packet.hopCount)
        assertEquals(a.id, packet.senderId)
        assertEquals(b.id, packet.receiverId)
        assertNull(packet.latitude)
        val announced = KeyPayloadCodec.decode(packet.payload)!!
        assertEquals(a.id, announced.nodeId)
        assertArrayEquals(a.key, announced.publicKey)
    }


    // ---------------------------------------------------------
    // Direct-only and identity checks
    // ---------------------------------------------------------

    @Test
    fun relayedMisaddressedAndImpersonatingKeyPacketsAreRejectedWithoutStoring() = runBlocking {

        val c = "DC-CCCCCC"

        // Arrived through a relay.
        assertEquals(KeyReceiveResult.NOT_DIRECT, b.exchange.onKeyPacket(keyPacket(a.id, b.id, a.key, hops = 1), a.id, 0L))
        // Addressed to another phone, or broadcast.
        assertEquals(KeyReceiveResult.NOT_FOR_THIS_PHONE, b.exchange.onKeyPacket(keyPacket(a.id, c, a.key), a.id, 0L))
        assertEquals(KeyReceiveResult.NOT_FOR_THIS_PHONE, b.exchange.onKeyPacket(keyPacket(a.id, null, a.key), a.id, 0L))
        // The phone on the link (C) claims to be A in the envelope or in the payload.
        assertEquals(KeyReceiveResult.WRONG_SENDER, b.exchange.onKeyPacket(keyPacket(a.id, b.id, a.key), c, 0L))
        assertEquals(KeyReceiveResult.WRONG_SENDER, b.exchange.onKeyPacket(keyPacket(c, b.id, a.key, payloadId = a.id), c, 0L))
        // Not a KEY packet, or an expired one.
        assertEquals(KeyReceiveResult.NOT_A_KEY, b.exchange.onKeyPacket(keyPacket(a.id, b.id, a.key).copy(messageType = MessageType.TEXT), a.id, 0L))
        assertEquals(KeyReceiveResult.INVALID, b.exchange.onKeyPacket(keyPacket(a.id, b.id, a.key).copy(ttlSeconds = 0), a.id, 0L))
        assertEquals(KeyReceiveResult.INVALID, b.exchange.onKeyPacket(keyPacket(a.id, b.id, a.key).copy(payload = "garbage"), a.id, 0L))

        assertTrue(b.records.rows.isEmpty())
        assertTrue(b.exchange.linkKeys.value.isEmpty())
    }


    @Test
    fun differentKeyForAKnownNodeIsAConflictThatKeepsTheStoredKeyAndBlocksVerification() = runBlocking {

        send(a, b)
        val impostorKey = newKey()

        val result = b.exchange.onKeyPacket(keyPacket(a.id, b.id, impostorKey), a.id, 1L)

        assertEquals(KeyReceiveResult.CONFLICT, result)
        assertArrayEquals(a.key, b.store.find(a.id)!!.publicKey)
        assertTrue(a.id in b.exchange.conflicts.value)
        assertNull(b.exchange.safetyCode(a.id))
        assertSame(
            VerifyResult.KeyMismatch,
            b.exchange.confirmVerification(a.id, EcdsaP256.fingerprint(a.key), linkReady = true, now = 2L)
        )
        assertEquals(TrustState.SEEN, b.store.find(a.id)!!.trust)
    }


    // ---------------------------------------------------------
    // Verification
    // ---------------------------------------------------------

    @Test
    fun explicitConfirmationVerifiesOnlyThisPhonesRecordOfThePeer() = runBlocking {

        send(a, b)
        send(b, a)

        val result = a.exchange.confirmVerification(b.id, EcdsaP256.fingerprint(b.key), linkReady = true, now = 50L)

        assertTrue(result is VerifyResult.Verified)
        assertEquals(TrustState.VERIFIED, a.store.find(b.id)!!.trust)
        // Verification is local: B has not verified A.
        assertEquals(TrustState.SEEN, b.store.find(a.id)!!.trust)
    }


    @Test
    fun receivingKeysAgainNeverPromotesOrDowngradesTrust() = runBlocking {

        send(a, b)
        repeat(3) {
            a.exchange.allowResend(b.id)
            assertEquals(KeyReceiveResult.ALREADY_STORED, send(a, b, now = it + 1L))
        }
        assertEquals(TrustState.SEEN, b.store.find(a.id)!!.trust)

        b.exchange.confirmVerification(a.id, EcdsaP256.fingerprint(a.key), linkReady = true, now = 10L)
        a.exchange.allowResend(b.id)
        assertEquals(KeyReceiveResult.ALREADY_STORED, send(a, b, now = 20L))

        assertEquals(TrustState.VERIFIED, b.store.find(a.id)!!.trust)
        assertEquals(10L, b.store.find(a.id)!!.verifiedAt)
    }


    @Test
    fun staleConfirmationsAreRejected() = runBlocking {

        send(a, b)
        val shown = EcdsaP256.fingerprint(a.key)

        // Not linked any more.
        assertSame(VerifyResult.NotLinked, b.exchange.confirmVerification(a.id, shown, linkReady = false, now = 1L))

        // The link closed after the code was shown: the pending confirmation is gone.
        b.exchange.onLinkClosed(a.id)
        assertSame(VerifyResult.NotLinked, b.exchange.confirmVerification(a.id, shown, linkReady = true, now = 2L))

        // A code made from a different key than the one on the link.
        a.exchange.allowResend(b.id)
        send(a, b, now = 3L)
        assertSame(VerifyResult.KeyMismatch, b.exchange.confirmVerification(a.id, EcdsaP256.fingerprint(newKey()), linkReady = true, now = 4L))

        // A confirmation for A never applies to another peer.
        assertSame(VerifyResult.NotLinked, b.exchange.confirmVerification("DC-CCCCCC", shown, linkReady = true, now = 5L))

        assertEquals(TrustState.SEEN, b.store.find(a.id)!!.trust)
        assertFalse(b.records.rows.containsKey("DC-CCCCCC"))
    }


    private companion object {

        fun newKey(): ByteArray =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded
    }
}

package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64


/**
 * Signed delivery receipts, phase 1: canonical bytes, strict codec and verification only.
 * Not connected to MessagingEngine yet, so these tests call the codec directly.
 */
class DeliveryReceiptCodecTest {

    private val idA = "DC-AAAAAA"
    private val idB = "DC-BBBBBB"
    private val idC = "DC-CCCCCC"

    private val keyB = SoftwareReceiptSigner()
    private val keyC = SoftwareReceiptSigner()

    private val message = ReceiptSubject(
        messageId = "6f1c2a52-0d8e-4c55-9d43-3b8f1d0c9a11",
        senderId = idA,
        receiverId = idC,
        messageType = MessageType.TEXT,
        priority = MessagePriority.NORMAL,
        createdAt = 1_760_000_000_000L,
        latitude = null,
        longitude = null,
        payload = "Need water at the school. नमस्ते 👋"
    )

    /** What sender A does on arrival: decode, then check against its own row and C's verified key. */
    private fun checkAtA(encoded: String, original: ReceiptSubject = message, key: ByteArray = keyC.publicKey): ReceiptCheck? =
        DeliveryReceiptCodec.decode(encoded)?.let { DeliveryReceiptCodec.verify(it, original, idC, key) }


    // ---------------------------------------------------------
    // Valid receipts
    // ---------------------------------------------------------

    @Test
    fun receiptSignedByTheRecipientVerifiesAgainstTheSendersOwnRecord() {

        val encoded = DeliveryReceiptCodec.create(message, idC, keyC)

        assertTrue(encoded.length <= DeliveryReceiptCodec.MAX_ENCODED_CHARS)
        assertEquals(ReceiptCheck.VALID, checkAtA(encoded))
        assertEquals(message.messageId, DeliveryReceiptCodec.decode(encoded)!!.originalMessageId)
    }


    @Test
    fun recipientsRelayedCopyHashesExactlyLikeTheSendersOriginal() = runBlocking {

        // Real engines and codec: A → B (relay) → C, so C signs what it actually stored.
        val policy = RetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, maxAttempts = 5, ackTimeoutMillis = 15_000L)
        val storeA = FakeMessageStore()
        val storeB = FakeMessageStore()
        val storeC = FakeMessageStore()
        val engineA = MessagingEngine(storeA, idA, policy)
        val engineB = MessagingEngine(storeB, idB, policy)
        val engineC = MessagingEngine(storeC, idC, policy)

        val original = engineA.createText(idC, "Bridge is down, use the north road", now = 0L)
        val toB = engineA.nextOutgoing(idB, now = 0L)!!
        engineB.onDataReceived(MessagePacketCodec.decode(toB.bytes) as DataPacket, idA, now = 0L)
        val toC = engineB.nextOutgoing(idC, now = 1_000L)!!
        engineC.onDataReceived(MessagePacketCodec.decode(toC.bytes) as DataPacket, idB, now = 1_000L)

        val atA = storeA.get(original.messageId).receiptSubject()!!
        val atC = storeC.get(original.messageId).receiptSubject()!!

        assertEquals(2, storeC.get(original.messageId).hopCount)
        assertArrayEquals(DeliveryReceiptCodec.messageDigest(atA), DeliveryReceiptCodec.messageDigest(atC))

        val encoded = DeliveryReceiptCodec.create(atC, idC, keyC)
        assertEquals(ReceiptCheck.VALID, checkAtA(encoded, original = atA))
    }


    @Test
    fun broadcastsHaveNoReceiptSubjectAndOnlyTheRecipientCanSign() {

        val sosBroadcast = EmergencyMessage(
            messageId = "m",
            senderId = idA,
            receiverId = null,
            messageType = MessageType.SOS,
            priority = MessagePriority.CRITICAL,
            content = "SOS",
            createdAt = 0L,
            expiresAt = 1L
        )

        assertNull(sosBroadcast.receiptSubject())

        // B holds a relay copy addressed to C; it must not be able to issue C's receipt through this API.
        assertThrows { DeliveryReceiptCodec.create(message, idB, keyB) }
    }


    // ---------------------------------------------------------
    // Forgery and tampering
    // ---------------------------------------------------------

    @Test
    fun relayForgingAReceiptWithItsOwnKeyIsRejectedEvenWhenItCopiesCsKeyPrefix() {

        // B signs C's receipt bytes with B's key: its key prefix gives it away.
        val forged = DeliveryReceiptCodec.create(message.copy(receiverId = idB), idB, keyB)
        val forgedForC = DeliveryReceiptCodec.decode(forged)!!

        assertEquals(ReceiptCheck.KEY_MISMATCH, DeliveryReceiptCodec.verify(forgedForC, message, idC, keyC.publicKey))

        // B also copies C's (public) key prefix: the signature still cannot verify under C's key.
        val signedByB = DeliveryReceiptCodec.signedBytes(message, EcdsaP256.fingerprint(keyC.publicKey))
        val disguised = WireReceipt(
            message.messageId,
            EcdsaP256.fingerprint(keyC.publicKey).copyOf(DeliveryReceiptCodec.KEY_PREFIX_BYTES),
            EcdsaP256.normalizeLowS(keyB.sign(signedByB))!!
        )

        assertEquals(ReceiptCheck.BAD_SIGNATURE, checkAtA(DeliveryReceiptCodec.encode(disguised)))
    }


    @Test
    fun everySingleBitFlipInAValidReceiptIsRejected() {

        val bytes = Base64.getDecoder().decode(DeliveryReceiptCodec.create(message, idC, keyC))

        for (index in bytes.indices) {
            for (bit in 0 until 8) {
                val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor (1 shl bit)).toByte() }
                val result = checkAtA(Base64.getEncoder().encodeToString(tampered))
                assertNotEquals("byte $index bit $bit", ReceiptCheck.VALID, result)
            }
        }
    }


    @Test
    fun receiptNeverConfirmsAMessageWhoseContentOrHeaderWasChangedOnTheWay() {

        // C signs what it received; A checks against what it sent. Any difference fails.
        val variants = listOf(
            message.copy(payload = message.payload + "!"),
            message.copy(senderId = idB),
            message.copy(messageType = MessageType.STATUS),
            message.copy(priority = MessagePriority.CRITICAL),
            message.copy(createdAt = message.createdAt + 1),
            message.copy(latitude = 28.6139, longitude = 77.2090)
        )

        for (received in variants) {
            val encoded = DeliveryReceiptCodec.create(received, idC, keyC)
            assertEquals(received.toString(), ReceiptCheck.BAD_SIGNATURE, checkAtA(encoded))
        }

        val located = message.copy(latitude = 28.6139, longitude = 77.2090)
        val moved = located.copy(longitude = 77.2091)
        assertEquals(ReceiptCheck.BAD_SIGNATURE, checkAtA(DeliveryReceiptCodec.create(moved, idC, keyC), original = located))
    }


    @Test
    fun rewritingTheMessageIdOfAValidReceiptDoesNotMakeItConfirmAnotherMessage() {

        val other = message.copy(messageId = "0a5b1c63-1e9f-4d66-8e54-4c9f2e1dab22")
        val valid = DeliveryReceiptCodec.decode(DeliveryReceiptCodec.create(message, idC, keyC))!!

        // Replayed against another message as-is.
        assertEquals(ReceiptCheck.NOT_THIS_MESSAGE, DeliveryReceiptCodec.verify(valid, other, idC, keyC.publicKey))

        // Replayed with its message id rewritten.
        val rewritten = WireReceipt(other.messageId, valid.keyPrefix, valid.signature)
        assertEquals(ReceiptCheck.BAD_SIGNATURE, DeliveryReceiptCodec.verify(rewritten, other, idC, keyC.publicKey))
    }


    // ---------------------------------------------------------
    // Keys and identity binding
    // ---------------------------------------------------------

    @Test
    fun keyMustBeValidAndVerifiedForTheMessagesRecipient() {

        val receipt = DeliveryReceiptCodec.decode(DeliveryReceiptCodec.create(message, idC, keyC))!!

        // A verified key for some other phone cannot vouch for C.
        assertEquals(ReceiptCheck.KEY_NOT_FOR_RECIPIENT, DeliveryReceiptCodec.verify(receipt, message, idB, keyC.publicKey))

        // A substituted key for C (e.g. swapped during discovery).
        assertEquals(ReceiptCheck.KEY_MISMATCH, DeliveryReceiptCodec.verify(receipt, message, idC, keyB.publicKey))

        // Not a usable P-256 key at all.
        assertEquals(ReceiptCheck.INVALID_KEY, DeliveryReceiptCodec.verify(receipt, message, idC, ByteArray(91)))
        assertEquals(
            ReceiptCheck.INVALID_KEY,
            DeliveryReceiptCodec.verify(receipt, message, idC, SoftwareReceiptSigner.generate("secp384r1").public.encoded)
        )
    }


    @Test
    fun signedBytesAreDomainSeparatedFromOtherSignatures() {

        val fingerprint = EcdsaP256.fingerprint(keyC.publicKey)
        val signedBytes = DeliveryReceiptCodec.signedBytes(message, fingerprint)

        assertTrue(String(signedBytes.copyOf(27), Charsets.US_ASCII) == "DisasterConnect/receipt/v1\u0000")

        // C's signature over the bare message digest (some other use of the key) is not a receipt.
        val digestSignature = EcdsaP256.normalizeLowS(keyC.sign(DeliveryReceiptCodec.messageDigest(message)))!!
        val misused = WireReceipt(message.messageId, fingerprint.copyOf(DeliveryReceiptCodec.KEY_PREFIX_BYTES), digestSignature)

        assertEquals(ReceiptCheck.BAD_SIGNATURE, DeliveryReceiptCodec.verify(misused, message, idC, keyC.publicKey))
    }


    // ---------------------------------------------------------
    // Replay, duplicates and receipt ids
    // ---------------------------------------------------------

    @Test
    fun sameReceiptVerifiesIdenticallyEveryTimeAndKeepsOneContentAddressedId() {

        val encoded = DeliveryReceiptCodec.create(message, idC, keyC)
        val id = DeliveryReceiptCodec.receiptMessageId(encoded)

        assertEquals(List(3) { ReceiptCheck.VALID }, List(3) { checkAtA(encoded) })
        assertEquals(id, DeliveryReceiptCodec.receiptMessageId(encoded))
        assertTrue(id.startsWith("r:") && id.length == 34)
        assertTrue(DeliveryReceiptCodec.hasConsistentId(id, encoded))
        assertTrue(id.toByteArray().size <= MessagePacketCodec.MAX_ID_BYTES)
    }


    @Test
    fun anyChangeToAReceiptChangesItsIdSoNobodyCanClaimTheRealIdFirst() {

        val encoded = DeliveryReceiptCodec.create(message, idC, keyC)
        val again = DeliveryReceiptCodec.create(message, idC, keyC)
        val bytes = Base64.getDecoder().decode(encoded)
        val altered = Base64.getEncoder().encodeToString(bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

        // ECDSA signatures are randomised: a second receipt for one message is valid but distinct.
        assertEquals(ReceiptCheck.VALID, checkAtA(again))
        assertNotEquals(DeliveryReceiptCodec.receiptMessageId(encoded), DeliveryReceiptCodec.receiptMessageId(again))

        assertFalse(DeliveryReceiptCodec.hasConsistentId(DeliveryReceiptCodec.receiptMessageId(encoded), altered))
    }


    // ---------------------------------------------------------
    // Malformed and oversized input
    // ---------------------------------------------------------

    @Test
    fun malformedTruncatedOversizedAndNonCanonicalReceiptsDecodeToNull() {

        val bytes = Base64.getDecoder().decode(DeliveryReceiptCodec.create(message, idC, keyC))
        fun encoded(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        val idEnd = 2 + bytes[1]
        val signatureLengthAt = idEnd + DeliveryReceiptCodec.KEY_PREFIX_BYTES

        assertNull(DeliveryReceiptCodec.decode(""))
        assertNull(DeliveryReceiptCodec.decode("not base64 at all!"))
        assertNull(DeliveryReceiptCodec.decode("A".repeat(DeliveryReceiptCodec.MAX_ENCODED_CHARS + 4)))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf(bytes.size - 1))))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes + 0.toByte())))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf().also { it[0] = 2 })))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf().also { it[1] = 0 })))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf().also { it[1] = 65 })))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf().also { it[signatureLengthAt] = 0 })))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf().also { it[signatureLengthAt] = 73 })))
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf(signatureLengthAt))))

        // Invalid UTF-8 in the message id.
        assertNull(DeliveryReceiptCodec.decode(encoded(bytes.copyOf().also { it[2] = 0xC3.toByte(); it[3] = 0x28 })))

        // The bytes are still valid: only the encodings above were broken.
        assertNotNull(DeliveryReceiptCodec.decode(encoded(bytes)))
    }


    @Test
    fun onlyCanonicalPaddedStandardBase64IsAccepted() {

        assertNotNull(DeliveryReceiptCodec.decodeCanonicalBase64("AA=="))

        // Same byte with non-zero unused bits, missing padding, whitespace and URL-safe characters.
        assertNull(DeliveryReceiptCodec.decodeCanonicalBase64("AB=="))
        assertNull(DeliveryReceiptCodec.decodeCanonicalBase64("AA"))
        assertNull(DeliveryReceiptCodec.decodeCanonicalBase64("AA==\n"))
        assertNull(DeliveryReceiptCodec.decodeCanonicalBase64("-_=="))
    }


    @Test
    fun highSVariantOfAValidReceiptIsDroppedAtDecodeSoRelaysCannotMultiplyIt() {

        val receipt = DeliveryReceiptCodec.decode(DeliveryReceiptCodec.create(message, idC, keyC))!!
        val (r, s) = EcdsaP256.parseSignature(receipt.signature)!!
        val twin = WireReceipt(receipt.originalMessageId, receipt.keyPrefix, EcdsaP256.encodeSignature(r, EcdsaP256.N - s))

        assertNull(DeliveryReceiptCodec.decode(DeliveryReceiptCodec.encode(twin)))
    }


    @Test
    fun messagesThatCouldNeverBeSentCannotBeConfirmed() {

        val tooLong = message.copy(payload = "x".repeat(MessagePacketCodec.MAX_PAYLOAD_BYTES + 1))
        val receipt = DeliveryReceiptCodec.decode(DeliveryReceiptCodec.create(message, idC, keyC))!!

        assertThrows { DeliveryReceiptCodec.create(tooLong, idC, keyC) }
        assertThrows { DeliveryReceiptCodec.create(message.copy(messageId = ""), idC, keyC) }
        assertEquals(ReceiptCheck.BAD_SIGNATURE, DeliveryReceiptCodec.verify(receipt, tooLong, idC, keyC.publicKey))
    }


    @Test
    fun misbehavingSignerIsRefusedInsteadOfProducingAnUnverifiableReceipt() {

        val garbageSigner = object : ReceiptSigner {
            override val publicKey = keyC.publicKey
            override fun sign(data: ByteArray) = ByteArray(70) { 1 }
        }
        val wrongKeySigner = object : ReceiptSigner {
            override val publicKey = keyC.publicKey
            override fun sign(data: ByteArray) = keyB.sign(data)
        }

        assertThrows { DeliveryReceiptCodec.create(message, idC, garbageSigner) }
        assertThrows { DeliveryReceiptCodec.create(message, idC, wrongKeySigner) }
    }


    private fun assertThrows(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }
}

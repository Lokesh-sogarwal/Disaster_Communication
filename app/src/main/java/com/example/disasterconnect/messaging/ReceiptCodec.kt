package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.EmergencyMessage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64


// =========================================================
// SIGNED DELIVERY RECEIPTS
// =========================================================

/**
 * The fields of a direct message that a receipt confirms. Hop count and remaining TTL change on
 * every hop, so they are left out; everything else is identical on the sender's row and on the
 * recipient's stored copy.
 */
data class ReceiptSubject(
    val messageId: String,
    val senderId: String,
    val receiverId: String,
    val messageType: String,
    val priority: String,
    val createdAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val payload: String
)

/** Null for broadcasts: only direct messages can be confirmed. */
fun EmergencyMessage.receiptSubject(): ReceiptSubject? = receiverId?.let {
    ReceiptSubject(messageId, senderId, it, messageType, priority, createdAt, latitude, longitude, content)
}

/** A decoded receipt. Nothing in it is trusted until [DeliveryReceiptCodec.verify] returns [ReceiptCheck.VALID]. */
class WireReceipt(
    val originalMessageId: String,
    // First bytes of the signer's key fingerprint, so a receipt from another key is told apart early.
    val keyPrefix: ByteArray,
    val signature: ByteArray
)

enum class ReceiptCheck {
    VALID,
    NOT_THIS_MESSAGE,
    KEY_NOT_FOR_RECIPIENT,
    INVALID_KEY,
    KEY_MISMATCH,
    BAD_SIGNATURE
}


/**
 * Receipt payload carried as Base64 text in a RECEIPT message (rules.md §13, §15):
 *
 * WIRE:   version, messageId (u8 length), keyPrefix(16), signature (u8 length, strict low-S DER)
 *
 * SIGNED: "DisasterConnect/receipt/v1" 0x00, version, kind, messageId, senderId, receiverId,
 *         keyFingerprint(32), messageDigest(32)
 *
 * DIGEST: SHA-256 of "DisasterConnect/message/v1" 0x00, messageId, senderId, receiverId, type,
 *         priority, createdAt(8), hasLocation(1), [lat bits(8), lng bits(8)], payload (u16 length)
 *
 * Strings are UTF-8 with a 1-byte length, as in [MessagePacketCodec]. The signed bytes are never
 * sent: the verifier rebuilds them from its own record, so a relay cannot change what is confirmed.
 */
object DeliveryReceiptCodec {

    const val VERSION: Byte = 1

    const val KIND_RECEIVED: Byte = 1

    const val KEY_PREFIX_BYTES = 16

    const val MAX_ENCODED_CHARS = 256

    private const val RECEIPT_ID_PREFIX = "r:"

    private const val RECEIPT_ID_HEX_CHARS = 32

    private val RECEIPT_DOMAIN = "DisasterConnect/receipt/v1".toByteArray(StandardCharsets.US_ASCII) + 0.toByte()

    private val MESSAGE_DOMAIN = "DisasterConnect/message/v1".toByteArray(StandardCharsets.US_ASCII) + 0.toByte()


    // ---------------------------------------------------------
    // Canonical bytes
    // ---------------------------------------------------------

    /** @throws IllegalArgumentException if the message could never have been sent on the wire. */
    fun messageDigest(subject: ReceiptSubject): ByteArray = EcdsaP256.sha256(canonical {
        write(MESSAGE_DOMAIN)
        writeShortString(subject.messageId)
        writeShortString(subject.senderId)
        writeShortString(subject.receiverId)
        writeShortString(subject.messageType)
        writeShortString(subject.priority)
        writeLong(subject.createdAt)
        val latitude = subject.latitude
        val longitude = subject.longitude
        if (latitude != null && longitude != null) {
            writeByte(1)
            writeLong(latitude.toBits())
            writeLong(longitude.toBits())
        } else writeByte(0)
        val payload = subject.payload.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MessagePacketCodec.MAX_PAYLOAD_BYTES) { "Message is too long." }
        writeShort(payload.size)
        write(payload)
    })

    /** The exact bytes the recipient signs. @throws IllegalArgumentException as [messageDigest]. */
    fun signedBytes(subject: ReceiptSubject, recipientKeyFingerprint: ByteArray): ByteArray {
        require(recipientKeyFingerprint.size == EcdsaP256.FINGERPRINT_BYTES) { "Invalid key fingerprint." }
        return canonical {
            write(RECEIPT_DOMAIN)
            writeByte(VERSION.toInt())
            writeByte(KIND_RECEIVED.toInt())
            writeShortString(subject.messageId)
            writeShortString(subject.senderId)
            writeShortString(subject.receiverId)
            write(recipientKeyFingerprint)
            write(messageDigest(subject))
        }
    }


    // ---------------------------------------------------------
    // Recipient side
    // ---------------------------------------------------------

    /**
     * Signs a receipt for a direct message this phone ([localNodeId]) received.
     * @throws IllegalArgumentException if the message is not addressed to this phone or the signer misbehaves.
     */
    fun create(subject: ReceiptSubject, localNodeId: String, signer: ReceiptSigner): String {
        require(subject.receiverId == localNodeId) { "Only the recipient can confirm a message." }
        val publicKey = signer.publicKey
        require(EcdsaP256.parsePublicKey(publicKey) != null) { "Signer key is not a P-256 key." }
        val fingerprint = EcdsaP256.fingerprint(publicKey)
        val data = signedBytes(subject, fingerprint)
        val signature = requireNotNull(EcdsaP256.normalizeLowS(signer.sign(data))) { "Signer returned an invalid signature." }
        require(EcdsaP256.verify(publicKey, data, signature)) { "Signer returned a signature that does not verify." }
        return encode(WireReceipt(subject.messageId, fingerprint.copyOf(KEY_PREFIX_BYTES), signature))
    }

    fun encode(receipt: WireReceipt): String {
        val messageId = receipt.originalMessageId.toByteArray(StandardCharsets.UTF_8)
        require(messageId.size in 1..MessagePacketCodec.MAX_ID_BYTES) { "Invalid identifier." }
        require(receipt.keyPrefix.size == KEY_PREFIX_BYTES) { "Invalid key prefix." }
        require(receipt.signature.size in EcdsaP256.MIN_SIGNATURE_BYTES..EcdsaP256.MAX_SIGNATURE_BYTES) { "Invalid signature." }
        val bytes = byteArrayOf(VERSION, messageId.size.toByte()) + messageId + receipt.keyPrefix +
            byteArrayOf(receipt.signature.size.toByte()) + receipt.signature
        return Base64.getEncoder().encodeToString(bytes)
    }


    // ---------------------------------------------------------
    // Relay and sender side
    // ---------------------------------------------------------

    /**
     * Returns null for anything malformed, non-canonical, oversized or carrying a non-strict or
     * high-S signature. Needs no keys, so relays can drop garbage too. Never throws.
     */
    fun decode(encoded: String): WireReceipt? {
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
        val bytes = decodeCanonicalBase64(encoded) ?: return null
        return try {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.get() != VERSION) return null
            val idLength = buffer.get().toInt() and 0xFF
            if (idLength !in 1..MessagePacketCodec.MAX_ID_BYTES) return null
            val idBytes = ByteArray(idLength).also { buffer.get(it) }
            val messageId = String(idBytes, StandardCharsets.UTF_8)
            if (!messageId.toByteArray(StandardCharsets.UTF_8).contentEquals(idBytes)) return null
            val keyPrefix = ByteArray(KEY_PREFIX_BYTES).also { buffer.get(it) }
            val signatureLength = buffer.get().toInt() and 0xFF
            if (signatureLength != buffer.remaining()) return null
            val signature = ByteArray(signatureLength).also { buffer.get(it) }
            if (!EcdsaP256.isStrictLowS(signature)) return null
            WireReceipt(messageId, keyPrefix, signature)
        } catch (_: BufferUnderflowException) {
            null
        }
    }

    /**
     * Checks [receipt] against the sender's own record of the message.
     *
     * [verifiedPublicKey] must be a key the user explicitly verified for [verifiedNodeId]
     * (rules.md §15). Never pass a key just because it arrived over BLE or matches a claimed node ID.
     */
    fun verify(
        receipt: WireReceipt,
        original: ReceiptSubject,
        verifiedNodeId: String,
        verifiedPublicKey: ByteArray,
        verifier: ReceiptVerifier = EcdsaP256
    ): ReceiptCheck {
        if (receipt.originalMessageId != original.messageId) return ReceiptCheck.NOT_THIS_MESSAGE
        if (verifiedNodeId != original.receiverId) return ReceiptCheck.KEY_NOT_FOR_RECIPIENT
        if (EcdsaP256.parsePublicKey(verifiedPublicKey) == null) return ReceiptCheck.INVALID_KEY
        val fingerprint = EcdsaP256.fingerprint(verifiedPublicKey)
        if (!fingerprint.copyOf(KEY_PREFIX_BYTES).contentEquals(receipt.keyPrefix)) return ReceiptCheck.KEY_MISMATCH
        val data = runCatching { signedBytes(original, fingerprint) }.getOrNull() ?: return ReceiptCheck.BAD_SIGNATURE
        return if (verifier.verify(verifiedPublicKey, data, receipt.signature)) ReceiptCheck.VALID else ReceiptCheck.BAD_SIGNATURE
    }


    // ---------------------------------------------------------
    // Receipt message ids
    // ---------------------------------------------------------

    /** Content-addressed id for the RECEIPT message, so nobody can claim a receipt's id ahead of the real one. */
    fun receiptMessageId(encoded: String): String =
        RECEIPT_ID_PREFIX + EcdsaP256.sha256(encoded.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(RECEIPT_ID_HEX_CHARS)

    fun hasConsistentId(messageId: String, encoded: String): Boolean = messageId == receiptMessageId(encoded)


    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    /** Standard padded Base64 only; any input that would not re-encode identically is rejected. */
    internal fun decodeCanonicalBase64(encoded: String): ByteArray? {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { Base64.getEncoder().encodeToString(it) == encoded }
    }

    private fun canonical(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()

    private fun DataOutputStream.writeShortString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MessagePacketCodec.MAX_ID_BYTES) { "Invalid identifier." }
        writeByte(bytes.size)
        write(bytes)
    }
}

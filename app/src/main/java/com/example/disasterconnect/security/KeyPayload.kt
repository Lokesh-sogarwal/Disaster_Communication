package com.example.disasterconnect.security

import com.example.disasterconnect.messaging.EcdsaP256
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64


// =========================================================
// KEY MESSAGE PAYLOAD
// =========================================================

/** A public key announced over a direct link. Untrusted until the users compare safety codes. */
class AnnouncedKey(
    val nodeId: String,
    val publicKey: ByteArray
)


/**
 * Payload of a KEY message, as Base64 text in the DataPacket payload (rules.md §13):
 *
 *   version(1) = 1, nodeId (u8 length, "DC-XXXXXX"), keyLength(1) = 91, publicKey(91), fingerprint(32)
 *
 * The fingerprint is SHA-256 of publicKey and must match; it catches corruption, not forgery.
 * Strict: canonical padded Base64, exact lengths, nothing trailing, key must be a valid P-256 point.
 */
object KeyPayloadCodec {

    const val VERSION: Byte = 1

    const val MAX_ENCODED_CHARS = 256

    fun encode(key: AnnouncedKey): String {
        val nodeId = key.nodeId.toByteArray(StandardCharsets.UTF_8)
        require(NODE_ID_PATTERN.matches(key.nodeId)) { "Invalid node ID." }
        require(EcdsaP256.parsePublicKey(key.publicKey) != null) { "Not a P-256 public key." }
        val bytes = byteArrayOf(VERSION, nodeId.size.toByte()) + nodeId +
            byteArrayOf(key.publicKey.size.toByte()) + key.publicKey + EcdsaP256.fingerprint(key.publicKey)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Null for anything malformed, non-canonical, oversized or with an invalid key. Never throws. */
    fun decode(encoded: String): AnnouncedKey? {
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (Base64.getEncoder().encodeToString(bytes) != encoded) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.get() != VERSION) return null
            val nodeIdLength = buffer.get().toInt() and 0xFF
            if (nodeIdLength != NODE_ID_BYTES) return null
            val nodeId = String(ByteArray(nodeIdLength).also { buffer.get(it) }, StandardCharsets.US_ASCII)
            if (!NODE_ID_PATTERN.matches(nodeId)) return null
            if ((buffer.get().toInt() and 0xFF) != EcdsaP256.PUBLIC_KEY_BYTES) return null
            val publicKey = ByteArray(EcdsaP256.PUBLIC_KEY_BYTES).also { buffer.get(it) }
            val fingerprint = ByteArray(EcdsaP256.FINGERPRINT_BYTES).also { buffer.get(it) }
            if (buffer.hasRemaining()) return null
            if (EcdsaP256.parsePublicKey(publicKey) == null) return null
            if (!EcdsaP256.fingerprint(publicKey).contentEquals(fingerprint)) return null
            AnnouncedKey(nodeId, publicKey)
        } catch (_: BufferUnderflowException) {
            null
        }
    }

    private const val NODE_ID_BYTES = 9

    private val NODE_ID_PATTERN = Regex("DC-[0-9A-F]{6}")
}


// =========================================================
// SAFETY CODE
// =========================================================

/**
 * 12-digit code both users compare out of band (rules.md §15). Each phone computes it locally from
 * both node IDs and both public keys:
 *
 *   party   = u8 length || nodeId (UTF-8) || publicKey (91 bytes)
 *   digest  = SHA-256("DisasterConnect/safety-code/v1" 0x00 || min(partyA, partyB) || max(partyA, partyB))
 *   code    = (first 8 digest bytes as unsigned 64-bit) mod 10^12, zero-padded, grouped "123 456 789 012"
 *
 * Parties are ordered by unsigned byte comparison, so both phones get the same code.
 * A substituted key or node ID on either side changes the code.
 */
object SafetyCode {

    const val DIGITS = 12

    private val DOMAIN = "DisasterConnect/safety-code/v1".toByteArray(StandardCharsets.US_ASCII) + 0.toByte()

    private const val MODULUS = 1_000_000_000_000L

    /** Null if either party is invalid or both are the same phone. */
    fun compute(localNodeId: String, localKey: ByteArray, peerNodeId: String, peerKey: ByteArray): String? {
        if (localNodeId == peerNodeId || localKey.contentEquals(peerKey)) return null
        val local = party(localNodeId, localKey) ?: return null
        val peer = party(peerNodeId, peerKey) ?: return null
        val (first, second) = if (compareUnsigned(local, peer) <= 0) local to peer else peer to local
        val digest = EcdsaP256.sha256(DOMAIN + first + second)
        val value = java.lang.Long.remainderUnsigned(ByteBuffer.wrap(digest, 0, 8).getLong(), MODULUS)
        return value.toString().padStart(DIGITS, '0').chunked(3).joinToString(" ")
    }

    private fun party(nodeId: String, key: ByteArray): ByteArray? {
        val id = nodeId.toByteArray(StandardCharsets.UTF_8)
        if (id.isEmpty() || id.size > 64 || EcdsaP256.parsePublicKey(key) == null) return null
        return byteArrayOf(id.size.toByte()) + id + key
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return a.size - b.size
    }
}

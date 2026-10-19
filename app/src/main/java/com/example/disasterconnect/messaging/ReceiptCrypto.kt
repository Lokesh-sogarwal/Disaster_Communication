package com.example.disasterconnect.messaging

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec


// =========================================================
// RECEIPT SIGNATURES (ECDSA P-256 / SHA-256)
// =========================================================

/** Signs receipts with this phone's identity key. The Android Keystore implementation comes in a later phase. */
interface ReceiptSigner {

    /** X.509 SubjectPublicKeyInfo of an uncompressed P-256 key (91 bytes). */
    val publicKey: ByteArray

    /** SHA256withECDSA over [data], DER-encoded. High-S output is normalised by the caller. */
    fun sign(data: ByteArray): ByteArray
}

interface ReceiptVerifier {

    /** False for anything invalid or malformed. Never throws. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}


/**
 * Strict ECDSA P-256 checks using only the platform's java.security (no extra dependencies).
 *
 * Keys must be the canonical 91-byte SPKI encoding of a point on P-256. Signatures must be
 * minimal DER with 1 ≤ r, s < n and low S, so a relay cannot make a second valid encoding.
 */
object EcdsaP256 : ReceiptVerifier {

    const val PUBLIC_KEY_BYTES = 91

    const val MIN_SIGNATURE_BYTES = 8

    const val MAX_SIGNATURE_BYTES = 72

    const val FINGERPRINT_BYTES = 32

    private const val ALGORITHM = "SHA256withECDSA"

    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = P - BigInteger.valueOf(3)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)

    /** Order of the P-256 group. */
    val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    private val HALF_N = N.shiftRight(1)


    // ---------------------------------------------------------
    // Keys
    // ---------------------------------------------------------

    /** SHA-256 of the SPKI bytes. Identifies a key; it is not a trust decision. */
    fun fingerprint(publicKey: ByteArray): ByteArray = sha256(publicKey)

    /** The key, only if [encoded] is a canonical uncompressed P-256 SPKI whose point lies on the curve. */
    fun parsePublicKey(encoded: ByteArray): ECPublicKey? {
        if (encoded.size != PUBLIC_KEY_BYTES) return null
        val key = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey
        }.getOrNull() ?: return null
        if (!isP256(key.params) || !key.encoded.contentEquals(encoded) || key.w == ECPoint.POINT_INFINITY) return null
        val x = key.w.affineX
        val y = key.w.affineY
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return null
        return key.takeIf { y.modPow(BigInteger.valueOf(2), P) == (x.pow(3) + A * x + B).mod(P) }
    }

    private fun isP256(params: ECParameterSpec): Boolean {
        val field = params.curve.field as? ECFieldFp ?: return false
        return field.p == P && params.curve.a == A && params.curve.b == B &&
            params.generator.affineX == GX && params.generator.affineY == GY &&
            params.order == N && params.cofactor == 1
    }


    // ---------------------------------------------------------
    // Signatures
    // ---------------------------------------------------------

    /** (r, s) from strict DER: SEQUENCE { INTEGER r, INTEGER s }, minimal lengths, 1 ≤ r, s < n, nothing trailing. */
    fun parseSignature(der: ByteArray): Pair<BigInteger, BigInteger>? {
        if (der.size !in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) return null
        if (der[0] != 0x30.toByte() || (der[1].toInt() and 0xFF) != der.size - 2) return null
        var offset = 2
        fun integer(): BigInteger? {
            if (offset + 2 > der.size || der[offset] != 0x02.toByte()) return null
            val length = der[offset + 1].toInt() and 0xFF
            val start = offset + 2
            if (length !in 1..33 || start + length > der.size) return null
            val first = der[start].toInt() and 0xFF
            // Negative, or a leading zero that is not needed for the sign bit.
            if (first and 0x80 != 0) return null
            if (first == 0 && (length == 1 || der[start + 1].toInt() and 0x80 == 0)) return null
            offset = start + length
            return BigInteger(1, der.copyOfRange(start, offset)).takeIf { it.signum() > 0 && it < N }
        }
        val r = integer() ?: return null
        val s = integer() ?: return null
        return if (offset == der.size) r to s else null
    }

    /** True for a strictly encoded signature with s ≤ n / 2. */
    fun isStrictLowS(der: ByteArray): Boolean = parseSignature(der)?.let { it.second <= HALF_N } ?: false

    /** The same signature with s replaced by n − s when needed, or null if [der] is not strict DER. */
    fun normalizeLowS(der: ByteArray): ByteArray? {
        val (r, s) = parseSignature(der) ?: return null
        return if (s <= HALF_N) der else encodeSignature(r, N - s)
    }

    fun encodeSignature(r: BigInteger, s: BigInteger): ByteArray {
        // toByteArray() is minimal two's complement, which is exactly DER for positive integers.
        val body = byteArrayOf(0x02, r.toByteArray().size.toByte()) + r.toByteArray() +
            byteArrayOf(0x02, s.toByteArray().size.toByte()) + s.toByteArray()
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val key = parsePublicKey(publicKey) ?: return false
        if (!isStrictLowS(signature)) return false
        return runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }


    // ---------------------------------------------------------
    // Hashing
    // ---------------------------------------------------------

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

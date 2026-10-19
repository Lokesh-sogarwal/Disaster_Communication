package com.example.disasterconnect.security

import com.example.disasterconnect.messaging.EcdsaP256
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint


// =========================================================
// CANONICAL P-256 PUBLIC KEY ENCODING
// =========================================================

// SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING (0 unused bits) }, then 0x04 || X || Y.
private val P256_SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d030107034200"
    .chunked(2)
    .map { it.toInt(16).toByte() }
    .toByteArray()

private const val COORDINATE_BYTES = 32


/**
 * The exact encoding [EcdsaP256] accepts, built from the key's point instead of trusting
 * the provider's own `encoded` form (the Android Keystore's could differ between devices).
 * Null if the key is not a valid P-256 key.
 */
fun canonicalP256PublicKey(key: ECPublicKey): ByteArray? {
    val point = key.w
    if (point == ECPoint.POINT_INFINITY) return null
    val x = fixedWidth(point.affineX) ?: return null
    val y = fixedWidth(point.affineY) ?: return null
    val encoded = P256_SPKI_PREFIX + 0x04.toByte() + x + y
    val parsed = EcdsaP256.parsePublicKey(encoded) ?: return null
    val sameCurve = key.params.order == EcdsaP256.N && key.params.curve == parsed.params.curve
    return encoded.takeIf { sameCurve && parsed.w == point }
}

/** Big-endian, left-padded to 32 bytes; null if negative or wider. */
internal fun fixedWidth(value: BigInteger): ByteArray? {
    if (value.signum() < 0) return null
    val bytes = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    if (bytes.size > COORDINATE_BYTES) return null
    return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
}

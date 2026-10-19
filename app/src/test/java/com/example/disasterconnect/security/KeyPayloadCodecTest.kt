package com.example.disasterconnect.security

import com.example.disasterconnect.messaging.EcdsaP256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64


class KeyPayloadCodecTest {

    private fun key(curve: String = "secp256r1"): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair().public.encoded

    private val publicKey = key()

    private val valid = KeyPayloadCodec.encode(AnnouncedKey("DC-A1B2C3", publicKey))

    private val bytes = Base64.getDecoder().decode(valid)

    private fun encoded(value: ByteArray) = Base64.getEncoder().encodeToString(value)

    // Offsets in the binary layout: version(1), idLength(1), id(9), keyLength(1), key(91), fingerprint(32).
    private val keyLengthAt = 11
    private val keyAt = 12
    private val fingerprintAt = keyAt + EcdsaP256.PUBLIC_KEY_BYTES


    @Test
    fun keyPayloadRoundTripsWithExactLayout() {

        val decoded = KeyPayloadCodec.decode(valid)!!

        assertEquals("DC-A1B2C3", decoded.nodeId)
        assertArrayEquals(publicKey, decoded.publicKey)
        assertEquals(1 + 1 + 9 + 1 + 91 + 32, bytes.size)
        assertEquals(KeyPayloadCodec.VERSION, bytes[0])
        assertArrayEquals(EcdsaP256.fingerprint(publicKey), bytes.copyOfRange(fingerprintAt, bytes.size))
        assertTrue(valid.length <= KeyPayloadCodec.MAX_ENCODED_CHARS)
    }


    @Test
    fun malformedTruncatedOversizedAndNonCanonicalPayloadsAreRejected() {

        assertNull(KeyPayloadCodec.decode(""))
        assertNull(KeyPayloadCodec.decode("not base64!"))
        assertNull(KeyPayloadCodec.decode("A".repeat(KeyPayloadCodec.MAX_ENCODED_CHARS + 4)))
        assertNull(KeyPayloadCodec.decode(valid.replace('+', '-').replace('/', '_').let { if (it == valid) "$valid=" else it }))
        assertNull(KeyPayloadCodec.decode("$valid\n"))
        assertNull(KeyPayloadCodec.decode(encoded(bytes.copyOf(bytes.size - 1))))
        assertNull(KeyPayloadCodec.decode(encoded(bytes + 0.toByte())))
        assertNull(KeyPayloadCodec.decode(encoded(bytes.copyOf().also { it[0] = 2 })))
        assertNull(KeyPayloadCodec.decode(encoded(bytes.copyOf().also { it[1] = 8 })))
        assertNull(KeyPayloadCodec.decode(encoded(bytes.copyOf().also { it[keyLengthAt] = 90 })))

        // Lower-case node ID: not the DC-XXXXXX format.
        assertNull(KeyPayloadCodec.decode(encoded(bytes.copyOf().also { it[5] = 'a'.code.toByte() })))

        assertNotNull(KeyPayloadCodec.decode(encoded(bytes)))
    }


    @Test
    fun invalidKeysAndFingerprintMismatchesAreRejected() {

        val offCurve = bytes.copyOf().also { it[fingerprintAt - 1] = (it[fingerprintAt - 1] + 1).toByte() }
        val wrongFingerprint = bytes.copyOf().also { it[bytes.size - 1] = (it[bytes.size - 1] + 1).toByte() }
        // A different valid key with the original fingerprint.
        val swappedKey = bytes.copyOf().also { key().copyInto(it, keyAt) }

        assertNull(KeyPayloadCodec.decode(encoded(offCurve)))
        assertNull(KeyPayloadCodec.decode(encoded(wrongFingerprint)))
        assertNull(KeyPayloadCodec.decode(encoded(swappedKey)))
    }


    @Test(expected = IllegalArgumentException::class)
    fun encodingRefusesNonP256Keys() {

        KeyPayloadCodec.encode(AnnouncedKey("DC-A1B2C3", key("secp384r1")))
    }


    @Test(expected = IllegalArgumentException::class)
    fun encodingRefusesInvalidNodeIds() {

        KeyPayloadCodec.encode(AnnouncedKey("someone", publicKey))
    }
}

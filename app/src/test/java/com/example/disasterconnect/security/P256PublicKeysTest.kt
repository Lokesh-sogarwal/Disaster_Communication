package com.example.disasterconnect.security

import com.example.disasterconnect.messaging.EcdsaP256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec


class P256PublicKeysTest {

    private fun generate(curve: String): ECPublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair().public as ECPublicKey


    @Test
    fun canonicalEncodingEqualsTheStandardSpkiAndIsAcceptedByEcdsaP256() {

        // Many keys, so coordinates with leading zero bytes (1 in 256 each) are almost surely included.
        repeat(400) {
            val key = generate("secp256r1")
            val canonical = canonicalP256PublicKey(key)

            assertNotNull(canonical)
            assertArrayEquals(key.encoded, canonical)
            assertNotNull(EcdsaP256.parsePublicKey(canonical!!))
        }
    }


    @Test
    fun keysOnOtherCurvesHaveNoCanonicalP256Encoding() {

        assertNull(canonicalP256PublicKey(generate("secp384r1")))
    }


    @Test
    fun coordinatesArePaddedToExactly32BytesAndOversizedOnesRejected() {

        assertArrayEquals(ByteArray(31) + 1.toByte(), fixedWidth(BigInteger.ONE))
        assertArrayEquals(ByteArray(32), fixedWidth(BigInteger.ZERO))

        val top = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        assertEquals(32, fixedWidth(top)!!.size)

        assertNull(fixedWidth(BigInteger.ONE.shiftLeft(256)))
        assertNull(fixedWidth(BigInteger.ONE.negate()))
    }
}

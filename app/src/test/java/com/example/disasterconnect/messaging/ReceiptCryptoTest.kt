package com.example.disasterconnect.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Signature


class ReceiptCryptoTest {

    private val signer = SoftwareReceiptSigner()

    private val data = "receipt bytes".toByteArray()

    private fun lowSSignature(): ByteArray = EcdsaP256.normalizeLowS(signer.sign(data))!!


    // ---------------------------------------------------------
    // Keys
    // ---------------------------------------------------------

    @Test
    fun generatedP256KeyIsAcceptedAndFingerprintIsSha256OfItsEncoding() {

        assertEquals(EcdsaP256.PUBLIC_KEY_BYTES, signer.publicKey.size)
        assertNotNull(EcdsaP256.parsePublicKey(signer.publicKey))

        assertArrayEquals(EcdsaP256.sha256(signer.publicKey), EcdsaP256.fingerprint(signer.publicKey))
        assertEquals(EcdsaP256.FINGERPRINT_BYTES, EcdsaP256.fingerprint(signer.publicKey).size)
    }


    @Test
    fun keysOfOtherCurvesWrongLengthsGarbageAndPointsOffTheCurveAreRejected() {

        val p384 = SoftwareReceiptSigner.generate("secp384r1").public.encoded
        val offCurve = signer.publicKey.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertNull(EcdsaP256.parsePublicKey(p384))
        assertNull(EcdsaP256.parsePublicKey(offCurve))
        assertNull(EcdsaP256.parsePublicKey(ByteArray(0)))
        assertNull(EcdsaP256.parsePublicKey(ByteArray(EcdsaP256.PUBLIC_KEY_BYTES)))
        assertNull(EcdsaP256.parsePublicKey(signer.publicKey.copyOf(EcdsaP256.PUBLIC_KEY_BYTES - 1)))
        assertNull(EcdsaP256.parsePublicKey(signer.publicKey + 0.toByte()))

        assertFalse(EcdsaP256.verify(offCurve, data, lowSSignature()))
        assertFalse(EcdsaP256.verify(p384, data, lowSSignature()))
    }


    // ---------------------------------------------------------
    // Signatures
    // ---------------------------------------------------------

    @Test
    fun validLowSSignatureVerifiesOnlyForTheSignedBytesAndKey() {

        val signature = lowSSignature()

        assertTrue(EcdsaP256.verify(signer.publicKey, data, signature))
        assertFalse(EcdsaP256.verify(signer.publicKey, "other bytes".toByteArray(), signature))
        assertFalse(EcdsaP256.verify(SoftwareReceiptSigner().publicKey, data, signature))
    }


    @Test
    fun highSTwinOfAValidSignatureIsRejectedEvenThoughThePlatformWouldAcceptIt() {

        val signature = lowSSignature()
        val (r, s) = EcdsaP256.parseSignature(signature)!!
        val highS = EcdsaP256.encodeSignature(r, EcdsaP256.N - s)

        // Proves the twin is a mathematically valid signature, so the test is meaningful.
        val platformAccepts = Signature.getInstance("SHA256withECDSA").run {
            initVerify(EcdsaP256.parsePublicKey(signer.publicKey))
            update(data)
            verify(highS)
        }

        assertTrue(platformAccepts)
        assertFalse(EcdsaP256.isStrictLowS(highS))
        assertFalse(EcdsaP256.verify(signer.publicKey, data, highS))
        assertArrayEquals(signature, EcdsaP256.normalizeLowS(highS))
    }


    @Test
    fun nonStrictDerEncodingsAreRejected() {

        val (r, s) = EcdsaP256.parseSignature(lowSSignature())!!
        val valid = EcdsaP256.encodeSignature(r, s)

        // Same integers with an extra unnecessary leading zero on r.
        val rBytes = byteArrayOf(0) + r.toByteArray()
        val sBytes = s.toByteArray()
        val body = byteArrayOf(0x02, rBytes.size.toByte()) + rBytes + byteArrayOf(0x02, sBytes.size.toByte()) + sBytes
        val nonMinimal = byteArrayOf(0x30, body.size.toByte()) + body

        assertTrue(EcdsaP256.isStrictLowS(valid))
        assertNull(EcdsaP256.parseSignature(nonMinimal))
        assertNull(EcdsaP256.parseSignature(valid + 0.toByte()))
        assertNull(EcdsaP256.parseSignature(valid.copyOf(valid.size - 1)))
        assertNull(EcdsaP256.parseSignature(valid.copyOf().also { it[0] = 0x31 }))
        assertNull(EcdsaP256.parseSignature(valid.copyOf().also { it[2] = 0x03 }))
        assertNull(EcdsaP256.parseSignature(EcdsaP256.encodeSignature(BigInteger.ZERO, s)))
        assertNull(EcdsaP256.parseSignature(EcdsaP256.encodeSignature(r, EcdsaP256.N)))
        assertNull(EcdsaP256.parseSignature(EcdsaP256.encodeSignature(r.negate(), s)))
        assertNull(EcdsaP256.parseSignature(ByteArray(0)))
        assertNull(EcdsaP256.parseSignature(ByteArray(EcdsaP256.MAX_SIGNATURE_BYTES + 1)))

        assertFalse(EcdsaP256.verify(signer.publicKey, data, nonMinimal))
    }


    @Test
    fun verifyNeverThrowsOnGarbage() {

        assertFalse(EcdsaP256.verify(ByteArray(0), ByteArray(0), ByteArray(0)))
        assertFalse(EcdsaP256.verify(signer.publicKey, data, ByteArray(70) { 0x30 }))
        assertFalse(EcdsaP256.verify(ByteArray(EcdsaP256.PUBLIC_KEY_BYTES) { 0x30 }, data, lowSSignature()))
    }
}

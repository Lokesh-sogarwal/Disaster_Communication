package com.example.disasterconnect.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec


class SafetyCodeTest {

    private fun key(): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

    private val idA = "DC-AAAAAA"
    private val idB = "DC-BBBBBB"
    private val keyA = key()
    private val keyB = key()


    @Test
    fun bothPhonesComputeTheSameCodeDespiteReversedLocalAndRemoteOrder() {

        val onA = SafetyCode.compute(idA, keyA, idB, keyB)
        val onB = SafetyCode.compute(idB, keyB, idA, keyA)

        assertEquals(onA, onB)
        assertTrue(onA!!.matches(Regex("\\d{3} \\d{3} \\d{3} \\d{3}")))
    }


    @Test
    fun differentKeysGiveDifferentCodes() {

        val codes = (1..50).map { SafetyCode.compute(idA, keyA, idB, key()) }.toSet()

        assertEquals(50, codes.size)
    }


    @Test
    fun substitutedKeyOrNodeIdChangesTheCode() {

        val genuine = SafetyCode.compute(idA, keyA, idB, keyB)

        // A man in the middle hands each phone its own key instead of the real one.
        assertNotEquals(genuine, SafetyCode.compute(idA, keyA, idB, key()))
        assertNotEquals(genuine, SafetyCode.compute(idA, key(), idB, keyB))

        // The same keys bound to other node IDs, or with the node IDs swapped, are a different code.
        assertNotEquals(genuine, SafetyCode.compute(idA, keyA, "DC-CCCCCC", keyB))
        assertNotEquals(genuine, SafetyCode.compute(idB, keyA, idA, keyB))
    }


    @Test
    fun codeIsDeterministicAndRejectsSamePhoneOrInvalidKeys() {

        assertEquals(SafetyCode.compute(idA, keyA, idB, keyB), SafetyCode.compute(idA, keyA.copyOf(), idB, keyB.copyOf()))

        assertNull(SafetyCode.compute(idA, keyA, idA, keyB))
        assertNull(SafetyCode.compute(idA, keyA, idB, keyA))
        assertNull(SafetyCode.compute(idA, keyA, idB, ByteArray(91)))
        assertNull(SafetyCode.compute("", keyA, idB, keyB))
    }
}

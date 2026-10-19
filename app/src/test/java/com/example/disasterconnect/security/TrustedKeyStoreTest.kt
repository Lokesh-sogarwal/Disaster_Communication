package com.example.disasterconnect.security

import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.data.TrustedKey
import com.example.disasterconnect.data.TrustedKeyDao
import com.example.disasterconnect.messaging.EcdsaP256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec


class TrustedKeyStoreTest {

    private val records = FakeTrustedKeyRecords()

    private val store = TrustedKeyStore(records)

    private val nodeC = "DC-CCCCCC"

    private fun p256Key(): ByteArray = key("secp256r1")

    private fun key(curve: String): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair().public.encoded


    // ---------------------------------------------------------
    // Storing keys
    // ---------------------------------------------------------

    @Test
    fun newKeyIsStoredAsSeenWithItsSha256FingerprintAndNoVerificationTime() = runBlocking {

        val key = p256Key()

        val result = store.recordSeen(nodeC, key, now = 1_000L)

        assertTrue(result is SeenKeyResult.Stored)
        val stored = store.find(nodeC)!!
        assertEquals(TrustState.SEEN, stored.trust)
        assertNull(stored.verifiedAt)
        assertEquals(1_000L, stored.firstSeenAt)
        assertArrayEquals(key, stored.publicKey)
        assertArrayEquals(EcdsaP256.sha256(key), stored.fingerprint)
    }


    @Test
    fun seeingTheSameKeyAgainChangesNothingAndItStaysSeen() = runBlocking {

        val key = p256Key()
        store.recordSeen(nodeC, key, now = 1_000L)

        val again = store.recordSeen(nodeC, key.copyOf(), now = 9_000L)

        assertTrue(again is SeenKeyResult.AlreadyStored)
        assertEquals(TrustState.SEEN, store.find(nodeC)!!.trust)
        assertEquals(1_000L, store.find(nodeC)!!.firstSeenAt)
    }


    @Test
    fun differentKeyForAKnownNodeIdIsReportedAsConflictAndNeverReplacesTheStoredKey() = runBlocking {

        val original = p256Key()
        val impostor = p256Key()
        store.recordSeen(nodeC, original, now = 1_000L)

        val result = store.recordSeen(nodeC, impostor, now = 2_000L)

        assertTrue(result is SeenKeyResult.Conflict)
        assertArrayEquals(original, (result as SeenKeyResult.Conflict).existing.publicKey)
        assertArrayEquals(original, store.find(nodeC)!!.publicKey)
    }


    @Test
    fun callerChangingItsArrayAfterwardsDoesNotChangeTheStoredKey() = runBlocking {

        val key = p256Key()
        val expected = key.copyOf()
        store.recordSeen(nodeC, key, now = 0L)

        key.fill(0)

        assertArrayEquals(expected, store.find(nodeC)!!.publicKey)
    }


    // ---------------------------------------------------------
    // Validation
    // ---------------------------------------------------------

    @Test
    fun malformedKeysAndNodeIdsAreRejectedAndNothingIsStored() = runBlocking {

        val valid = p256Key()
        val offCurve = valid.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertSame(SeenKeyResult.InvalidKey, store.recordSeen(nodeC, ByteArray(0), now = 0L))
        assertSame(SeenKeyResult.InvalidKey, store.recordSeen(nodeC, ByteArray(EcdsaP256.PUBLIC_KEY_BYTES), now = 0L))
        assertSame(SeenKeyResult.InvalidKey, store.recordSeen(nodeC, key("secp384r1"), now = 0L))
        assertSame(SeenKeyResult.InvalidKey, store.recordSeen(nodeC, offCurve, now = 0L))
        assertSame(SeenKeyResult.InvalidKey, store.recordSeen(nodeC, valid + 0.toByte(), now = 0L))

        assertSame(SeenKeyResult.InvalidNodeId, store.recordSeen("DC-cccccc", valid, now = 0L))
        assertSame(SeenKeyResult.InvalidNodeId, store.recordSeen("C", valid, now = 0L))
        assertSame(SeenKeyResult.InvalidNodeId, store.recordSeen("DC-CCCCCC0", valid, now = 0L))

        assertTrue(records.rows.isEmpty())
    }


    @Test
    fun storedRowsWithInconsistentFingerprintKeyOrTrustAreNeverReturned() = runBlocking {

        val key = p256Key()
        val fingerprint = EcdsaP256.sha256(key)
        val wrongFingerprint = fingerprint.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val corrupted = listOf(
            TrustedKey("DC-000001", key, wrongFingerprint, TrustState.SEEN, 0L, null),
            TrustedKey("DC-000002", ByteArray(EcdsaP256.PUBLIC_KEY_BYTES), fingerprint, TrustState.SEEN, 0L, null),
            TrustedKey("DC-000003", key, fingerprint, "TRUSTED", 0L, null),
            TrustedKey("DC-000004", key, fingerprint, TrustState.VERIFIED, 0L, null),
            TrustedKey("DC-000005", key, fingerprint, TrustState.SEEN, 0L, 5L)
        )
        corrupted.forEach { records.rows[it.nodeId] = it }

        corrupted.forEach { assertNull(it.nodeId, store.find(it.nodeId)) }

        // And a corrupted row is never silently overwritten either.
        assertTrue(store.recordSeen("DC-000001", key, now = 1L) is SeenKeyResult.Conflict)
        assertArrayEquals(wrongFingerprint, records.rows.getValue("DC-000001").fingerprint)
    }


    // ---------------------------------------------------------
    // Trust transitions
    // ---------------------------------------------------------

    @Test
    fun seeingKeysNeverVerifiesThemAndConfirmVerifiedIsTheOnlyTransition() = runBlocking {

        repeat(5) { store.recordSeen("DC-00000$it", p256Key(), now = it.toLong()) }
        repeat(5) { store.recordSeen("DC-00000$it", records.rows.getValue("DC-00000$it").publicKey, now = 99L) }

        assertTrue(records.rows.values.all { it.trust == TrustState.SEEN && it.verifiedAt == null })

        // Public operations: record a SEEN key, read one, and the user's explicit confirmation.
        fun publicMethods(type: Class<*>) =
            type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()

        assertEquals(setOf("recordSeen", "find", "confirmVerified"), publicMethods(TrustedKeyStore::class.java))
        assertEquals(setOf("insertSeen", "find", "markVerified", "observeAll"), publicMethods(TrustedKeyDao::class.java))
        assertEquals(setOf("insertSeen", "find", "markVerified"), publicMethods(TrustedKeyRecords::class.java))
    }


    @Test
    fun confirmationVerifiesOnlyTheMatchingSeenRecord() = runBlocking {

        val key = p256Key()
        store.recordSeen(nodeC, key, now = 1_000L)

        val result = store.confirmVerified(nodeC, EcdsaP256.fingerprint(key), now = 5_000L)

        assertTrue(result is VerifyResult.Verified)
        val stored = store.find(nodeC)!!
        assertEquals(TrustState.VERIFIED, stored.trust)
        assertEquals(5_000L, stored.verifiedAt)
        assertArrayEquals(key, stored.publicKey)
        assertEquals(1_000L, stored.firstSeenAt)
    }


    @Test
    fun confirmationWithAnotherFingerprintOrForAnUnknownNodeChangesNothing() = runBlocking {

        val key = p256Key()
        store.recordSeen(nodeC, key, now = 1_000L)

        assertSame(VerifyResult.KeyMismatch, store.confirmVerified(nodeC, EcdsaP256.fingerprint(p256Key()), now = 2L))
        assertSame(VerifyResult.KeyMismatch, store.confirmVerified(nodeC, ByteArray(32), now = 2L))
        assertSame(VerifyResult.NotFound, store.confirmVerified("DC-DDDDDD", EcdsaP256.fingerprint(key), now = 2L))

        assertEquals(TrustState.SEEN, store.find(nodeC)!!.trust)
        assertNull(store.find(nodeC)!!.verifiedAt)
    }


    @Test
    fun verifiedKeyStaysVerifiedAndUnchangedWhenSeenOrConfirmedAgain() = runBlocking {

        val key = p256Key()
        store.recordSeen(nodeC, key, now = 1_000L)
        store.confirmVerified(nodeC, EcdsaP256.fingerprint(key), now = 5_000L)

        val seenAgain = store.recordSeen(nodeC, key.copyOf(), now = 9_000L)
        val confirmedAgain = store.confirmVerified(nodeC, EcdsaP256.fingerprint(key), now = 9_000L)

        assertTrue(seenAgain is SeenKeyResult.AlreadyStored)
        assertTrue(confirmedAgain is VerifyResult.AlreadyVerified)
        assertEquals(TrustState.VERIFIED, store.find(nodeC)!!.trust)
        assertEquals(5_000L, store.find(nodeC)!!.verifiedAt)
    }


    @Test
    fun differentKeyNeverReplacesAVerifiedKey() = runBlocking {

        val key = p256Key()
        store.recordSeen(nodeC, key, now = 1_000L)
        store.confirmVerified(nodeC, EcdsaP256.fingerprint(key), now = 5_000L)
        val impostor = p256Key()

        assertTrue(store.recordSeen(nodeC, impostor, now = 6_000L) is SeenKeyResult.Conflict)
        assertSame(VerifyResult.KeyMismatch, store.confirmVerified(nodeC, EcdsaP256.fingerprint(impostor), now = 7_000L))

        val stored = store.find(nodeC)!!
        assertArrayEquals(key, stored.publicKey)
        assertEquals(TrustState.VERIFIED, stored.trust)
        assertEquals(5_000L, stored.verifiedAt)
    }


    @Test
    fun corruptedRecordIsNeverVerified() = runBlocking {

        val key = p256Key()
        records.rows[nodeC] = TrustedKey(nodeC, key, ByteArray(32), TrustState.SEEN, 0L, null)

        assertSame(VerifyResult.InvalidRecord, store.confirmVerified(nodeC, ByteArray(32), now = 1L))
        assertEquals(TrustState.SEEN, records.rows.getValue(nodeC).trust)
    }
}

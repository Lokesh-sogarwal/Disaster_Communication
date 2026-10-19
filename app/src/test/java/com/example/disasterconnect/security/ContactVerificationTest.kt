package com.example.disasterconnect.security

import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.data.TrustedKey
import com.example.disasterconnect.messaging.EcdsaP256
import com.example.disasterconnect.messaging.ReceiptSigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec


class ContactVerificationTest {

    private fun key(): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

    private val localId = "DC-AAAAAA"
    private val peerId = "DC-BBBBBB"
    private val localKey = key()
    private val peerKey = key()

    private val ready = IdentityStatus.Ready(
        signer = object : ReceiptSigner {
            override val publicKey = localKey
            override fun sign(data: ByteArray) = ByteArray(0)
        },
        fingerprint = EcdsaP256.fingerprint(localKey),
        created = false,
        storage = KeyStorage.UNKNOWN
    )

    private fun record(key: ByteArray, trust: String = TrustState.SEEN) =
        TrustedKey(peerId, key, EcdsaP256.fingerprint(key), trust, 0L, if (trust == TrustState.VERIFIED) 9L else null)

    private fun view(
        identity: IdentityStatus? = ready,
        linked: Boolean = true,
        linkKey: ByteArray? = peerKey,
        stored: TrustedKey? = record(peerKey),
        conflict: Boolean = false
    ) = verificationView(identity, localId, localKey, peerId, linked, linkKey, stored, conflict)


    @Test
    fun codeIsShownOnlyWhenTheLinkKeyIsExactlyTheStoredRecord() {

        val compare = view() as VerificationView.Compare

        assertEquals(SafetyCode.compute(localId, localKey, peerId, peerKey), compare.code)
        assertArrayEquals(EcdsaP256.fingerprint(peerKey), compare.fingerprint)
        assertEquals(false, compare.verified)

        // The stored record holds another key: never compare against the link key.
        assertSame(VerificationView.KeyChanged, view(stored = record(key())))
        assertSame(VerificationView.KeyChanged, view(conflict = true))
    }


    @Test
    fun noCodeWithoutALinkAKeyOrAUsableIdentity() {

        assertSame(VerificationView.NotConnected, view(linked = false))
        assertSame(VerificationView.WaitingForKey, view(linkKey = null))
        assertSame(VerificationView.WaitingForKey, view(stored = null))
        assertTrue(view(identity = null) is VerificationView.IdentityProblem)
        assertTrue(view(identity = IdentityStatus.Lost) is VerificationView.IdentityProblem)
        assertTrue(view(identity = IdentityStatus.Unavailable("x")) is VerificationView.IdentityProblem)
    }


    @Test
    fun statusLabelsFollowTheStoredRecordAndConflicts() {

        assertEquals(KeyTrustStatus.VERIFICATION_REQUIRED, keyTrustStatus(peerId, emptyList(), emptySet()))
        assertEquals(KeyTrustStatus.NOT_VERIFIED, keyTrustStatus(peerId, listOf(record(peerKey)), emptySet()))
        assertEquals(KeyTrustStatus.VERIFIED, keyTrustStatus(peerId, listOf(record(peerKey, TrustState.VERIFIED)), emptySet()))
        assertEquals(KeyTrustStatus.KEY_CHANGED, keyTrustStatus(peerId, listOf(record(peerKey, TrustState.VERIFIED)), setOf(peerId)))

        // A corrupted record never shows as verified.
        val corrupted = TrustedKey(peerId, peerKey, ByteArray(32), TrustState.VERIFIED, 0L, 9L)
        assertEquals(KeyTrustStatus.VERIFICATION_REQUIRED, keyTrustStatus(peerId, listOf(corrupted), emptySet()))

        assertEquals(
            listOf("Verification required", "Not verified", "Verified", "Key changed"),
            KeyTrustStatus.entries.map { it.label }
        )
        assertTrue((view(stored = record(peerKey, TrustState.VERIFIED)) as VerificationView.Compare).verified)
    }
}

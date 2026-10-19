package com.example.disasterconnect.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.messaging.DeliveryReceiptCodec
import com.example.disasterconnect.messaging.EcdsaP256
import com.example.disasterconnect.messaging.ReceiptCheck
import com.example.disasterconnect.messaging.ReceiptSubject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.RSAKeyGenParameterSpec


/**
 * Real Android Keystore. Uses a test alias and preferences file, so the app's own
 * identity (dc_identity_v1) is never touched. Only the tests delete keys.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val alias = "dc_identity_test"

    private val preferences = "dc_identity_test_prefs"

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun identity() = DeviceIdentity(context, alias, preferences)

    private fun ready(status: IdentityStatus): IdentityStatus.Ready {
        assertTrue("expected Ready, got $status", status is IdentityStatus.Ready)
        return status as IdentityStatus.Ready
    }

    @After
    fun cleanUp() {
        keyStore.deleteEntry(alias)
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
    }


    // ---------------------------------------------------------
    // Creation and persistence
    // ---------------------------------------------------------

    @Test
    fun keyIsCreatedOnceAndTheSameKeyIsReturnedOnEveryLaterAccess() {

        assertSame(IdentityStatus.Missing, identity().load())
        assertFalse(keyStore.containsAlias(alias))

        val first = ready(identity().loadOrCreate())
        val second = ready(identity().loadOrCreate())
        val third = ready(DeviceIdentity(context, alias, preferences).load())

        assertTrue(first.created)
        assertFalse(second.created)
        assertFalse(third.created)
        assertArrayEquals(first.fingerprint, second.fingerprint)
        assertArrayEquals(first.fingerprint, third.fingerprint)
        assertArrayEquals(first.signer.publicKey, third.signer.publicKey)
    }


    @Test
    fun publicKeyIsTheExactEncodingEcdsaP256ExpectsAndMatchesTheKeystoreKey() {

        val identity = ready(identity().loadOrCreate())
        val publicKey = identity.signer.publicKey
        val keystoreKey = keyStore.getCertificate(alias).publicKey as ECPublicKey

        assertEquals(EcdsaP256.PUBLIC_KEY_BYTES, publicKey.size)
        assertEquals(keystoreKey.w, EcdsaP256.parsePublicKey(publicKey)!!.w)
        assertArrayEquals(EcdsaP256.fingerprint(publicKey), identity.fingerprint)
    }


    @Test
    fun keystoreProvidersOwnPublicKeyEncodingOnThisDevice() {

        // Records what this device's Keystore returns; the app never relies on it (it re-encodes the point).
        val identity = ready(identity().loadOrCreate())

        assertArrayEquals(identity.signer.publicKey, keyStore.getCertificate(alias).publicKey.encoded)
    }


    // ---------------------------------------------------------
    // Signing
    // ---------------------------------------------------------

    @Test
    fun keystoreSignaturesVerifyWithEcdsaP256AndProduceValidReceipts() {

        val identity = ready(identity().loadOrCreate())
        val data = "keystore signing check".toByteArray()

        val signature = EcdsaP256.normalizeLowS(identity.signer.sign(data))!!
        assertTrue(EcdsaP256.verify(identity.signer.publicKey, data, signature))
        assertFalse(EcdsaP256.verify(identity.signer.publicKey, "other".toByteArray(), signature))

        val message = ReceiptSubject(
            messageId = "6f1c2a52-0d8e-4c55-9d43-3b8f1d0c9a11",
            senderId = "DC-AAAAAA",
            receiverId = "DC-CCCCCC",
            messageType = MessageType.TEXT,
            priority = MessagePriority.NORMAL,
            createdAt = 1_760_000_000_000L,
            latitude = null,
            longitude = null,
            payload = "Are you safe?"
        )
        val receipt = DeliveryReceiptCodec.decode(DeliveryReceiptCodec.create(message, "DC-CCCCCC", identity.signer))!!

        assertEquals(
            ReceiptCheck.VALID,
            DeliveryReceiptCodec.verify(receipt, message, "DC-CCCCCC", identity.signer.publicKey)
        )
    }


    @Test
    fun privateKeyIsNotExportableAndSigningNeedsNoUserAuthentication() {

        ready(identity().loadOrCreate())
        val privateKey = (keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey
        val info = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore").getKeySpec(privateKey, KeyInfo::class.java)

        assertNull(privateKey.encoded)
        assertFalse(info.isUserAuthenticationRequired)
        assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes and KeyProperties.PURPOSE_SIGN)
        assertTrue(KeyProperties.DIGEST_SHA256 in info.digests)
    }


    // ---------------------------------------------------------
    // Loss and incompatibility: never silently replaced
    // ---------------------------------------------------------

    @Test
    fun lostKeyIsReportedAndOnlyTheExplicitReplacementCreatesANewOne() {

        val original = ready(identity().loadOrCreate())
        keyStore.deleteEntry(alias)

        assertSame(IdentityStatus.Lost, identity().loadOrCreate())
        assertSame(IdentityStatus.Lost, identity().load())
        assertFalse(keyStore.containsAlias(alias))

        val replacement = ready(identity().replaceLostIdentity())

        assertTrue(replacement.created)
        assertNotEquals(original.fingerprint.toList(), replacement.fingerprint.toList())
        assertArrayEquals(replacement.fingerprint, ready(identity().load()).fingerprint)
    }


    @Test
    fun explicitReplacementNeverTouchesAnExistingKey() {

        val original = ready(identity().loadOrCreate())

        val result = ready(identity().replaceLostIdentity())

        assertFalse(result.created)
        assertArrayEquals(original.fingerprint, result.fingerprint)
    }


    @Test
    fun keyThatDoesNotMatchTheRecordedIdentityIsRefusedAndLeftInPlace() {

        ready(identity().loadOrCreate())
        val keyBefore = keyStore.getCertificate(alias).publicKey.encoded
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit()
            .putString("identity_key_fingerprint", "00".repeat(32)).commit()

        assertTrue(identity().loadOrCreate() is IdentityStatus.Incompatible)
        assertArrayEquals(keyBefore, keyStore.getCertificate(alias).publicKey.encoded)
    }


    @Test
    fun nonP256KeyUnderTheAliasIsIncompatibleAndNeverOverwritten() {

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
            )
            generateKeyPair()
        }
        val keyBefore = keyStore.getCertificate(alias).publicKey.encoded

        assertTrue(identity().loadOrCreate() is IdentityStatus.Incompatible)
        assertTrue(identity().replaceLostIdentity() is IdentityStatus.Incompatible)
        assertArrayEquals(keyBefore, keyStore.getCertificate(alias).publicKey.encoded)
    }
}

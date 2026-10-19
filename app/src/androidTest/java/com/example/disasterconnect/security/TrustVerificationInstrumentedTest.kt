package com.example.disasterconnect.security

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.messaging.EcdsaP256
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec


/** Real SQLite and the real Android Keystore. Test-only database name and key alias. */
@RunWith(AndroidJUnit4::class)
class TrustVerificationInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val name = "trust-verification-test.db"

    private val alias = "dc_identity_trust_test"

    private val preferences = "dc_identity_trust_test_prefs"

    private var database: AppDatabase? = null

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { database = it }

    private fun store(db: AppDatabase) = TrustedKeyStore(RoomTrustedKeyRecords(db.trustedKeyDao()))

    private fun key(): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
    }


    @Test
    fun atomicUpdateVerifiesOnlyTheMatchingSeenRowAndPersistsAcrossReopen() = runBlocking {

        val peerKey = key()
        val otherKey = key()
        var db = open()
        store(db).recordSeen("DC-BBBBBB", peerKey, now = 1L)
        store(db).recordSeen("DC-CCCCCC", otherKey, now = 1L)

        // Wrong fingerprint, then the right one.
        assertEquals(0, db.trustedKeyDao().markVerified("DC-BBBBBB", EcdsaP256.fingerprint(otherKey), 5L))
        assertTrue(store(db).confirmVerified("DC-BBBBBB", EcdsaP256.fingerprint(peerKey), now = 7L) is VerifyResult.Verified)
        // A second update finds no SEEN row: already verified rows are never rewritten.
        assertEquals(0, db.trustedKeyDao().markVerified("DC-BBBBBB", EcdsaP256.fingerprint(peerKey), 99L))

        db.close()
        db = open()

        val verified = store(db).find("DC-BBBBBB")!!
        assertEquals(TrustState.VERIFIED, verified.trust)
        assertEquals(7L, verified.verifiedAt)
        assertArrayEquals(peerKey, verified.publicKey)
        assertEquals(TrustState.SEEN, store(db).find("DC-CCCCCC")!!.trust)

        // A conflicting key never replaces the verified one in SQLite either.
        assertTrue(store(db).recordSeen("DC-BBBBBB", key(), now = 8L) is SeenKeyResult.Conflict)
        assertArrayEquals(peerKey, store(db).find("DC-BBBBBB")!!.publicKey)
        assertEquals(2, db.trustedKeyDao().observeAll().first().size)
    }


    @Test
    fun keystoreIdentityInteroperatesWithKeyExchangeAndSafetyCodes() = runBlocking {

        val identity = DeviceIdentity(context, alias, preferences).loadOrCreate() as IdentityStatus.Ready
        val localKey = identity.signer.publicKey
        val db = open()
        val local = KeyExchange(store(db), "DC-AAAAAA", localKey)

        // The Keystore public key survives the KEY payload format unchanged.
        val announced = KeyPayloadCodec.decode(KeyPayloadCodec.encode(AnnouncedKey("DC-AAAAAA", localKey)))!!
        assertArrayEquals(localKey, announced.publicKey)

        // A peer's view: it stores our Keystore key and computes the same code we do.
        val peerKey = key()
        val peerDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val peer = KeyExchange(store(peerDatabase), "DC-BBBBBB", peerKey)
        val packet = com.example.disasterconnect.messaging.MessagePacketCodec.decode(local.keyPacketFor("DC-BBBBBB", 0L)!!)
            as com.example.disasterconnect.messaging.DataPacket
        assertSame(KeyReceiveResult.STORED, peer.onKeyPacket(packet, "DC-AAAAAA", 0L))

        assertEquals(
            SafetyCode.compute("DC-AAAAAA", localKey, "DC-BBBBBB", peerKey),
            peer.safetyCode("DC-AAAAAA")
        )
        peerDatabase.close()
    }
}

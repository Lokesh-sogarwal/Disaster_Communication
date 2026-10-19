package com.example.disasterconnect.messaging

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.data.DeliveryProof
import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.security.DeviceIdentity
import com.example.disasterconnect.security.IdentityStatus
import com.example.disasterconnect.security.RoomTrustedKeyRecords
import com.example.disasterconnect.security.TrustedKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore


/**
 * A → C on real Room databases, with C signing in the real Android Keystore and A trusting
 * C's key only after an explicit confirmation in its real trust table.
 */
@RunWith(AndroidJUnit4::class)
class SignedReceiptInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val alias = "dc_identity_receipt_test"

    private val preferences = "dc_identity_receipt_test_prefs"

    private val databaseA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private val databaseC = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After
    fun cleanUp() {
        databaseA.close()
        databaseC.close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store(db: AppDatabase) = RoomMessageStore(db.emergencyMessageDao(), db.messageHandoffDao())

    private suspend fun transfer(from: MessagingEngine, to: MessagingEngine, fromId: String, toId: String, now: Long) {
        val outbound = from.nextOutgoing(toId, now)!!
        from.onTransmitted(outbound.messageId, toId, success = true, now)
        val result = to.onDataReceived(MessagePacketCodec.decode(outbound.bytes) as DataPacket, fromId, now)
        from.onAck(MessagePacketCodec.decode(MessagePacketCodec.encode(result.ack)) as AckPacket, toId, now)
    }


    @Test
    fun keystoreSignedReceiptMakesTheMessageVerifiedDeliveredInRoom() = runBlocking {

        val identityC = DeviceIdentity(context, alias, preferences).loadOrCreate() as IdentityStatus.Ready
        val trustA = TrustedKeyStore(RoomTrustedKeyRecords(databaseA.trustedKeyDao()))
        trustA.recordSeen("DC-CCCCCC", identityC.signer.publicKey, now = 0L)
        trustA.confirmVerified("DC-CCCCCC", identityC.fingerprint, now = 1L)

        val receiptTrust = object : ReceiptTrust {
            override suspend fun requiresSignedReceipt(nodeId: String) = trustA.find(nodeId)?.trust == TrustState.VERIFIED
            override suspend fun receiptKey(nodeId: String) = trustA.find(nodeId)?.takeIf { it.trust == TrustState.VERIFIED }?.publicKey
        }
        val a = MessagingEngine(store(databaseA), "DC-AAAAAA", receiptTrust = receiptTrust)
        val c = MessagingEngine(store(databaseC), "DC-CCCCCC", receiptSigner = { identityC.signer })

        val original = a.createText("DC-CCCCCC", "Are you safe?", now = 0L)
        transfer(a, c, "DC-AAAAAA", "DC-CCCCCC", now = 0L)

        val messagesA = databaseA.emergencyMessageDao()
        assertEquals(DeliveryState.FORWARDED, messagesA.findByMessageId(original.messageId)!!.deliveryState)
        assertEquals(DeliveryProof.LINK_ACK, messagesA.findByMessageId(original.messageId)!!.deliveryProof)
        assertEquals(1, databaseC.emergencyMessageDao().countReceiptsFor(original.messageId))

        transfer(c, a, "DC-CCCCCC", "DC-AAAAAA", now = 1L)

        val delivered = messagesA.findByMessageId(original.messageId)!!
        assertEquals(DeliveryState.DELIVERED, delivered.deliveryState)
        assertEquals(DeliveryProof.SIGNED_RECEIPT, delivered.deliveryProof)
        assertTrue(databaseA.emergencyMessageDao().getPending(2L).none { it.messageType == MessageType.RECEIPT })
    }
}

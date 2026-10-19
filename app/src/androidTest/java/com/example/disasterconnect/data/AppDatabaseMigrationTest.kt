package com.example.disasterconnect.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec


/**
 * Real SQLite files in the schemas Room generated for earlier versions, migrated with
 * [AppDatabase.MIGRATIONS]. Room validates every table against the current entities on open,
 * so a migration that does not match the v4 schema exactly fails here.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val name = "migration-test.db"

    private var database: AppDatabase? = null

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }


    // ---------------------------------------------------------
    // Earlier schemas (copied from Room's generated AppDatabase_Impl for those versions)
    // ---------------------------------------------------------

    private val sosPacketsV1 =
        "CREATE TABLE IF NOT EXISTS `sos_packets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sosId` TEXT NOT NULL, " +
            "`timestamp` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `accuracy` REAL NOT NULL, " +
            "`batteryPercentage` INTEGER NOT NULL, `chargingStatus` TEXT NOT NULL, `chargingType` TEXT NOT NULL, " +
            "`cellularCarrier` TEXT NOT NULL, `cellularNetworkType` TEXT NOT NULL, `signalStrength` TEXT NOT NULL, " +
            "`isSimAvailable` INTEGER NOT NULL, `internetStatus` TEXT NOT NULL, `isWifiActive` INTEGER NOT NULL, " +
            "`isMobileDataActive` INTEGER NOT NULL, `deviceModel` TEXT NOT NULL, `androidVersion` TEXT NOT NULL, " +
            "`emergencyMessage` TEXT NOT NULL, `isSynced` INTEGER NOT NULL)"

    private val emergencyMessagesV2Columns =
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` TEXT NOT NULL, `senderId` TEXT NOT NULL, " +
            "`receiverId` TEXT, `messageType` TEXT NOT NULL, `priority` TEXT NOT NULL, `content` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, " +
            "`hopCount` INTEGER NOT NULL, `currentRelayId` TEXT, `deliveryState` TEXT NOT NULL, `lastAttemptAt` INTEGER"

    private val emergencyMessageIndexes = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_emergency_messages_messageId` ON `emergency_messages` (`messageId`)",
        "CREATE INDEX IF NOT EXISTS `index_emergency_messages_deliveryState` ON `emergency_messages` (`deliveryState`)",
        "CREATE INDEX IF NOT EXISTS `index_emergency_messages_expiresAt` ON `emergency_messages` (`expiresAt`)"
    )

    private val schemaV3 = listOf(
        sosPacketsV1,
        "CREATE TABLE IF NOT EXISTS `emergency_messages` ($emergencyMessagesV2Columns, " +
            "`attemptCount` INTEGER NOT NULL DEFAULT 0, `isIncoming` INTEGER NOT NULL DEFAULT 0)"
    ) + emergencyMessageIndexes + listOf(
        "CREATE TABLE IF NOT EXISTS `message_handoffs` (`messageId` TEXT NOT NULL, `peerId` TEXT NOT NULL, " +
            "`handedOffAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `peerId`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0e821ab9dbcaf442da67030109d6fc05')"
    )

    // v4: v3 plus the trusted-key table (Phase 2).
    private val schemaV4 = schemaV3.filterNot { it.startsWith("INSERT OR REPLACE INTO room_master_table") } + listOf(
        "CREATE TABLE IF NOT EXISTS `trusted_keys` (`nodeId` TEXT NOT NULL, `publicKey` BLOB NOT NULL, " +
            "`fingerprint` BLOB NOT NULL, `trust` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, `verifiedAt` INTEGER, " +
            "PRIMARY KEY(`nodeId`))",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '87bc4910f758edc3587e76e185ceaf61')"
    )

    // v2 as shipped in the initial commit: no attemptCount / isIncoming, no handoff table.
    private val schemaV2 = listOf(
        sosPacketsV1,
        "CREATE TABLE IF NOT EXISTS `emergency_messages` ($emergencyMessagesV2Columns)"
    ) + emergencyMessageIndexes + listOf(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
    )

    private fun createOldDatabase(version: Int, schema: List<String>, rows: SQLiteDatabase.() -> Unit) {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use {
            schema.forEach(it::execSQL)
            it.rows()
            it.version = version
        }
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also {
                database = it
                it.openHelper.writableDatabase
            }


    // ---------------------------------------------------------
    // Representative rows
    // ---------------------------------------------------------

    private fun SQLiteDatabase.insertMessage(
        messageId: String, senderId: String, receiverId: String?, type: String, priority: String,
        content: String, state: String, latitude: Double? = null, longitude: Double? = null,
        hopCount: Int = 0, relay: String? = null, extra: String = "", extraValues: String = ""
    ) {
        execSQL(
            "INSERT INTO emergency_messages (messageId, senderId, receiverId, messageType, priority, content, " +
                "createdAt, expiresAt, latitude, longitude, hopCount, currentRelayId, deliveryState, lastAttemptAt$extra) " +
                "VALUES (?, ?, ?, ?, ?, ?, 1000, 86401000, ?, ?, ?, ?, ?, NULL$extraValues)",
            arrayOf(messageId, senderId, receiverId, type, priority, content, latitude, longitude, hopCount, relay, state)
        )
    }

    private fun SQLiteDatabase.insertSos() {
        execSQL(
            "INSERT INTO sos_packets (sosId, timestamp, latitude, longitude, accuracy, batteryPercentage, chargingStatus, " +
                "chargingType, cellularCarrier, cellularNetworkType, signalStrength, isSimAvailable, internetStatus, " +
                "isWifiActive, isMobileDataActive, deviceModel, androidVersion, emergencyMessage, isSynced) " +
                "VALUES ('sos-1', 5000, 28.6139, 77.209, 12.5, 41, 'NOT_CHARGING', 'NONE', 'Carrier', 'LTE', 'GOOD', 1, " +
                "'OFFLINE', 0, 0, 'Pixel', '16', 'Trapped on second floor', 0)"
        )
    }


    // ---------------------------------------------------------
    // Tests
    // ---------------------------------------------------------

    @Test
    fun version3DatabaseMigratesToVersion4WithEveryMessageHandoffAndSosPreserved() = runBlocking {

        createOldDatabase(3, schemaV3) {
            insertMessage("own-queued", "DC-AAAAAA", "DC-CCCCCC", "TEXT", "NORMAL", "Are you safe?", "QUEUED",
                extra = ", attemptCount, isIncoming", extraValues = ", 2, 0")
            insertMessage("relay-copy", "DC-BBBBBB", "DC-CCCCCC", "TEXT", "NORMAL", "Bridge is down", "QUEUED",
                hopCount = 1, relay = "DC-BBBBBB", extra = ", attemptCount, isIncoming", extraValues = ", 0, 1")
            insertMessage("sos-broadcast", "DC-AAAAAA", null, "SOS", "CRITICAL", "SOS: trapped", "FORWARDED",
                latitude = 28.6139, longitude = 77.209, extra = ", attemptCount, isIncoming", extraValues = ", 1, 0")
            insertMessage("delivered", "DC-DDDDDD", "DC-AAAAAA", "TEXT", "HIGH", "On my way", "DELIVERED",
                hopCount = 2, relay = "DC-BBBBBB", extra = ", attemptCount, isIncoming", extraValues = ", 0, 1")
            execSQL("INSERT INTO message_handoffs (messageId, peerId, handedOffAt) VALUES ('sos-broadcast', 'DC-BBBBBB', 7000)")
            execSQL("INSERT INTO message_handoffs (messageId, peerId, handedOffAt) VALUES ('relay-copy', 'DC-BBBBBB', 8000)")
            insertSos()
        }

        val migrated = openMigrated()
        val messages = migrated.emergencyMessageDao()

        assertEquals(5, migrated.openHelper.readableDatabase.version)

        val own = messages.findByMessageId("own-queued")!!
        assertEquals(DeliveryState.QUEUED, own.deliveryState)
        assertEquals(2, own.attemptCount)
        assertEquals(false, own.isIncoming)
        assertEquals("Are you safe?", own.content)

        val relay = messages.findByMessageId("relay-copy")!!
        assertEquals(true, relay.isIncoming)
        assertEquals("DC-CCCCCC", relay.receiverId)
        assertEquals(1, relay.hopCount)
        assertEquals("DC-BBBBBB", relay.currentRelayId)

        val sos = messages.findByMessageId("sos-broadcast")!!
        assertEquals(MessageType.SOS, sos.messageType)
        assertEquals(MessagePriority.CRITICAL, sos.priority)
        assertEquals(DeliveryState.FORWARDED, sos.deliveryState)
        assertNull(sos.receiverId)
        assertEquals(28.6139, sos.latitude!!, 0.0)
        assertEquals(77.209, sos.longitude!!, 0.0)

        assertEquals(DeliveryState.DELIVERED, messages.findByMessageId("delivered")!!.deliveryState)
        assertEquals(4, messages.observeAll().first().size)

        assertEquals(1, migrated.messageHandoffDao().count("sos-broadcast", "DC-BBBBBB"))
        assertEquals(1, migrated.messageHandoffDao().count("relay-copy", "DC-BBBBBB"))

        val sosPacket = migrated.sosDao().getAllSosPackets().first().single()
        assertEquals("Trapped on second floor", sosPacket.emergencyMessage)
        assertEquals(41, sosPacket.batteryPercentage)

        // Duplicate suppression still relies on the unique messageId index.
        assertEquals(-1L, messages.insert(own.copy(id = 0, content = "duplicate")))
        assertEquals("Are you safe?", messages.findByMessageId("own-queued")!!.content)

        // The new table exists and starts empty.
        assertNull(migrated.trustedKeyDao().find("DC-CCCCCC"))
    }


    @Test
    fun version4DatabaseMigratesToVersion5KeepingMessagesKeysAndStatesWithNoProofClaimed() = runBlocking {

        val peerKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(peerKey)

        createOldDatabase(4, schemaV4) {
            insertMessage("delivered-v4", "DC-AAAAAA", "DC-CCCCCC", "TEXT", "NORMAL", "On my way", "DELIVERED",
                extra = ", attemptCount, isIncoming", extraValues = ", 1, 0")
            insertMessage("sos-v4", "DC-AAAAAA", null, "SOS", "CRITICAL", "SOS", "FORWARDED",
                latitude = 1.0, longitude = 2.0, extra = ", attemptCount, isIncoming", extraValues = ", 1, 0")
            execSQL(
                "INSERT INTO trusted_keys (nodeId, publicKey, fingerprint, trust, firstSeenAt, verifiedAt) VALUES (?, ?, ?, 'VERIFIED', 1, 2)",
                arrayOf("DC-CCCCCC", peerKey, fingerprint)
            )
            execSQL("INSERT INTO message_handoffs (messageId, peerId, handedOffAt) VALUES ('sos-v4', 'DC-BBBBBB', 3)")
        }

        val migrated = openMigrated()

        assertEquals(5, migrated.openHelper.readableDatabase.version)
        val delivered = migrated.emergencyMessageDao().findByMessageId("delivered-v4")!!
        assertEquals(DeliveryState.DELIVERED, delivered.deliveryState)
        // Old deliveries are never upgraded to "verified".
        assertEquals(DeliveryProof.NONE, delivered.deliveryProof)
        assertNull(delivered.receiptFor)
        val sos = migrated.emergencyMessageDao().findByMessageId("sos-v4")!!
        assertEquals(DeliveryState.FORWARDED, sos.deliveryState)
        assertEquals(DeliveryProof.NONE, sos.deliveryProof)
        assertEquals(1, migrated.messageHandoffDao().count("sos-v4", "DC-BBBBBB"))

        val key = migrated.trustedKeyDao().find("DC-CCCCCC")!!
        assertEquals(TrustState.VERIFIED, key.trust)
        assertArrayEquals(peerKey, key.publicKey)
    }


    @Test
    fun receiptRowsAndProofPersistAndMessageWithReceiptIsOneTransaction() = runBlocking {

        createOldDatabase(4, schemaV4) { }
        val messages = openMigrated().emergencyMessageDao()
        val incoming = EmergencyMessage(
            messageId = "m1", senderId = "DC-AAAAAA", receiverId = "DC-CCCCCC", messageType = MessageType.TEXT,
            priority = MessagePriority.NORMAL, content = "hi", createdAt = 0L, expiresAt = Long.MAX_VALUE, isIncoming = true,
            deliveryState = DeliveryState.DELIVERED
        )
        val receipt = incoming.copy(
            messageId = "r:1", senderId = "DC-CCCCCC", receiverId = "DC-AAAAAA", messageType = MessageType.RECEIPT,
            content = "receipt", isIncoming = false, deliveryState = DeliveryState.QUEUED, receiptFor = "m1"
        )

        // Missing receipt is found by the recovery query, then both rows are stored together.
        messages.insert(incoming.copy(messageId = "m0"))
        assertEquals(listOf("m0"), messages.getMissingReceipts("DC-CCCCCC", now = 1L, limit = 10).map { it.messageId })

        assertTrue(messages.insertAll(listOf(incoming, receipt)).all { it != -1L })
        assertEquals(1, messages.countReceiptsFor("m1"))
        // A duplicate original is ignored without a second receipt.
        assertEquals(-1L, messages.insertAll(listOf(incoming, receipt.copy(messageId = "r:2"))).first())

        messages.update(incoming.copy(id = messages.findByMessageId("m1")!!.id, deliveryProof = DeliveryProof.SIGNED_RECEIPT))
        assertEquals(DeliveryProof.SIGNED_RECEIPT, messages.findByMessageId("m1")!!.deliveryProof)
        assertEquals("m1", messages.findByMessageId("r:1")!!.receiptFor)
    }


    @Test
    fun version2DatabaseFromTheInitialReleaseMigratesThroughVersion3To4() = runBlocking {

        createOldDatabase(2, schemaV2) {
            insertMessage("old-sos", "DC-AAAAAA", null, "SOS", "CRITICAL", "SOS", "QUEUED", latitude = 1.0, longitude = 2.0)
            insertMessage("old-text", "DC-AAAAAA", "DC-BBBBBB", "TEXT", "NORMAL", "hello", "CREATED")
            insertSos()
        }

        val migrated = openMigrated()

        assertEquals(5, migrated.openHelper.readableDatabase.version)
        val sos = migrated.emergencyMessageDao().findByMessageId("old-sos")!!
        assertEquals(DeliveryState.QUEUED, sos.deliveryState)
        assertEquals(0, sos.attemptCount)
        assertEquals(false, sos.isIncoming)
        assertEquals("hello", migrated.emergencyMessageDao().findByMessageId("old-text")!!.content)
        assertEquals(1, migrated.sosDao().getAllSosPackets().first().size)
    }


    @Test
    fun trustedKeysEnterAsSeenAndAStoredKeyIsNeverReplacedInRealSqlite() = runBlocking {

        createOldDatabase(3, schemaV3) { }
        val keys = openMigrated().trustedKeyDao()
        fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded
        val first = key()
        val second = key()
        val sha = { bytes: ByteArray -> MessageDigest.getInstance("SHA-256").digest(bytes) }

        assertTrue(keys.insertSeen("DC-CCCCCC", first, sha(first), seenAt = 10L) != -1L)
        assertEquals(-1L, keys.insertSeen("DC-CCCCCC", second, sha(second), seenAt = 20L))

        val stored = keys.find("DC-CCCCCC")!!
        assertArrayEquals(first, stored.publicKey)
        assertArrayEquals(sha(first), stored.fingerprint)
        assertEquals(TrustState.SEEN, stored.trust)
        assertEquals(10L, stored.firstSeenAt)
        assertNull(stored.verifiedAt)
    }
}

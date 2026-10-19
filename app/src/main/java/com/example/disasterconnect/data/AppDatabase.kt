package com.example.disasterconnect.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SosPacket::class,
        EmergencyMessage::class,
        MessageHandoff::class,
        TrustedKey::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    abstract fun emergencyMessageDao(): EmergencyMessageDao

    abstract fun messageHandoffDao(): MessageHandoffDao

    abstract fun trustedKeyDao(): TrustedKeyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "disaster_connect_db"
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }


        private val MIGRATION_1_2 =
            object : Migration(1, 2) {

                override fun migrate(database: SupportSQLiteDatabase) {

                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS emergency_messages (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "messageId TEXT NOT NULL, senderId TEXT NOT NULL, " +
                            "receiverId TEXT, messageType TEXT NOT NULL, " +
                            "priority TEXT NOT NULL, content TEXT NOT NULL, " +
                            "createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, " +
                            "latitude REAL, longitude REAL, hopCount INTEGER NOT NULL, " +
                            "currentRelayId TEXT, deliveryState TEXT NOT NULL, " +
                            "lastAttemptAt INTEGER)"
                    )

                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_emergency_messages_messageId " +
                            "ON emergency_messages (messageId)"
                    )

                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_emergency_messages_deliveryState " +
                            "ON emergency_messages (deliveryState)"
                    )

                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_emergency_messages_expiresAt " +
                            "ON emergency_messages (expiresAt)"
                    )
                }
            }


        // BLE messaging: retry counter, message direction and the per-peer handoff log.
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {

                override fun migrate(database: SupportSQLiteDatabase) {

                    database.execSQL(
                        "ALTER TABLE emergency_messages " +
                            "ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0"
                    )

                    database.execSQL(
                        "ALTER TABLE emergency_messages " +
                            "ADD COLUMN isIncoming INTEGER NOT NULL DEFAULT 0"
                    )

                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS message_handoffs (" +
                            "messageId TEXT NOT NULL, peerId TEXT NOT NULL, " +
                            "handedOffAt INTEGER NOT NULL, " +
                            "PRIMARY KEY(messageId, peerId))"
                    )
                }
            }


        // Signed delivery receipts, phase 2: pinned public keys of other phones. Existing tables are untouched.
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {

                override fun migrate(database: SupportSQLiteDatabase) {

                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS trusted_keys (" +
                            "nodeId TEXT NOT NULL, publicKey BLOB NOT NULL, " +
                            "fingerprint BLOB NOT NULL, trust TEXT NOT NULL, " +
                            "firstSeenAt INTEGER NOT NULL, verifiedAt INTEGER, " +
                            "PRIMARY KEY(nodeId))"
                    )
                }
            }


        // Signed delivery receipts, phase 4: delivery proof per message, and the message a receipt confirms.
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {

                override fun migrate(database: SupportSQLiteDatabase) {

                    database.execSQL(
                        "ALTER TABLE emergency_messages " +
                            "ADD COLUMN deliveryProof TEXT NOT NULL DEFAULT 'NONE'"
                    )

                    database.execSQL(
                        "ALTER TABLE emergency_messages " +
                            "ADD COLUMN receiptFor TEXT"
                    )

                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_emergency_messages_receiptFor " +
                            "ON emergency_messages (receiptFor)"
                    )
                }
            }


        // Every migration, in order. Shared with the migration tests so they exercise exactly this list.
        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}

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
        EmergencyMessage::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    abstract fun emergencyMessageDao(): EmergencyMessageDao

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
                    .addMigrations(MIGRATION_1_2)
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
    }
}

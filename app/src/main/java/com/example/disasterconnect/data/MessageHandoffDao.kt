package com.example.disasterconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface MessageHandoffDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(handoff: MessageHandoff)

    @Query("SELECT COUNT(*) FROM message_handoffs WHERE messageId = :messageId AND peerId = :peerId")
    suspend fun count(messageId: String, peerId: String): Int

    @Query("SELECT COUNT(*) FROM message_handoffs WHERE messageId = :messageId")
    suspend fun countForMessage(messageId: String): Int
}

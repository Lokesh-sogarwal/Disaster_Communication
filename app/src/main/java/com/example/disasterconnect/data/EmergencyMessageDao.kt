package com.example.disasterconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow


@Dao
interface EmergencyMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: EmergencyMessage): Long

    @Query(
        "SELECT * FROM emergency_messages " +
            "WHERE deliveryState NOT IN ('DELIVERED', 'EXPIRED') " +
            "AND expiresAt > :now " +
            "ORDER BY CASE priority " +
            "WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END, createdAt ASC"
    )
    suspend fun getPending(now: Long): List<EmergencyMessage>

    @Query("SELECT * FROM emergency_messages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EmergencyMessage>>

    @Query(
        "UPDATE emergency_messages SET deliveryState = :state, " +
            "currentRelayId = :relayId, hopCount = :hopCount, lastAttemptAt = :attemptAt " +
            "WHERE messageId = :messageId"
    )
    suspend fun updateDelivery(
        messageId: String,
        state: String,
        relayId: String?,
        hopCount: Int,
        attemptAt: Long
    )

    @Query(
        "UPDATE emergency_messages SET deliveryState = 'EXPIRED' " +
            "WHERE expiresAt <= :now AND deliveryState NOT IN ('DELIVERED', 'EXPIRED')"
    )
    suspend fun expireMessages(now: Long): Int


    // ---------------------------------------------------------
    // BLE messaging
    // ---------------------------------------------------------

    @Update
    suspend fun update(message: EmergencyMessage)

    @Query("SELECT * FROM emergency_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: String): EmergencyMessage?

    @Query(
        "SELECT * FROM emergency_messages " +
            "WHERE deliveryState IN ('CREATED', 'QUEUED', 'FORWARDED') " +
            "AND expiresAt > :now " +
            "ORDER BY CASE priority " +
            "WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END, createdAt ASC"
    )
    suspend fun getRelayCandidates(now: Long): List<EmergencyMessage>

    @Query("SELECT * FROM emergency_messages WHERE deliveryState = 'SENT'")
    suspend fun getAwaitingAck(): List<EmergencyMessage>


    // ---------------------------------------------------------
    // Signed delivery receipts
    // ---------------------------------------------------------

    /** A received message and its receipt in one transaction: both rows exist afterwards, or neither. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<EmergencyMessage>): List<Long>

    @Query("SELECT COUNT(*) FROM emergency_messages WHERE messageType = 'RECEIPT' AND receiptFor = :messageId")
    suspend fun countReceiptsFor(messageId: String): Int

    @Query(
        "SELECT COUNT(*) FROM emergency_messages WHERE messageType = 'RECEIPT' AND isIncoming = 1 " +
            "AND deliveryState IN ('CREATED', 'QUEUED', 'SENT', 'FORWARDED') AND expiresAt > :now"
    )
    suspend fun countPendingRelayReceipts(now: Long): Int

    /** Personal messages this phone received that still have no receipt of its own (e.g. the key was not ready). */
    @Query(
        "SELECT * FROM emergency_messages AS m " +
            "WHERE m.isIncoming = 1 AND m.receiverId = :localNodeId " +
            "AND m.messageType IN ('TEXT', 'STATUS', 'LOCATION') AND m.expiresAt > :now " +
            "AND NOT EXISTS (SELECT 1 FROM emergency_messages AS r WHERE r.messageType = 'RECEIPT' " +
            "AND r.receiptFor = m.messageId AND r.senderId = :localNodeId) " +
            "ORDER BY m.id ASC LIMIT :limit"
    )
    suspend fun getMissingReceipts(localNodeId: String, now: Long, limit: Int): List<EmergencyMessage>

    @Query(
        "SELECT * FROM emergency_messages " +
            "WHERE (isIncoming = 1 AND senderId = :peerId) " +
            "OR (isIncoming = 0 AND receiverId = :peerId) " +
            "ORDER BY id ASC"
    )
    fun observeConversation(peerId: String): Flow<List<EmergencyMessage>>
}

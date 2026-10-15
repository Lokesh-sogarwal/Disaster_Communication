package com.example.disasterconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}

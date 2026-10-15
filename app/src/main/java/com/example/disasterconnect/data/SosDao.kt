package com.example.disasterconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SosDao {
    @Insert
    suspend fun insertSos(sosPacket: SosPacket)

    @Query("SELECT * FROM sos_packets ORDER BY timestamp DESC")
    fun getAllSosPackets(): Flow<List<SosPacket>>

    @Query("SELECT * FROM sos_packets WHERE isSynced = 0")
    suspend fun getUnsyncedPackets(): List<SosPacket>

    @Query("UPDATE sos_packets SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)
}

package com.example.disasterconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sos_packets")
data class SosPacket(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sosId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val batteryPercentage: Int,
    val chargingStatus: String,
    val chargingType: String,
    val cellularCarrier: String,
    val cellularNetworkType: String,
    val signalStrength: String,
    val isSimAvailable: Boolean,
    val internetStatus: String,
    val isWifiActive: Boolean,
    val isMobileDataActive: Boolean,
    val deviceModel: String,
    val androidVersion: String,
    val emergencyMessage: String,
    val isSynced: Boolean = false
)

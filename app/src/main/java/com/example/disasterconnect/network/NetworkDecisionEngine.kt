package com.example.disasterconnect.network

import com.example.disasterconnect.data.MessagePriority


// =========================================================
// LINK AND GATEWAY DECISION MODEL
// =========================================================

enum class TransportType {
    BLE,
    WIFI_DIRECT,
    INTERNET
}


data class LinkCandidate(
    val nodeId: String,
    val transport: TransportType,
    val linkQuality: Int,
    val reliability: Int,
    val batteryPercent: Int? = null,
    val congestion: Int = 0,
    val hopsToGateway: Int? = null,
    val isTrustedGateway: Boolean = false,
    val hasInternet: Boolean = false
)


// =========================================================
// ADAPTIVE NETWORK SELECTION
// =========================================================

class NetworkDecisionEngine {

    fun bestCandidate(
        candidates: List<LinkCandidate>,
        priority: String
    ): LinkCandidate? {

        return candidates
            .filter { it.isUsable() }
            .maxByOrNull { it.scoreFor(priority) }
    }


    private fun LinkCandidate.isUsable(): Boolean {

        return linkQuality > 0 && reliability > 0
    }


    private fun LinkCandidate.scoreFor(
        priority: String
    ): Int {

        val gatewayScore =
            when {
                isTrustedGateway && hasInternet -> 105
                isTrustedGateway -> 40
                hasInternet -> 20
                else -> 0
            }

        val hopsScore =
            hopsToGateway
                ?.let { (12 - it.coerceAtMost(12)) * 2 }
                ?: 0

        val batteryScore =
            (batteryPercent ?: 50) / 5

        val transportScore =
            when (transport) {
                TransportType.WIFI_DIRECT -> 8
                TransportType.BLE -> 5
                TransportType.INTERNET -> 12
            }

        val critical = priority == MessagePriority.CRITICAL

        val qualityWeight = if (critical) 3 else 2

        val reliabilityWeight = if (critical) 4 else 2

        val batteryWeight = if (critical) 0 else 1

        return (linkQuality * qualityWeight) +
                (reliability * reliabilityWeight) +
                gatewayScore +
                hopsScore +
                transportScore +
                (batteryScore * batteryWeight) -
                congestion.coerceIn(0, 100)
    }
}

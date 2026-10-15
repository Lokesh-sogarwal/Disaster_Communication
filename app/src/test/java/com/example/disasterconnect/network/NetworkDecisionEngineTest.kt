package com.example.disasterconnect.network

import com.example.disasterconnect.data.MessagePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test


class NetworkDecisionEngineTest {

    private val decisionEngine =
        NetworkDecisionEngine()


    @Test
    fun criticalSosPrefersTrustedInternetGateway() {

        val selected =
            decisionEngine.bestCandidate(
                candidates = listOf(
                    LinkCandidate(
                        nodeId = "nearby-relay",
                        transport = TransportType.WIFI_DIRECT,
                        linkQuality = 95,
                        reliability = 90,
                        batteryPercent = 90
                    ),
                    LinkCandidate(
                        nodeId = "trusted-gateway",
                        transport = TransportType.BLE,
                        linkQuality = 70,
                        reliability = 85,
                        batteryPercent = 35,
                        isTrustedGateway = true,
                        hasInternet = true,
                        hopsToGateway = 0
                    )
                ),
                priority = MessagePriority.CRITICAL
            )

        assertEquals(
            "trusted-gateway",
            selected?.nodeId
        )
    }


    @Test
    fun skipsUnavailableLinks() {

        val selected =
            decisionEngine.bestCandidate(
                candidates = listOf(
                    LinkCandidate(
                        nodeId = "unavailable",
                        transport = TransportType.BLE,
                        linkQuality = 0,
                        reliability = 100
                    )
                ),
                priority = MessagePriority.NORMAL
            )

        assertNull(selected)
    }
}

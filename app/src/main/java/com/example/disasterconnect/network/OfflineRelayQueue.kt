package com.example.disasterconnect.network

import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.EmergencyMessageDao


// =========================================================
// STORE-AND-FORWARD QUEUE
// =========================================================

class OfflineRelayQueue(
    private val messageDao: EmergencyMessageDao,
    private val decisionEngine: NetworkDecisionEngine
) {

    suspend fun enqueue(message: EmergencyMessage): Boolean {

        val queuedMessage =
            message.copy(
                deliveryState = DeliveryState.QUEUED
            )

        return messageDao.insert(queuedMessage) != -1L
    }


    suspend fun nextTransfer(
        candidates: List<LinkCandidate>,
        now: Long = System.currentTimeMillis()
    ): RelayTransfer? {

        messageDao.expireMessages(now)

        val message =
            messageDao
                .getPending(now)
                .firstOrNull()
                ?: return null

        val candidate =
            decisionEngine.bestCandidate(
                candidates,
                message.priority
            )
                ?: return null

        return RelayTransfer(
            message = message,
            destination = candidate
        )
    }


    suspend fun recordForwarded(
        transfer: RelayTransfer,
        now: Long = System.currentTimeMillis()
    ) {

        val reachedGateway =
            transfer.destination.isTrustedGateway &&
                    transfer.destination.hasInternet

        messageDao.updateDelivery(
            messageId = transfer.message.messageId,
            state =
                if (reachedGateway)
                    DeliveryState.GATEWAY_REACHED
                else
                    DeliveryState.FORWARDED,
            relayId = transfer.destination.nodeId,
            hopCount = transfer.message.hopCount + 1,
            attemptAt = now
        )
    }
}


data class RelayTransfer(
    val message: EmergencyMessage,
    val destination: LinkCandidate
)

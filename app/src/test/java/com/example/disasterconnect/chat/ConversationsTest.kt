package com.example.disasterconnect.chat

import com.example.disasterconnect.data.DeliveryProof
import com.example.disasterconnect.data.DeliveryState
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class ConversationsTest {

    private val me = "DC-AAAAAA"
    private val b = "DC-BBBBBB"
    private val c = "DC-CCCCCC"

    private fun message(
        id: Long,
        incoming: Boolean,
        sender: String,
        receiver: String?,
        state: String = DeliveryState.QUEUED,
        type: String = MessageType.TEXT,
        priority: String = MessagePriority.NORMAL
    ) = EmergencyMessage(
        id = id,
        messageId = "m$id",
        senderId = sender,
        receiverId = receiver,
        messageType = type,
        priority = priority,
        content = "text $id",
        createdAt = id * 1_000,
        expiresAt = Long.MAX_VALUE,
        deliveryState = state,
        isIncoming = incoming
    )


    @Test
    fun messagesGroupByTheOtherPhoneInBothDirections() {

        val conversations = buildConversations(
            listOf(
                message(1, incoming = false, sender = me, receiver = b),
                message(2, incoming = true, sender = b, receiver = me, state = DeliveryState.DELIVERED),
                message(3, incoming = true, sender = c, receiver = null, type = MessageType.SOS, priority = MessagePriority.CRITICAL)
            )
        )

        assertEquals(listOf(ConversationKey.Peer(c), ConversationKey.Peer(b)), conversations.map { it.key })
        assertEquals(2, conversations[1].messageCount)
        assertEquals("m2", conversations[1].latest.messageId)
        assertTrue(conversations[0].hasEmergency)
    }


    @Test
    fun ownSosBroadcastsHaveTheirOwnEntryAndFailuresAreCounted() {

        val conversations = buildConversations(
            listOf(
                message(1, incoming = false, sender = me, receiver = null, type = MessageType.SOS, priority = MessagePriority.CRITICAL),
                message(2, incoming = false, sender = me, receiver = b, state = DeliveryState.FAILED)
            )
        )

        assertEquals(ConversationKey.OwnBroadcasts, conversations[1].key)
        assertEquals(1, conversations[0].failedCount)
    }


    @Test
    fun labelsMatchStoredStatesWithoutUpgrading() {

        val queued = message(1, incoming = false, sender = me, receiver = b)

        assertEquals(DeliveryLabel.WAITING_FOR_CONNECTION, queued.deliveryLabel(peerLinked = false))
        assertEquals(DeliveryLabel.PENDING, queued.deliveryLabel(peerLinked = true))
        assertEquals(DeliveryLabel.SENT, queued.copy(deliveryState = DeliveryState.SENT).deliveryLabel(peerLinked = true))
        assertEquals(DeliveryLabel.DELIVERED, queued.copy(deliveryState = DeliveryState.DELIVERED).deliveryLabel(peerLinked = false))
        assertEquals(DeliveryLabel.FAILED, queued.copy(deliveryState = DeliveryState.FAILED).deliveryLabel(peerLinked = true))
        assertEquals(DeliveryLabel.PASSED_ON, queued.copy(deliveryState = DeliveryState.FORWARDED).deliveryLabel(peerLinked = false))
        assertEquals(DeliveryLabel.RECEIVED, message(2, incoming = true, sender = b, receiver = me).deliveryLabel(peerLinked = false))
    }


    @Test
    fun broadcastWaitingTextDoesNotMentionASingleRecipient() {

        val sos = message(1, incoming = false, sender = me, receiver = null, type = MessageType.SOS, priority = MessagePriority.CRITICAL)
        val direct = message(2, incoming = false, sender = me, receiver = b)

        val label = DeliveryLabel.WAITING_FOR_CONNECTION

        assertEquals("It will be passed to nearby phones as soon as one is in range.", sos.deliveryExplanation(label))
        assertEquals(label.explanation, direct.deliveryExplanation(label))
    }


    @Test
    fun relayCopiesCarriedForOtherPhonesAreHiddenFromThisPhonesInbox() {

        val forMe = message(1, incoming = true, sender = b, receiver = me)
        val carriedForC = message(2, incoming = true, sender = b, receiver = c)
        val sos = message(3, incoming = true, sender = b, receiver = null, type = MessageType.SOS)

        assertTrue(carriedForC.isRelayCopy(me))
        assertEquals(listOf("m1", "m3"), listOf(forMe, carriedForC, sos).visibleTo(me).map { it.messageId })
    }


    @Test
    fun forwardedDirectMessageSaysDeliveryIsNotConfirmed() {

        val forwarded = message(1, incoming = false, sender = me, receiver = c, state = DeliveryState.FORWARDED)

        assertEquals(DeliveryLabel.PASSED_ON, forwarded.deliveryLabel(peerLinked = false))
        assertTrue(forwarded.deliveryExplanation(DeliveryLabel.PASSED_ON).contains("not confirmed"))
    }


    @Test
    fun retryIsOfferedOnlyWhereTheEngineRetries() {

        val failed = message(1, incoming = false, sender = me, receiver = b, state = DeliveryState.FAILED)

        assertTrue(failed.canRetry())
        assertFalse(failed.copy(deliveryState = DeliveryState.SENT).canRetry())
        assertFalse(failed.copy(isIncoming = true).canRetry())
    }


    @Test
    fun deliveryReceiptsAndKeyMessagesAreNeverShownAsChatMessages() {

        val me = "DC-CCCCCC"
        val text = EmergencyMessage(messageId = "m1", senderId = "DC-AAAAAA", receiverId = me, messageType = MessageType.TEXT,
            priority = MessagePriority.NORMAL, content = "hi", createdAt = 0L, expiresAt = 1L, isIncoming = true)
        val ownReceipt = text.copy(messageId = "r:1", senderId = me, receiverId = "DC-AAAAAA", messageType = MessageType.RECEIPT,
            isIncoming = false, deliveryState = DeliveryState.QUEUED, receiptFor = "m1")
        val key = text.copy(messageId = "k:1", messageType = MessageType.KEY)

        assertEquals(listOf("m1"), listOf(text, ownReceipt, key).visibleTo(me).map { it.messageId })
    }


    @Test
    fun deliveredIsShownAsVerifiedOnlyWithAStoredSignedReceipt() {

        val sent = EmergencyMessage(messageId = "m1", senderId = "DC-AAAAAA", receiverId = "DC-CCCCCC", messageType = MessageType.TEXT,
            priority = MessagePriority.NORMAL, content = "hi", createdAt = 0L, expiresAt = 1L)

        assertEquals(DeliveryLabel.DELIVERED, sent.copy(deliveryState = DeliveryState.DELIVERED, deliveryProof = DeliveryProof.LINK_ACK).deliveryLabel(false))
        assertEquals(DeliveryLabel.DELIVERED, sent.copy(deliveryState = DeliveryState.DELIVERED).deliveryLabel(false))
        assertEquals(DeliveryLabel.DELIVERED_VERIFIED, sent.copy(deliveryState = DeliveryState.DELIVERED, deliveryProof = DeliveryProof.SIGNED_RECEIPT).deliveryLabel(false))
        assertEquals(DeliveryLabel.AWAITING_RECEIPT, sent.copy(deliveryState = DeliveryState.FORWARDED, deliveryProof = DeliveryProof.LINK_ACK).deliveryLabel(false))
        assertEquals(DeliveryLabel.PASSED_ON, sent.copy(deliveryState = DeliveryState.FORWARDED).deliveryLabel(false))

        assertEquals("Delivered · verified by recipient", DeliveryLabel.DELIVERED_VERIFIED.text)
        assertTrue(DeliveryLabel.DELIVERED.text.contains("unverified"))
    }
}

package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class MessagePacketCodecTest {

    private val textPacket =
        DataPacket(
            messageId = "6f1c2a52-0d8e-4c55-9d43-3b8f1d0c9a11",
            senderId = "DC-A1B2C3",
            receiverId = "DC-D4E5F6",
            messageType = MessageType.TEXT,
            priority = MessagePriority.NORMAL,
            createdAt = 1_760_000_000_000L,
            ttlSeconds = 86_400,
            hopCount = 0,
            payload = "Need water at the school. नमस्ते 👋"
        )


    @Test
    fun dataPacketRoundTripsWithReceiver() {

        val decoded = MessagePacketCodec.decode(MessagePacketCodec.encode(textPacket))

        assertEquals(textPacket, decoded)
    }


    @Test
    fun sosBroadcastRoundTripsWithLocationAndNoReceiver() {

        val sos = textPacket.copy(
            receiverId = null,
            messageType = MessageType.SOS,
            priority = MessagePriority.CRITICAL,
            hopCount = 3,
            latitude = 28.6139,
            longitude = 77.2090
        )

        assertEquals(sos, MessagePacketCodec.decode(MessagePacketCodec.encode(sos)))
    }


    @Test
    fun ackRoundTrips() {

        val ack = AckPacket("msg-1", "DC-D4E5F6", AckStatus.DUPLICATE)

        assertEquals(ack, MessagePacketCodec.decode(MessagePacketCodec.encode(ack)))
    }


    @Test
    fun malformedOrTruncatedBytesDecodeToNull() {

        val encoded = MessagePacketCodec.encode(textPacket)

        assertNull(MessagePacketCodec.decode(ByteArray(0)))
        assertNull(MessagePacketCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(MessagePacketCodec.decode(encoded.copyOf().also { it[0] = 9 }))
        assertNull(MessagePacketCodec.decode(encoded.copyOf().also { it[1] = 7 }))
        assertNull(MessagePacketCodec.decode("HELLO".toByteArray()))
    }


    @Test
    fun existingTypeIndexesAreUnchangedAndReceiptAndKeyUseTheirReservedIndexes() {

        // Byte 3 of a DATA packet is the type index.
        val types = listOf(MessageType.TEXT, MessageType.SOS, MessageType.STATUS, MessageType.LOCATION)
        assertEquals(listOf<Byte>(0, 1, 2, 3), types.map { MessagePacketCodec.encode(textPacket.copy(messageType = it))[3] })

        assertEquals(4, MessagePacketCodec.TYPE_INDEX_RECEIPT)
        assertEquals(5, MessagePacketCodec.TYPE_INDEX_KEY)

        // RECEIPT at index 4 and KEY at index 5 round-trip; anything beyond is still dropped.
        for ((type, index) in listOf(MessageType.RECEIPT to MessagePacketCodec.TYPE_INDEX_RECEIPT, MessageType.KEY to MessagePacketCodec.TYPE_INDEX_KEY)) {
            val packet = textPacket.copy(messageType = type)
            val encoded = MessagePacketCodec.encode(packet)
            assertEquals(index.toByte(), encoded[3])
            assertEquals(packet, MessagePacketCodec.decode(encoded))
        }

        val encoded = MessagePacketCodec.encode(textPacket)
        assertNull(MessagePacketCodec.decode(encoded.copyOf().also { it[3] = 6 }))
    }


    @Test(expected = IllegalArgumentException::class)
    fun unknownTypesCannotBeEncoded() {

        MessagePacketCodec.encode(textPacket.copy(messageType = "VOICE"))
    }


    @Test(expected = IllegalArgumentException::class)
    fun oversizedPayloadIsRejectedAtEncode() {

        MessagePacketCodec.encode(textPacket.copy(payload = "x".repeat(MessagePacketCodec.MAX_PAYLOAD_BYTES + 1)))
    }


    @Test
    fun largestPayloadStillFitsInFrameLimitAtMinimumMtu() {

        val packet = MessagePacketCodec.encode(
            textPacket.copy(payload = "x".repeat(MessagePacketCodec.MAX_PAYLOAD_BYTES), latitude = 1.0, longitude = 1.0)
        )

        assertTrue(PacketFraming.split(packet, 0, PacketFraming.DEFAULT_MTU).size <= PacketFraming.MAX_FRAMES)
    }
}

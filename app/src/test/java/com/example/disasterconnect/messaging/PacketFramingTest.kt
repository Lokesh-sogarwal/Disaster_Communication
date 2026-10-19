package com.example.disasterconnect.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class PacketFramingTest {

    private val packet = ByteArray(1000) { (it * 7).toByte() }


    @Test
    fun framesFitMinimumMtuAndReassemble() {

        val frames = PacketFraming.split(packet, transferId = 42, mtu = PacketFraming.DEFAULT_MTU)

        assertTrue(frames.all { it.size <= PacketFraming.DEFAULT_MTU - 3 })
        assertEquals(67, frames.size)
        assertArrayEquals(packet, reassemble(frames))
    }


    @Test
    fun negotiatedMtuUsesFewerFrames() {

        val frames = PacketFraming.split(packet, transferId = 1, mtu = 185)

        assertTrue(frames.all { it.size <= 182 })
        assertEquals(6, frames.size)
        assertArrayEquals(packet, reassemble(frames))
    }


    @Test
    fun framesNeverExceedTheGattAttributeLimitEvenAtTheMtuAndroid14Forces() {

        // Regression (two-phone QA, vivo V2509 ↔ OnePlus CPH2717, Android 16): MTU 517 gave 514-byte
        // frames and notifyCharacteristicChanged threw, killing the app on a 1024-byte message.
        val largest = MessagePacketCodec.encode(
            DataPacket(
                messageId = "6f1c2a52-0d8e-4c55-9d43-3b8f1d0c9a11",
                senderId = "DC-DAADF7",
                receiverId = "DC-D85D30",
                messageType = "TEXT",
                priority = "NORMAL",
                createdAt = 0L,
                ttlSeconds = 86_400,
                hopCount = 0,
                payload = "x".repeat(MessagePacketCodec.MAX_PAYLOAD_BYTES)
            )
        )

        for (mtu in listOf(23, 185, 247, 512, 515, 516, 517, 600)) {
            val frames = PacketFraming.split(largest, 1, mtu)
            assertTrue("mtu $mtu", frames.all { it.size <= PacketFraming.MAX_ATTRIBUTE_BYTES })
            assertTrue(largest.contentEquals(reassemble(frames)))
        }
        assertEquals(PacketFraming.MAX_ATTRIBUTE_BYTES, PacketFraming.split(largest, 1, 517).first().size)
    }


    @Test
    fun outOfOrderAndDuplicateFramesStillReassembleOnce() {

        val frames = PacketFraming.split(packet, transferId = 9, mtu = 100)
        val reassembler = PacketReassembler()
        val shuffled = frames.reversed() + frames.first()

        val results = shuffled.mapNotNull { reassembler.accept(it, now = 0L) }

        assertEquals(1, results.size)
        assertArrayEquals(packet, results.single())
    }


    @Test
    fun missingFrameNeverCompletesAndStalePartialIsDropped() {

        val frames = PacketFraming.split(packet, transferId = 3, mtu = 100)
        val reassembler = PacketReassembler(timeoutMillis = 1_000L)

        frames.drop(1).forEach { assertNull(reassembler.accept(it, now = 0L)) }

        // After the timeout, the late first frame starts a new partial instead of completing the old one.
        assertNull(reassembler.accept(frames.first(), now = 5_000L))
    }


    @Test
    fun interleavedTransfersAreKeptApart() {

        val other = ByteArray(300) { 1 }
        val first = PacketFraming.split(packet, transferId = 1, mtu = 100)
        val second = PacketFraming.split(other, transferId = 2, mtu = 100)
        val reassembler = PacketReassembler()

        val results = (0 until maxOf(first.size, second.size))
            .flatMap { listOfNotNull(first.getOrNull(it), second.getOrNull(it)) }
            .mapNotNull { reassembler.accept(it, now = 0L) }

        assertEquals(2, results.size)
        assertArrayEquals(other, results[0])
        assertArrayEquals(packet, results[1])
    }


    @Test
    fun handshakeTextIsNotAFrame() {

        assertFalse(PacketFraming.isFrame("HELLO".toByteArray()))
        assertFalse(PacketFraming.isFrame("ACK|DC-A1B2C3".toByteArray()))
    }


    @Test
    fun queueKeepsOneFrameInFlightAndReportsEachTransferOnce() {

        val queue = OutgoingFrameQueue<String>()
        val results = mutableListOf<Pair<String, Boolean>>()

        val first = queue.enqueue("a", listOf(byteArrayOf(1), byteArrayOf(2))) { results += "a" to it }
        val second = queue.enqueue("b", listOf(byteArrayOf(3))) { results += "b" to it }

        assertEquals("a", first.next?.first)
        assertNull(second.next)

        val afterFirstFrame = queue.onFrameResult(true)
        assertArrayEquals(byteArrayOf(2), afterFirstFrame.next?.second)

        val afterTransferA = queue.onFrameResult(true)
        afterTransferA.completions.forEach { it() }
        assertEquals("b", afterTransferA.next?.first)

        queue.onFrameResult(false).completions.forEach { it() }

        assertEquals(listOf("a" to true, "b" to false), results)
    }


    @Test
    fun disconnectFailsOnlyThatTargetsTransfers() {

        val queue = OutgoingFrameQueue<String>()
        val results = mutableListOf<Pair<String, Boolean>>()
        queue.enqueue("a", listOf(byteArrayOf(1))) { results += "a" to it }
        queue.enqueue("b", listOf(byteArrayOf(2))) { results += "b" to it }

        val outcome = queue.failWhere { it == "a" }
        outcome.completions.forEach { it() }

        assertEquals(listOf("a" to false), results)
        assertEquals("b", outcome.next?.first)
    }


    private fun reassemble(frames: List<ByteArray>): ByteArray? {

        val reassembler = PacketReassembler()
        return frames.mapNotNull { reassembler.accept(it, now = 0L) }.singleOrNull()
    }
}

package com.example.disasterconnect.messaging

import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets


// =========================================================
// WIRE PACKETS
// =========================================================

sealed interface WirePacket

/** An emergency message as it travels over one hop. */
data class DataPacket(
    val messageId: String,
    val senderId: String,
    val receiverId: String?,
    val messageType: String,
    val priority: String,
    val createdAt: Long,
    // Remaining lifetime when sent; relative so that phone clocks do not need to agree.
    val ttlSeconds: Int,
    val hopCount: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val payload: String
) : WirePacket

enum class AckStatus(val code: Byte) {
    ACCEPTED(0),
    DUPLICATE(1),
    REJECTED(2),
    EXPIRED(3)
}

/** Sent by the receiving phone once a [DataPacket] has been stored. */
data class AckPacket(
    val messageId: String,
    val fromNodeId: String,
    val status: AckStatus
) : WirePacket


// =========================================================
// BINARY CODEC
// =========================================================

/**
 * Compact big-endian encoding.
 *
 * DATA: version, kind, flags, type, priority, hops, createdAt(8), ttlSeconds(4),
 *       messageId, senderId, [receiverId], [lat(8), lng(8)], payload(u16 length)
 * ACK:  version, kind, status, messageId, fromNodeId
 *
 * Strings are UTF-8 with a 1-byte length unless noted. Trailing bytes are ignored
 * so later protocol versions can append fields.
 */
object MessagePacketCodec {

    const val VERSION: Byte = 1

    const val MAX_PAYLOAD_BYTES = 1024

    const val MAX_ID_BYTES = 64

    private const val KIND_DATA: Byte = 1
    private const val KIND_ACK: Byte = 2
    private const val FLAG_RECEIVER = 0x01
    private const val FLAG_LOCATION = 0x02

    /**
     * Type indexes 4 and 5 belong to [MessageType.RECEIPT] and [MessageType.KEY] (rules.md §13).
     * KEY is handled by KeyExchange (direct links only); RECEIPT by MessagingEngine's receipt handler.
     * Neither ever reaches the inbox. Existing indexes never change.
     */
    const val TYPE_INDEX_RECEIPT = 4

    const val TYPE_INDEX_KEY = 5

    private val TYPES = listOf(MessageType.TEXT, MessageType.SOS, MessageType.STATUS, MessageType.LOCATION)

    private val ACTIVE_RESERVED_TYPES = mapOf(TYPE_INDEX_RECEIPT to MessageType.RECEIPT, TYPE_INDEX_KEY to MessageType.KEY)

    private fun typeIndex(type: String): Int =
        TYPES.indexOf(type).takeIf { it >= 0 } ?: ACTIVE_RESERVED_TYPES.entries.firstOrNull { it.value == type }?.key ?: -1

    private fun typeAt(index: Int): String? = TYPES.getOrNull(index) ?: ACTIVE_RESERVED_TYPES[index]
    private val PRIORITIES = listOf(MessagePriority.CRITICAL, MessagePriority.HIGH, MessagePriority.NORMAL)

    fun payloadFits(text: String): Boolean =
        text.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES

    /** @throws IllegalArgumentException if the packet cannot be represented on the wire. */
    fun encode(packet: DataPacket): ByteArray {
        val messageId = idBytes(packet.messageId)
        val senderId = idBytes(packet.senderId)
        val receiverId = packet.receiverId?.let(::idBytes)
        val payload = packet.payload.toByteArray(StandardCharsets.UTF_8)
        val type = typeIndex(packet.messageType)
        val priority = PRIORITIES.indexOf(packet.priority)
        val hasLocation = packet.latitude != null && packet.longitude != null
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Message is too long (${payload.size} bytes)." }
        require(type >= 0) { "Unknown message type ${packet.messageType}." }
        require(priority >= 0) { "Unknown priority ${packet.priority}." }
        require(packet.hopCount in 0..255) { "Invalid hop count." }
        require(packet.ttlSeconds >= 0) { "Invalid TTL." }

        val size = 6 + 8 + 4 + 1 + messageId.size + 1 + senderId.size +
            (receiverId?.let { 1 + it.size } ?: 0) + (if (hasLocation) 16 else 0) + 2 + payload.size
        val flags = (if (receiverId != null) FLAG_RECEIVER else 0) or (if (hasLocation) FLAG_LOCATION else 0)

        return ByteBuffer.allocate(size).apply {
            put(VERSION); put(KIND_DATA); put(flags.toByte()); put(type.toByte()); put(priority.toByte())
            put(packet.hopCount.toByte()); putLong(packet.createdAt); putInt(packet.ttlSeconds)
            putShortString(messageId); putShortString(senderId)
            receiverId?.let { putShortString(it) }
            if (hasLocation) { putDouble(packet.latitude!!); putDouble(packet.longitude!!) }
            putShort(payload.size.toShort()); put(payload)
        }.array()
    }

    fun encode(ack: AckPacket): ByteArray {
        val messageId = idBytes(ack.messageId)
        val fromNodeId = idBytes(ack.fromNodeId)
        return ByteBuffer.allocate(3 + 1 + messageId.size + 1 + fromNodeId.size).apply {
            put(VERSION); put(KIND_ACK); put(ack.status.code)
            putShortString(messageId); putShortString(fromNodeId)
        }.array()
    }

    /** Returns null for anything malformed, unknown or from another protocol version. Never throws. */
    fun decode(bytes: ByteArray): WirePacket? = try {
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.get() != VERSION) null else when (buffer.get()) {
            KIND_DATA -> decodeData(buffer)
            KIND_ACK -> decodeAck(buffer)
            else -> null
        }
    } catch (_: BufferUnderflowException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeData(buffer: ByteBuffer): DataPacket? {
        val flags = buffer.get().toInt()
        val type = typeAt(buffer.get().toInt()) ?: return null
        val priority = PRIORITIES.getOrNull(buffer.get().toInt()) ?: return null
        val hopCount = buffer.get().toInt() and 0xFF
        val createdAt = buffer.getLong()
        val ttlSeconds = buffer.getInt().takeIf { it >= 0 } ?: return null
        val messageId = buffer.getShortString() ?: return null
        val senderId = buffer.getShortString() ?: return null
        val receiverId = if (flags and FLAG_RECEIVER != 0) buffer.getShortString() ?: return null else null
        var latitude: Double? = null
        var longitude: Double? = null
        if (flags and FLAG_LOCATION != 0) {
            latitude = buffer.getDouble().takeIf { it in -90.0..90.0 } ?: return null
            longitude = buffer.getDouble().takeIf { it in -180.0..180.0 } ?: return null
        }
        val payloadSize = buffer.getShort().toInt() and 0xFFFF
        if (payloadSize > MAX_PAYLOAD_BYTES) return null
        val payload = ByteArray(payloadSize).also { buffer.get(it) }
        return DataPacket(
            messageId, senderId, receiverId, type, priority, createdAt, ttlSeconds, hopCount,
            latitude, longitude, String(payload, StandardCharsets.UTF_8)
        )
    }

    private fun decodeAck(buffer: ByteBuffer): AckPacket? {
        val statusCode = buffer.get()
        val status = AckStatus.entries.firstOrNull { it.code == statusCode } ?: return null
        val messageId = buffer.getShortString() ?: return null
        val fromNodeId = buffer.getShortString() ?: return null
        return AckPacket(messageId, fromNodeId, status)
    }

    private fun idBytes(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ID_BYTES) { "Invalid identifier." }
        return bytes
    }

    private fun ByteBuffer.putShortString(bytes: ByteArray) {
        put(bytes.size.toByte()); put(bytes)
    }

    private fun ByteBuffer.getShortString(): String? {
        val length = get().toInt() and 0xFF
        if (length == 0 || length > MAX_ID_BYTES) return null
        return String(ByteArray(length).also { get(it) }, StandardCharsets.UTF_8)
    }
}

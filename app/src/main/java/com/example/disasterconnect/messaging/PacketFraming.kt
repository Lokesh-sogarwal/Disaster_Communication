package com.example.disasterconnect.messaging


// =========================================================
// MTU-SIZED FRAMES
// =========================================================

/**
 * Splits a packet into frames that fit one GATT write/notification.
 *
 * Frame: MAGIC(1) transferId(2) index(1) count(1) chunk(...)
 *
 * MAGIC is 0xFE, which never appears in UTF-8, so frames cannot be confused with the
 * text HELLO / ACK handshake that shares the same characteristic.
 */
object PacketFraming {

    const val MAGIC: Byte = 0xFE.toByte()

    const val HEADER_BYTES = 5

    const val DEFAULT_MTU = 23

    const val MAX_MTU = 517

    const val MAX_FRAMES = 255

    // ATT opcode + handle that every write/notification spends out of the MTU.
    private const val ATT_OVERHEAD = 3

    // GATT caps any attribute value at 512 bytes, whatever the MTU. Android 14+ always negotiates
    // MTU 517, and a 514-byte frame makes writeCharacteristic / notifyCharacteristicChanged throw.
    const val MAX_ATTRIBUTE_BYTES = 512

    fun isFrame(value: ByteArray): Boolean =
        value.size > HEADER_BYTES && value[0] == MAGIC

    fun chunkSize(mtu: Int): Int =
        minOf(mtu.coerceIn(DEFAULT_MTU, MAX_MTU) - ATT_OVERHEAD, MAX_ATTRIBUTE_BYTES) - HEADER_BYTES

    /** @throws IllegalArgumentException if the packet needs more than [MAX_FRAMES] frames. */
    fun split(packet: ByteArray, transferId: Int, mtu: Int): List<ByteArray> {
        require(packet.isNotEmpty()) { "Empty packet." }
        val chunk = chunkSize(mtu)
        val count = (packet.size + chunk - 1) / chunk
        require(count <= MAX_FRAMES) { "Packet needs $count frames; limit is $MAX_FRAMES." }
        return (0 until count).map { index ->
            val start = index * chunk
            val end = minOf(packet.size, start + chunk)
            ByteArray(HEADER_BYTES + end - start).also { frame ->
                frame[0] = MAGIC
                frame[1] = (transferId shr 8).toByte()
                frame[2] = transferId.toByte()
                frame[3] = index.toByte()
                frame[4] = count.toByte()
                packet.copyInto(frame, HEADER_BYTES, start, end)
            }
        }
    }
}


// =========================================================
// REASSEMBLY
// =========================================================

/**
 * Rebuilds packets from frames of one link. Tolerates duplicate and out-of-order
 * frames, and drops partial packets that stop arriving.
 */
class PacketReassembler(
    private val timeoutMillis: Long = 30_000L,
    private val maxPartialPackets: Int = 4
) {

    private class Partial(val count: Int, val startedAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    private val partials = LinkedHashMap<Int, Partial>()

    /** Returns the complete packet when [frame] was its last missing piece, else null. */
    @Synchronized
    fun accept(frame: ByteArray, now: Long): ByteArray? {
        if (!PacketFraming.isFrame(frame)) return null
        val transferId = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val index = frame[3].toInt() and 0xFF
        val count = frame[4].toInt() and 0xFF
        if (count == 0 || index >= count) return null

        partials.entries.removeAll { now - it.value.startedAt > timeoutMillis }
        var partial = partials[transferId]
        if (partial != null && partial.count != count) {
            partials.remove(transferId)
            partial = null
        }
        if (partial == null) {
            if (partials.size >= maxPartialPackets) partials.remove(partials.keys.first())
            partial = Partial(count, now).also { partials[transferId] = it }
        }
        if (partial.chunks[index] == null) {
            partial.chunks[index] = frame.copyOfRange(PacketFraming.HEADER_BYTES, frame.size)
            partial.received++
        }
        if (partial.received < count) return null

        partials.remove(transferId)
        val size = partial.chunks.sumOf { it!!.size }
        val packet = ByteArray(size)
        var offset = 0
        partial.chunks.forEach { chunk ->
            chunk!!.copyInto(packet, offset)
            offset += chunk.size
        }
        return packet
    }
}


// =========================================================
// OUTGOING FRAME QUEUE
// =========================================================

/**
 * Android BLE allows one outstanding write or notification at a time. This queue keeps
 * exactly one frame in flight and reports each transfer's outcome once.
 *
 * Callers run [Outcome.completions] and transmit [Outcome.next] outside the lock.
 */
class OutgoingFrameQueue<T> {

    class Outcome<T>(val next: Pair<T, ByteArray>?, val completions: List<() -> Unit>)

    private class Transfer<T>(val target: T, val frames: List<ByteArray>, val onComplete: (Boolean) -> Unit) {
        var sent = 0
    }

    private val transfers = ArrayDeque<Transfer<T>>()

    private var inFlight: Transfer<T>? = null

    @Synchronized
    fun inFlightTarget(): T? = inFlight?.target

    @Synchronized
    fun enqueue(target: T, frames: List<ByteArray>, onComplete: (Boolean) -> Unit): Outcome<T> {
        transfers.addLast(Transfer(target, frames, onComplete))
        return Outcome(if (inFlight == null) takeNext() else null, emptyList())
    }

    /** Records whether the frame in flight was confirmed by the BLE stack. */
    @Synchronized
    fun onFrameResult(success: Boolean): Outcome<T> {
        val transfer = inFlight ?: return Outcome(null, emptyList())
        inFlight = null
        val completions = mutableListOf<() -> Unit>()
        if (success) {
            transfer.sent++
            if (transfer.sent == transfer.frames.size) {
                transfers.remove(transfer)
                completions += { transfer.onComplete(true) }
            }
        } else {
            transfers.remove(transfer)
            completions += { transfer.onComplete(false) }
        }
        return Outcome(takeNext(), completions)
    }

    /** Fails every transfer for targets matching [predicate], e.g. after a disconnect. */
    @Synchronized
    fun failWhere(predicate: (T) -> Boolean): Outcome<T> {
        val failed = transfers.filter { predicate(it.target) }
        transfers.removeAll(failed)
        val clearedInFlight = inFlight?.let { it in failed } == true
        if (clearedInFlight) inFlight = null
        val next = if (inFlight == null) takeNext() else null
        return Outcome(next, failed.map { transfer -> { transfer.onComplete(false) } })
    }

    private fun takeNext(): Pair<T, ByteArray>? {
        val transfer = transfers.firstOrNull() ?: return null
        inFlight = transfer
        return transfer.target to transfer.frames[transfer.sent]
    }
}

package com.example.disasterconnect.security

import com.example.disasterconnect.data.MessagePriority
import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.messaging.DataPacket
import com.example.disasterconnect.messaging.EcdsaP256
import com.example.disasterconnect.messaging.MessagePacketCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


// =========================================================
// DIRECT-LINK KEY EXCHANGE
// =========================================================

enum class KeyReceiveResult {
    STORED,
    ALREADY_STORED,
    CONFLICT,
    NOT_A_KEY,
    NOT_DIRECT,
    NOT_FOR_THIS_PHONE,
    WRONG_SENDER,
    INVALID
}


/**
 * Swaps public keys with directly linked phones (rules.md §13, §15). Pure Kotlin.
 *
 * - KEY packets are accepted only with hopCount 0, addressed to this phone, from the phone at the
 *   other end of the link, naming that same node ID in the payload. They are never stored as
 *   messages and never forwarded.
 * - Received keys only ever enter the trust store as SEEN. A different key for a known node ID is a
 *   conflict: the stored key is kept and the node is flagged "Key changed".
 * - Our key is sent at most once per peer per app run (older apps silently drop KEY packets),
 *   plus once more whenever the user explicitly asks from the verification screen.
 * - Verification needs the key received on the current link to be exactly the stored one.
 */
class KeyExchange(
    private val store: TrustedKeyStore,
    private val localNodeId: String,
    localPublicKey: ByteArray
) {

    private val localKey = localPublicKey.copyOf()

    val localPublicKey: ByteArray
        get() = localKey.copyOf()

    private val mutex = Mutex()

    private val sentTo = ConcurrentHashMap.newKeySet<String>()

    // Keys received over the current link, already confirmed equal to the stored key.
    private val _linkKeys = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    val linkKeys: StateFlow<Map<String, ByteArray>> = _linkKeys

    // Node IDs that presented a key different from the stored one during this app run.
    private val _conflicts = MutableStateFlow<Set<String>>(emptySet())

    val conflicts: StateFlow<Set<String>> = _conflicts

    init {
        require(EcdsaP256.parsePublicKey(localKey) != null) { "Local key is not a P-256 public key." }
    }


    // ---------------------------------------------------------
    // Sending
    // ---------------------------------------------------------

    /** Our KEY packet for [peerId], or null if it was already sent to that peer during this run. */
    fun keyPacketFor(peerId: String, now: Long): ByteArray? {
        if (peerId == localNodeId || !sentTo.add(peerId)) return null
        return MessagePacketCodec.encode(
            DataPacket(
                messageId = "k:" + UUID.randomUUID(),
                senderId = localNodeId,
                receiverId = peerId,
                messageType = MessageType.KEY,
                priority = MessagePriority.NORMAL,
                createdAt = now,
                ttlSeconds = KEY_TTL_SECONDS,
                hopCount = 0,
                payload = KeyPayloadCodec.encode(AnnouncedKey(localNodeId, localKey))
            )
        )
    }

    /** The send failed, or the user asked to exchange keys again: allow one more send. */
    fun allowResend(peerId: String) {
        sentTo.remove(peerId)
    }


    // ---------------------------------------------------------
    // Receiving
    // ---------------------------------------------------------

    suspend fun onKeyPacket(packet: DataPacket, peerId: String, now: Long): KeyReceiveResult = mutex.withLock {
        if (packet.messageType != MessageType.KEY) return@withLock KeyReceiveResult.NOT_A_KEY
        if (packet.hopCount != 0) return@withLock KeyReceiveResult.NOT_DIRECT
        if (packet.receiverId != localNodeId) return@withLock KeyReceiveResult.NOT_FOR_THIS_PHONE
        if (packet.senderId != peerId || peerId == localNodeId) return@withLock KeyReceiveResult.WRONG_SENDER
        if (packet.ttlSeconds <= 0 || packet.latitude != null || packet.longitude != null) {
            return@withLock KeyReceiveResult.INVALID
        }
        val announced = KeyPayloadCodec.decode(packet.payload) ?: return@withLock KeyReceiveResult.INVALID
        if (announced.nodeId != peerId) return@withLock KeyReceiveResult.WRONG_SENDER

        when (val result = store.recordSeen(peerId, announced.publicKey, now)) {
            is SeenKeyResult.Stored, is SeenKeyResult.AlreadyStored -> {
                if (peerId in _conflicts.value) return@withLock KeyReceiveResult.CONFLICT
                _linkKeys.update { it + (peerId to announced.publicKey) }
                if (result is SeenKeyResult.Stored) KeyReceiveResult.STORED else KeyReceiveResult.ALREADY_STORED
            }
            is SeenKeyResult.Conflict -> {
                _conflicts.update { it + peerId }
                _linkKeys.update { it - peerId }
                KeyReceiveResult.CONFLICT
            }
            SeenKeyResult.InvalidKey, SeenKeyResult.InvalidNodeId -> KeyReceiveResult.INVALID
        }
    }

    /** Any pending verification for [peerId] ends with the link. */
    fun onLinkClosed(peerId: String) {
        _linkKeys.update { it - peerId }
    }


    // ---------------------------------------------------------
    // Verification
    // ---------------------------------------------------------

    /** The code for the key received over the current link, or null if there is none to compare. */
    fun safetyCode(peerId: String): String? =
        _linkKeys.value[peerId]?.let { SafetyCode.compute(localNodeId, localKey, peerId, it) }

    /**
     * The user tapped "Codes match" for the code made from [expectedFingerprint]. Applied only if the
     * peer is still linked and still presents exactly that key; otherwise nothing changes.
     */
    suspend fun confirmVerification(peerId: String, expectedFingerprint: ByteArray, linkReady: Boolean, now: Long): VerifyResult =
        mutex.withLock {
            if (!linkReady) return@withLock VerifyResult.NotLinked
            if (peerId in _conflicts.value) return@withLock VerifyResult.KeyMismatch
            val linkKey = _linkKeys.value[peerId] ?: return@withLock VerifyResult.NotLinked
            if (!EcdsaP256.fingerprint(linkKey).contentEquals(expectedFingerprint)) return@withLock VerifyResult.KeyMismatch
            store.confirmVerified(peerId, expectedFingerprint, now)
        }

    companion object {

        // KEY packets only cross one direct link, so a short lifetime is enough.
        const val KEY_TTL_SECONDS = 60
    }
}

package com.example.disasterconnect.messaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.disasterconnect.ble.BleConnectionManager
import com.example.disasterconnect.ble.BleLinkListener
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.data.MessageType
import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.network.NearbyNodeState
import com.example.disasterconnect.security.DeviceIdentity
import com.example.disasterconnect.security.IdentityStatus
import com.example.disasterconnect.security.KeyExchange
import com.example.disasterconnect.security.RoomTrustedKeyRecords
import com.example.disasterconnect.security.TrustedKeyStore
import com.example.disasterconnect.security.VerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap


// =========================================================
// BLE MESSAGING COORDINATOR
// =========================================================

/**
 * Process-wide glue between [MessagingEngine] (rules + Room) and [BleConnectionManager]
 * (links). Lives as long as the app process, independent of any screen.
 */
object MessagingCoordinator : BleLinkListener {

    private const val TICK_MILLIS = 5_000L
    private const val AUTO_CONNECT_COOLDOWN_MILLIS = 30_000L
    private const val AUTO_CONNECT_VISIBLE_MILLIS = 10_000L
    private const val IDLE_DISCONNECT_MILLIS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var engine: MessagingEngine? = null
    private var appContext: Context? = null
    @Volatile private var localNodeId: String? = null

    // Peers with a message transfer in progress: one message in flight per link.
    private val sending = ConcurrentHashMap.newKeySet<String>()
    private val lastAutoConnectAt = ConcurrentHashMap<String, Long>()
    @Volatile private var autoConnectedPeer: String? = null
    @Volatile private var lastActivityAt = 0L

    // Personal-contact key exchange. Independent of message and SOS handling: nothing there waits for it.
    private val _identity = MutableStateFlow<IdentityStatus?>(null)
    val identity: StateFlow<IdentityStatus?> = _identity

    private val _keyExchange = MutableStateFlow<KeyExchange?>(null)
    val keyExchange: StateFlow<KeyExchange?> = _keyExchange


    // ---------------------------------------------------------
    // Public API
    // ---------------------------------------------------------

    /** Idempotent. Returns whether BLE is running; messaging works offline either way. */
    @Synchronized
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        if (engine == null) {
            appContext = app
            val database = AppDatabase.getDatabase(app)
            val nodeId = BleConnectionManager.nodeId(app)
            localNodeId = nodeId
            val trustedKeys = TrustedKeyStore(RoomTrustedKeyRecords(database.trustedKeyDao()))
            engine = MessagingEngine(
                RoomMessageStore(database.emergencyMessageDao(), database.messageHandoffDao()),
                nodeId,
                receiptTrust = VerifiedKeyReceiptTrust(trustedKeys),
                // Receipts are signed only with this phone's real Keystore key; none while it is unavailable.
                receiptSigner = { (_identity.value as? IdentityStatus.Ready)?.signer }
            )
            BleConnectionManager.linkListener = this
            scope.launch { startKeyExchange(app, database, nodeId) }
            scope.launch {
                while (isActive) {
                    runCatching { tick() }
                    delay(TICK_MILLIS)
                }
            }
        }
        return BleConnectionManager.startNode(app)
    }

    /** Stores a text for [peerId] and sends it now if linked, otherwise when the peer is next nearby. */
    suspend fun sendText(context: Context, peerId: String, text: String): EmergencyMessage {
        start(context)
        val message = engine!!.createText(peerId, text, System.currentTimeMillis())
        flush(peerId)
        return message
    }

    fun retry(messageId: String) {
        scope.launch {
            engine?.retry(messageId)
            flushReadyLinks()
        }
    }

    /** User asked (verification screen) to send this phone's key to [peerId] again. */
    fun requestKeyExchange(peerId: String) {
        _keyExchange.value?.allowResend(peerId)
        sendKey(peerId)
    }

    /** The user tapped "Codes match". Applied only if [peerId] still presents exactly that key. */
    suspend fun confirmVerification(peerId: String, expectedFingerprint: ByteArray): VerifyResult =
        _keyExchange.value?.confirmVerification(
            peerId,
            expectedFingerprint,
            BleConnectionManager.isLinkReady(peerId),
            System.currentTimeMillis()
        ) ?: VerifyResult.NotLinked


    // ---------------------------------------------------------
    // Link events
    // ---------------------------------------------------------

    override fun onLinkReady(nodeId: String) {
        lastActivityAt = System.currentTimeMillis()
        flush(nodeId)
        sendKey(nodeId)
    }

    override fun onLinkClosed(nodeId: String) {
        _keyExchange.value?.onLinkClosed(nodeId)
        sending.remove(nodeId)
        if (autoConnectedPeer == nodeId) autoConnectedPeer = null
        scope.launch { engine?.requeueForPeer(nodeId) }
    }

    override fun onPacketReceived(nodeId: String, packet: ByteArray) {
        lastActivityAt = System.currentTimeMillis()
        scope.launch {
            val engine = engine ?: return@launch
            when (val decoded = MessagePacketCodec.decode(packet)) {
                is DataPacket -> {
                    // KEY packets never reach the engine: not stored, shown, ACKed or relayed.
                    if (decoded.messageType == MessageType.KEY) {
                        _keyExchange.value?.onKeyPacket(decoded, nodeId, System.currentTimeMillis())
                        // Reply with this phone's key if that peer has not had it during this run.
                        sendKey(nodeId)
                        return@launch
                    }
                    val result = engine.onDataReceived(decoded, nodeId, System.currentTimeMillis())
                    // A lost ACK is harmless: the sender retries and gets DUPLICATE.
                    BleConnectionManager.send(nodeId, MessagePacketCodec.encode(result.ack)) { }
                    result.message?.let { stored ->
                        // Relay copies and receipts are carried for someone else, so this phone's user is not notified.
                        if (stored.messageType != MessageType.RECEIPT && (stored.receiverId == null || stored.receiverId == localNodeId)) {
                            announce(stored)
                        }
                        // New broadcasts, relay copies and a new message's signed receipt can go out straight away.
                        flushReadyLinks()
                    }
                }
                is AckPacket -> {
                    engine.onAck(decoded, nodeId, System.currentTimeMillis())
                    flush(nodeId)
                }
                null -> Unit
            }
        }
    }


    // ---------------------------------------------------------
    // Sending
    // ---------------------------------------------------------

    private fun flush(peerId: String) {
        if (!BleConnectionManager.isLinkReady(peerId) || !sending.add(peerId)) return
        scope.launch {
            val engine = engine
            val outbound = engine?.nextOutgoing(peerId, System.currentTimeMillis())
            if (engine == null || outbound == null) {
                sending.remove(peerId)
                return@launch
            }
            lastActivityAt = System.currentTimeMillis()
            BleConnectionManager.send(peerId, outbound.bytes) { success ->
                scope.launch {
                    engine.onTransmitted(outbound.messageId, peerId, success, System.currentTimeMillis())
                    sending.remove(peerId)
                    if (success) flush(peerId)
                }
            }
        }
    }

    private fun sendKey(peerId: String) {
        val exchange = _keyExchange.value ?: return
        if (!BleConnectionManager.isLinkReady(peerId)) return
        val bytes = exchange.keyPacketFor(peerId, System.currentTimeMillis()) ?: return
        BleConnectionManager.send(peerId, bytes) { success ->
            // A failed write may be retried on the next link; an older app that drops KEY is not retried.
            if (!success) exchange.allowResend(peerId)
        }
    }

    private suspend fun startKeyExchange(app: Context, database: AppDatabase, nodeId: String) {
        val status = DeviceIdentity(app).loadOrCreate()
        _identity.value = status
        if (status !is IdentityStatus.Ready) return
        _keyExchange.value = KeyExchange(
            TrustedKeyStore(RoomTrustedKeyRecords(database.trustedKeyDao())),
            nodeId,
            status.signer.publicKey
        )
        // Links that were ready before the Keystore finished loading.
        BleConnectionManager.connections.value.keys.forEach(::sendKey)
        // Receipts for messages that arrived before the key was available.
        engine?.recoverReceipts(System.currentTimeMillis())
        flushReadyLinks()
    }

    /**
     * Only VERIFIED keys may prove delivery (rules.md §11, §15). Personal direct messages only:
     * SOS and broadcasts never consult this.
     */
    private class VerifiedKeyReceiptTrust(private val keys: TrustedKeyStore) : ReceiptTrust {

        override suspend fun requiresSignedReceipt(nodeId: String) =
            keys.find(nodeId)?.trust == TrustState.VERIFIED

        override suspend fun receiptKey(nodeId: String): ByteArray? {
            if (nodeId in (_keyExchange.value?.conflicts?.value ?: emptySet())) return null
            return keys.find(nodeId)?.takeIf { it.trust == TrustState.VERIFIED }?.publicKey
        }
    }

    private fun flushReadyLinks() {
        val peers = BleConnectionManager.connections.value.keys
        peers.filter(BleConnectionManager::isLinkReady).forEach(::flush)
    }

    private suspend fun tick() {
        val engine = engine ?: return
        val now = System.currentTimeMillis()
        engine.requeueTimedOut(now)
        engine.recoverReceipts(now)
        flushReadyLinks()

        val outgoingPeer = BleConnectionManager.outgoingLinkNodeId
        if (outgoingPeer != null) {
            // Free the single client slot once an automatic connection has nothing left to do.
            if (outgoingPeer == autoConnectedPeer && outgoingPeer !in sending &&
                now - lastActivityAt > IDLE_DISCONNECT_MILLIS && !engine.hasPendingFor(outgoingPeer, now)
            ) {
                autoConnectedPeer = null
                BleConnectionManager.disconnect(outgoingPeer)
            }
            return
        }
        if (BleConnectionManager.hasOutgoingLink) return
        autoConnect(engine, now)
    }

    /** Connects to the strongest visible peer that has something waiting for it. */
    private suspend fun autoConnect(engine: MessagingEngine, now: Long) {
        val context = appContext ?: return
        val candidates = NearbyNetworkRepository.nodes.value.values
            .filter { it.state == NearbyNodeState.NEARBY && now - it.lastSeenAt < AUTO_CONNECT_VISIBLE_MILLIS }
            .filter { !BleConnectionManager.isLinkReady(it.nodeId) }
            .filter { now - (lastAutoConnectAt[it.nodeId] ?: 0L) > AUTO_CONNECT_COOLDOWN_MILLIS }
            .sortedByDescending { it.rssi }
        for (node in candidates) {
            if (!engine.hasPendingFor(node.nodeId, now)) continue
            lastAutoConnectAt[node.nodeId] = now
            if (BleConnectionManager.connect(context, node.nodeId, node.device) == null) {
                autoConnectedPeer = node.nodeId
                lastActivityAt = now
            }
            return
        }
    }


    // ---------------------------------------------------------
    // User feedback
    // ---------------------------------------------------------

    private fun announce(message: EmergencyMessage) {
        val context = appContext ?: return
        val text = if (message.messageType == MessageType.SOS) {
            "🚨 SOS received from ${message.senderId}"
        } else {
            "📩 New message from ${message.senderId}"
        }
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}

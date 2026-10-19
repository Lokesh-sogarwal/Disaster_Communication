package com.example.disasterconnect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.disasterconnect.ble.BleConnectionManager
import com.example.disasterconnect.ble.ConnectionState
import com.example.disasterconnect.ble.ManagedConnection
import com.example.disasterconnect.chat.Conversation
import com.example.disasterconnect.chat.ConversationKey
import com.example.disasterconnect.chat.DeliveryLabel
import com.example.disasterconnect.chat.buildConversations
import com.example.disasterconnect.chat.canRetry
import com.example.disasterconnect.chat.deliveryExplanation
import com.example.disasterconnect.chat.deliveryLabel
import com.example.disasterconnect.chat.isEmergency
import com.example.disasterconnect.chat.visibleTo
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.data.EmergencyMessage
import com.example.disasterconnect.messaging.MessagePacketCodec
import com.example.disasterconnect.messaging.MessagingCoordinator
import com.example.disasterconnect.network.NearbyNetworkRepository
import com.example.disasterconnect.ui.components.CardTone
import com.example.disasterconnect.ui.components.DisasterCard
import com.example.disasterconnect.ui.components.DisasterTextField
import com.example.disasterconnect.ui.components.NoticeCard
import com.example.disasterconnect.ui.components.PrimaryButton
import com.example.disasterconnect.ui.components.ScreenHeader
import com.example.disasterconnect.ui.components.StatusBadge
import com.example.disasterconnect.ui.components.StatusType
import com.example.disasterconnect.ui.components.TertiaryButton
import com.example.disasterconnect.ui.theme.DisasterConnectTheme
import com.example.disasterconnect.ui.theme.DisasterTheme
import com.example.disasterconnect.ui.theme.Sizes
import com.example.disasterconnect.ui.theme.Spacing
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =========================================================
// MESSAGES INBOX AND CHAT
// =========================================================

/**
 * Three modes over the same message table:
 * - inbox (no peer): one card per conversation;
 * - conversation with one node: history plus composer;
 * - your SOS alerts: SOS broadcasts sent from this phone (read-only; SOS is sent from Home).
 */
class ChatActivity : ComponentActivity() {

    private val peerNodeId by lazy {

        intent.getStringExtra(EXTRA_PEER_NODE_ID)
    }

    private val showOwnBroadcasts by lazy {

        intent.getBooleanExtra(EXTRA_OWN_BROADCASTS, false)
    }

    private var errorMessage by mutableStateOf<String?>(null)

    // =========================================================
    // ACTIVITY LIFECYCLE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val dao =
            AppDatabase.getDatabase(this).emergencyMessageDao()

        val localNodeId =
            BleConnectionManager.nodeId(this)

        setContent {

            DisasterConnectTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    val connections by BleConnectionManager.connections.collectAsState()

                    val peer = peerNodeId

                    when {

                        peer != null -> {

                            // Relay copies (A's messages to a third phone carried by this one) stay hidden.
                            val messages by remember {
                                dao.observeConversation(peer).map { it.visibleTo(localNodeId) }
                            }.collectAsState(initial = null)

                            ConversationScreen(
                                localNodeId = localNodeId,
                                peerNodeId = peer,
                                linked = connections[peer].isLinked(),
                                bluetoothReady = hasBluetoothPermissions(),
                                messages = messages,
                                errorMessage = errorMessage,
                                onSend = ::send,
                                onRetry = MessagingCoordinator::retry,
                                onOpenNearbyDevices = ::openNearbyDevices,
                                onBack = ::finish
                            )
                        }

                        showOwnBroadcasts -> {

                            // Same observeAll() query, filtered to SOS broadcasts sent from this phone.
                            val messages by remember {
                                dao.observeAll().map { all ->
                                    all.filter { it.conversationIsOwnBroadcast() }.sortedBy { it.id }
                                }
                            }.collectAsState(initial = null)

                            OwnBroadcastsScreen(
                                messages = messages,
                                onBack = ::finish
                            )
                        }

                        else -> {

                            val messages by remember { dao.observeAll() }
                                .collectAsState(initial = null)

                            InboxScreen(
                                localNodeId = localNodeId,
                                conversations = messages?.let { buildConversations(it.visibleTo(localNodeId)) },
                                connections = connections,
                                onOpen = ::openConversation,
                                onOpenNearbyDevices = ::openNearbyDevices,
                                onBack = ::finish
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        MessagingCoordinator.start(this)

        // Scanning lets the coordinator find the peer and deliver queued messages.
        if (hasBluetoothPermissions()) {
            NearbyNetworkRepository.start(this, SCAN_CONSUMER)
        }
    }

    override fun onPause() {
        NearbyNetworkRepository.stop(SCAN_CONSUMER)
        super.onPause()
    }

    // =========================================================
    // ACTIONS
    // =========================================================

    private fun send(
        text: String
    ): Boolean {

        val peer = peerNodeId ?: return false

        if (!MessagePacketCodec.payloadFits(text.trim())) {
            errorMessage = "Message is too long."
            return false
        }

        errorMessage = null

        lifecycleScope.launch {
            try {
                MessagingCoordinator.sendText(this@ChatActivity, peer, text)
            } catch (exception: IllegalArgumentException) {
                errorMessage = exception.message
            }
        }

        return true
    }

    private fun openConversation(
        key: ConversationKey
    ) {

        startActivity(
            when (key) {
                is ConversationKey.Peer -> intent(this, key.nodeId)
                ConversationKey.OwnBroadcasts -> ownBroadcastsIntent(this)
            }
        )
    }

    private fun openNearbyDevices() {

        startActivity(Intent(this, NearbyDevicesActivity::class.java))
    }

    private fun hasBluetoothPermissions(): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    companion object {

        private const val EXTRA_PEER_NODE_ID = "peer_node_id"

        private const val EXTRA_OWN_BROADCASTS = "own_broadcasts"

        private const val SCAN_CONSUMER = "chat-screen"

        /** [peerNodeId] null opens the inbox with every conversation. */
        fun intent(
            context: Context,
            peerNodeId: String?
        ): Intent {

            return Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_PEER_NODE_ID, peerNodeId)
        }

        fun ownBroadcastsIntent(
            context: Context
        ): Intent {

            return Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_OWN_BROADCASTS, true)
        }
    }
}

private fun ManagedConnection?.isLinked(): Boolean =
    this?.connectionState == ConnectionState.CONNECTED

private fun EmergencyMessage.conversationIsOwnBroadcast(): Boolean =
    !isIncoming && receiverId == null


// =========================================================
// INBOX
// =========================================================

@Composable
fun InboxScreen(

    localNodeId: String,

    conversations: List<Conversation>?,

    connections: Map<String, ManagedConnection>,

    onOpen: (ConversationKey) -> Unit,

    onOpenNearbyDevices: () -> Unit,

    onBack: () -> Unit
) {

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        contentPadding =
            PaddingValues(
                horizontal = Spacing.screen,
                vertical = Spacing.lg
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        item {

            ScreenHeader(
                title = "Messages",
                subtitle = "Your emergency communications.",
                onBack = onBack
            )
        }

        item {

            Text(
                text = "Your device ID: $localNodeId",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {

            // Still reading from the database: show nothing rather than a false "empty".
            conversations == null -> Unit

            conversations.isEmpty() -> item {

                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {

                    NoticeCard(
                        title = "No messages yet",
                        message =
                            "Messages work without Internet. Find a phone nearby, then tap " +
                                    "Message on its card to start a conversation.",
                        tone = CardTone.INFO
                    )

                    PrimaryButton(
                        text = "Find nearby devices",
                        onClick = onOpenNearbyDevices,
                        icon = Icons.Filled.Phone,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            else -> items(
                items = conversations,
                key = { it.key.toString() }
            ) { conversation ->

                val peer = (conversation.key as? ConversationKey.Peer)?.nodeId

                ConversationCard(
                    conversation = conversation,
                    linked = peer != null && connections[peer].isLinked(),
                    onClick = { onOpen(conversation.key) }
                )
            }
        }
    }
}


@Composable
private fun ConversationCard(

    conversation: Conversation,

    linked: Boolean,

    onClick: () -> Unit
) {

    val latest = conversation.latest

    val title =
        when (val key = conversation.key) {
            is ConversationKey.Peer -> key.nodeId
            ConversationKey.OwnBroadcasts -> "Your SOS alerts"
        }

    val label = latest.deliveryLabel(peerLinked = linked)

    DisasterCard(
        onClick = onClick,
        tone = if (conversation.hasEmergency) CardTone.INFO else CardTone.DEFAULT
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

            Text(
                text = latest.createdAt.timeLabel(),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (conversation.hasEmergency) {

            Spacer(modifier = Modifier.height(Spacing.xs))

            StatusBadge(
                status = StatusType.EMERGENCY,
                label = "SOS / Emergency"
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        Text(
            text = (if (latest.isIncoming) "" else "You: ") + latest.content,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {

            StatusBadge(
                status = label.statusType(),
                label = label.text
            )

            if (conversation.key is ConversationKey.Peer && linked) {

                StatusBadge(
                    status = StatusType.CONNECTED,
                    label = "Connected"
                )
            }
        }

        if (conversation.failedCount > 0) {

            Spacer(modifier = Modifier.height(Spacing.xs))

            Text(
                text =
                    if (conversation.failedCount == 1) "1 message failed. Open to retry."
                    else "${conversation.failedCount} messages failed. Open to retry.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


// =========================================================
// CONVERSATION
// =========================================================

@Composable
fun ConversationScreen(

    localNodeId: String,

    peerNodeId: String,

    linked: Boolean,

    bluetoothReady: Boolean,

    messages: List<EmergencyMessage>?,

    errorMessage: String?,

    onSend: (String) -> Boolean,

    onRetry: (String) -> Unit,

    onOpenNearbyDevices: () -> Unit,

    onBack: () -> Unit
) {

    var draft by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    val messageCount = messages?.size ?: 0

    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
    ) {

        // ---------------------------------------------------------
        // Header and connection status
        // ---------------------------------------------------------

        Column(
            modifier =
                Modifier.padding(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = Spacing.lg
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {

            ScreenHeader(
                title = peerNodeId,
                subtitle = "Conversation • You are $localNodeId",
                onBack = onBack
            )

            StatusBadge(
                status = if (linked) StatusType.CONNECTED else StatusType.DISCONNECTED,
                label = if (linked) "Connected" else "Not connected"
            )

            if (!bluetoothReady) {

                NoticeCard(
                    title = "Messaging is paused",
                    message =
                        "Bluetooth permission is needed to send. Your messages are kept " +
                                "on this phone until then.",
                    tone = CardTone.WARNING
                )

                TertiaryButton(
                    text = "Open Nearby Devices",
                    onClick = onOpenNearbyDevices,
                    icon = Icons.Filled.Phone
                )

            } else if (!linked) {

                Text(
                    text =
                        "$peerNodeId is not connected. New messages wait on this phone and " +
                                "send automatically when it is nearby. No Internet is needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = Spacing.md),
            color = MaterialTheme.colorScheme.outlineVariant
        )


        // ---------------------------------------------------------
        // History
        // ---------------------------------------------------------

        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    horizontal = Spacing.screen,
                    vertical = Spacing.md
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {

            if (messages != null && messages.isEmpty()) {

                item {

                    Text(
                        text =
                            "No messages yet. Write one below. It is delivered when " +
                                    "$peerNodeId is connected.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(
                items = messages.orEmpty(),
                key = { it.messageId }
            ) { message ->

                MessageBubble(
                    message = message,
                    peerLinked = linked,
                    onRetry = { onRetry(message.messageId) }
                )
            }
        }


        // ---------------------------------------------------------
        // Composer
        // ---------------------------------------------------------

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val draftBytes = draft.trim().toByteArray().size

        val tooLong = draftBytes > MessagePacketCodec.MAX_PAYLOAD_BYTES

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Spacing.screen,
                        vertical = Spacing.md
                    ),
            verticalAlignment = Alignment.Top
        ) {

            DisasterTextField(
                value = draft,
                onValueChange = { draft = it },
                label = "Message",
                helperText = "$draftBytes / ${MessagePacketCodec.MAX_PAYLOAD_BYTES} bytes",
                errorText =
                    when {
                        tooLong -> "Too long. Shorten the message to send it."
                        else -> errorMessage
                    },
                maxLines = 5,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

            PrimaryButton(
                text = "Send",
                onClick = { if (onSend(draft)) draft = "" },
                enabled = draft.isNotBlank() && !tooLong,
                icon = Icons.AutoMirrored.Filled.Send,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}


// =========================================================
// YOUR SOS ALERTS
// =========================================================

@Composable
fun OwnBroadcastsScreen(

    messages: List<EmergencyMessage>?,

    onBack: () -> Unit
) {

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        contentPadding =
            PaddingValues(
                horizontal = Spacing.screen,
                vertical = Spacing.lg
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        item {

            ScreenHeader(
                title = "Your SOS alerts",
                subtitle = "Alerts sent to every nearby phone. Send a new SOS from Home.",
                onBack = onBack
            )
        }

        if (messages != null && messages.isEmpty()) {

            item {

                Text(
                    text = "You have not sent an SOS from this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(
            items = messages.orEmpty(),
            key = { it.messageId }
        ) { message ->

            // Broadcasts have no single recipient, so there is no link to wait for.
            MessageBubble(
                message = message,
                peerLinked = false,
                onRetry = {}
            )
        }
    }
}


// =========================================================
// MESSAGE BUBBLE
// =========================================================

@Composable
private fun MessageBubble(

    message: EmergencyMessage,

    peerLinked: Boolean,

    onRetry: () -> Unit
) {

    val incoming = message.isIncoming

    val emergency = message.isEmergency()

    val label = message.deliveryLabel(peerLinked)

    val semantic = DisasterTheme.colors

    // Ordinary: incoming on a light surface (left), outgoing on charcoal (right).
    // SOS: emergency red with white text on either side.
    val container =
        when {
            emergency -> semantic.emergency
            incoming -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.primary
        }

    val content =
        when {
            emergency -> semantic.onEmergency
            incoming -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onPrimary
        }

    val speaker = if (incoming) "From ${message.senderId}" else "You"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End
    ) {

        Surface(
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            buildString {
                                if (emergency) append("Emergency SOS. ")
                                append("$speaker, ${message.createdAt.timeLabel()}. ")
                                append(message.content)
                                append(". ${label.text}.")
                            }
                    },
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = content,
            border =
                if (incoming && !emergency) BorderStroke(Sizes.border, MaterialTheme.colorScheme.outlineVariant)
                else null
        ) {

            Column(
                modifier = Modifier.padding(Spacing.md)
            ) {

                if (emergency) {

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(Sizes.iconSmall)
                        )

                        Spacer(modifier = Modifier.width(Spacing.xs))

                        Text(
                            text = "SOS / EMERGENCY",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.xs))
                }

                Text(
                    text = speaker,
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Long text wraps; nothing scrolls sideways.
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (emergency && message.latitude != null && message.longitude != null) {

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Icon(
                            imageVector = Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(Sizes.iconSmall)
                        )

                        Spacer(modifier = Modifier.width(Spacing.xs))

                        Text(
                            text = "Shared location: %.5f, %.5f".format(message.latitude, message.longitude),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Icon(
                        imageVector = label.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.iconSmall)
                    )

                    Spacer(modifier = Modifier.width(Spacing.xs))

                    Text(
                        text = "${label.text} • ${message.createdAt.timeLabel()}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (incoming && message.currentRelayId != null && message.currentRelayId != message.senderId) {

                    Text(
                        text = "Passed on by ${message.currentRelayId}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!incoming && label != DeliveryLabel.DELIVERED && label != DeliveryLabel.DELIVERED_VERIFIED && label != DeliveryLabel.SENT) {

                    Text(
                        text = message.deliveryExplanation(label),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (message.canRetry()) {

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    RetryButton(
                        onRetry = onRetry,
                        contentColor = content
                    )
                }
            }
        }
    }
}


/** Outlined in the bubble's own text colour so it stays readable on charcoal or red. */
@Composable
private fun RetryButton(

    onRetry: () -> Unit,

    contentColor: Color
) {

    Surface(
        onClick = onRetry,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = contentColor,
        border = BorderStroke(Sizes.border, contentColor),
        modifier = Modifier.semantics { contentDescription = "Retry sending this message" }
    ) {

        Row(
            modifier =
                Modifier
                    .padding(
                        horizontal = Spacing.lg,
                        vertical = Spacing.md
                    ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(Sizes.iconSmall)
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}


// =========================================================
// DISPLAY HELPERS
// =========================================================

private fun Long.timeLabel(): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(this))

private fun DeliveryLabel.icon(): ImageVector = when (this) {
    DeliveryLabel.RECEIVED -> Icons.Outlined.Info
    DeliveryLabel.WAITING_FOR_CONNECTION, DeliveryLabel.PENDING -> Icons.Filled.Refresh
    DeliveryLabel.SENT -> Icons.Filled.Done
    DeliveryLabel.DELIVERED -> Icons.Filled.Done
    DeliveryLabel.DELIVERED_VERIFIED -> Icons.Filled.CheckCircle
    DeliveryLabel.AWAITING_RECEIPT -> Icons.Filled.Done
    DeliveryLabel.PASSED_ON -> Icons.Filled.Share
    DeliveryLabel.FAILED -> Icons.Outlined.Warning
    DeliveryLabel.EXPIRED -> Icons.Outlined.Close
    DeliveryLabel.REACHED_GATEWAY, DeliveryLabel.REACHED_SERVER -> Icons.Filled.CheckCircle
}

private fun DeliveryLabel.statusType(): StatusType = when (this) {
    DeliveryLabel.DELIVERED_VERIFIED, DeliveryLabel.REACHED_GATEWAY, DeliveryLabel.REACHED_SERVER -> StatusType.DELIVERED
    DeliveryLabel.DELIVERED, DeliveryLabel.AWAITING_RECEIPT,
    DeliveryLabel.SENT, DeliveryLabel.PASSED_ON, DeliveryLabel.RECEIVED -> StatusType.CONNECTED
    DeliveryLabel.PENDING, DeliveryLabel.WAITING_FOR_CONNECTION -> StatusType.PENDING
    DeliveryLabel.FAILED, DeliveryLabel.EXPIRED -> StatusType.WARNING
}

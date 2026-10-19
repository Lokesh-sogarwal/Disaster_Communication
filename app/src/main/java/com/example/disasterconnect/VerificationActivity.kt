package com.example.disasterconnect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.disasterconnect.ble.BleConnectionManager
import com.example.disasterconnect.ble.ConnectionState
import com.example.disasterconnect.data.AppDatabase
import com.example.disasterconnect.messaging.MessagingCoordinator
import com.example.disasterconnect.security.KeyTrustStatus
import com.example.disasterconnect.security.VerificationView
import com.example.disasterconnect.security.VerifyResult
import com.example.disasterconnect.security.isConsistent
import com.example.disasterconnect.security.verificationView
import com.example.disasterconnect.ui.components.CardTone
import com.example.disasterconnect.ui.components.DisasterCard
import com.example.disasterconnect.ui.components.NoticeCard
import com.example.disasterconnect.ui.components.PrimaryButton
import com.example.disasterconnect.ui.components.ScreenHeader
import com.example.disasterconnect.ui.components.SecondaryButton
import com.example.disasterconnect.ui.components.StatusBadge
import com.example.disasterconnect.ui.components.StatusType
import com.example.disasterconnect.ui.components.TertiaryButton
import com.example.disasterconnect.ui.theme.DisasterConnectTheme
import com.example.disasterconnect.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


// =========================================================
// CONTACT VERIFICATION (SAFETY CODE)
// =========================================================

/**
 * Compares a 12-digit safety code with one directly connected phone. Personal messages only:
 * nothing here is needed to send, receive or relay SOS alerts.
 */
class VerificationActivity : ComponentActivity() {

    private val peerNodeId by lazy {

        intent.getStringExtra(EXTRA_PEER_NODE_ID).orEmpty()
    }

    // =========================================================
    // ACTIVITY LIFECYCLE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        MessagingCoordinator.start(this)

        // Opening this screen is an explicit request: offer this phone's key once more.
        MessagingCoordinator.requestKeyExchange(peerNodeId)

        val localNodeId =
            BleConnectionManager.nodeId(this)

        val keys =
            AppDatabase.getDatabase(this).trustedKeyDao()

        val peer = peerNodeId

        setContent {

            DisasterConnectTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    val identity by MessagingCoordinator.identity.collectAsState()

                    val exchange by MessagingCoordinator.keyExchange.collectAsState()

                    val linkKeys by remember(exchange) { exchange?.linkKeys ?: MutableStateFlow(emptyMap()) }
                        .collectAsState()

                    val conflicts by remember(exchange) { exchange?.conflicts ?: MutableStateFlow(emptySet()) }
                        .collectAsState()

                    val connections by BleConnectionManager.connections.collectAsState()

                    val stored by remember {
                        keys.observeAll().map { all -> all.firstOrNull { it.nodeId == peer }?.takeIf { it.isConsistent() } }
                    }.collectAsState(initial = null)

                    val view =
                        verificationView(
                            identity = identity,
                            localNodeId = localNodeId,
                            localKey = exchange?.localPublicKey,
                            peerNodeId = peer,
                            linked = connections[peer]?.connectionState == ConnectionState.CONNECTED &&
                                BleConnectionManager.isLinkReady(peer),
                            linkKey = linkKeys[peer],
                            stored = stored,
                            conflict = peer in conflicts
                        )

                    VerificationScreen(
                        peerNodeId = peer,
                        view = view,
                        onConfirm = { fingerprint -> MessagingCoordinator.confirmVerification(peer, fingerprint) },
                        onResendKey = { MessagingCoordinator.requestKeyExchange(peer) },
                        onClose = ::finish
                    )
                }
            }
        }
    }

    companion object {

        private const val EXTRA_PEER_NODE_ID = "peer_node_id"

        fun intent(
            context: Context,
            peerNodeId: String
        ): Intent {

            return Intent(context, VerificationActivity::class.java)
                .putExtra(EXTRA_PEER_NODE_ID, peerNodeId)
        }
    }
}


// =========================================================
// SCREEN
// =========================================================

private enum class Outcome { CODES_DIFFER, NOT_SAVED }

@Composable
fun VerificationScreen(

    peerNodeId: String,

    view: VerificationView,

    onConfirm: suspend (ByteArray) -> VerifyResult,

    onResendKey: () -> Unit,

    onClose: () -> Unit
) {

    val scope = rememberCoroutineScope()

    // Reset whenever the key being compared changes, so no answer carries over to another key.
    val compareKey = (view as? VerificationView.Compare)?.fingerprint?.toList()

    var outcome by remember(compareKey) { mutableStateOf<Outcome?>(null) }

    var saving by remember(compareKey) { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Spacing.screen,
                    vertical = Spacing.lg
                ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {

        ScreenHeader(
            title = "Verify contact",
            subtitle = peerNodeId,
            onBack = onClose
        )

        StatusBadge(
            status = view.trustStatus().badgeType(),
            label = view.trustStatus().label
        )

        when (view) {

            is VerificationView.IdentityProblem ->
                NoticeCard(
                    title = "Verification unavailable",
                    message = view.message,
                    tone = CardTone.WARNING
                )

            VerificationView.KeyChanged ->
                NoticeCard(
                    title = "Key changed",
                    message =
                        "$peerNodeId presented a different security key from the one saved on this phone. " +
                            "It may be a different phone using the same ID. The saved key was kept and nothing " +
                            "was verified. Do not rely on this phone's identity for personal messages.",
                    tone = CardTone.EMERGENCY
                )

            VerificationView.NotConnected ->
                NoticeCard(
                    title = "Connect to verify",
                    message =
                        "Connect to $peerNodeId on Nearby Devices, close to the other person. " +
                            "Both phones exchange security keys automatically once connected."
                )

            VerificationView.WaitingForKey -> {

                NoticeCard(
                    title = "Waiting for $peerNodeId's key",
                    message =
                        "Connected, but this phone has not received $peerNodeId's security key yet. " +
                            "If it never arrives, that phone may be running an older version of DisasterConnect."
                )

                SecondaryButton(
                    text = "Send my key again",
                    onClick = onResendKey,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is VerificationView.Compare ->
                CompareSection(
                    peerNodeId = peerNodeId,
                    view = view,
                    outcome = outcome,
                    saving = saving,
                    onMatch = {
                        saving = true
                        scope.launch {
                            val result = onConfirm(view.fingerprint)
                            saving = false
                            // Success is shown only from the database state (view.verified), never assumed.
                            outcome = if (result is VerifyResult.Verified || result is VerifyResult.AlreadyVerified) null else Outcome.NOT_SAVED
                        }
                    },
                    onMismatch = { outcome = Outcome.CODES_DIFFER },
                    onCancel = onClose
                )
        }

        Text(
            text =
                "Verification is only for personal messages. SOS alerts are always received and passed on, " +
                    "whether or not a phone is verified.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompareSection(

    peerNodeId: String,

    view: VerificationView.Compare,

    outcome: Outcome?,

    saving: Boolean,

    onMatch: () -> Unit,

    onMismatch: () -> Unit,

    onCancel: () -> Unit
) {

    DisasterCard(
        tone = CardTone.INFO
    ) {

        Text(
            text = "Safety code",
            style = MaterialTheme.typography.labelLarge
        )

        Text(
            text = view.code,
            style =
                MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
            modifier =
                Modifier
                    .padding(vertical = Spacing.sm)
                    .semantics {
                        // Read digit by digit, group by group.
                        contentDescription = "Safety code " +
                            view.code.split(" ").joinToString(", ") { group -> group.toList().joinToString(" ") }
                    }
        )

        Text(
            text =
                "Compare this code with the code on $peerNodeId's screen, face to face or through another " +
                    "channel you trust. Read every digit. Do not accept a code sent over this app.",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    when {

        view.verified ->
            NoticeCard(
                title = "Verified",
                message =
                    "You verified $peerNodeId's security key on this phone" +
                        (view.verifiedAt?.let { " on " + DateFormat.format("d MMM yyyy", it) } ?: "") + ". " +
                        "$peerNodeId must verify this phone separately on their own device.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

        outcome == Outcome.CODES_DIFFER ->
            NoticeCard(
                title = "Codes don't match",
                message =
                    "This phone may not be $peerNodeId. Nothing was saved and the contact stays not verified. " +
                        "Do not rely on this phone's identity for personal messages.",
                tone = CardTone.EMERGENCY,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

        else -> {

            if (outcome == Outcome.NOT_SAVED) {

                NoticeCard(
                    title = "Not verified",
                    message = "The connection or the key changed before saving, so nothing was saved. Compare the new code.",
                    tone = CardTone.WARNING,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            PrimaryButton(
                text = if (saving) "Saving…" else "Codes match",
                onClick = onMatch,
                enabled = !saving,
                icon = Icons.Filled.Check,
                modifier = Modifier.fillMaxWidth()
            )

            SecondaryButton(
                text = "Codes don't match",
                onClick = onMismatch,
                enabled = !saving,
                icon = Icons.Filled.Close,
                modifier = Modifier.fillMaxWidth()
            )

            TertiaryButton(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


// ---------------------------------------------------------
// Status helpers
// ---------------------------------------------------------

private fun VerificationView.trustStatus(): KeyTrustStatus = when (this) {
    VerificationView.KeyChanged -> KeyTrustStatus.KEY_CHANGED
    is VerificationView.Compare -> if (verified) KeyTrustStatus.VERIFIED else KeyTrustStatus.NOT_VERIFIED
    else -> KeyTrustStatus.VERIFICATION_REQUIRED
}

/** Each status has its own icon and word (StatusBadge never relies on colour alone). */
internal fun KeyTrustStatus.badgeType(): StatusType = when (this) {
    KeyTrustStatus.VERIFIED -> StatusType.DELIVERED
    KeyTrustStatus.NOT_VERIFIED -> StatusType.DISCONNECTED
    KeyTrustStatus.VERIFICATION_REQUIRED -> StatusType.PENDING
    KeyTrustStatus.KEY_CHANGED -> StatusType.EMERGENCY
}

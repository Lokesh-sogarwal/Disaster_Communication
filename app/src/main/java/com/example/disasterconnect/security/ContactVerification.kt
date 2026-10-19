package com.example.disasterconnect.security

import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.data.TrustedKey
import com.example.disasterconnect.messaging.EcdsaP256


// =========================================================
// CONTACT VERIFICATION STATE (for personal messages only)
// =========================================================
// None of this is consulted for SOS: SOS alerts are received and relayed from any phone.

enum class KeyTrustStatus(val label: String) {
    VERIFICATION_REQUIRED("Verification required"),
    NOT_VERIFIED("Not verified"),
    VERIFIED("Verified"),
    KEY_CHANGED("Key changed")
}

/** Status shown for [nodeId], from the stored keys and this run's key conflicts. */
fun keyTrustStatus(nodeId: String, storedKeys: List<TrustedKey>, conflicts: Set<String>): KeyTrustStatus {
    if (nodeId in conflicts) return KeyTrustStatus.KEY_CHANGED
    val stored = storedKeys.firstOrNull { it.nodeId == nodeId }?.takeIf { it.isConsistent() }
        ?: return KeyTrustStatus.VERIFICATION_REQUIRED
    return if (stored.trust == TrustState.VERIFIED) KeyTrustStatus.VERIFIED else KeyTrustStatus.NOT_VERIFIED
}


sealed interface VerificationView {

    /** This phone has no usable identity key, so no code can be shown. */
    data class IdentityProblem(val message: String) : VerificationView

    /** The peer presented a key different from the stored one. Verification is blocked. */
    data object KeyChanged : VerificationView

    /** Not linked to the peer now. */
    data object NotConnected : VerificationView

    /** Linked, but the peer's key has not arrived on this link (it may run an older app). */
    data object WaitingForKey : VerificationView

    /** The key on this link is exactly the stored record: safe to compare. */
    class Compare(
        val code: String,
        // The key the code was made from; a confirmation applies to this key only.
        val fingerprint: ByteArray,
        val verified: Boolean,
        val verifiedAt: Long?
    ) : VerificationView
}

/**
 * What the verification screen may show. A code is offered only when the key received over the
 * current link, the stored record and the node ID all agree.
 */
fun verificationView(
    identity: IdentityStatus?,
    localNodeId: String,
    localKey: ByteArray?,
    peerNodeId: String,
    linked: Boolean,
    linkKey: ByteArray?,
    stored: TrustedKey?,
    conflict: Boolean
): VerificationView {
    when (identity) {
        null -> return VerificationView.IdentityProblem("This phone's security key is still loading.")
        is IdentityStatus.Lost -> return VerificationView.IdentityProblem(
            "This phone's security key was lost. Restoring an identity is not available yet."
        )
        is IdentityStatus.Incompatible, is IdentityStatus.Unavailable, IdentityStatus.Missing ->
            return VerificationView.IdentityProblem("This phone's security key is unavailable.")
        is IdentityStatus.Ready -> Unit
    }
    if (conflict) return VerificationView.KeyChanged
    if (!linked) return VerificationView.NotConnected
    if (linkKey == null || localKey == null) return VerificationView.WaitingForKey
    val record = stored?.takeIf { it.nodeId == peerNodeId && it.isConsistent() } ?: return VerificationView.WaitingForKey
    val fingerprint = EcdsaP256.fingerprint(linkKey)
    if (!record.fingerprint.contentEquals(fingerprint)) return VerificationView.KeyChanged
    val code = SafetyCode.compute(localNodeId, localKey, peerNodeId, linkKey) ?: return VerificationView.WaitingForKey
    return VerificationView.Compare(code, fingerprint, record.trust == TrustState.VERIFIED, record.verifiedAt)
}

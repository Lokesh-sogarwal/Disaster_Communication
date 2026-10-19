package com.example.disasterconnect.security

import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.data.TrustedKey
import com.example.disasterconnect.data.TrustedKeyDao
import com.example.disasterconnect.messaging.EcdsaP256


// =========================================================
// TRUSTED-KEY STORE
// =========================================================

/** Persistence used by [TrustedKeyStore]. Production: [RoomTrustedKeyRecords]; tests use a fake. */
interface TrustedKeyRecords {

    /** Stores [publicKey] as SEEN. False (and nothing changed) if [nodeId] already has a key. */
    suspend fun insertSeen(nodeId: String, publicKey: ByteArray, fingerprint: ByteArray, seenAt: Long): Boolean

    suspend fun find(nodeId: String): TrustedKey?

    /** Atomically SEEN → VERIFIED for exactly this node ID and fingerprint. False if no such SEEN row. */
    suspend fun markVerified(nodeId: String, fingerprint: ByteArray, verifiedAt: Long): Boolean
}

class RoomTrustedKeyRecords(private val dao: TrustedKeyDao) : TrustedKeyRecords {

    override suspend fun insertSeen(nodeId: String, publicKey: ByteArray, fingerprint: ByteArray, seenAt: Long) =
        dao.insertSeen(nodeId, publicKey, fingerprint, seenAt) != -1L

    override suspend fun find(nodeId: String) = dao.find(nodeId)

    override suspend fun markVerified(nodeId: String, fingerprint: ByteArray, verifiedAt: Long) =
        dao.markVerified(nodeId, fingerprint, verifiedAt) == 1
}


sealed interface SeenKeyResult {

    /** Newly stored, as SEEN. */
    class Stored(val key: TrustedKey) : SeenKeyResult

    /** The same key was already stored for this node ID; its trust state is unchanged. */
    class AlreadyStored(val key: TrustedKey) : SeenKeyResult

    /** A different key is already pinned to this node ID. Nothing was changed. */
    class Conflict(val existing: TrustedKey) : SeenKeyResult

    data object InvalidNodeId : SeenKeyResult

    data object InvalidKey : SeenKeyResult
}


sealed interface VerifyResult {

    /** The database confirmed the change to VERIFIED. */
    class Verified(val key: TrustedKey) : VerifyResult

    /** This exact key was already verified; nothing changed. */
    class AlreadyVerified(val key: TrustedKey) : VerifyResult

    /** No stored key for this node ID. */
    data object NotFound : VerifyResult

    /** The stored key is not the one the user compared (it changed, or the UI was stale). Nothing changed. */
    data object KeyMismatch : VerifyResult

    /** The stored row fails validation. Nothing changed. */
    data object InvalidRecord : VerifyResult
    /** The peer is not linked now, or has not presented its key on this link. Nothing changed. */
    data object NotLinked : VerifyResult
}


/**
 * Pins other phones' public keys to their node IDs (rules.md §15).
 *
 * - Keys only ever enter as [TrustState.SEEN]; receiving a key proves nothing about who sent it.
 * - A node ID keeps its first key. A different key is reported as [SeenKeyResult.Conflict], never stored.
 * - [confirmVerified] is the only way to VERIFIED, and only the user's "Codes match" action may call it.
 *
 * Not connected to receipt verification or live messaging yet.
 */
class TrustedKeyStore(private val records: TrustedKeyRecords) {

    suspend fun recordSeen(nodeId: String, publicKey: ByteArray, now: Long): SeenKeyResult {
        if (!NODE_ID_PATTERN.matches(nodeId)) return SeenKeyResult.InvalidNodeId
        if (EcdsaP256.parsePublicKey(publicKey) == null) return SeenKeyResult.InvalidKey
        val key = publicKey.copyOf()
        val fingerprint = EcdsaP256.fingerprint(key)
        if (records.insertSeen(nodeId, key, fingerprint, now)) {
            return records.find(nodeId)?.let(SeenKeyResult::Stored) ?: SeenKeyResult.InvalidKey
        }
        // Also reached when another caller stored a key first; the stored row always wins.
        val existing = records.find(nodeId) ?: return SeenKeyResult.InvalidKey
        return if (existing.isConsistent() && existing.publicKey.contentEquals(key)) {
            SeenKeyResult.AlreadyStored(existing)
        } else {
            SeenKeyResult.Conflict(existing)
        }
    }

    /** The stored key, or null if there is none or the stored row fails validation. */
    suspend fun find(nodeId: String): TrustedKey? = records.find(nodeId)?.takeIf { it.isConsistent() }

    /**
     * The user compared safety codes for [expectedFingerprint] and confirmed they match.
     * Changes only the SEEN row holding exactly that key, in one atomic update, so a key that
     * changed after the code was shown can never be verified by the stale confirmation.
     */
    suspend fun confirmVerified(nodeId: String, expectedFingerprint: ByteArray, now: Long): VerifyResult {
        val stored = records.find(nodeId) ?: return VerifyResult.NotFound
        if (!stored.isConsistent()) return VerifyResult.InvalidRecord
        if (!stored.fingerprint.contentEquals(expectedFingerprint)) return VerifyResult.KeyMismatch
        if (stored.trust == TrustState.VERIFIED) return VerifyResult.AlreadyVerified(stored)
        records.markVerified(nodeId, expectedFingerprint, now)
        // Report only what the database now holds.
        val after = records.find(nodeId)?.takeIf { it.isConsistent() } ?: return VerifyResult.InvalidRecord
        return when {
            !after.fingerprint.contentEquals(expectedFingerprint) -> VerifyResult.KeyMismatch
            after.trust == TrustState.VERIFIED -> VerifyResult.Verified(after)
            else -> VerifyResult.KeyMismatch
        }
    }
}


/** Valid key, matching fingerprint, and a known trust state that agrees with its timestamp. */
fun TrustedKey.isConsistent(): Boolean =
    NODE_ID_PATTERN.matches(nodeId) &&
        EcdsaP256.parsePublicKey(publicKey) != null &&
        EcdsaP256.fingerprint(publicKey).contentEquals(fingerprint) &&
        when (trust) {
            TrustState.SEEN -> verifiedAt == null
            TrustState.VERIFIED -> verifiedAt != null
            else -> false
        }

// Same format as BleConnectionManager's node IDs (rules.md §13). A format check, not proof of identity.
private val NODE_ID_PATTERN = Regex("DC-[0-9A-F]{6}")

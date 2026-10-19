package com.example.disasterconnect.security

import com.example.disasterconnect.data.TrustState
import com.example.disasterconnect.data.TrustedKey


/** Same semantics as TrustedKeyDao: INSERT OR IGNORE as SEEN, and a conditional SEEN → VERIFIED update. */
internal class FakeTrustedKeyRecords : TrustedKeyRecords {

    val rows = mutableMapOf<String, TrustedKey>()

    override suspend fun insertSeen(nodeId: String, publicKey: ByteArray, fingerprint: ByteArray, seenAt: Long): Boolean {
        if (nodeId in rows) return false
        rows[nodeId] = TrustedKey(nodeId, publicKey, fingerprint, TrustState.SEEN, seenAt, null)
        return true
    }

    override suspend fun find(nodeId: String) = rows[nodeId]

    override suspend fun markVerified(nodeId: String, fingerprint: ByteArray, verifiedAt: Long): Boolean {
        val row = rows[nodeId] ?: return false
        if (!row.fingerprint.contentEquals(fingerprint) || row.trust != TrustState.SEEN) return false
        rows[nodeId] = TrustedKey(row.nodeId, row.publicKey, row.fingerprint, TrustState.VERIFIED, row.firstSeenAt, verifiedAt)
        return true
    }
}

package com.example.disasterconnect.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow


/**
 * Deliberately narrow: keys can only enter as SEEN, and an existing row is never replaced or deleted.
 * The only other write is [markVerified], used solely by the user's safety-code confirmation.
 */
@Dao
interface TrustedKeyDao {

    /** Returns -1 when the node ID already has a stored key (nothing is changed). */
    @Query(
        "INSERT OR IGNORE INTO trusted_keys (nodeId, publicKey, fingerprint, trust, firstSeenAt, verifiedAt) " +
            "VALUES (:nodeId, :publicKey, :fingerprint, '${TrustState.SEEN}', :seenAt, NULL)"
    )
    suspend fun insertSeen(nodeId: String, publicKey: ByteArray, fingerprint: ByteArray, seenAt: Long): Long

    @Query("SELECT * FROM trusted_keys WHERE nodeId = :nodeId LIMIT 1")
    suspend fun find(nodeId: String): TrustedKey?

    /**
     * One atomic statement: only the SEEN row for exactly this node ID and key fingerprint becomes
     * VERIFIED. Returns the number of rows changed (0 if the key changed or it was already verified).
     */
    @Query(
        "UPDATE trusted_keys SET trust = '${TrustState.VERIFIED}', verifiedAt = :verifiedAt " +
            "WHERE nodeId = :nodeId AND fingerprint = :fingerprint AND trust = '${TrustState.SEEN}'"
    )
    suspend fun markVerified(nodeId: String, fingerprint: ByteArray, verifiedAt: Long): Int

    @Query("SELECT * FROM trusted_keys ORDER BY nodeId")
    fun observeAll(): Flow<List<TrustedKey>>
}

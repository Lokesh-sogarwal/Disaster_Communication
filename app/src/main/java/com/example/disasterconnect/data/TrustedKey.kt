package com.example.disasterconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey


// =========================================================
// TRUSTED PUBLIC KEYS
// =========================================================

object TrustState {

    // Key received for a node ID. Not trusted for anything.
    const val SEEN = "SEEN"

    // Confirmed by the users out of band (safety-code comparison, later phase). No code path sets it yet.
    const val VERIFIED = "VERIFIED"
}


/**
 * Public key of another phone, pinned to its node ID (rules.md §15).
 * A node ID keeps its first stored key; a different key is never written over it.
 */
@Entity(tableName = "trusted_keys")
class TrustedKey(
    @PrimaryKey
    val nodeId: String,
    // Canonical 91-byte P-256 SubjectPublicKeyInfo (EcdsaP256.PUBLIC_KEY_BYTES).
    val publicKey: ByteArray,
    // SHA-256 of publicKey.
    val fingerprint: ByteArray,
    val trust: String,
    val firstSeenAt: Long,
    val verifiedAt: Long?
)

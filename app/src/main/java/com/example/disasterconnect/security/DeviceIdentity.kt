package com.example.disasterconnect.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.example.disasterconnect.messaging.EcdsaP256
import com.example.disasterconnect.messaging.ReceiptSigner
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec


// =========================================================
// DEVICE SIGNING IDENTITY (ANDROID KEYSTORE)
// =========================================================

enum class KeyStorage { STRONGBOX, SECURE_HARDWARE, SOFTWARE, UNKNOWN }

sealed interface IdentityStatus {

    class Ready(
        val signer: ReceiptSigner,
        // SHA-256 of signer.publicKey.
        val fingerprint: ByteArray,
        val created: Boolean,
        val storage: KeyStorage
    ) : IdentityStatus

    /** No identity key yet, and [DeviceIdentity.load] does not create one. */
    data object Missing : IdentityStatus

    /** This install had an identity key and it is gone. A new one needs [DeviceIdentity.replaceLostIdentity]. */
    data object Lost : IdentityStatus

    /** A key exists under the alias but cannot serve as this phone's identity. It is left untouched. */
    data class Incompatible(val reason: String) : IdentityStatus

    /** The Keystore failed. Nothing was created, replaced or deleted. */
    data class Unavailable(val reason: String) : IdentityStatus
}


/**
 * This phone's receipt-signing key: ECDSA P-256 / SHA-256 in the Android Keystore (rules.md §15).
 *
 * - The private key never leaves the Keystore and is not backed up. No user authentication is needed to sign.
 * - Separate from the node ID: the node ID is a routing label, this key is what others can verify.
 * - Never deletes or overwrites a key. A key is only generated when the alias is definitely empty,
 *   and after a loss only through the explicit [replaceLostIdentity].
 * - The fingerprint of the current key is recorded (public data) to tell "first run" from "key lost".
 */
class DeviceIdentity(
    context: Context,
    private val alias: String = KEY_ALIAS,
    preferencesName: String = PREFERENCES
) {

    private val appContext = context.applicationContext

    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)


    // ---------------------------------------------------------
    // Public API
    // ---------------------------------------------------------

    /** Opens the existing identity. Never creates one. */
    fun load(): IdentityStatus = synchronized(LOCK) { open(create = false) }

    /** Opens the existing identity, or creates it on first run. Never replaces a lost or incompatible key. */
    fun loadOrCreate(): IdentityStatus = synchronized(LOCK) { open(create = true) }

    /**
     * Starts a new identity after [IdentityStatus.Lost]. Other phones will see a different key for this
     * node ID and must re-verify. Does nothing to an existing key: then it behaves like [load].
     */
    fun replaceLostIdentity(): IdentityStatus = synchronized(LOCK) {
        val keyStore = openKeyStore() ?: return UNAVAILABLE
        val exists = runCatching { keyStore.containsAlias(alias) }.getOrNull() ?: return UNAVAILABLE
        if (exists) return open(create = false)
        // Written synchronously: the record decides between "first run" and "key lost".
        preferences.edit(commit = true) { remove(KEY_FINGERPRINT) }
        open(create = true)
    }


    // ---------------------------------------------------------
    // Loading
    // ---------------------------------------------------------

    private fun open(create: Boolean): IdentityStatus {
        val keyStore = openKeyStore() ?: return UNAVAILABLE
        val exists = runCatching { keyStore.containsAlias(alias) }.getOrNull() ?: return UNAVAILABLE
        val recorded = preferences.getString(KEY_FINGERPRINT, null)
        return when {
            exists -> existing(keyStore, recorded, created = false)
            recorded != null -> IdentityStatus.Lost
            !create -> IdentityStatus.Missing
            else -> generate(keyStore)
        }
    }

    private fun existing(keyStore: KeyStore, recorded: String?, created: Boolean): IdentityStatus {
        val entry = try {
            keyStore.getEntry(alias, null)
        } catch (_: GeneralSecurityException) {
            return IdentityStatus.Unavailable("The identity key could not be read.")
        } catch (_: ProviderException) {
            return IdentityStatus.Unavailable("The identity key could not be read.")
        }
        val privateEntry = entry as? KeyStore.PrivateKeyEntry
            ?: return IdentityStatus.Incompatible("The identity alias does not hold a private key.")
        val certificateKey = privateEntry.certificate?.publicKey as? ECPublicKey
            ?: return IdentityStatus.Incompatible("The identity key is not an EC key.")
        val publicKey = canonicalP256PublicKey(certificateKey)
            ?: return IdentityStatus.Incompatible("The identity key is not a P-256 key.")
        val fingerprint = EcdsaP256.fingerprint(publicKey)
        if (recorded != null && recorded != fingerprint.toHex()) {
            return IdentityStatus.Incompatible("The identity key does not match this install's recorded identity.")
        }

        val info = keyInfo(privateEntry.privateKey)
            ?: return IdentityStatus.Unavailable("The identity key details could not be read.")
        if (info.purposes and KeyProperties.PURPOSE_SIGN == 0 || KeyProperties.DIGEST_SHA256 !in info.digests) {
            return IdentityStatus.Incompatible("The identity key cannot sign with SHA-256.")
        }
        if (info.isUserAuthenticationRequired) {
            return IdentityStatus.Incompatible("The identity key needs user authentication to sign.")
        }

        // Proves that this private key, this exact public-key encoding and EcdsaP256 agree.
        val signer = KeystoreReceiptSigner(privateEntry.privateKey, publicKey)
        val probe = SELF_TEST_DOMAIN + fingerprint
        val selfTest = try {
            EcdsaP256.normalizeLowS(signer.sign(probe))?.let { EcdsaP256.verify(publicKey, probe, it) } ?: false
        } catch (_: GeneralSecurityException) {
            return IdentityStatus.Unavailable("The identity key could not sign.")
        } catch (_: ProviderException) {
            return IdentityStatus.Unavailable("The identity key could not sign.")
        }
        if (!selfTest) return IdentityStatus.Incompatible("The identity key's signatures do not verify.")

        if (recorded == null) preferences.edit(commit = true) { putString(KEY_FINGERPRINT, fingerprint.toHex()) }
        return IdentityStatus.Ready(signer, fingerprint, created, storageOf(info))
    }


    // ---------------------------------------------------------
    // Generation
    // ---------------------------------------------------------

    private fun generate(keyStore: KeyStore): IdentityStatus {
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            try {
                generateKeyPair(strongBox)
            } catch (e: ProviderException) {
                // StrongBoxUnavailableException is a ProviderException. Fall back only if nothing was created.
                if (!strongBox || keyStore.containsAlias(alias)) throw e
                generateKeyPair(strongBox = false)
            }
        } catch (_: GeneralSecurityException) {
            return IdentityStatus.Unavailable("The identity key could not be created.")
        } catch (_: ProviderException) {
            return IdentityStatus.Unavailable("The identity key could not be created.")
        }
        return existing(keyStore, recorded = null, created = true)
    }

    private fun generateKeyPair(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }


    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private fun openKeyStore(): KeyStore? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    private fun keyInfo(privateKey: PrivateKey): KeyInfo? = try {
        KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE).getKeySpec(privateKey, KeyInfo::class.java)
    } catch (_: GeneralSecurityException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun storageOf(info: KeyInfo): KeyStorage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyStorage.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyStorage.SECURE_HARDWARE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyStorage.SOFTWARE
                else -> KeyStorage.UNKNOWN
            }
        } else {
            if (info.isInsideSecureHardware) KeyStorage.SECURE_HARDWARE else KeyStorage.SOFTWARE
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }


    companion object {

        const val KEY_ALIAS = "dc_identity_v1"

        // Excluded from backup together with the node ID (res/xml/backup_rules.xml, data_extraction_rules.xml).
        const val PREFERENCES = "disaster_connect_identity"

        private const val KEY_FINGERPRINT = "identity_key_fingerprint"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private val UNAVAILABLE = IdentityStatus.Unavailable("Android Keystore is unavailable.")

        // Domain-separated from receipts ("DisasterConnect/receipt/v1"), so the probe can never be a receipt.
        private val SELF_TEST_DOMAIN = "DisasterConnect/identity-self-test/v1".toByteArray(Charsets.US_ASCII) + 0.toByte()

        // One lock for every instance: two generators must never race on the same alias.
        private val LOCK = Any()
    }
}


/** Signs inside the Keystore. Throws GeneralSecurityException or ProviderException if the key cannot be used. */
private class KeystoreReceiptSigner(
    private val privateKey: PrivateKey,
    publicKey: ByteArray
) : ReceiptSigner {

    private val encodedPublicKey = publicKey.copyOf()

    override val publicKey: ByteArray
        get() = encodedPublicKey.copyOf()

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
}

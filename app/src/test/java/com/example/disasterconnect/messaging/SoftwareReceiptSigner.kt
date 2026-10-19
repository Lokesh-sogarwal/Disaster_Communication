package com.example.disasterconnect.messaging

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec


/** JVM-only P-256 signer for tests. The app will sign with an Android Keystore key instead. */
internal class SoftwareReceiptSigner(
    private val keyPair: KeyPair = generate()
) : ReceiptSigner {

    override val publicKey: ByteArray = keyPair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    companion object {

        fun generate(curve: String = "secp256r1"): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    }
}

package io.buildthisplease.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

interface InstallationSigner {
    val keyId: String
    fun hasKey(): Boolean
    fun publicKeySpki(): ByteArray
    fun sign(requestHash: String): ByteArray
    fun reset()
}

class AndroidKeystoreInstallationSigner internal constructor(
    private val alias: String,
) : InstallationSigner {
    private val keyStore: KeyStore get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    override val keyId: String
        get() = installationKeyId(publicKeySpki())
    override fun hasKey(): Boolean = keyStore.containsAlias(alias)

    override fun publicKeySpki(): ByteArray {
        ensureKey()
        return requireNotNull(keyStore.getCertificate(alias)).publicKey.encoded
    }

    override fun sign(requestHash: String): ByteArray {
        ensureKey()
        val privateKey = requireNotNull(keyStore.getKey(alias, null))
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey as java.security.PrivateKey)
            update("BuildThisPlease/android/v1\n$requestHash".toByteArray())
            sign()
        }
        return derEcdsaToRaw(der)
    }

    override fun reset() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun ensureKey() {
        if (hasKey()) return
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    companion object { private const val KEYSTORE = "AndroidKeyStore" }
}

internal fun installationKeyId(publicKeySpki: ByteArray): String =
    "p256_${publicKeySpki.sha256().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }}"

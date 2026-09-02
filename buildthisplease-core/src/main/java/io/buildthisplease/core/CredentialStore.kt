package io.buildthisplease.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    suspend fun readSession(): StoredSession?
    suspend fun writeSession(session: StoredSession)
    suspend fun clearSession()
}

@Serializable
data class StoredSession(val token: String, val installationId: String, val expiresAt: String)

class EncryptedNoBackupCredentialStore internal constructor(
    context: Context,
    private val keyAlias: String,
    fileName: String,
) : CredentialStore {
    private val file = AtomicFile(File(File(context.noBackupFilesDir, "buildthisplease").apply { mkdirs() }, fileName))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun readSession(): StoredSession? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) return@withContext null
            runCatching {
                val bytes = file.openRead().use { it.readBytes() }
                require(bytes.size > 12)
                val iv = bytes.copyOfRange(0, 12)
                val ciphertext = bytes.copyOfRange(12, bytes.size)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
                }
                json.decodeFromString<StoredSession>(cipher.doFinal(ciphertext).decodeToString())
            }.getOrNull()
        }
    }

    override suspend fun writeSession(session: StoredSession) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
            val bytes = cipher.iv + cipher.doFinal(json.encodeToString(session).toByteArray())
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                file.failWrite(stream)
                throw failure
            }
        }
    }

    override suspend fun clearSession() = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object { private const val TRANSFORMATION = "AES/GCM/NoPadding" }
}

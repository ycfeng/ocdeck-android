package io.github.ycfeng.ocdeck.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    suspend fun putSecret(alias: String, secret: String)

    suspend fun getSecret(alias: String): String?

    suspend fun removeSecret(alias: String)
}

enum class CredentialStoreOperation {
    Read,
    Write,
    Remove,
}

class CredentialStoreException(
    val operation: CredentialStoreOperation,
) : IllegalStateException()

class SecureCredentialStore(context: Context) : CredentialStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)

    override suspend fun putSecret(alias: String, secret: String) = runOperation(CredentialStoreOperation.Write) {
        if (!preferences.edit().putString(alias, encrypt(secret)).commit()) {
            throw CredentialStoreException(CredentialStoreOperation.Write)
        }
    }

    override suspend fun getSecret(alias: String): String? = runOperation(CredentialStoreOperation.Read) {
        preferences.getString(alias, null)?.let(::decrypt)
    }

    override suspend fun removeSecret(alias: String) = runOperation(CredentialStoreOperation.Remove) {
        if (!preferences.edit().remove(alias).commit()) {
            throw CredentialStoreException(CredentialStoreOperation.Remove)
        }
    }

    private suspend fun <T> runOperation(
        operation: CredentialStoreOperation,
        block: () -> T,
    ): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: CredentialStoreException) {
            throw failure
        } catch (_: Exception) {
            throw CredentialStoreException(operation)
        }
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted)
            .joinToString(separator = ":") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "Invalid encrypted credential payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keySpec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val GCM_TAG_LENGTH_BITS = 128
        const val MASTER_KEY_ALIAS = "ocdeck_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

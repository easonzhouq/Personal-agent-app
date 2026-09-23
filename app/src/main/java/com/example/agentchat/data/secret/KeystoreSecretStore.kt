package com.example.agentchat.data.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretStore(context: Context) : SecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun putApiKey(configId: String, apiKey: String) {
        val storageKey = storageKey(configId)
        val encrypted = encrypt(configId, apiKey)
        if (!preferences.edit().putString(storageKey, encrypted).putStringSet(CONFIG_IDS_PREFERENCE, storedConfigIds() + configId).commit()) {
            throw SecretStoreException.StorageFailed()
        }
    }

    override suspend fun getApiKey(configId: String): String? {
        val value = preferences.getString(storageKey(configId), null) ?: return null
        return decrypt(configId, value)
    }

    override suspend fun deleteApiKey(configId: String) {
        if (!preferences.edit().remove(storageKey(configId)).putStringSet(CONFIG_IDS_PREFERENCE, storedConfigIds() - configId).commit()) {
            throw SecretStoreException.StorageFailed()
        }
    }

    override suspend fun clearAllApiKeys() {
        val keys = preferences.all.keys.filter { it.startsWith(STORAGE_KEY_PREFIX) } + CONFIG_IDS_PREFERENCE
        if (keys.isNotEmpty() && !keys.fold(preferences.edit()) { editor, key -> editor.remove(key) }.commit()) throw SecretStoreException.StorageFailed()
    }

    override suspend fun snapshotApiKeys(): ApiKeySnapshot = ApiKeySnapshot(
        preferences.all
            .filterKeys { it.startsWith(STORAGE_KEY_PREFIX) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap(),
    )

    override suspend fun restoreApiKeys(snapshot: ApiKeySnapshot) {
        clearAllApiKeys()
        if (snapshot.entries.isNotEmpty() && !snapshot.entries.entries.fold(preferences.edit()) { editor, (key, value) -> editor.putString(key, value) }.commit()) {
            throw SecretStoreException.StorageFailed()
        }
    }

    private fun storedConfigIds(): Set<String> = preferences.getStringSet(CONFIG_IDS_PREFERENCE, emptySet()).orEmpty()

    private fun encrypt(configId: String, apiKey: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(IV_LENGTH_BYTES).also(secureRandom::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.updateAAD(configId.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(
                ByteBuffer.allocate(iv.size + ciphertext.size).put(iv).put(ciphertext).array(),
                android.util.Base64.NO_WRAP,
            )
        } catch (error: SecretStoreException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SecretStoreException.EncryptionFailed(error)
        }
    }

    private fun decrypt(configId: String, encoded: String): String {
        return try {
            val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH_BYTES) throw IllegalArgumentException("Invalid encrypted payload")
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.updateAAD(configId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (error: SecretStoreException) {
            throw error
        } catch (error: Exception) {
            throw SecretStoreException.DecryptionFailed(error)
        }
    }

    private fun getOrCreateKey(): java.security.Key {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(KEY_SIZE_BITS)
                            .build(),
                    )
                    generateKey()
                }
            }
            return requireNotNull(keyStore.getKey(KEY_ALIAS, null))
        } catch (error: Exception) {
            throw SecretStoreException.KeyStoreUnavailable(error)
        }
    }

    private fun storageKey(configId: String): String {
        if (configId.isBlank()) throw SecretStoreException.InvalidConfigId()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(configId.toByteArray(Charsets.UTF_8))
        return STORAGE_KEY_PREFIX + android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }

    companion object {
        const val PREFERENCES_NAME = "agent_chat_secret_store"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "agent_chat_api_keys"
        private const val STORAGE_KEY_PREFIX = "api_key_"
        private const val CONFIG_IDS_PREFERENCE = "api_key_config_ids"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private val secureRandom = SecureRandom()
    }
}

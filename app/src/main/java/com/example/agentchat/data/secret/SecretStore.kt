package com.example.agentchat.data.secret

interface SecretStore {
    suspend fun putApiKey(configId: String, apiKey: String)

    suspend fun getApiKey(configId: String): String?

    suspend fun deleteApiKey(configId: String)

    suspend fun clearAllApiKeys()

    suspend fun snapshotApiKeys(): ApiKeySnapshot

    suspend fun restoreApiKeys(snapshot: ApiKeySnapshot)
}

class ApiKeySnapshot internal constructor(internal val entries: Map<String, String>) {
    override fun toString(): String = "ApiKeySnapshot(<redacted>)"
}

sealed class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidConfigId : SecretStoreException("Config id must not be blank")

    class KeyStoreUnavailable(cause: Throwable? = null) :
        SecretStoreException("Android Keystore is unavailable", cause)

    class EncryptionFailed(cause: Throwable? = null) :
        SecretStoreException("Unable to encrypt API key", cause)

    class DecryptionFailed(cause: Throwable? = null) :
        SecretStoreException("Unable to decrypt stored API key", cause)

    class StorageFailed(cause: Throwable? = null) :
        SecretStoreException("Unable to persist stored API key", cause)
}

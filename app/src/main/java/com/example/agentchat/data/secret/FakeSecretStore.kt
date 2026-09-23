package com.example.agentchat.data.secret

class FakeSecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun putApiKey(configId: String, apiKey: String) {
        if (configId.isBlank()) throw SecretStoreException.InvalidConfigId()
        values[configId] = apiKey
    }

    override suspend fun getApiKey(configId: String): String? {
        if (configId.isBlank()) throw SecretStoreException.InvalidConfigId()
        return values[configId]
    }

    override suspend fun deleteApiKey(configId: String) {
        if (configId.isBlank()) throw SecretStoreException.InvalidConfigId()
        values.remove(configId)
    }

    override suspend fun clearAllApiKeys() = values.clear()

    override suspend fun snapshotApiKeys() = ApiKeySnapshot(values.toMap())

    override suspend fun restoreApiKeys(snapshot: ApiKeySnapshot) {
        values.clear()
        values.putAll(snapshot.entries)
    }
}

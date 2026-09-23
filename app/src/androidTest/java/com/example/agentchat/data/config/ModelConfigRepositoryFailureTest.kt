package com.example.agentchat.data.config

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.data.db.AgentDatabase
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelConfigRepositoryFailureTest {
    private lateinit var database: AgentDatabase
    private lateinit var secrets: RecordingSecretStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).build()
        secrets = RecordingSecretStore()
    }

    @After
    fun tearDown() = if (::database.isInitialized) database.close() else Unit

    @Test
    fun secretFailureDoesNotLeaveRoomConfig() = runBlocking {
        secrets.failWrites = true
        val repository = RoomModelConfigRepository(database, secrets)

        runCatching { repository.save(config(), "new-key", makeDefault = true) }

        runBlocking { assertNull(database.configDao().findById("config")) }
        assertEquals(emptyMap<String, String>(), secrets.values)
    }

    @Test
    fun databaseFailureRestoresPreviousKey() = runBlocking {
        val repository = RoomModelConfigRepository(database, secrets)
        repository.save(config(), "old-key", makeDefault = true)
        database.close()

        runCatching { repository.save(config().copy(modelName = "new-model"), "new-key", makeDefault = true) }

        assertEquals("old-key", secrets.values["config"])
    }

    @Test
    fun concurrentSavesSerializeWithoutRestoringOverAnotherSuccess() = runBlocking {
        val repository = RoomModelConfigRepository(database, secrets)
        val first = config().copy(id = "first", displayName = "First")
        val second = config().copy(id = "second", displayName = "Second")

        coroutineScope {
            listOf(
                async { repository.save(first, "first-key", makeDefault = true) },
                async { repository.save(second, "second-key", makeDefault = false) },
            ).awaitAll()
        }

        assertEquals(setOf("first", "second"), database.configDao().findAll().map { it.id }.toSet())
        assertEquals(1, database.configDao().findAll().count { it.isDefault })
    }

    @Test
    fun cancellationDuringKeyWriteIsRethrownAfterCompensation() {
        secrets.throwCancellation = true
        val repository = RoomModelConfigRepository(database, secrets)

        assertThrows(CancellationException::class.java) {
            runBlocking { repository.save(config(), "cancelled-key", makeDefault = true) }
        }
        runBlocking { assertNull(database.configDao().findById("config")) }
    }

    private fun config() = ModelConfig(
        id = "config",
        displayName = "Config",
        baseUrl = "https://example.test",
        modelName = "model",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
    )
}

private class RecordingSecretStore : SecretStore {
    val values = mutableMapOf<String, String>()
    var failWrites = false
    var throwCancellation = false

    override suspend fun putApiKey(configId: String, apiKey: String) {
        if (throwCancellation) throw CancellationException("cancelled")
        if (failWrites) error("secret write failed")
        values[configId] = apiKey
    }

    override suspend fun getApiKey(configId: String): String? = values[configId]

    override suspend fun deleteApiKey(configId: String) {
        if (failWrites) error("secret delete failed")
        values.remove(configId)
    }

    override suspend fun clearAllApiKeys() = values.clear()
    override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(values.toMap())
    override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) { values.clear(); values.putAll(snapshot.entries) }
}

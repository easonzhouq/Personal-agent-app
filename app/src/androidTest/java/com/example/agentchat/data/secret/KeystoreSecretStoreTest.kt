package com.example.agentchat.data.secret

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private lateinit var context: Context
    private lateinit var store: KeystoreSecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(KeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = KeystoreSecretStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(KeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun storeAndRetrieveApiKey() = runBlocking {
        store.putApiKey("openai", "secret-value")

        assertEquals("secret-value", store.getApiKey("openai"))
    }

    @Test
    fun replacingApiKeyReturnsOnlyLatestValue() = runBlocking {
        store.putApiKey("openai", "old-value")
        val firstCiphertext = context.getSharedPreferences(
            KeystoreSecretStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).all.filterKeys { it.startsWith("api_key_") }.values.single()
        store.putApiKey("openai", "new-value")
        val secondCiphertext = context.getSharedPreferences(
            KeystoreSecretStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).all.filterKeys { it.startsWith("api_key_") }.values.single()

        assertEquals("new-value", store.getApiKey("openai"))
        assertNotEquals(firstCiphertext, secondCiphertext)
    }

    @Test
    fun deleteRemovesApiKey() = runBlocking {
        store.putApiKey("openai", "secret-value")

        store.deleteApiKey("openai")

        assertNull(store.getApiKey("openai"))
    }

    @Test
    fun missingApiKeyReturnsNull() = runBlocking {
        assertNull(store.getApiKey("missing"))
    }

    @Test
    fun blankConfigIdReturnsTypedValidationError() {
        val error = assertThrows(SecretStoreException.InvalidConfigId::class.java) {
            runBlocking { store.getApiKey(" ") }
        }

        assertEquals("Config id must not be blank", error.message)
    }

    @Test
    fun valuePersistsWhenStoreIsReopened() = runBlocking {
        store.putApiKey("openai", "secret-value")

        val reopened = KeystoreSecretStore(context)

        assertEquals("secret-value", reopened.getApiKey("openai"))
    }

    @Test
    fun configIdsAreIsolated() = runBlocking {
        store.putApiKey("one", "first")
        store.putApiKey("two", "second")

        assertEquals("first", store.getApiKey("one"))
        assertEquals("second", store.getApiKey("two"))
        store.deleteApiKey("one")
        assertNull(store.getApiKey("one"))
        assertEquals("second", store.getApiKey("two"))
    }

    @Test
    fun rawPreferencesNeverContainApiKeyPlaintext() = runBlocking {
        val firstSecret = "first-secret-value"
        val secondSecret = "second-secret-value"
        store.putApiKey("one", firstSecret)
        store.putApiKey("two", secondSecret)

        val rawValues = context.getSharedPreferences(
            KeystoreSecretStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).all.filterKeys { it.startsWith("api_key_") }.values.map { it.toString() }

        assertEquals(2, rawValues.size)
        rawValues.forEach { value ->
            assertNotEquals(firstSecret, value)
            assertNotEquals(secondSecret, value)
            assertTrue(!value.contains(firstSecret))
            assertTrue(!value.contains(secondSecret))
        }
        assertNotEquals(rawValues[0], rawValues[1])
    }

    @Test
    fun swappingCiphertextsBetweenConfigIdsFailsWithTypedError() = runBlocking {
        store.putApiKey("one", "first-secret")
        store.putApiKey("two", "second-secret")
        val preferences = context.getSharedPreferences(
            KeystoreSecretStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val entries = preferences.all.filterKeys { it.startsWith("api_key_") }.mapValues { it.value.toString() }
        val keys = entries.keys.toList()
        preferences.edit()
            .putString(keys[0], entries.getValue(keys[1]))
            .putString(keys[1], entries.getValue(keys[0]))
            .commit()

        assertThrows(SecretStoreException.DecryptionFailed::class.java) {
            runBlocking { store.getApiKey("one") }
        }
        assertThrows(SecretStoreException.DecryptionFailed::class.java) {
            runBlocking { store.getApiKey("two") }
        }
    }

    @Test
    fun corruptedCiphertextReturnsTypedDecryptionErrorWithoutSecret() = runBlocking {
        store.putApiKey("openai", "secret-value")
        val preferences = context.getSharedPreferences(
            KeystoreSecretStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val storageKey = preferences.all.keys.single { it.startsWith("api_key_") }
        preferences.edit().putString(storageKey, Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))).commit()

        val error = assertThrows(SecretStoreException.DecryptionFailed::class.java) {
            runBlocking { store.getApiKey("openai") }
        }

        assertEquals("Unable to decrypt stored API key", error.message)
    }

    @Test
    fun snapshotClearRestoreIncludesLegacyUnregisteredKey() = runBlocking {
        val preferences = context.getSharedPreferences(KeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString("api_key_legacy_unregistered", "legacy-ciphertext").commit()

        val snapshot = store.snapshotApiKeys()
        store.clearAllApiKeys()
        assertTrue(preferences.all.keys.none { it.startsWith("api_key_") })
        store.restoreApiKeys(snapshot)

        assertEquals("legacy-ciphertext", preferences.getString("api_key_legacy_unregistered", null))
    }
}

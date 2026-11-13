package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionSettingsFormTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun successClearsOnlyAfterConfirmationAndRejectsConcurrentSubmit() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<TranscriptionSettings, String>>()
        val form = TranscriptionSettingsForm(backgroundScope) { settings, secret ->
            calls += settings to secret
            started.complete(Unit)
            release.await()
        }
        form.initialize(TranscriptionSettings())
        form.setSecret("first-secret")
        form.setLanguage("en")
        assertTrue(form.submit())
        assertTrue(form.state.value.saving)
        assertFalse(form.submit()) // avant même la planification de la première coroutine
        started.await()
        form.setSecret("must-not-overwrite")
        form.setLanguage("de")
        assertEquals("first-secret", form.state.value.secret)
        assertFalse(form.submit())
        release.complete(Unit)
        runCurrent()
        assertFalse(form.state.value.saving)
        assertEquals("", form.state.value.secret)
        assertEquals("en", form.state.value.language)
        assertEquals(1, calls.size)
        form.setSecret("new-secret")
        form.setLanguage("de")
        assertTrue(form.submit())
        runCurrent()
        assertEquals(listOf("first-secret", "new-secret"), calls.map { it.second })
        assertEquals(listOf("en", "de"), calls.map { it.first.language })
        assertEquals("", form.state.value.secret)
    }

    @Test fun failureKeepsSecretAndAllowsRetry() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val form = TranscriptionSettingsForm(backgroundScope) { _, _ ->
            calls++
            if (calls == 1) { started.complete(Unit); release.await(); error("secret from provider") }
        }
        form.initialize(TranscriptionSettings())
        form.setSecret("secret from provider")
        assertTrue(form.submit())
        started.await()
        assertEquals("secret from provider", form.state.value.secret)
        release.complete(Unit)
        runCurrent()
        assertFalse(form.state.value.saving)
        assertEquals("secret from provider", form.state.value.secret)
        assertFalse(form.state.value.message!!.contains("secret from provider"))
        assertFalse(form.state.value.toString().contains("secret from provider"))
        assertTrue(form.submit())
        runCurrent()
        assertEquals("", form.state.value.secret)
        assertEquals(2, calls)
    }

    @Test fun durableWriteBarrierRetainsKeyAndPersistsSubmissionsInOrder() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stateFile = File(tmp.root, "state.json")
        var writes = 0
        val repository = LocalStateRepository(LocalStateStore(stateFile, rename = { from, to ->
            writes++
            if (writes == 1) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            from.renameTo(to)
        }))
        repository.awaitInitialized()
        val keys = mutableMapOf<String, String>()
        val store = object : CredentialStore {
            override fun save(secret: String): String = "ref-${keys.size + 1}".also { keys[it] = secret }
            override fun read(ref: String): String = keys.getValue(ref)
            override fun remove(ref: String) { keys.remove(ref) }
        }
        val manager = TranscriptionSettingsManager(repository, store)
        val firstFinished = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        var saves = 0
        val form = TranscriptionSettingsForm(backgroundScope) { settings, key ->
            manager.save(settings, key)
            if (++saves == 1) firstFinished.complete(Unit) else secondFinished.complete(Unit)
        }
        form.initialize(TranscriptionSettings())
        form.setLanguage("en")
        form.setSecret("first")
        assertTrue(form.submit())
        try {
            runCurrent()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("first", form.state.value.secret)
            assertFalse(form.submit())
        } finally { release.countDown() }
        firstFinished.await()
        runCurrent()
        assertEquals("", form.state.value.secret)
        form.setLanguage("de")
        form.setSecret("second")
        assertTrue(form.submit())
        secondFinished.await()
        runCurrent()
        val saved = LocalStateStore(stateFile).load().transcriptionSettings
        assertEquals("de", saved.language)
        assertEquals("second", store.read(saved.credentialRef!!))
        assertFalse(stateFile.readText().contains("first"))
        assertFalse(stateFile.readText().contains("second"))
    }

    @Test fun invalidAddressIsShownWithoutSavingOrClearingKey() = runTest {
        var calls = 0
        val form = TranscriptionSettingsForm(backgroundScope) { _, _ -> calls++ }
        form.initialize(TranscriptionSettings())
        form.setSecret("secret")
        form.setBaseUrl("https://user:password@example.invalid/v1")
        assertFalse(form.submit())
        assertEquals(0, calls)
        assertEquals("secret", form.state.value.secret)
        assertTrue(form.state.value.message!!.contains("Adresse invalide"))
        assertFalse(form.state.value.message!!.contains("password"))
    }
}

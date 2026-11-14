package top.supergeoff.dictee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SynthesisSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val secret = "synthetic-synthesis-key"
    private fun file() = File(tmp.root, "state.json")
    private class FakeCredentials : CredentialStore {
        val values = mutableMapOf<String, String>()
        val removed = mutableListOf<String>()
        var fail = false
        var failRemove = false
        override fun save(secret: String): String {
            if (fail) error(secret)
            return "ref-${values.size + 1}".also { values[it] = secret }
        }
        override fun read(ref: String): String = values[ref] ?: error("synthetic-synthesis-key")
        override fun remove(ref: String) {
            removed += ref
            if (failRemove) error("synthetic-removal-error")
            values.remove(ref)
        }
    }
    private fun task(ref: String): TranscriptionTask {
        val context = SynthesisContext("https://example.invalid/v1", ref, "model", "prompt", PromptMetadata("type", "date", "durée"), null, null)
        val work = SynthesisWork(SynthesisPhase.READY, "record.md", false, context, 0, null, null, null)
        return TranscriptionTask("1", "record", "record.mp3", "record.vtt", "content://folder", "hash",
            "https://example.invalid/v1", "stt-ref", "fr", "stt-model", TranscriptionPhase.SYNTHESIZING, synthesis = work)
    }

    @Test fun urlValidationAndUnconfiguredValue() = runBlocking {
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, credentials)
        listOf("/v1", "https://example.invalid/v1", "http://example.invalid:8080/v1").forEach {
            manager.save(SynthesisSettings(baseUrl = it), null)
            assertEquals(it, LocalStateStore(file()).load().synthesisSettings.baseUrl)
        }
        listOf("https://user:synthetic@example.invalid/v1", "https://example.invalid/v1?key=synthetic",
            "https://example.invalid/v1#synthetic", "https://example.invalid/v1/", "https://example.invalid/other/v1",
            "https://example.invalid/%76%31", "ftp://example.invalid/v1", "https://example.invalid:0/v1").forEach {
            try { manager.save(SynthesisSettings(baseUrl = it), secret); fail(it) }
            catch (e: Exception) { assertFalse(e.toString().contains("synthetic")) }
        }
        assertTrue(credentials.values.isEmpty())
        assertFalse(file().readText().contains(secret))
    }

    @Test fun blankKeepsKeyAndRotationPreservesActiveReference() = runBlocking {
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, credentials)
        manager.save(SynthesisSettings(baseUrl = "https://example.invalid/v1", model = "model"), secret)
        val old = repository.state.value!!.synthesisSettings.credentialRef!!
        repository.updateDurably { it.copy(transcriptionTasks = listOf(task(old))) }
        manager.save(SynthesisSettings(model = "second", credentialRef = "untrusted-ref"), "")
        assertEquals(old, repository.state.value!!.synthesisSettings.credentialRef)
        manager.save(SynthesisSettings(model = "third"), "synthetic-replacement")
        val state = LocalStateStore(file()).load()
        assertNotEquals(old, state.synthesisSettings.credentialRef)
        assertEquals(old, state.transcriptionTasks.single().synthesis!!.context!!.credentialRef)
        assertEquals(secret, credentials.read(old))
        assertEquals("synthetic-replacement", credentials.read(state.synthesisSettings.credentialRef!!))
        assertFalse(file().readText().contains(secret))
        assertFalse(file().readText().contains("synthetic-replacement"))
    }

    @Test fun concurrentSavesAndTaskUpdatePreserveReferences() = runBlocking {
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, credentials)
        val a = async(Dispatchers.Default) { manager.save(SynthesisSettings(model = "a"), secret) }
        val b = async(Dispatchers.Default) { manager.save(SynthesisSettings(model = "b"), "synthetic-other") }
        val c = async(Dispatchers.Default) { repository.updateDurably { it.copy(transcriptionTasks = listOf(task("active-ref"))) } }
        a.await(); b.await(); c.await()
        val state = LocalStateStore(file()).load()
        assertEquals(2, credentials.values.size)
        assertTrue(state.synthesisSettings.credentialRef in credentials.values.keys)
        assertEquals("active-ref", state.transcriptionTasks.single().synthesis!!.context!!.credentialRef)
        assertFalse(file().readText().contains(secret))
    }

    @Test fun failuresNeverRevealSecretOrPublishSettings() = runBlocking {
        val credentials = FakeCredentials().apply { fail = true }
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, credentials)
        try { manager.save(SynthesisSettings(), secret); fail() } catch (e: Exception) {
            assertFalse(e.toString().contains(secret)); assertNull(e.cause)
        }
        assertNull(repository.awaitInitialized().synthesisSettings.credentialRef)
        assertFalse(file().exists())
        credentials.fail = false
        val broken = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try { SynthesisSettingsManager(broken, credentials).save(SynthesisSettings(), secret); fail() }
        catch (e: Exception) { assertFalse(e.toString().contains(secret)); assertNull(e.cause) }
        assertNull(broken.state.value!!.synthesisSettings.credentialRef)
        assertTrue(credentials.values.isEmpty())
        assertEquals(listOf("ref-1"), credentials.removed)
    }

    @Test fun failedRotationRemovesOnlyNewReferenceAndPreservesActiveTask() = runBlocking {
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, credentials)
        manager.save(SynthesisSettings(model = "old"), secret)
        val old = repository.state.value!!.synthesisSettings.credentialRef!!
        repository.updateDurably { it.copy(transcriptionTasks = listOf(task(old))) }
        val before = file().readText()
        val broken = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try { SynthesisSettingsManager(broken, credentials).save(SynthesisSettings(model = "new"), "synthetic-replacement"); fail() }
        catch (e: SynthesisClientException) { assertEquals(SynthesisFailureKind.CREDENTIAL, e.kind) }
        assertEquals(listOf("ref-2"), credentials.removed)
        assertEquals(mapOf(old to secret), credentials.values)
        assertEquals(before, file().readText())
        assertEquals(old, broken.state.value!!.transcriptionTasks.single().synthesis!!.context!!.credentialRef)
        assertEquals(old, broken.state.value!!.synthesisSettings.credentialRef)
    }

    @Test fun failureBeforeRenameRemovesNewReference() = runBlocking {
        val directory = File(tmp.root, "subdir").apply { mkdir() }
        val stateFile = File(directory, "state.json")
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(stateFile))
        repository.awaitInitialized()
        assertTrue(directory.delete())
        directory.writeText("not a directory")
        try { SynthesisSettingsManager(repository, credentials).save(SynthesisSettings(), secret); fail() }
        catch (e: SynthesisClientException) { assertEquals(SynthesisFailureKind.CREDENTIAL, e.kind) }
        assertEquals(listOf("ref-1"), credentials.removed)
        assertTrue(credentials.values.isEmpty())
    }

    @Test fun validationFailureBeforeWriteRemovesNewReference() = runBlocking {
        val incompleteTask = task("active-ref").copy(serverAddress = "")
        val repository = LocalStateRepository(LocalStateStore(file()), load = {
            LocalState(transcriptionTasks = listOf(incompleteTask))
        })
        val credentials = FakeCredentials()
        try { SynthesisSettingsManager(repository, credentials).save(SynthesisSettings(), secret); fail() }
        catch (e: SynthesisClientException) { assertEquals(SynthesisFailureKind.CREDENTIAL, e.kind) }
        assertEquals(listOf("ref-1"), credentials.removed)
        assertTrue(credentials.values.isEmpty())
        assertFalse(file().exists())
    }

    @Test fun failedSaveWithoutNewKeyDoesNotRemoveOldReference() = runBlocking {
        val credentials = FakeCredentials()
        val ref = credentials.save(secret)
        LocalStateStore(file()).save(LocalState(synthesisSettings = SynthesisSettings(credentialRef = ref)))
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try { SynthesisSettingsManager(repository, credentials).save(SynthesisSettings(model = "new"), ""); fail() }
        catch (e: SynthesisClientException) { assertEquals(SynthesisFailureKind.CREDENTIAL, e.kind) }
        assertTrue(credentials.removed.isEmpty())
        assertEquals(secret, credentials.read(ref))
        assertEquals(ref, LocalStateStore(file()).load().synthesisSettings.credentialRef)
    }

    @Test fun cleanupFailureIsSanitized() = runBlocking {
        val credentials = FakeCredentials().apply { failRemove = true }
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try { SynthesisSettingsManager(repository, credentials).save(SynthesisSettings(), secret); fail() }
        catch (e: SynthesisClientException) {
            assertEquals(SynthesisFailureKind.CREDENTIAL, e.kind)
            assertNull(e.cause)
            assertFalse(e.toString().contains("synthetic"))
        }
        assertEquals(listOf("ref-1"), credentials.removed)
        assertEquals(secret, credentials.read("ref-1"))
    }

    @Test fun uncertainRenameFailureKeepsPotentiallyPublishedReference() = runBlocking {
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { from, to ->
            check(from.renameTo(to))
            error("synthetic-rename-error")
        }))
        try { SynthesisSettingsManager(repository, credentials).save(SynthesisSettings(), secret); fail() }
        catch (e: SynthesisClientException) { assertNull(e.cause) }
        assertTrue(credentials.removed.isEmpty())
        val ref = LocalStateStore(file()).load().synthesisSettings.credentialRef!!
        assertEquals(secret, credentials.read(ref))
    }

    @Test fun cancellationDuringQueuedWriteKeepsPotentiallyPublishedReference() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { from, to ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            from.renameTo(to)
        }))
        val save = launch(Dispatchers.Default) { SynthesisSettingsManager(repository, credentials).save(SynthesisSettings(), secret) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            save.cancelAndJoin()
            assertTrue(credentials.removed.isEmpty())
        } finally { release.countDown() }
        // Une seconde opération attend la fin de la première écriture dans la même file.
        repository.updateDurably { it }
        val ref = LocalStateStore(file()).load().synthesisSettings.credentialRef!!
        assertEquals(secret, credentials.read(ref))
        assertTrue(credentials.removed.isEmpty())
    }

    @Test fun incompleteSynthesisSettingsDoNotBlockMissingVtt() = runBlocking {
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, FakeCredentials())
        manager.save(SynthesisSettings(), null)
        val phone = MemoryStore().apply { files["Note-20261111090000.mp3"] = "synthetic-audio".toByteArray() }
        val state = repository.awaitInitialized()
        val tasks = TranscriptionPreparation.prepare(phone, "content://folder",
            state.transcriptionSettings.copy(baseUrl = "https://example.invalid/v1", credentialRef = "stt-ref"),
            emptyMap(), null) { "id" }
        assertEquals(1, tasks.size)
        assertNull(tasks.single().synthesis)
        assertEquals("stt-ref", tasks.single().encryptedKeyRef)
    }

    @Test fun testUsesSavedKeyOnlyWhenDraftKeyIsEmptyWithoutSavingIt() = runBlocking {
        val credentials = FakeCredentials()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = SynthesisSettingsManager(repository, credentials)
        manager.save(SynthesisSettings(), secret)
        val calls = mutableListOf<Pair<SynthesisSettings, String>>()
        val client = object : SynthesisClient {
            override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray = error("not used")
            override suspend fun test(settings: SynthesisSettings, secret: String) { calls += settings to secret }
        }
        val draft = SynthesisSettings("https://example.invalid/v1", "draft-model", prompt = "draft-prompt")
        manager.test(draft, "", client)
        manager.test(draft, "synthetic-draft-key", client)
        assertEquals(listOf(secret, "synthetic-draft-key"), calls.map { it.second })
        assertEquals(listOf(draft, draft), calls.map { it.first })
        assertEquals(1, credentials.values.size)
        assertFalse(file().readText().contains("synthetic-draft-key"))
        credentials.values.clear()
        try { manager.test(draft, "", client); fail() } catch (e: Exception) {
            assertFalse(e.toString().contains(secret)); assertNull(e.cause)
        }
        assertEquals(2, calls.size)
    }
}

package top.supergeoff.dictee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SynthesisSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val secret = "synthetic-synthesis-key"
    private fun file() = File(tmp.root, "state.json")
    private class FakeCredentials : CredentialStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun save(secret: String): String {
            if (fail) error(secret)
            return "ref-${values.size + 1}".also { values[it] = secret }
        }
        override fun read(ref: String): String = values[ref] ?: error("synthetic-synthesis-key")
        override fun remove(ref: String) { values.remove(ref) }
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

package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val synthetic = "synthetic-key-never-log"
    private fun file() = File(tmp.root, "state.json")
    private class FakeStore : CredentialStore {
        val values = mutableMapOf<String, String>()
        var failSave = false
        override fun save(secret: String): String {
            if (failSave) throw IllegalStateException(secret)
            return "ref-${values.size + 1}".also { values[it] = secret }
        }
        override fun read(ref: String): String = values[ref] ?: throw IllegalStateException("synthetic-key-never-log")
        override fun remove(ref: String) { values.remove(ref) }
    }
    private fun task(ref: String) = TranscriptionTask("1", "record", "record.mp3", "record.vtt", "content://folder", "hash", "https://example.invalid/v1", ref, "fr", "dictee-diarize", TranscriptionPhase.TRACKING)

    @Test fun defaultsAndMigration() {
        val defaults = TranscriptionSettings()
        assertEquals("/v1", defaults.baseUrl)
        assertEquals("fr", defaults.language)
        assertEquals("dictee-diarize", defaults.model)
        assertNull(defaults.credentialRef)
        file().writeText("{\"version\":3}")
        assertEquals(defaults, LocalStateStore(file()).load().transcriptionSettings)
    }

    @Test fun newKeyPreservesTaskReferenceAndStateIsSecretFree() = runBlocking {
        val store = FakeStore()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val settings = TranscriptionSettingsManager(repository, store)
        settings.save(TranscriptionSettings(baseUrl = "https://example.invalid/v1"), synthetic)
        val old = repository.state.value!!.transcriptionSettings.credentialRef!!
        repository.updateDurably { it.copy(transcriptionTasks = listOf(task(old))) }
        settings.save(repository.state.value!!.transcriptionSettings.copy(language = "en"), "synthetic-key-replacement")
        val loaded = LocalStateStore(file()).load()
        assertEquals(old, loaded.transcriptionTasks.single().encryptedKeyRef)
        assertNotEquals(old, loaded.transcriptionSettings.credentialRef)
        assertEquals(synthetic, store.read(old))
        assertEquals("en", loaded.transcriptionSettings.language)
        assertFalse(file().readText().contains(synthetic))
        assertFalse(file().readText().contains("synthetic-key-replacement"))
        assertFalse(loaded.toString().contains(synthetic))
    }

    @Test fun failureDoesNotLeakAndDoesNotPublish() = runBlocking {
        val store = FakeStore().apply { failSave = true }
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = TranscriptionSettingsManager(repository, store)
        try { manager.save(TranscriptionSettings(), synthetic); fail() } catch (e: Exception) {
            assertFalse(e.toString().contains(synthetic)); assertNull(e.cause)
        }
        assertNull(repository.awaitInitialized().transcriptionSettings.credentialRef)
        assertFalse(file().exists())
    }

    @Test fun failedStateWriteDoesNotPublishSettings() = runBlocking {
        val store = FakeStore()
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try { TranscriptionSettingsManager(repository, store).save(TranscriptionSettings(), synthetic); fail() }
        catch (e: Exception) { assertFalse(e.toString().contains(synthetic)); assertNull(e.cause) }
        assertNull(repository.state.value!!.transcriptionSettings.credentialRef)
    }

    @Test fun inaccessibleKeyDurablySuspendsTaskWithoutLeaking() = runBlocking {
        val repository = LocalStateRepository(LocalStateStore(file()))
        repository.updateDurably { it.copy(transcriptionTasks = listOf(task("missing"))) }
        val manager = TranscriptionSettingsManager(repository, FakeStore())
        assertNull(manager.readForTask("1"))
        val loaded = LocalStateStore(file()).load().transcriptionTasks.single()
        assertTrue(loaded.suspended)
        assertEquals("missing", loaded.encryptedKeyRef)
        assertTrue(loaded.suspensionReason!!.contains("réglages"))
        assertFalse(file().readText().contains(synthetic))
    }
    @Test fun concurrentTaskAndSettingsUpdatesShareRepository() = runBlocking {
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = TranscriptionSettingsManager(repository, FakeStore())
        val a = async(kotlinx.coroutines.Dispatchers.Default) { manager.save(TranscriptionSettings(language = "en"), synthetic) }
        val b = async(kotlinx.coroutines.Dispatchers.Default) { repository.updateDurably { it.copy(transcriptionTasks = listOf(task("older-ref"))) } }
        a.await(); b.await()
        val loaded = LocalStateStore(file()).load()
        assertEquals("en", loaded.transcriptionSettings.language)
        assertEquals("older-ref", loaded.transcriptionTasks.single().encryptedKeyRef)
    }

    @Test fun failingSaveAndSuspensionDoNotWriteSecretToJournal() = runBlocking {
        val journal = Journal(File(tmp.root, "journal")) { java.time.LocalDateTime.of(2026, 1, 1, 12, 0) }
        val repository = LocalStateRepository(LocalStateStore(file()), onSaveError = { journal.error(Source.STT, it.message ?: "erreur") })
        val store = FakeStore().apply { failSave = true }
        val manager = TranscriptionSettingsManager(repository, store)
        try { manager.save(TranscriptionSettings(), synthetic); fail() } catch (e: CredentialException) { journal.error(Source.STT, e.message!!) }
        repository.updateDurably { it.copy(transcriptionTasks = listOf(task("missing"))) }
        manager.readForTask("1")
        assertFalse(journal.exportText().contains(synthetic))
        assertFalse(file().readText().contains(synthetic))
    }

    @Test fun failedSuspensionWriteDoesNotAllowCredentialUse() = runBlocking {
        LocalStateStore(file()).save(LocalState(transcriptionTasks = listOf(task("missing"))))
        val repository = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        try { TranscriptionSettingsManager(repository, FakeStore()).readForTask("1"); fail() }
        catch (e: CredentialException) { assertFalse(e.toString().contains(synthetic)); assertNull(e.cause) }
        assertFalse(LocalStateStore(file()).load().transcriptionTasks.single().suspended)
    }
}

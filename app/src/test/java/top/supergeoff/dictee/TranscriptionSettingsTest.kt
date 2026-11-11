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

    @Test fun urlValidationRejectsCredentialsQueryFragmentAndUnexpectedPaths() = runBlocking {
        val valid = listOf("/v1", "https://example.invalid/v1", "http://localhost:8080/v1", "https://[::1]:443/v1")
        val invalid = listOf(
            "https://user:synthetic-secret@example.invalid/v1",
            "https://user%3Asynthetic-secret@example.invalid/v1",
            "https://example.invalid/v1?api_key=synthetic-secret",
            "https://example.invalid/v1?", "https://example.invalid/v1#synthetic-secret",
            "https://example.invalid/v1#", "https://example.invalid/v1/",
            "https://example.invalid/secret/v1", "https://example.invalid/%76%31",
            "ftp://example.invalid/v1", "https:///v1", "https://example.invalid:0/v1",
            "https://example.invalid:65536/v1", "https://example.invalid:/v1",
        )
        valid.forEach { assertTrue(it, isValidTranscriptionBaseUrl(it)) }
        val store = FakeStore()
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = TranscriptionSettingsManager(repository, store)
        invalid.forEach { address ->
            assertFalse(address, isValidTranscriptionBaseUrl(address))
            try { manager.save(TranscriptionSettings(baseUrl = address), synthetic); fail(address) }
            catch (e: InvalidTranscriptionAddressException) { assertFalse(e.toString().contains("synthetic-secret")) }
            try { LocalStateStore(file()).save(LocalState(transcriptionSettings = TranscriptionSettings(baseUrl = address))); fail(address) }
            catch (e: InvalidTranscriptionAddressException) { assertFalse(e.toString().contains("synthetic-secret")) }
            assertTrue(store.values.isEmpty())
            assertFalse(file().exists())
            assertFalse(File(tmp.root, "state.json.tmp").exists())
        }
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
    @Test fun migrationWarningPersistsUntilARealAddressIsSavedAndTaskDoesNotMove() = runBlocking {
        val old = org.json.JSONObject().put("version", 3).put("transcriptionSettings", org.json.JSONObject()
            .put("baseUrl", "https://example.invalid/v1?key=synthetic-old-secret")
            .put("credentialRef", "kept-ref").put("language", "en").put("model", "old-model"))
        file().writeText(old.toString())
        val repository = LocalStateRepository(LocalStateStore(file()))
        val manager = TranscriptionSettingsManager(repository, FakeStore())
        val migrated = repository.awaitInitialized()
        assertTrue(migrated.transcriptionAddressNeedsReview)
        repository.updateDurably { it.copy(transcriptionTasks = listOf(task("task-ref"))) }
        manager.save(migrated.transcriptionSettings, null)
        assertTrue(LocalStateStore(file()).load().transcriptionAddressNeedsReview)
        manager.save(migrated.transcriptionSettings.copy(baseUrl = "https://new.example.invalid/v1"), null)
        val loaded = LocalStateStore(file()).load()
        assertFalse(loaded.transcriptionAddressNeedsReview)
        assertEquals("kept-ref", loaded.transcriptionSettings.credentialRef)
        assertEquals(task("task-ref"), loaded.transcriptionTasks.single())
        assertFalse(file().readText().contains("synthetic-old-secret"))
    }

    @Test fun unsafeFrozenTaskAddressIsNeverWrittenOrUsed() = runBlocking {
        val secret = "synthetic-task-secret"
        val unsafe = task("task-ref").copy(serverAddress = "https://example.invalid/v1?key=$secret")
        try { LocalStateStore(file()).save(LocalState(transcriptionTasks = listOf(unsafe))); fail() }
        catch (e: InvalidTranscriptionAddressException) { assertFalse(e.toString().contains(secret)) }
        assertFalse(file().exists())
        assertFalse(File(tmp.root, "state.json.tmp").exists())
        LocalStateStore(file()).save(LocalState(transcriptionTasks = listOf(task("task-ref"))))
        val old = org.json.JSONObject(file().readText())
        old.getJSONArray("transcriptionTasks").getJSONObject(0).put("serverAddress", unsafe.serverAddress)
        file().writeText(old.toString())
        val fake = FakeStore().apply { values["task-ref"] = synthetic }
        val repo = LocalStateRepository(LocalStateStore(file()))
        assertNull(TranscriptionSettingsManager(repo, fake).readForTask("1"))
        assertTrue(repo.awaitInitialized().transcriptionTasks.single().suspended)
        repo.updateDurably { it.copy(theme = ThemeChoice.DARK) }
        val blocked = LocalStateStore(file()).load().transcriptionTasks.single()
        assertEquals("", blocked.serverAddress)
        assertTrue(blocked.suspended)
        assertEquals("task-ref", blocked.encryptedKeyRef)
        assertFalse(file().readText().contains(secret))
        TranscriptionSettingsManager(repo, fake).save(TranscriptionSettings(baseUrl = "https://new.example.invalid/v1"), null)
        assertEquals(blocked, LocalStateStore(file()).load().transcriptionTasks.single())
        assertNull(TranscriptionSettingsManager(repo, fake).readForTask("1"))
    }

    @Test fun migrationCoversForbiddenAddressFormsWithoutChangingOtherSettings() {
        val invalid = listOf("https://user:synthetic@example.invalid/v1",
            "https://example.invalid/v1?token=synthetic", "https://example.invalid/v1#synthetic",
            "https://example.invalid/other", "not a url")
        invalid.forEach { address ->
            val before = org.json.JSONObject().put("version", 3).put("transcriptionSettings", org.json.JSONObject()
                .put("baseUrl", address).put("credentialRef", "ref-kept").put("language", "en").put("model", "model-kept")).toString()
            file().writeText(before)
            val loaded = LocalStateStore(file()).load()
            assertEquals(before, file().readText())
            assertEquals(TranscriptionSettings("/v1", "en", "model-kept", "ref-kept"), loaded.transcriptionSettings)
            assertTrue(loaded.transcriptionAddressNeedsReview)
            assertFalse(loaded.toString().contains("synthetic"))
            LocalStateStore(file()).save(loaded)
            assertFalse(file().readText().contains("synthetic"))
            assertTrue(LocalStateStore(file()).load().transcriptionAddressNeedsReview)
        }
        val valid = TranscriptionSettings(baseUrl = "https://example.invalid/v1", credentialRef = "ref-kept")
        LocalStateStore(file()).save(LocalState(transcriptionSettings = valid))
        assertEquals(valid, LocalStateStore(file()).load().transcriptionSettings)
        assertFalse(LocalStateStore(file()).load().transcriptionAddressNeedsReview)
    }

    @Test fun migratedTasksNeverUseCredentialsOrChangeServerWhenSettingsChange() = runBlocking {
        val store = LocalStateStore(file())
        val phases = TranscriptionPhase.entries
        val tasks = phases.map { task("task-ref").copy(localId = it.name, phase = it) }
        store.save(LocalState(transcriptionTasks = tasks))
        val old = org.json.JSONObject(file().readText())
        val secret = "synthetic-old-task-secret"
        val array = old.getJSONArray("transcriptionTasks")
        phases.forEachIndexed { i, _ -> array.getJSONObject(i).put("serverAddress", "https://example.invalid/v1?key=$secret") }
        file().writeText(old.toString())
        var reads = 0
        val credentials = object : CredentialStore {
            override fun save(secret: String) = "new-ref"
            override fun read(ref: String): String { reads++; return synthetic }
            override fun remove(ref: String) = Unit
        }
        val repo = LocalStateRepository(store)
        val manager = TranscriptionSettingsManager(repo, credentials)
        val migrated = repo.awaitInitialized().transcriptionTasks
        phases.forEach { assertNull(manager.readForTask(it.name)) }
        assertEquals(0, reads)
        manager.save(TranscriptionSettings(baseUrl = "https://new.example.invalid/v1"), null)
        assertEquals(migrated, store.load().transcriptionTasks)
        assertFalse(file().readText().contains(secret))
        phases.forEach { assertNull(manager.readForTask(it.name)) }
        assertEquals(0, reads)
    }


    @Test fun terminalEmptyAddressIsSafeButCannotBeReactivatedWithoutSuspension() {
        val store = LocalStateStore(file())
        val terminal = task("kept-ref").copy(phase = TranscriptionPhase.COMPLETED, serverAddress = "")
        store.save(LocalState(transcriptionTasks = listOf(terminal)))
        assertEquals(terminal, store.load().transcriptionTasks.single())
        val reactivated = terminal.copy(phase = TranscriptionPhase.TRACKING)
        try { store.save(LocalState(transcriptionTasks = listOf(reactivated))); fail() }
        catch (_: InvalidTranscriptionAddressException) { }
        assertEquals(terminal, store.load().transcriptionTasks.single())
    }

}

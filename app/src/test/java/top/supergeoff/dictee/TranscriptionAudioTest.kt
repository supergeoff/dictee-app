package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionAudioTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun task() = TranscriptionTask(
        "local-1", "20261111120000", "note.mp3", "note.vtt", "content://folder",
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "https://example.invalid/v1", "ref", "fr", "dictee-diarize", TranscriptionPhase.PENDING,
    )

    @Test fun `hash reads the file content as SHA-256`() {
        val store = MemoryStore().apply { files["note.mp3"] = "abc".toByteArray() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TranscriptionAudio.sha256(store, "note.mp3"))
    }

    @Test fun `verification rejects changed and missing audio`() {
        val store = MemoryStore().apply { files["note.mp3"] = "abc".toByteArray() }
        assertTrue(TranscriptionAudio.matches(store, task()))
        store.files["note.mp3"] = "abd".toByteArray()
        assertFalse(TranscriptionAudio.matches(store, task()))
        store.files.remove("note.mp3")
        assertFalse(TranscriptionAudio.matches(store, task()))
    }

    @Test fun `coordinator does not submit when real verification finds a changed mp3`() = runBlocking {
        val disk = LocalStateStore(File(tmp.root, "state.json"))
        disk.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(disk)
        val credentials = object : CredentialStore {
            override fun save(secret: String) = "ref"
            override fun read(ref: String) = "synthetic-key"
            override fun remove(ref: String) = Unit
        }
        val phone = MemoryStore().apply { files["note.mp3"] = "abd".toByteArray() }
        var sent = false
        val client = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult { sent = true; error("must not send") }
            override suspend fun get(task: TranscriptionTask): ServerTask = error("must not read")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("must not cancel")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { TranscriptionAudio.matches(phone, it) })
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("local-1"))
        assertFalse(sent)
        assertTrue(disk.load().transcriptionTasks.single().suspended)
    }
}

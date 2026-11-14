package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Parcours nominal DIC-25, rejoué hors téléphone : un VTT existe, « Créer le MD » puis « Refaire le MD »
 * écrivent le MD sans jamais envoyer le MP3 à la transcription.
 */
class MdCreationFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
    private val sttSettings = TranscriptionSettings("https://example.invalid/v1", "fr", "model", "ref")
    private val mdSettings = SynthesisSettings("https://example.invalid/v1", "model", "ref")

    private class RecordingTranscriptionClient : TranscriptionClient {
        var touched = false
        override suspend fun submit(task: TranscriptionTask): SubmitResult { touched = true; error("le VTT existe : aucun envoi de MP3 attendu") }
        override suspend fun get(task: TranscriptionTask): ServerTask { touched = true; error("le VTT existe : aucune requête attendue") }
        override suspend fun cancel(task: TranscriptionTask): ServerTask { touched = true; error("unexpected") }
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }

    private class MdClient(private val content: String) : SynthesisClient {
        var calls = 0
        override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray { calls++; return content.toByteArray() }
        override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
    }

    private fun coordinator(
        phone: MemoryStore, repo: LocalStateRepository, stt: TranscriptionClient, md: SynthesisClient,
    ) = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, FakeCredentials()), RecoverySafety(repo),
        verifyAudio = { true }, schedule = {},
        synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
        phoneStore = { phone }, synthesisCredentialAccessible = { true },
        saveVtt = { task, bytes, promote -> promote { phone.files[task.vttName] = bytes } },
        prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder",
            sttSettings, emptyMap(), names, mdSettings, request, active) { java.util.UUID.randomUUID().toString() } })

    @Test fun `creer le MD depuis un VTT existant ecrit le MD sans transcription`() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val stt = RecordingTranscriptionClient()
        val md = MdClient("première synthèse")
        val coordinator = coordinator(phone, LocalStateRepository(LocalStateStore(File(tmp.root, "state.json"))), stt, md)
        val id = coordinator.start(setOf("20261111120000"), RequestedWork.MISSING).single()
        val created = LocalStateStore(File(tmp.root, "state.json")).load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.SYNTHESIZING, created.phase)
        assertEquals(SynthesisPhase.READY, created.synthesis!!.phase)
        assertFalse(stt.touched)
        assertEquals(RunOutcome.Finished, coordinator.runBounded(id))
        assertEquals("première synthèse", phone.files.getValue("Note-20261111120000.md").toString(Charsets.UTF_8))
        assertEquals(1, md.calls)
        assertFalse(stt.touched)
    }

    @Test fun `refaire le MD remplace le MD sans retranscrire`() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
            files["Note-20261111120000.md"] = "ancien MD".toByteArray()
        }
        val stt = RecordingTranscriptionClient()
        val md = MdClient("nouvelle synthèse")
        val coordinator = coordinator(phone, LocalStateRepository(LocalStateStore(File(tmp.root, "state.json"))), stt, md)
        val id = coordinator.start(setOf("20261111120000"), RequestedWork.REDO_MD).single()
        assertEquals(RunOutcome.Finished, coordinator.runBounded(id))
        assertEquals("nouvelle synthèse", phone.files.getValue("Note-20261111120000.md").toString(Charsets.UTF_8))
        assertEquals(1, md.calls)
        assertFalse(stt.touched)
    }
}

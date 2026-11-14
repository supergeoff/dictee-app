package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SynthesisIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val validVtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
    private val sttSettings = TranscriptionSettings("https://example.invalid/v1", "fr", "model", "ref")
    private val mdSettings = SynthesisSettings("https://example.invalid/v1", "model", "ref")
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "synthetic"
        override fun remove(ref: String) = Unit
    }

    @Test fun newVttFeedsMdAndFailureNeverUsesOldVtt() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = "ancien vtt".toByteArray()
            files["Note-20261111120000.md"] = "ancien md".toByteArray()
        }
        var fail = true
        var sent: ByteArray? = null
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult = if (fail) throw TranscriptionHttpException(400) else SubmitResult.DirectVtt(validVtt)
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val md = object : SynthesisClient {
            override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray { sent = vtt; return "nouveau md".toByteArray() }
            override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
        }
        val stateStore = LocalStateStore(File(tmp.root, "state.json"))
        val repo = LocalStateRepository(stateStore)
        var id = 0
        val coordinator = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, schedule = {}, synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true },
            saveVtt = { task, bytes -> phone.files[task.vttName] = bytes },
            prepareRequested = { names, request -> TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), names, mdSettings, request) { "id-${id++}" } })
        val first = coordinator.start(setOf("20261111120000"), RequestedWork.REDO_VTT_AND_MD).single()
        coordinator.runBounded(first)
        assertNull(sent)
        assertEquals("ancien vtt", phone.files["Note-20261111120000.vtt"]!!.toString(Charsets.UTF_8))
        assertEquals("ancien md", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
        fail = false
        val second = coordinator.start(setOf("20261111120000"), RequestedWork.REDO_VTT_AND_MD).single()
        coordinator.runBounded(second)
        assertArrayEquals(validVtt, phone.files["Note-20261111120000.vtt"])
        assertEquals(TranscriptionAudio.sha256(phone, "Note-20261111120000.vtt"), stateStore.load().transcriptionTasks.last().synthesis!!.context!!.vttSha256)
        coordinator.runBounded(second)
        assertArrayEquals(validVtt, sent)
        assertEquals("nouveau md", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
    }

    @Test fun missingSynthesisSettingsKeepsVtt() = runBlocking {
        val phone = MemoryStore().apply { files["Note-20261111120000.mp3"] = "audio".toByteArray() }
        val stateStore = LocalStateStore(File(tmp.root, "state.json"))
        val repo = LocalStateRepository(stateStore)
        var mdCalls = 0
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask) = SubmitResult.DirectVtt(validVtt)
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val md = object : SynthesisClient {
            override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray { mdCalls++; return "md".toByteArray() }
            override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
        }
        var configured = false
        var id = 0
        val coordinator = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, schedule = {}, synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true }, saveVtt = { task, bytes -> phone.files[task.vttName] = bytes },
            prepareRequested = { names, request -> TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), names,
                if (configured) mdSettings else SynthesisSettings(), request) { "id-${id++}" } })
        val first = coordinator.start(null).single()
        coordinator.runBounded(first)
        assertArrayEquals(validVtt, phone.files["Note-20261111120000.vtt"])
        assertEquals(SynthesisPhase.SUSPENDED, stateStore.load().transcriptionTasks.single().synthesis!!.phase)
        configured = true
        coordinator.reconcile()
        assertEquals(0, mdCalls)
        val second = coordinator.start(setOf("20261111120000"), RequestedWork.REDO_MD).single()
        coordinator.runBounded(second)
        assertEquals(1, mdCalls)
    }

    @Test fun existingVttDoesNotRequireSttSettings() {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio synthétique".toByteArray()
            files["Note-20261111120000.vtt"] = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
        }
        val tasks = TranscriptionPreparation.prepare(phone, "content://folder", TranscriptionSettings(), emptyMap(), null,
            SynthesisSettings("https://example.invalid/v1", "model", "ref"), RequestedWork.MISSING) { "id" }
        assertEquals(1, tasks.size)
        assertEquals(TranscriptionPhase.SYNTHESIZING, tasks.single().phase)
        assertEquals(SynthesisPhase.READY, tasks.single().synthesis!!.phase)
    }
}

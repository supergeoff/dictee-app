package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Preuves DIC-25, une par constat du brief. Les tests en forme finale (constats 3, 4 et 9) échouent tant que
 * le défaut existe : leur échec est la preuve. Les tests de caractérisation (constats 5, 6, 7, 8 et 10)
 * décrivent le comportement actuel et sont inversés avec la correction.
 */
class Dic25FindingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "state.json")
    private val store get() = LocalStateStore(file)

    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
    private val sttSettings = TranscriptionSettings("https://example.invalid/v1", "fr", "model", "ref")
    private val mdSettings = SynthesisSettings("https://example.invalid/v1", "model", "ref")

    private class FakeStt(
        var onSubmit: suspend (TranscriptionTask) -> SubmitResult = { SubmitResult.Accepted(ServerTask("server-1", ServerStatus.QUEUED, null, null)) },
        var onGet: suspend (TranscriptionTask) -> ServerTask = { ServerTask("server-1", ServerStatus.IN_PROGRESS, 0.5, null) },
        var onCancel: suspend (TranscriptionTask) -> ServerTask = { ServerTask("server-1", ServerStatus.CANCELLED, null, null) },
    ) : TranscriptionClient {
        var submissions = 0
        var reads = 0
        var cancels = 0
        override suspend fun submit(task: TranscriptionTask): SubmitResult { submissions++; return onSubmit(task) }
        override suspend fun get(task: TranscriptionTask): ServerTask { reads++; return onGet(task) }
        override suspend fun cancel(task: TranscriptionTask): ServerTask { cancels++; return onCancel(task) }
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }

    private class FakeMd(val block: suspend () -> ByteArray = { "synthèse".toByteArray() }) : SynthesisClient {
        var calls = 0
        override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray { calls++; return block() }
        override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
    }

    private fun baseCoordinator(
        repository: LocalStateRepository, stt: FakeStt, safety: RecoverySafety, log: ListLog? = null,
        md: SynthesisClient? = null, phone: MemoryStore? = null, clock: () -> Long = System::currentTimeMillis,
    ) = TranscriptionCoordinator(repository, stt, TranscriptionSettingsManager(repository, FakeCredentials()), safety,
        verifyAudio = { true }, schedule = {}, log = log, clock = clock,
        synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage-" + System.nanoTime())),
        phoneStore = { phone ?: MemoryStore() }, synthesisCredentialAccessible = { true })

    private fun task(id: String, name: String, phase: TranscriptionPhase) = TranscriptionTask(id, name,
        "Note-$name.mp3", "Note-$name.vtt", "content://folder", "hash", "https://example.invalid/v1", "ref", "fr", "model", phase)

    private fun synthesisOnly(name: String): TranscriptionTask {
        val hash = TranscriptionAudio.sha256(vtt)
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "template",
            PromptMetadata("Note", "date", "durée"), "prompt", hash)
        return TranscriptionTask("syn-$name", name, "Note-$name.mp3", "Note-$name.vtt", "content://folder",
            "", "", "", "", "", TranscriptionPhase.SYNTHESIZING,
            synthesis = SynthesisWork(SynthesisPhase.READY, "Note-$name.md", false, context, 0, null, null, null))
    }

    @Test fun `constat 3 start rend la main pendant un appel de synthese bloque`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(synthesisOnly("20261111090000"))))
        val repository = LocalStateRepository(store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val md = FakeMd { entered.complete(Unit); release.await(); "synthèse".toByteArray() }
        val phone = MemoryStore().apply {
            files["Note-20261111090000.vtt"] = vtt
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val coordinator = baseCoordinator(repository, FakeStt(), RecoverySafety(repository), md = md, phone = phone)
        val running = async(Dispatchers.Default) { coordinator.runBounded("syn-20261111090000") }
        entered.await()
        val started = withTimeoutOrNull(1_000) { coordinator.start(setOf("20261111120000"), RequestedWork.MISSING) }
        release.complete(Unit)
        running.await()
        assertNotNull("start attend la fin de l'appel réseau : la création de tâche doit être indépendante", started)
        assertTrue(repository.awaitInitialized().transcriptionTasks.any { it.recordingName == "20261111120000" })
    }

    @Test fun `constat 4 la creation d une tache de synthese n attend pas le verrou de transfert`() = runBlocking {
        val repository = LocalStateRepository(store)
        val safety = RecoverySafety(repository)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) { safety.withTransferLock { held.complete(Unit); release.await() } }
        held.await()
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val coordinator = TranscriptionCoordinator(repository, FakeStt(), TranscriptionSettingsManager(repository, FakeCredentials()),
            safety, verifyAudio = { true }, schedule = {},
            synthesisClient = FakeMd(), synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true },
            prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder",
                sttSettings, emptyMap(), names, mdSettings, request, active) { "id" } })
        val started = withTimeoutOrNull(1_000) { coordinator.start(setOf("20261111120000"), RequestedWork.MISSING) }
        release.complete(Unit)
        holder.await()
        assertEquals(listOf("id"), started)
    }

    @Test fun `constat 5 sans reglages de synthese la tache de MD seule nait terminee et suspendue`() {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val tasks = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(),
            setOf("20261111120000"), SynthesisSettings(), RequestedWork.MISSING) { "id-1" }
        val task = tasks.single()
        assertEquals(TranscriptionPhase.COMPLETED, task.phase)
        assertEquals(SynthesisPhase.SUSPENDED, task.synthesis!!.phase)
        assertEquals("Configure la synthèse puis crée explicitement un travail MD.", task.synthesis.suspensionReason)
    }

    @Test fun `constat 6 chaque nouvel appui ajoute une tache identique`() {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val first = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(),
            setOf("20261111120000"), SynthesisSettings(), RequestedWork.MISSING) { "id-1" }
        val second = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(),
            setOf("20261111120000"), SynthesisSettings(), RequestedWork.MISSING) { "id-2" }
        assertEquals(2, (first + second).size)
        assertTrue(second.single().phase.isTerminal())
    }

    @Test fun `constat 7 la file traite la plus ancienne tache d abord`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(
            task("older", "20261111090000", TranscriptionPhase.TRACKING).copy(serverId = "server-1"),
            synthesisOnly("20261111120000"),
        )))
        val repository = LocalStateRepository(store)
        val stt = FakeStt()
        val md = FakeMd()
        val coordinator = baseCoordinator(repository, stt, RecoverySafety(repository), md = md,
            phone = MemoryStore().apply { files["Note-20261111120000.vtt"] = vtt })
        val runner = TranscriptionInteractiveRunner(repository, coordinator,
            TranscriptionWorkerRunner(repository, coordinator, cancelScheduled = {}))
        val job = async(Dispatchers.Default) { runner.run() }
        delay(700)
        assertEquals("la synthèse récente attend la fin de la transcription ancienne", 0, md.calls)
        job.cancel()
    }

    @Test fun `constat 8 une synthese pas en tete annonce deja en cours`() {
        val waiting = synthesisOnly("20261111120000")
        assertEquals("Synthèse en cours. Le MD sera enregistré dès réception.", synthesisProgressMessage(waiting))
    }

    @Test fun `constat 9 une annulation non confirmee ne bloque pas la tache suivante`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(
            task("cancelled", "20261111090000", TranscriptionPhase.CANCEL_REQUESTED).copy(serverId = "server-1"),
            task("next", "20261111120000", TranscriptionPhase.PENDING),
        )))
        val repository = LocalStateRepository(store)
        val stt = FakeStt(onCancel = { ServerTask("server-1", ServerStatus.IN_PROGRESS, 0.5, null) })
        val coordinator = baseCoordinator(repository, stt, RecoverySafety(repository))
        val runner = TranscriptionInteractiveRunner(repository, coordinator,
            TranscriptionWorkerRunner(repository, coordinator, cancelScheduled = {}))
        withTimeoutOrNull(3_000) { runner.run() }
        assertEquals("la tâche prête doit partir malgré l'annulation en attente", 1, stt.submissions)
        assertTrue("l'annulation est traitée au moins une fois", stt.cancels >= 1)
    }

    @Test fun `constat 10 une 429 a l envoi met la tache en echec definitif`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task("local-1", "20261111120000", TranscriptionPhase.PENDING))))
        val repository = LocalStateRepository(store)
        val stt = FakeStt(onSubmit = { throw TranscriptionHttpException(429) })
        baseCoordinator(repository, stt, RecoverySafety(repository)).runBounded("local-1")
        val saved = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.FAILED, saved.phase)
        assertFalse(saved.suspended)
        assertTrue(saved.error!!.contains("429"))
    }
}

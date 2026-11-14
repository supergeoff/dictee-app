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
        val coordinator = TranscriptionCoordinator(repository, FakeStt(), TranscriptionSettingsManager(repository, FakeCredentials()),
            RecoverySafety(repository), verifyAudio = { true }, schedule = {},
            synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true },
            prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder",
                sttSettings, emptyMap(), names, mdSettings, request, active) { "id-b" } })
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

    @Test fun `constat 5 corrige aucune tache de MD seule sans reglages de synthese`() {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val tasks = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(),
            setOf("20261111120000"), SynthesisSettings(), RequestedWork.MISSING) { "id-1" }
        assertTrue("aucune tâche ne naît terminée : la demande est signalée à l'écran", tasks.isEmpty())
    }

    @Test fun `constat 6 corrige chaque appui sans reglage ne cumule aucune tache`() {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = vtt
        }
        val first = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(),
            setOf("20261111120000"), SynthesisSettings(), RequestedWork.MISSING) { "id-1" }
        val second = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(),
            setOf("20261111120000"), SynthesisSettings(), RequestedWork.MISSING) { "id-2" }
        assertEquals(0, (first + second).size)
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

    @Test fun `constat 8 corrige une synthese pas en tete annonce son attente`() {
        val waiting = synthesisOnly("20261111120000")
        val head = task("head", "20261111090000", TranscriptionPhase.TRACKING)
        val tasks = listOf(head, waiting)
        assertEquals("En attente : 2e dans la file. Aucun appel n'est parti.", synthesisProgressMessage(waiting, tasks))
        // En tête, le message d'appel reprend la main.
        assertEquals("Synthèse en cours. Le MD sera enregistré dès réception.", synthesisProgressMessage(waiting, listOf(waiting)))
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

    @Test fun `constat 10 corrige une 429 a l envoi programme des tentatives espacees puis suspend`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task("local-1", "20261111120000", TranscriptionPhase.PENDING))))
        val repository = LocalStateRepository(store)
        var now = 0L
        val stt = FakeStt(onSubmit = { throw TranscriptionHttpException(429) })
        val coordinator = baseCoordinator(repository, stt, RecoverySafety(repository), clock = { now })
        assertEquals(RunOutcome.Resume, coordinator.runBounded("local-1"))
        var saved = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.PENDING, saved.phase)
        assertFalse(saved.suspended)
        assertEquals(1, saved.submitAttempts)
        assertEquals(60_000L, saved.nextAttemptAtMillis)
        // La porte retient la nouvelle tentative avant l'heure prévue.
        now = 30_000L
        assertEquals(RunOutcome.Resume, coordinator.runBounded("local-1"))
        assertEquals(1, stt.submissions)
        // Deuxième tentative au moment prévu, puis deux minutes d'attente.
        now = 60_000L
        assertEquals(RunOutcome.Resume, coordinator.runBounded("local-1"))
        saved = store.load().transcriptionTasks.single()
        assertEquals(2, saved.submitAttempts)
        assertEquals(60_000L + 120_000L, saved.nextAttemptAtMillis)
        // Troisième échec : suspension avec la raison "serveur occupé".
        now = 200_000L
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("local-1"))
        saved = store.load().transcriptionTasks.single()
        assertEquals(3, stt.submissions)
        assertTrue(saved.suspended)
        assertEquals("Le serveur de transcription est occupé. Relance quand il est disponible.", saved.suspensionReason)
    }

    @Test fun `constat 10 corrige les autres codes 4xx gardent l echec immediat`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task("local-1", "20261111120000", TranscriptionPhase.PENDING))))
        val repository = LocalStateRepository(store)
        val stt = FakeStt(onSubmit = { throw TranscriptionHttpException(401) })
        baseCoordinator(repository, stt, RecoverySafety(repository)).runBounded("local-1")
        val saved = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.FAILED, saved.phase)
        assertFalse(saved.suspended)
        assertTrue(saved.error!!.contains("401"))
    }
}

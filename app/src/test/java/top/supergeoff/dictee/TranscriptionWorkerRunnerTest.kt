package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionWorkerRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val disk get() = LocalStateStore(File(tmp.root, "state.json"))
    private fun task(phase: TranscriptionPhase = TranscriptionPhase.PENDING) = TranscriptionTask(
        "local-1", "20261111120000", "audio.mp3", "audio.vtt", "content://folder",
        "hash", "https://example.invalid/v1", "ref", "fr", "dictee-diarize", phase,
    )
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "synthetic"
        override fun remove(ref: String) = Unit
    }
    private class Client : TranscriptionClient {
        var submissions = 0
        var delayMs = 0L
        override suspend fun submit(task: TranscriptionTask): SubmitResult {
            submissions++
            delay(delayMs)
            return SubmitResult.Accepted(ServerTask("job-1", ServerStatus.QUEUED, null, null))
        }
        override suspend fun get(task: TranscriptionTask) = ServerTask("job-1", ServerStatus.IN_PROGRESS, null, null)
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }
    private fun runner(repository: LocalStateRepository, client: Client, removed: MutableList<String>): TranscriptionWorkerRunner {
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { true })
        return TranscriptionWorkerRunner(repository, coordinator, cancelScheduled = { removed += it })
    }

    @Test fun `interactive timeout notifies service without a UI change and preserves schedule`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(synthesisTask())))
        val entered = CompletableDeferred<Unit>()
        val client = synthesisClient { entered.complete(Unit); CompletableDeferred<Unit>().await(); byteArrayOf() }
        val repo = LocalStateRepository(disk)
        val coordinator = synthesisCoordinator(repo, client) { 1_000L }
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        val runner = TranscriptionInteractiveRunner(repo, coordinator, worker, timeoutMs = 100)
        val jobs = ActiveWork()
        val ui = MutableStateFlow("inchangé")
        val stopped = CompletableDeferred<Unit>()
        jobs.begin()
        val service = launch { observeServiceWork(ui, jobs) { _, active -> if (active == 0) stopped.complete(Unit) } }
        val lifecycle = InteractiveTranscriptionLifecycle(this) { runner.run() }
        val interactive = launch { try { lifecycle.request().join() } finally { jobs.end() } }
        withTimeout(5_000) { entered.await(); interactive.join() }
        assertEquals(0, jobs.value)
        withTimeout(1_000) { stopped.await() }
        service.cancelAndJoin()
        assertEquals(SynthesisPhase.CALLING, disk.load().transcriptionTasks.single().synthesis!!.phase)
        assertEquals(1, disk.load().transcriptionTasks.single().synthesis!!.attempts)
        assertTrue(removed.isEmpty())
    }

    @Test fun `new durable task between empty queue read and loop exit is followed`() = runBlocking {
        disk.save(LocalState())
        val repo = LocalStateRepository(disk)
        val calls = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        val phone = MemoryStore().apply { files["audio.vtt"] = vtt }
        val coordinator = TranscriptionCoordinator(repo, Client(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, prepareRequested = { _, _, _ -> listOf(synthesisTask()) }, schedule = { scheduled += it },
            synthesisClient = synthesisClient { calls += "md"; "# Synthèse".toByteArray() },
            synthesisStorage = SynthesisStorage(File(tmp.root, "stage")), phoneStore = { phone },
            synthesisCredentialAccessible = { true }, clock = { 1_000L })
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        val emptyRead = CompletableDeferred<Unit>()
        val allowExit = CompletableDeferred<Unit>()
        var runs = 0
        val lifecycle = InteractiveTranscriptionLifecycle(this) {
            TranscriptionInteractiveRunner(repo, coordinator, worker).run()
            if (++runs == 1) { emptyRead.complete(Unit); allowExit.await() }
        }
        val first = lifecycle.request()
        withTimeout(5_000) { emptyRead.await() }
        assertEquals(listOf("local-1"), coordinator.start(setOf("20261111120000")))
        val second = lifecycle.request()
        allowExit.complete(Unit)
        withTimeout(5_000) { first.join(); second.join() }
        assertEquals(listOf("md"), calls)
        assertEquals(listOf("local-1"), scheduled)
        assertEquals(TranscriptionPhase.COMPLETED, disk.load().transcriptionTasks.single().phase)
        assertEquals(listOf("local-1"), removed)
    }

    @Test fun `stopping interactive lifecycle leaves scheduled synthesis to worker`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(synthesisTask())))
        val entered = CompletableDeferred<Unit>()
        val repo = LocalStateRepository(disk)
        val coordinator = synthesisCoordinator(repo, synthesisClient { entered.complete(Unit); CompletableDeferred<Unit>().await(); byteArrayOf() }) { 1_000L }
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        val jobs = ActiveWork()
        val lifecycle = InteractiveTranscriptionLifecycle(this) { TranscriptionInteractiveRunner(repo, coordinator, worker).run() }
        val action = launch { jobs.track { lifecycle.request().join() } }
        withTimeout(5_000) { entered.await() }
        lifecycle.stop()
        withTimeout(5_000) { action.join() }
        assertEquals(0, jobs.value)
        assertEquals(SynthesisPhase.CALLING, disk.load().transcriptionTasks.single().synthesis!!.phase)
        assertTrue(removed.isEmpty())
    }

    @Test fun `worker timeout leaves interrupted submission suspended on next run`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(disk)
        val client = Client().apply { delayMs = 500 }
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { true })
        val runner = TranscriptionWorkerRunner(repository, coordinator, cancelScheduled = {}, timeoutMs = 50)
        assertEquals(WorkerRun.Retry, runner.run("local-1", 0))
        assertEquals(TranscriptionPhase.SUBMITTING, disk.load().transcriptionTasks.single().phase)
        assertEquals(WorkerRun.Finished, runner.run("local-1", 1))
        val saved = disk.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.UNKNOWN_SUBMISSION, saved.phase)
        assertTrue(saved.suspended)
        assertEquals(1, client.submissions)
    }

    @Test fun `worker arriving before task persistence does not send or retire immediately`() = runBlocking {
        val client = Client()
        val removed = mutableListOf<String>()
        val runner = runner(LocalStateRepository(disk), client, removed)
        assertEquals(WorkerRun.Retry, runner.run("local-1", 0))
        assertEquals(0, client.submissions)
        assertTrue(removed.isEmpty())
    }

    @Test fun `worker retires a long absent task without sending`() = runBlocking {
        val client = Client()
        val removed = mutableListOf<String>()
        val runner = runner(LocalStateRepository(disk), client, removed)
        assertEquals(WorkerRun.Finished, runner.run("local-1", 12))
        assertEquals(listOf("local-1"), removed)
        assertEquals(0, client.submissions)
    }

    @Test fun `worker preserves active periodic work after submitting`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(task())))
        val client = Client()
        val removed = mutableListOf<String>()
        val runner = runner(LocalStateRepository(disk), client, removed)
        assertEquals(WorkerRun.Finished, runner.run("local-1", 0))
        assertEquals(1, client.submissions)
        assertEquals("job-1", disk.load().transcriptionTasks.single().serverId)
        assertTrue(removed.isEmpty())
    }

    @Test fun `worker retires periodic work for a terminal task`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(task(TranscriptionPhase.COMPLETED))))
        val client = Client()
        val removed = mutableListOf<String>()
        val runner = runner(LocalStateRepository(disk), client, removed)
        assertEquals(WorkerRun.Finished, runner.run("local-1", 0))
        assertEquals(listOf("local-1"), removed)
        assertEquals(0, client.submissions)
    }

    @Test fun `worker keeps suspended task without network or retirement`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(task().copy(suspended = true, suspensionReason = "Attente"))))
        val client = Client()
        val removed = mutableListOf<String>()
        val runner = runner(LocalStateRepository(disk), client, removed)
        assertEquals(WorkerRun.Finished, runner.run("local-1", 0))
        assertEquals(0, client.submissions)
        assertTrue(removed.isEmpty())
    }
    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
    private fun synthesisTask(id: String = "local-1", name: String = "20261111120000"): TranscriptionTask {
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "prompt",
            PromptMetadata("Note", "date", "durée"), "prompt", TranscriptionAudio.sha256(vtt))
        return task().copy(localId = id, recordingName = name, phase = TranscriptionPhase.SYNTHESIZING,
            synthesis = SynthesisWork(SynthesisPhase.READY, "audio.md", false, context, 0, null, null, null))
    }

    private fun synthesisCoordinator(repo: LocalStateRepository, client: SynthesisClient, clock: () -> Long): TranscriptionCoordinator {
        val phone = MemoryStore().apply { files["audio.vtt"] = vtt }
        return TranscriptionCoordinator(repo, Client(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, synthesisClient = client, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true }, clock = clock)
    }

    private fun synthesisClient(block: suspend () -> ByteArray) = object : SynthesisClient {
        override suspend fun complete(context: SynthesisContext, vtt: ByteArray) = block()
        override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
    }

    @Test fun `worker timeout persists calling and restart respects retry deadline and budget`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(synthesisTask())))
        var now = 1_000L
        var calls = 0
        val client = synthesisClient { calls++; if (calls == 1) delay(500) else throw SynthesisClientException(SynthesisFailureKind.TRANSIENT); byteArrayOf() }
        var repo = LocalStateRepository(disk)
        var coordinator = synthesisCoordinator(repo, client) { now }
        val removed = mutableListOf<String>()
        assertEquals(WorkerRun.Retry, TranscriptionWorkerRunner(repo, coordinator, { removed += it }, 50).run("local-1", 0))
        var work = disk.load().transcriptionTasks.single().synthesis!!
        assertEquals(SynthesisPhase.CALLING, work.phase)
        assertEquals(1, work.attempts)
        assertEquals(61_000L, work.nextAttemptAtMillis)
        repo = LocalStateRepository(disk); coordinator = synthesisCoordinator(repo, client) { now }
        val runner = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        runner.run("local-1", 1)
        work = disk.load().transcriptionTasks.single().synthesis!!
        assertEquals(SynthesisPhase.READY, work.phase)
        runner.run("local-1", 1); assertEquals(1, calls)
        now = work.nextAttemptAtMillis!!; runner.run("local-1", 1); assertEquals(2, calls)
        runner.run("local-1", 1); assertEquals(2, calls)
        now = disk.load().transcriptionTasks.single().synthesis!!.nextAttemptAtMillis!!
        runner.run("local-1", 1); runner.run("local-1", 1)
        assertEquals(3, calls)
        assertTrue(disk.load().transcriptionTasks.single().suspended)
        assertTrue(removed.isEmpty())
    }

    @Test fun `interactive and worker share coordinator without concurrent synthesis calls`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(synthesisTask())))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val client = synthesisClient { calls++; entered.complete(Unit); release.await(); "# Synthèse".toByteArray() }
        val repo = LocalStateRepository(disk)
        val coordinator = synthesisCoordinator(repo, client) { 1_000L }
        val removed = mutableListOf<String>()
        val runner = TranscriptionWorkerRunner(repo, coordinator, { synchronized(removed) { removed += it } })
        val service = async(Dispatchers.Default) { TranscriptionInteractiveRunner(repo, coordinator, runner).run() }
        withTimeout(5_000) { entered.await() }
        val worker = async(Dispatchers.Default) { runner.run("local-1", 0) }
        delay(50); assertEquals(1, calls)
        release.complete(Unit)
        withTimeout(5_000) { service.await(); worker.await() }
        assertEquals(1, calls)
        assertEquals(TranscriptionPhase.COMPLETED, disk.load().transcriptionTasks.single().phase)
        assertTrue(removed.contains("local-1"))
    }

    @Test fun `interactive waits for durable synthesis deadline and cancellation preserves schedule`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(synthesisTask())))
        var calls = 0
        val client = synthesisClient { calls++; throw SynthesisClientException(SynthesisFailureKind.TRANSIENT) }
        val repo = LocalStateRepository(disk)
        val coordinator = synthesisCoordinator(repo, client) { 1_000L }
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        val waiting = CompletableDeferred<Long>()
        val service = launch { TranscriptionInteractiveRunner(repo, coordinator, worker, clock = { 1_000L },
            wait = { _, millis -> waiting.complete(millis); CompletableDeferred<Unit>().await() }).run() }
        assertEquals(60_000L, withTimeout(5_000) { waiting.await() })
        assertEquals(1, calls)
        service.cancelAndJoin()
        assertTrue(removed.isEmpty())
        assertEquals(TranscriptionPhase.SYNTHESIZING, disk.load().transcriptionTasks.single().phase)
    }

    @Test fun `interactive runs oldest vtt before newer synthesis and skips suspended work`() = runBlocking {
        val oldest = task().copy(localId = "vtt", recordingName = "20261110120000")
        val md = synthesisTask("md", "20261111120000")
        val blocked = synthesisTask("blocked", "20261109120000").copy(suspended = true)
        disk.save(LocalState(transcriptionTasks = listOf(md, blocked, oldest)))
        val order = mutableListOf<String>()
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult { order += "vtt"; return SubmitResult.BackgroundUnsupported }
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val client = synthesisClient { order += "md"; "# Synthèse".toByteArray() }
        val repo = LocalStateRepository(disk)
        val phone = MemoryStore().apply { files["audio.vtt"] = vtt }
        val coordinator = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, synthesisClient = client, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
            phoneStore = { phone }, synthesisCredentialAccessible = { true })
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        withTimeout(5_000) { TranscriptionInteractiveRunner(repo, coordinator, worker).run() }
        assertEquals(listOf("vtt", "md"), order)
        assertEquals(setOf("vtt", "md"), removed.toSet())
        assertTrue(disk.load().transcriptionTasks.first { it.localId == "blocked" }.suspended)
    }

    @Test fun `notification identifies synthesis instead of transcription`() {
        val md = synthesisTask()
        val progress = TranscriptionProgress.current(listOf(md))!!.second
        assertTrue(transcriptionNotificationLabel(progress, listOf(md)).startsWith("Synthèse"))
        assertTrue(transcriptionNotificationLabel(progress, listOf(task())).startsWith("Transcription"))
    }

    @Test fun `service cancellation during call leaves durable calling for worker`() = runBlocking {
        disk.save(LocalState(transcriptionTasks = listOf(synthesisTask())))
        var now = 1_000L
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val client = synthesisClient { calls++; entered.complete(Unit); CompletableDeferred<Unit>().await(); byteArrayOf() }
        val repo = LocalStateRepository(disk)
        val coordinator = synthesisCoordinator(repo, client) { now }
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        val service = launch { TranscriptionInteractiveRunner(repo, coordinator, worker).run() }
        withTimeout(5_000) { entered.await() }
        service.cancelAndJoin()
        assertEquals(SynthesisPhase.CALLING, disk.load().transcriptionTasks.single().synthesis!!.phase)
        assertEquals(1, disk.load().transcriptionTasks.single().synthesis!!.attempts)
        assertTrue(removed.isEmpty())
        worker.run("local-1", 0)
        assertEquals(1, calls)
        assertEquals(SynthesisPhase.READY, disk.load().transcriptionTasks.single().synthesis!!.phase)
        now += 59_999
        worker.run("local-1", 0)
        assertEquals(1, calls)
    }

    @Test fun `interactive deadline wait wakes on persisted cancellation`() = runBlocking {
        val initial = synthesisTask().let { it.copy(synthesis = it.synthesis!!.copy(nextAttemptAtMillis = 61_000L, attempts = 1)) }
        disk.save(LocalState(transcriptionTasks = listOf(initial)))
        var calls = 0
        val client = synthesisClient { calls++; "md".toByteArray() }
        val repo = LocalStateRepository(disk)
        val coordinator = synthesisCoordinator(repo, client) { 1_000L }
        val worker = TranscriptionWorkerRunner(repo, coordinator, {})
        val service = launch { TranscriptionInteractiveRunner(repo, coordinator, worker, clock = { 1_000L }).run() }
        withTimeout(5_000) { while (!service.isActive || repo.state.value == null) delay(1) }
        coordinator.requestCancel("local-1")
        withTimeout(5_000) { service.join() }
        assertEquals(0, calls)
        assertEquals(TranscriptionPhase.CANCELLED, disk.load().transcriptionTasks.single().phase)
    }

    @Test fun `interactive runs oldest synthesis before newer vtt`() = runBlocking {
        val md = synthesisTask("md", "20261110120000")
        val newer = task().copy(localId = "vtt", recordingName = "20261111120000")
        disk.save(LocalState(transcriptionTasks = listOf(newer, md)))
        val order = mutableListOf<String>()
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult { order += "vtt"; return SubmitResult.BackgroundUnsupported }
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val repo = LocalStateRepository(disk)
        val phone = MemoryStore().apply { files["audio.vtt"] = vtt }
        val coordinator = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, synthesisClient = synthesisClient { order += "md"; "# Synthèse".toByteArray() },
            synthesisStorage = SynthesisStorage(File(tmp.root, "stage")), phoneStore = { phone }, synthesisCredentialAccessible = { true })
        val removed = mutableListOf<String>()
        val worker = TranscriptionWorkerRunner(repo, coordinator, { removed += it })
        withTimeout(5_000) { TranscriptionInteractiveRunner(repo, coordinator, worker).run() }
        assertEquals(listOf("md", "vtt"), order)
        assertEquals(setOf("md", "vtt"), removed.toSet())
    }

}

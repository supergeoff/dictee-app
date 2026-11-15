package top.supergeoff.dictee

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutomaticProcessingIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val names = listOf("20261111120000", "20261111130000")
    private val folder = "content://synthetic/folder"
    private val bytes = javaClass.getResource("/stream.bin")!!.readBytes()
    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
    private val sttSettings = TranscriptionSettings("https://example.invalid/v1", "fr", "model", "ref")
    private val mdSettings = SynthesisSettings("https://example.invalid/v1", "model", "ref")
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private inner class Harness(enabled: Boolean, val failFirst: Boolean = false, val failScheduling: Boolean = false) {
        val phone = MemoryStore()
        val disk = LocalStateStore(File(tmp.newFolder(), "state.json"))
        val repo: LocalStateRepository
        val safety: RecoverySafety
        val scheduled = mutableListOf<String>()
        val submits = mutableListOf<String>()
        var mdCalls = 0
        val credentials = FakeCredentials()
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult {
                submits += task.recordingName
                if (failFirst && task.recordingName == names.first()) throw TranscriptionHttpException(400)
                return SubmitResult.DirectVtt(vtt)
            }
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val md = object : SynthesisClient {
            override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray {
                mdCalls++
                return "synthèse synthétique".toByteArray()
            }
            override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
        }
        init {
            disk.save(LocalState(phoneTreeUri = folder, autoProcessAfterRecovery = enabled,
                transcriptionSettings = sttSettings, synthesisSettings = mdSettings))
            repo = LocalStateRepository(disk)
            safety = RecoverySafety(repo)
        }
        fun coordinator(repository: LocalStateRepository = repo, guard: RecoverySafety = safety, launch: suspend () -> Unit = {}) =
            TranscriptionCoordinator(repository, stt, TranscriptionSettingsManager(repository, credentials), guard,
                verifyAudio = { TranscriptionAudio.sha256(phone, it.mp3Name) == it.mp3Sha256 },
                schedule = { if (failScheduling && scheduled.isEmpty()) { scheduled += it; throw java.io.IOException("scheduling") }; scheduled += it },
                synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
                phoneStore = { phone }, synthesisCredentialAccessible = { true },
                saveVtt = { task, data, promote ->
                    val spool = File(tmp.root, "spool").apply { writeBytes(data) }
                    phone.writeGuarded(task.vttName, spool, task.replaceVtt, promote)
                },
                launch = launch,
                prepareAutomatic = { name, uri ->
                    val state = repository.awaitInitialized()
                    TranscriptionPreparation.prepareAutomatic(phone, uri, state.transcriptionSettings, state.errors, name, state.synthesisSettings) { "unused" }
                })
        suspend fun recover(entered: CountDownLatch? = null, release: CountDownLatch? = null, coordinator: TranscriptionCoordinator = coordinator()): List<RecoveryResult> {
            var files = 0
            val link = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) {
                if (++files == 2 && entered != null) {
                    entered.countDown()
                    check(release!!.await(10, TimeUnit.SECONDS))
                }
                reply(Cmd.FILE, byteArrayOf(0) + u32(bytes.size.toLong())); audio(bytes); reply(Cmd.FILE, byteArrayOf(2))
            } }
            val automatic = AutomaticProcessingCoordinator(repo, coordinator)
            return RecoveryJob(V05Session(link, {}), phone, tmp.newFolder(), ListLog()).run(
                names.map { ListItem.Entry(it, bytes.size.toLong(), 1) }, { _, _ -> }, { automatic.reconcile() },
                beforeRecovery = { safety.begin(it.name, folder) }, validateResult = { safety.finish(it, folder) },
                withRecoveryLock = { safety.withTransferLock(it) })
        }
        suspend fun drain(coordinator: TranscriptionCoordinator = coordinator()) {
            for (task in disk.load().transcriptionTasks) {
                repeat(3) { coordinator.runBounded(task.localId) }
            }
        }
    }

    @Test fun automaticRecoveryStartsInteractiveTrackingWithoutManualDrain() = runBlocking {
        val h = Harness(true)
        val jobs = ActiveWork()
        lateinit var starter: InteractiveTranscriptionStarter
        val coordinator = h.coordinator(launch = { starter.request() })
        val worker = TranscriptionWorkerRunner(h.repo, coordinator, {})
        val lifecycle = InteractiveTranscriptionLifecycle(this) {
            TranscriptionInteractiveRunner(h.repo, coordinator, worker, wait = { _, _ -> }).run()
        }
        starter = InteractiveTranscriptionStarter(this, jobs, {}, lifecycle)
        h.recover(coordinator = coordinator)
        withTimeout(5_000) {
            h.repo.state.first { state -> state != null && state.transcriptionTasks.size == 2 &&
                state.transcriptionTasks.all { it.phase == TranscriptionPhase.COMPLETED } }
        }
        assertEquals(names, h.submits)
        assertEquals(2, h.mdCalls)
        withTimeout(5_000) { jobs.changes.first { it == 0 } }
        Unit
    }

    @Test fun backgroundReconciliationSchedulesWithoutInteractiveService() = runBlocking {
        val h = Harness(true)
        val jobs = ActiveWork()
        var foreground = false
        var recovering = false
        var services = 0
        var follows = 0
        val lifecycle = InteractiveTranscriptionLifecycle(this) { follows++; InteractiveRun.QueueEmpty }
        val starter = InteractiveTranscriptionStarter(this, jobs, { services++ }, lifecycle,
            canResume = { foreground }, canLaunchAutomatically = { foreground || recovering })
        h.repo.updateDurably { it.copy(transcriptionTasks = listOf(sampleTask())) }
        starter.resume(h.repo)
        starter.requestAutomatic()
        yield()
        assertEquals(0, services)
        assertEquals(0, follows)
        recovering = true
        starter.requestAutomatic()?.join()
        assertEquals(1, follows)
        recovering = false
        foreground = true
        starter.resume(h.repo)
        withTimeout(5_000) { jobs.changes.first { it == 0 } }
        assertEquals(2, follows)
    }

    @Test fun foregroundResumeTracksDurableServerTaskOnceAndBalancesJobs() = runBlocking {
        val h = Harness(true)
        val task = sampleTask().copy(phase = TranscriptionPhase.TRACKING, serverId = "server-1")
        h.repo.updateDurably { it.copy(transcriptionTasks = listOf(task)) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var submits = 0
        var gets = 0
        val client = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult { submits++; error("MP3 renvoyé") }
            override suspend fun get(task: TranscriptionTask): ServerTask {
                gets++
                entered.complete(Unit)
                release.await()
                return ServerTask("server-1", ServerStatus.COMPLETED, 1.0, vtt)
            }
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val coordinator = TranscriptionCoordinator(h.repo, client, TranscriptionSettingsManager(h.repo, h.credentials), h.safety,
            verifyAudio = { error("vérification MP3 inattendue") }, schedule = {},
            saveVtt = { _, _, promote -> promote {} })
        val worker = TranscriptionWorkerRunner(h.repo, coordinator, {})
        var loops = 0
        var maxLoops = 0
        val lifecycle = InteractiveTranscriptionLifecycle(this) {
            loops++
            maxLoops = maxOf(maxLoops, loops)
            try { TranscriptionInteractiveRunner(h.repo, coordinator, worker).run() } finally { loops-- }
        }
        val jobs = ActiveWork()
        var services = 0
        val starter = InteractiveTranscriptionStarter(this, jobs, { services++ }, lifecycle)
        coordinator.reconcile()
        starter.resume(h.repo)
        withTimeout(5_000) { entered.await() }
        starter.resume(h.repo)
        assertEquals(2, jobs.value)
        release.complete(Unit)
        withTimeout(5_000) { jobs.changes.first { it == 0 } }
        assertEquals(0, submits)
        assertEquals(1, gets)
        assertEquals(1, maxLoops)
        assertEquals(2, services)
        assertEquals(TranscriptionPhase.COMPLETED, h.disk.load().transcriptionTasks.single().phase)
        Unit
    }

    @Test fun batchCompletesAndRestartDoesNotDuplicate() = runBlocking {
        val h = Harness(true)
        assertTrue(h.recover().all { it is RecoveryResult.Ok })
        h.drain()
        for (name in names) for (ext in listOf("mp3", "vtt", "md")) assertTrue("$name.$ext", "Note-$name.$ext" in h.phone.files)
        val before = h.disk.load().transcriptionTasks
        val restarted = LocalStateRepository(h.disk)
        val coordinator = h.coordinator(restarted, RecoverySafety(restarted))
        AutomaticProcessingCoordinator(restarted, coordinator).reconcile()
        coordinator.reconcile()
        h.drain(coordinator)
        assertEquals(before, h.disk.load().transcriptionTasks)
        assertEquals(names, h.submits)
        assertEquals(2, h.mdCalls)
    }

    @Test fun failedFirstProcessingDoesNotBlockSecond() = runBlocking {
        val h = Harness(true, failFirst = true)
        h.recover(); h.drain()
        assertEquals(names, h.submits)
        assertFalse("Note-${names.first()}.vtt" in h.phone.files)
        assertTrue("Note-${names.last()}.md" in h.phone.files)
    }

    @Test fun disabledProducesOnlyMp3() = runBlocking {
        val h = Harness(false)
        h.recover(); h.drain()
        assertEquals(names.map { "Note-$it.mp3" }.toSet(), h.phone.files.keys)
        assertTrue(h.disk.load().transcriptionTasks.isEmpty())
    }

    @Test fun schedulingFailureOnFirstResultDoesNotStopRecovery() = runBlocking {
        val h = Harness(true, failScheduling = true)
        assertTrue(h.recover().all { it is RecoveryResult.Ok })
        assertTrue(names.all { "Note-$it.mp3" in h.phone.files })
        assertEquals(names, h.disk.load().transcriptionTasks.map { it.recordingName })
        assertTrue(h.disk.load().transcriptionTasks.last().localId in h.scheduled)
    }

    @Test fun firstTranscriptionProgressesWhileSecondTransferIsInProgress() = runBlocking {
        val h = Harness(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovery = async(Dispatchers.IO) { h.recover(entered, release) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val id = h.disk.load().transcriptionTasks.single().localId
            val outcome = withTimeout(3000) { h.coordinator().runBounded(id) }
            assertEquals(RunOutcome.Resume, outcome)
            assertTrue("Note-${names.first()}.vtt" in h.phone.files)
            assertFalse(recovery.isCompleted)
        } finally { release.countDown() }
        withTimeout(10000) { recovery.await() }
        Unit
    }
}

package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
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
}

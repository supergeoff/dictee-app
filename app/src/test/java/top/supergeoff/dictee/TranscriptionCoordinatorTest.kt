package top.supergeoff.dictee

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "state.json")
    private val store get() = LocalStateStore(file)

    private fun task(id: String = "local-1", phase: TranscriptionPhase = TranscriptionPhase.PENDING) =
        TranscriptionTask(id, "20261111120000", "Note-20261111120000.mp3", "Note-20261111120000.vtt",
            "content://folder", "hash", "https://example.invalid/v1", "ref", "fr", "dictee-diarize", phase)

    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "synthetic-key"
        override fun remove(ref: String) = Unit
    }

    private class FakeClient : TranscriptionClient {
        var submissions = 0
        var reads = 0
        var onSubmit: suspend (TranscriptionTask) -> SubmitResult = {
            SubmitResult.Accepted(ServerTask("server-1", ServerStatus.QUEUED, null, null))
        }
        var onGet: suspend (TranscriptionTask) -> ServerTask = {
            ServerTask("server-1", ServerStatus.IN_PROGRESS, 0.4, null)
        }
        override suspend fun submit(task: TranscriptionTask): SubmitResult { submissions++; return onSubmit(task) }
        override suspend fun get(task: TranscriptionTask): ServerTask { reads++; return onGet(task) }
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected cancel")
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }

    private fun coordinator(repository: LocalStateRepository, client: FakeClient) =
        TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials), RecoverySafety(repository),
            verifyAudio = { true })

    @Test fun `reconciliation restores missing work for active tasks but not terminal tasks`() = runBlocking {
        val pending = task(id = "pending")
        val tracking = task(id = "tracking", phase = TranscriptionPhase.TRACKING).copy(serverId = "server-1")
        val suspended = task(id = "suspended").copy(suspended = true, suspensionReason = "Attente")
        val finished = task(id = "finished", phase = TranscriptionPhase.COMPLETED)
        store.save(LocalState(transcriptionTasks = listOf(pending, tracking, suspended, finished)))
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        val scheduled = mutableListOf<String>()
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { true }, schedule = { scheduled += it })
        coordinator.reconcile()
        assertEquals(setOf("pending", "tracking", "suspended"), scheduled.toSet())
        assertEquals(0, client.submissions)
        assertEquals(0, client.reads)
    }

    @Test fun `schedule is confirmed before durable task creation and service launch`() = runBlocking {
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        var scheduled = false
        var launched = false
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { true },
            prepare = { listOf(task()) },
            schedule = { id ->
                assertEquals("local-1", id)
                assertTrue(repository.awaitInitialized().transcriptionTasks.isEmpty())
                scheduled = true
            },
            launch = {
                assertTrue(scheduled)
                assertEquals("local-1", store.load().transcriptionTasks.single().localId)
                launched = true
            })
        assertEquals(listOf("local-1"), coordinator.start(null))
        assertTrue(launched)
    }

    @Test fun `schedule failure leaves no task and starts no service`() = runBlocking {
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        var launched = false
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { true }, prepare = { listOf(task()) },
            schedule = { throw java.io.IOException("scheduler unavailable") }, launch = { launched = true })
        try { coordinator.start(null); fail("scheduling must fail") } catch (_: java.io.IOException) { }
        assertTrue(repository.awaitInitialized().transcriptionTasks.isEmpty())
        assertFalse(launched)
        assertEquals(0, client.submissions)
    }

    @Test fun `submission and server id are persisted before the next network request`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        client.onSubmit = {
            assertEquals(TranscriptionPhase.SUBMITTING, store.load().transcriptionTasks.single().phase)
            SubmitResult.Accepted(ServerTask("server-1", ServerStatus.QUEUED, null, null))
        }
        client.onGet = {
            val saved = store.load().transcriptionTasks.single()
            assertEquals("server-1", saved.serverId)
            assertEquals(TranscriptionPhase.TRACKING, saved.phase)
            ServerTask("server-1", ServerStatus.IN_PROGRESS, 0.4, null)
        }
        val coordinator = coordinator(repository, client)
        coordinator.runBounded("local-1")
        coordinator.runBounded("local-1")
        assertEquals(1, client.submissions)
        assertEquals(1, client.reads)
        assertEquals(0.4, store.load().transcriptionTasks.single().progress!!, 0.001)
    }

    @Test fun `a lost submission is suspended without sending a second request`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task(phase = TranscriptionPhase.SUBMITTING))))
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        coordinator(repository, client).runBounded("local-1")
        val saved = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.UNKNOWN_SUBMISSION, saved.phase)
        assertTrue(saved.suspended)
        assertEquals(0, client.submissions)
        assertEquals(0, client.reads)
    }

    @Test fun `a 404 keeps the server id and suspends rather than resubmitting`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task(phase = TranscriptionPhase.TRACKING).copy(serverId = "server-1"))))
        val repository = LocalStateRepository(store)
        val client = FakeClient().apply { onGet = { throw TranscriptionHttpException(404) } }
        val coordinator = coordinator(repository, client)
        coordinator.runBounded("local-1")
        coordinator.runBounded("local-1")
        val saved = store.load().transcriptionTasks.single()
        assertEquals("server-1", saved.serverId)
        assertTrue(saved.suspended)
        assertEquals(1, client.reads)
        assertEquals(0, client.submissions)
    }

    @Test fun `concurrent submissions for the same recording result in a single post`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = FakeClient().apply { onSubmit = {
            entered.complete(Unit)
            release.await()
            SubmitResult.Accepted(ServerTask("server-1", ServerStatus.QUEUED, null, null))
        } }
        val coordinator = coordinator(repository, client)
        val first = async(Dispatchers.Default) { coordinator.runBounded("local-1") }
        entered.await()
        val second = async(Dispatchers.Default) { coordinator.runBounded("local-1") }
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(1, client.submissions)
        assertEquals("server-1", store.load().transcriptionTasks.single().serverId)
    }

    @Test fun `newer recording waits for the older active task`() = runBlocking {
        val older = task(id = "older").copy(recordingName = "20261111090000")
        val newer = task(id = "newer").copy(recordingName = "20261111120000")
        store.save(LocalState(transcriptionTasks = listOf(newer, older)))
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        val coordinator = coordinator(repository, client)
        assertEquals(RunOutcome.Resume, coordinator.runBounded("newer"))
        assertEquals(0, client.submissions)
        coordinator.runBounded("older")
        assertEquals(1, client.submissions)
        assertEquals(TranscriptionPhase.PENDING, store.load().transcriptionTasks.first { it.localId == "newer" }.phase)
    }

    @Test fun `failed persistence prevents sending audio`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(LocalStateStore(file, rename = { _, _ -> false }))
        val client = FakeClient()
        try { coordinator(repository, client).runBounded("local-1"); fail("disk failure must propagate") }
        catch (_: java.io.IOException) { }
        assertEquals(0, client.submissions)
        assertEquals(TranscriptionPhase.PENDING, store.load().transcriptionTasks.single().phase)
    }

    @Test fun `inaccessible credential prevents any network request`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        val missing = object : CredentialStore {
            override fun save(secret: String) = "unused"
            override fun read(ref: String): String = throw CredentialException()
            override fun remove(ref: String) = Unit
        }
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, missing),
            RecoverySafety(repository), verifyAudio = { true })
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("local-1"))
        assertTrue(store.load().transcriptionTasks.single().suspended)
        assertEquals(0, client.submissions)
    }

    @Test fun `explicit unauthorized response fails without an uncertain submission`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(store)
        val client = FakeClient().apply { onSubmit = { throw TranscriptionHttpException(401) } }
        coordinator(repository, client).runBounded("local-1")
        val saved = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.FAILED, saved.phase)
        assertFalse(saved.suspended)
        assertTrue(saved.error!!.contains("401"))
    }

    @Test fun `changed recording stops before any submission`() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val repository = LocalStateRepository(store)
        val client = FakeClient()
        val coordinator = TranscriptionCoordinator(repository, client, TranscriptionSettingsManager(repository, credentials),
            RecoverySafety(repository), verifyAudio = { false })
        coordinator.runBounded("local-1")
        val saved = store.load().transcriptionTasks.single()
        assertTrue(saved.suspended)
        assertEquals(TranscriptionPhase.PENDING, saved.phase)
        assertEquals(0, client.submissions)
    }
}

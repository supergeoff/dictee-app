package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SynthesisCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "synthetic"
        override fun remove(ref: String) = Unit
    }
    private val stt = object : TranscriptionClient {
        override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected STT")
        override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected STT")
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected STT cancellation")
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }
    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray()
    private val phone = MemoryStore()
    private var folder: PhoneStore = phone
    private val stateFile get() = File(tmp.root, "state.json")
    private val store get() = LocalStateStore(stateFile)
    private var now = 1_000L
    private var calls = 0
    private var accessible = true
    private var response: suspend () -> ByteArray = { "# Synthèse".toByteArray() }
    private val md = object : SynthesisClient {
        override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray { calls++; return response() }
        override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
    }
    private fun task(): TranscriptionTask {
        phone.files["Note-20261111120000.vtt"] = vtt
        val hash = TranscriptionAudio.sha256(phone, "Note-20261111120000.vtt")
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "prompt", PromptMetadata("Note", "date", "durée"), "prompt", hash)
        val work = SynthesisWork(SynthesisPhase.READY, "Note-20261111120000.md", false, context, 0, null, null, null)
        return TranscriptionTask("id", "20261111120000", "Note-20261111120000.mp3", "Note-20261111120000.vtt", "content://folder", "", "", "", "", "", TranscriptionPhase.SYNTHESIZING, synthesis = work)
    }
    private fun coordinator(repo: LocalStateRepository) = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
        verifyAudio = { true }, schedule = {}, synthesisClient = md, synthesisStorage = SynthesisStorage(File(tmp.root, "stage")),
        phoneStore = { folder }, synthesisCredentialAccessible = { accessible }, clock = { now })

    @Test fun attemptBudgetSurvivesRestart() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        var repo = LocalStateRepository(store)
        response = {
            assertEquals(calls, store.load().transcriptionTasks.single().synthesis!!.attempts)
            assertEquals(SynthesisPhase.CALLING, store.load().transcriptionTasks.single().synthesis!!.phase)
            throw SynthesisClientException(SynthesisFailureKind.TRANSIENT)
        }
        var coordinator = coordinator(repo)
        coordinator.runBounded("id")
        assertEquals(1, calls)
        assertEquals(now + 60_000, store.load().transcriptionTasks.single().synthesis!!.nextAttemptAtMillis)
        repo = LocalStateRepository(store); coordinator = coordinator(repo)
        coordinator.runBounded("id"); assertEquals(1, calls)
        now += 60_000; coordinator.runBounded("id"); assertEquals(2, calls)
        assertEquals(now + 120_000, store.load().transcriptionTasks.single().synthesis!!.nextAttemptAtMillis)
        now += 120_000; coordinator.runBounded("id"); assertEquals(3, calls)
        assertEquals(SynthesisPhase.SUSPENDED, store.load().transcriptionTasks.single().synthesis!!.phase)
        coordinator.retrySynthesis("id")
        assertEquals(0, store.load().transcriptionTasks.single().synthesis!!.attempts)
    }

    @Test fun changedVttAndCancellationBlockPromotion() = runBlocking {
        val initial = task().copy(serverId = "old-server", synthesis = task().synthesis!!.copy(replaceMd = true))
        phone.files["Note-20261111120000.md"] = "ancien".toByteArray()
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val repo = LocalStateRepository(store)
        val coordinator = coordinator(repo)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        response = { entered.complete(Unit); release.await(); "nouveau".toByteArray() }
        val run = async(Dispatchers.Default) { coordinator.runBounded("id") }
        entered.await()
        val cancel = async(Dispatchers.Default) { coordinator.requestCancel("id") }
        while (store.load().transcriptionTasks.single().phase != TranscriptionPhase.CANCELLED) kotlinx.coroutines.delay(5)
        release.complete(Unit)
        run.await(); cancel.await()
        assertEquals("ancien", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
        assertFalse(File(tmp.root, "stage/synthesis-id.md").exists())
        assertEquals(SynthesisPhase.CANCELLED, store.load().transcriptionTasks.single().synthesis!!.phase)
    }

    @Test fun vttChangedBeforeAndDuringCallDoesNotPromote() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        phone.files["Note-20261111120000.vtt"] = "modifié".toByteArray()
        coordinator(LocalStateRepository(store)).runBounded("id")
        assertEquals(0, calls)
        assertTrue(store.load().transcriptionTasks.single().suspended)
        store.save(LocalState(transcriptionTasks = listOf(task())))
        response = { phone.files["Note-20261111120000.vtt"] = "autre".toByteArray(); "md".toByteArray() }
        coordinator(LocalStateRepository(store)).runBounded("id")
        assertEquals(1, calls)
        assertNull(phone.files["Note-20261111120000.md"])
        assertTrue(store.load().transcriptionTasks.single().suspended)
    }

    @Test fun inconsistentNullContextNeverCallsModel() = runBlocking {
        val initial = task()
        store.save(LocalState(transcriptionTasks = listOf(initial.copy(suspended = true,
            synthesis = initial.synthesis!!.copy(phase = SynthesisPhase.SUSPENDED, context = null, attempts = 1, stagedSha256 = "a".repeat(64))))))
        val coordinator = coordinator(LocalStateRepository(store))
        coordinator.retrySynthesis("id")
        coordinator.runBounded("id")
        assertEquals(0, calls)
        assertEquals(1, store.load().transcriptionTasks.single().synthesis!!.attempts)
    }

    @Test fun invalidResponseSuspendsImmediately() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        response = { throw SynthesisClientException(SynthesisFailureKind.INVALID_RESPONSE) }
        coordinator(LocalStateRepository(store)).runBounded("id")
        assertEquals(1, calls)
        assertEquals(SynthesisPhase.SUSPENDED, store.load().transcriptionTasks.single().synthesis!!.phase)
        assertNull(phone.files["Note-20261111120000.md"])
    }

    @Test fun inaccessibleCredentialDoesNotConsumeAttemptAndPermanentFailureSuspends() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val coordinator = coordinator(LocalStateRepository(store))
        accessible = false
        coordinator.runBounded("id")
        assertEquals(0, calls)
        assertEquals(0, store.load().transcriptionTasks.single().synthesis!!.attempts)
        accessible = true
        coordinator.retrySynthesis("id")
        response = { throw SynthesisClientException(SynthesisFailureKind.PERMANENT) }
        coordinator.runBounded("id")
        assertEquals(1, calls)
        assertEquals(SynthesisPhase.SUSPENDED, store.load().transcriptionTasks.single().synthesis!!.phase)
    }

    @Test fun mdAppearingDuringCallIsNotReplaced() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        response = { phone.files["Note-20261111120000.md"] = "extérieur".toByteArray(); "réponse".toByteArray() }
        coordinator(LocalStateRepository(store)).runBounded("id")
        assertEquals("extérieur", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
        assertTrue(store.load().transcriptionTasks.single().suspended)
        assertTrue(store.load().synthesisProvenance.isEmpty())
    }

    @Test fun promotionGuardWinsBeforeCancellation() = runBlocking {
        val original = task().copy(synthesis = task().synthesis!!.copy(replaceMd = true))
        phone.files["Note-20261111120000.md"] = "ancien".toByteArray()
        store.save(LocalState(transcriptionTasks = listOf(original)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                phone.writeGuarded(fileName, source, replace) { action -> promote {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    action()
                } }
            }
        }
        val coordinator = coordinator(LocalStateRepository(store))
        val run = async(Dispatchers.Default) { coordinator.runBounded("id") }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val cancel = async(Dispatchers.Default) { coordinator.requestCancel("id") }
        release.countDown()
        run.await(); cancel.await()
        val finished = store.load().transcriptionTasks.single()
        assertTrue(finished.phase == TranscriptionPhase.COMPLETED || finished.phase == TranscriptionPhase.CANCELLED)
        assertEquals("# Synthèse", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
    }

    @Test fun transientDelayStartsWhenFailureIsReceived() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        response = { now += 30_000; throw SynthesisClientException(SynthesisFailureKind.TRANSIENT) }
        val coordinator = coordinator(LocalStateRepository(store))
        coordinator.runBounded("id")
        assertEquals(now + 60_000, store.load().transcriptionTasks.single().synthesis!!.nextAttemptAtMillis)
    }

    @Test fun restartDuringCallingConsumesAttempt() = runBlocking {
        val initial = task()
        store.save(LocalState(transcriptionTasks = listOf(initial.copy(synthesis = initial.synthesis!!.copy(
            phase = SynthesisPhase.CALLING, attempts = 1, nextAttemptAtMillis = now + 60_000)))))
        val coordinator = coordinator(LocalStateRepository(store))
        coordinator.runBounded("id"); assertEquals(0, calls)
        now += 60_000
        response = { assertEquals(2, store.load().transcriptionTasks.single().synthesis!!.attempts); "md".toByteArray() }
        coordinator.runBounded("id")
        assertEquals(1, calls)
        assertEquals(TranscriptionPhase.COMPLETED, store.load().transcriptionTasks.single().phase)
    }

    @Test fun promotionSucceededBeforeFailureFinalizesWithoutNetwork() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        folder = object : PhoneStore by phone {
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) {
                phone.writeGuarded(fileName, source, replace, promote)
                throw PhoneStoreException("panne synthétique après promotion")
            }
        }
        val coordinator = coordinator(LocalStateRepository(store))
        coordinator.runBounded("id")
        assertEquals(1, calls)
        assertEquals(SynthesisPhase.SUSPENDED, store.load().transcriptionTasks.single().synthesis!!.phase)
        assertNotNull(phone.files["Note-20261111120000.md"])
        folder = phone
        val restarted = coordinator(LocalStateRepository(store))
        restarted.retrySynthesis("id"); restarted.runBounded("id")
        assertEquals(1, calls)
        assertEquals(TranscriptionPhase.COMPLETED, store.load().transcriptionTasks.single().phase)
        assertTrue(store.load().synthesisProvenance.isEmpty())
        assertFalse(File(tmp.root, "stage/synthesis-id.md").exists())
    }

    @Test fun identicalExternalMdNeverCreatesProvenance() = runBlocking {
        store.save(LocalState(transcriptionTasks = listOf(task())))
        response = { phone.files["Note-20261111120000.md"] = "identique".toByteArray(); "identique".toByteArray() }
        coordinator(LocalStateRepository(store)).runBounded("id")
        assertTrue(store.load().synthesisProvenance.isEmpty())
        assertEquals(SynthesisPhase.COMPLETED, store.load().transcriptionTasks.single().synthesis!!.phase)
        assertFalse(File(tmp.root, "stage/synthesis-id.md").exists())
    }

    @Test fun ambiguousPriorIdenticalMdNeverCreatesProvenance() = runBlocking {
        val original = task()
        val stage = SynthesisStorage(File(tmp.root, "stage"))
        val md = "identique".toByteArray()
        val hash = stage.stage("id", md)
        phone.files["Note-20261111120000.md"] = md
        store.save(LocalState(transcriptionTasks = listOf(original.copy(synthesis = original.synthesis!!.copy(
            phase = SynthesisPhase.SAVE_PENDING, stagedSha256 = hash, promotionStarted = true, previousMdSha256 = hash)))))
        coordinator(LocalStateRepository(store)).runBounded("id")
        assertEquals(0, calls)
        assertTrue(store.load().synthesisProvenance.isEmpty())
        assertEquals(SynthesisPhase.COMPLETED, store.load().transcriptionTasks.single().synthesis!!.phase)
    }

    @Test fun lostStageNeverInventsProvenance() = runBlocking {
        phone.failWrites = true
        store.save(LocalState(transcriptionTasks = listOf(task())))
        val coordinator = coordinator(LocalStateRepository(store))
        coordinator.runBounded("id")
        assertTrue(File(tmp.root, "stage/synthesis-id.md").delete())
        phone.failWrites = false
        coordinator.retrySynthesis("id"); coordinator.runBounded("id")
        assertEquals(1, calls)
        assertTrue(store.load().synthesisProvenance.isEmpty())
        assertEquals(SynthesisPhase.SUSPENDED, store.load().transcriptionTasks.single().synthesis!!.phase)
    }

    @Test fun stagedResultResumesWithoutNetwork() = runBlocking {
        phone.failWrites = true
        store.save(LocalState(transcriptionTasks = listOf(task())))
        var coordinator = coordinator(LocalStateRepository(store))
        coordinator.runBounded("id")
        assertEquals(1, calls)
        assertNotNull(store.load().transcriptionTasks.single().synthesis!!.stagedSha256)
        phone.failWrites = false
        coordinator = coordinator(LocalStateRepository(store))
        coordinator.retrySynthesis("id")
        coordinator.runBounded("id")
        assertEquals(1, calls)
        assertEquals(TranscriptionPhase.COMPLETED, store.load().transcriptionTasks.single().phase)
        assertEquals(1, store.load().synthesisProvenance.size)
    }
}

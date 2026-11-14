package top.supergeoff.dictee

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            saveVtt = { task, bytes, promote -> promote { phone.files[task.vttName] = bytes } },
            prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), names, mdSettings, request, active) { "id-${id++}" } })
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
            phoneStore = { phone }, synthesisCredentialAccessible = { true }, saveVtt = { task, bytes, promote -> promote { phone.files[task.vttName] = bytes } },
            prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), names,
                if (configured) mdSettings else SynthesisSettings(), request, active) { "id-${id++}" } })
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

    @Test fun existingVttDoesNotRequireSttSettings() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio synthétique".toByteArray()
            files["Note-20261111120000.vtt"] = validVtt
        }
        val store = LocalStateStore(File(tmp.root, "state.json"))
        val repo = LocalStateRepository(store)
        val stt = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected STT")
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected STT")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected STT")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        var calls = 0
        val md = object : SynthesisClient {
            override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray {
                calls++; assertArrayEquals(validVtt, vtt); return "md".toByteArray()
            }
            override suspend fun test(settings: SynthesisSettings, secret: String) = Unit
        }
        val coordinator = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { error("unexpected audio verification") }, schedule = {}, synthesisClient = md,
            synthesisStorage = SynthesisStorage(File(tmp.root, "stage")), phoneStore = { phone }, synthesisCredentialAccessible = { true },
            prepareRequested = { names, request, active -> TranscriptionPreparation.prepare(phone, "content://folder", TranscriptionSettings(),
                emptyMap(), names, mdSettings, request, active) { "id" } })
        val id = coordinator.start(null).single()
        assertEquals(RunOutcome.Finished, coordinator.runBounded(id))
        assertEquals(1, calls)
        assertEquals("md", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
    }

    @Test fun existingVttSnapshotUsesSameBytesForPromptAndHash() {
        val first = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v Alice>Bonjour\n".toByteArray()
        val second = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v Bob>Bonjour\n".toByteArray()
        val memory = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.vtt"] = first
        }
        var opens = 0
        val phone = object : PhoneStore by memory {
            override fun open(fileName: String): java.io.InputStream = if (fileName.endsWith(".vtt")) {
                (++opens).let { if (it == 1) first else second }.inputStream()
            } else memory.open(fileName)
        }
        val task = TranscriptionPreparation.prepare(phone, "content://folder", TranscriptionSettings(), emptyMap(), null,
            mdSettings.copy(prompt = "{{intervenants}}"), RequestedWork.MISSING) { "id" }.single()
        assertTrue(task.synthesis!!.context!!.systemPrompt!!.contains("Alice"))
        assertEquals(TranscriptionAudio.sha256(memory, "Note-20261111120000.vtt"), task.synthesis!!.context!!.vttSha256)
        assertEquals(1, opens)
    }

    @Test fun savingResumeReconcilesMatchingVttWithoutReplacingDifferentVtt() = runBlocking {
        val phone = MemoryStore().apply { files["Note-20261111120000.mp3"] = "audio".toByteArray() }
        val initial = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), null,
            mdSettings, RequestedWork.MISSING) { "id" }.single().copy(phase = TranscriptionPhase.SAVING, serverId = "server")
        val store = LocalStateStore(File(tmp.root, "state.json"))
        val client = object : TranscriptionClient {
            override suspend fun get(task: TranscriptionTask) = ServerTask("server", ServerStatus.COMPLETED, 1.0, validVtt)
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        var writes = 0
        var crashAfterPromotion = true
        fun coordinator(repo: LocalStateRepository) = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { phone }, saveVtt = { task, bytes, promote -> promote {
                writes++; if (phone.files.containsKey(task.vttName)) throw PhoneStoreException("existe")
                phone.files[task.vttName] = bytes
                if (crashAfterPromotion) { crashAfterPromotion = false; throw java.io.IOException("crash synthétique") }
            } })
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        assertEquals(RunOutcome.Resume, coordinator(LocalStateRepository(store)).runBounded("id"))
        assertEquals(TranscriptionPhase.SAVING, store.load().transcriptionTasks.single().phase)
        assertArrayEquals(validVtt, phone.files[initial.vttName])
        assertEquals(RunOutcome.Resume, coordinator(LocalStateRepository(store)).runBounded("id"))
        assertEquals(TranscriptionPhase.SYNTHESIZING, store.load().transcriptionTasks.single().phase)
        assertEquals(1, writes)
        phone.files[initial.vttName] = "extérieur".toByteArray()
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        assertEquals(RunOutcome.Suspended, coordinator(LocalStateRepository(store)).runBounded("id"))
        assertEquals("extérieur", phone.files[initial.vttName]!!.toString(Charsets.UTF_8))
        phone.files[initial.vttName] = validVtt
        store.save(LocalState(transcriptionTasks = listOf(initial.copy(replaceVtt = true))))
        assertEquals(RunOutcome.Resume, coordinator(LocalStateRepository(store)).runBounded("id"))
        assertEquals(TranscriptionPhase.SYNTHESIZING, store.load().transcriptionTasks.single().phase)
        assertEquals(2, writes)
    }

    private fun resumeClient() = object : TranscriptionClient {
        override suspend fun get(task: TranscriptionTask) = ServerTask("server", ServerStatus.COMPLETED, 1.0, validVtt)
        override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected")
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
        override suspend fun test(settings: TranscriptionSettings) = Unit
    }

    private fun missingSavingTask(phone: PhoneStore, hasMd: Boolean): TranscriptionTask {
        val task = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), null,
            mdSettings, RequestedWork.MISSING) { "id" }.single()
        assertEquals(!hasMd, task.synthesis != null)
        assertFalse(task.replaceVtt)
        return task.copy(phase = TranscriptionPhase.SAVING, serverId = "server")
    }

    @Test fun missingVttOnlyResumeRecognizesAlreadyPromotedVtt() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.md"] = "md existant".toByteArray()
        }
        val initial = missingSavingTask(phone, hasMd = true)
        val store = LocalStateStore(File(tmp.root, "state.json"))
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        var writes = 0
        fun coordinator() = LocalStateRepository(store).let { repo ->
            TranscriptionCoordinator(repo, resumeClient(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
                verifyAudio = { true }, phoneStore = { phone }, saveVtt = { task, bytes, promote -> promote {
                    writes++
                    if (phone.files.containsKey(task.vttName)) throw PhoneStoreException("Le VTT existe déjà.")
                    phone.files[task.vttName] = bytes
                    throw java.io.IOException("interruption après promotion")
                } })
        }
        assertEquals(RunOutcome.Resume, coordinator().runBounded("id"))
        assertEquals(TranscriptionPhase.SAVING, store.load().transcriptionTasks.single().phase)
        assertArrayEquals(validVtt, phone.files[initial.vttName])
        assertEquals(RunOutcome.Finished, coordinator().runBounded("id"))
        val completed = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.COMPLETED, completed.phase)
        assertFalse(completed.suspended)
        assertNull(completed.synthesis)
        assertEquals(1, writes)
        assertEquals("md existant", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
    }

    @Test fun missingVttOnlyResumePreservesDifferentVttAndSuspends() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.md"] = "md existant".toByteArray()
        }
        val initial = missingSavingTask(phone, hasMd = true)
        val external = "vtt extérieur".toByteArray()
        phone.files[initial.vttName] = external
        val store = LocalStateStore(File(tmp.root, "state.json"))
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val repo = LocalStateRepository(store)
        var promotions = 0
        val coordinator = TranscriptionCoordinator(repo, resumeClient(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { phone }, saveVtt = { task, bytes, promote -> promote {
                if (phone.files.containsKey(task.vttName) && !task.replaceVtt) throw PhoneStoreException("Le VTT existe déjà.")
                promotions++; phone.files[task.vttName] = bytes
            } })
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("id"))
        val suspended = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.SAVING, suspended.phase)
        assertTrue(suspended.suspended)
        assertTrue(suspended.suspensionReason!!.contains("VTT"))
        assertArrayEquals(external, phone.files[initial.vttName])
        assertEquals("md existant", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
        assertEquals(0, promotions)
    }

    private suspend fun assertCancelDuringVttReconciliation(alreadySaved: Boolean) = kotlinx.coroutines.coroutineScope {
        val memory = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.md"] = "md existant".toByteArray()
        }
        val initial = missingSavingTask(memory, hasMd = true)
        if (alreadySaved) memory.files[initial.vttName] = validVtt
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val phone = object : PhoneStore by memory {
            override fun list(): List<PhoneFile> {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return memory.list()
            }
        }
        val store = LocalStateStore(File(tmp.root, "cancel-list-$alreadySaved.json"))
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val repo = LocalStateRepository(store)
        val client = object : TranscriptionClient {
            override suspend fun get(task: TranscriptionTask) = ServerTask("server", ServerStatus.COMPLETED, 1.0, validVtt)
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = throw TranscriptionNetworkException()
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        var writes = 0
        val coordinator = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { phone }, saveVtt = { task, bytes, promote -> promote {
                writes++
                if (memory.files.containsKey(task.vttName) && !task.replaceVtt) throw PhoneStoreException("Le VTT existe déjà.")
                memory.files[task.vttName] = bytes
            } })
        val run = async(Dispatchers.Default) { coordinator.runBounded("id") }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val cancel = async(Dispatchers.Default) { coordinator.requestCancel("id") }
            withTimeout(5000) { while (repo.awaitInitialized().transcriptionTasks.single().phase != TranscriptionPhase.CANCEL_REQUESTED) delay(1) }
            release.countDown()
            assertEquals(RunOutcome.Resume, run.await())
            cancel.await()
            assertEquals(TranscriptionPhase.CANCEL_REQUESTED, store.load().transcriptionTasks.single().phase)
            assertEquals(0, writes)
            if (alreadySaved) assertArrayEquals(validVtt, memory.files[initial.vttName]) else assertNull(memory.files[initial.vttName])
            assertEquals("md existant", memory.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
        } finally {
            release.countDown()
        }
    }

    @Test fun cancellationDuringMissingVttOnlyListPreventsPromotion() = runBlocking { assertCancelDuringVttReconciliation(false) }
    @Test fun cancellationDuringMissingVttOnlyListPreventsFinalization() = runBlocking { assertCancelDuringVttReconciliation(true) }

    private suspend fun assertVttPromotionGuardOrder(cancelFirst: Boolean) = kotlinx.coroutines.coroutineScope {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.md"] = "md existant".toByteArray()
        }
        val initial = missingSavingTask(phone, hasMd = true)
        val store = LocalStateStore(File(tmp.root, "guard-$cancelFirst.json"))
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val repo = LocalStateRepository(store)
        val guard = java.util.concurrent.locks.ReentrantLock()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val promoted = CountDownLatch(1)
        val finishSave = CountDownLatch(1)
        val client = object : TranscriptionClient {
            override suspend fun get(task: TranscriptionTask): ServerTask {
                assertFalse(guard.isLocked)
                return ServerTask("server", ServerStatus.COMPLETED, 1.0, validVtt)
            }
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask {
                assertFalse(guard.isLocked)
                throw TranscriptionNetworkException()
            }
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        var phaseAtPromotion: TranscriptionPhase? = null
        var writes = 0
        val coordinator = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { phone }, promotionGuard = guard, saveVtt = { task, bytes, promote ->
                if (cancelFirst) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                var called = false
                promote {
                    called = true
                    if (!cancelFirst) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    phaseAtPromotion = store.load().transcriptionTasks.single().phase
                    if (phone.files.containsKey(task.vttName) && !task.replaceVtt) throw PhoneStoreException("Le VTT existe déjà.")
                    writes++
                    phone.files[task.vttName] = bytes
                }
                if (!called) throw PhoneStoreException("Promotion refusée")
                promoted.countDown()
                check(finishSave.await(10, TimeUnit.SECONDS))
            })
        val run = async(Dispatchers.Default) { coordinator.runBounded("id") }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val cancel = async(Dispatchers.Default) { coordinator.requestCancel("id") }
            if (!cancelFirst) {
                withTimeout(5000) { while (!guard.hasQueuedThreads()) delay(1) }
                assertEquals(TranscriptionPhase.SAVING, store.load().transcriptionTasks.single().phase)
                release.countDown()
                assertTrue(promoted.await(10, TimeUnit.SECONDS))
            }
            withTimeout(5000) { while (repo.awaitInitialized().transcriptionTasks.single().phase != TranscriptionPhase.CANCEL_REQUESTED) delay(1) }
            release.countDown()
            finishSave.countDown()
            assertEquals(RunOutcome.Resume, run.await())
            cancel.await()
            assertEquals(TranscriptionPhase.CANCEL_REQUESTED, store.load().transcriptionTasks.single().phase)
            assertEquals(if (cancelFirst) 0 else 1, writes)
            assertEquals(if (cancelFirst) null else TranscriptionPhase.SAVING, phaseAtPromotion)
            if (cancelFirst) assertNull(phone.files[initial.vttName]) else assertArrayEquals(validVtt, phone.files[initial.vttName])
            assertEquals("md existant", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
        } finally {
            release.countDown()
            finishSave.countDown()
        }
    }

    @Test fun cancellationWinsBeforeVttPromotionGuard() = runBlocking { assertVttPromotionGuardOrder(true) }
    @Test fun vttPromotionGuardWinsBeforeCancellation() = runBlocking { assertVttPromotionGuardOrder(false) }

    @Test fun missingVttAppearingBeforeGuardedPromotionIsNotReplaced() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111120000.md"] = "md existant".toByteArray()
        }
        val initial = missingSavingTask(phone, hasMd = true)
        val store = LocalStateStore(File(tmp.root, "appearing.json"))
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val repo = LocalStateRepository(store)
        val external = "vtt extérieur".toByteArray()
        val coordinator = TranscriptionCoordinator(repo, resumeClient(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { phone }, saveVtt = { task, bytes, promote ->
                val spool = File(tmp.root, "vtt-spool").apply { writeBytes(bytes) }
                phone.files[task.vttName] = external
                phone.writeGuarded(task.vttName, spool, task.replaceVtt, promote)
            })
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("id"))
        assertTrue(store.load().transcriptionTasks.single().suspended)
        assertArrayEquals(external, phone.files[initial.vttName])
        assertEquals("md existant", phone.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
    }

    private suspend fun assertReconciliationStreamFailure(hasMd: Boolean, failOnClose: Boolean) {
        val memory = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            if (hasMd) files["Note-20261111120000.md"] = "md existant".toByteArray()
        }
        val initial = missingSavingTask(memory, hasMd)
        memory.files[initial.vttName] = validVtt
        var opens = 0
        val phone = object : PhoneStore by memory {
            override fun open(fileName: String): java.io.InputStream {
                if (fileName != initial.vttName) return memory.open(fileName)
                opens++
                return object : java.io.FilterInputStream(memory.open(fileName)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!failOnClose) throw java.io.IOException("lecture synthétique")
                        return super.read(b, off, len)
                    }
                    override fun close() {
                        super.close()
                        if (failOnClose) throw java.io.IOException("fermeture synthétique")
                    }
                }
            }
        }
        val store = LocalStateStore(File(tmp.root, "state-$hasMd-$failOnClose.json"))
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val repo = LocalStateRepository(store)
        var writes = 0
        val coordinator = TranscriptionCoordinator(repo, resumeClient(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { phone }, saveVtt = { _, _, promote -> promote { writes++ } })
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("id"))
        val suspended = store.load().transcriptionTasks.single()
        assertEquals(TranscriptionPhase.SAVING, suspended.phase)
        assertTrue(suspended.suspended)
        assertTrue(suspended.suspensionReason!!.contains("VTT"))
        assertTrue(suspended.suspensionReason!!.contains("inaccessible"))
        assertEquals(RunOutcome.Suspended, coordinator.runBounded("id"))
        assertEquals(1, opens)
        assertEquals(0, writes)
        assertArrayEquals(validVtt, memory.files[initial.vttName])
        if (hasMd) assertEquals("md existant", memory.files["Note-20261111120000.md"]!!.toString(Charsets.UTF_8))
    }

    @Test fun synthesisVttReconciliationSuspendsOnReadFailure() = runBlocking { assertReconciliationStreamFailure(false, false) }
    @Test fun synthesisVttReconciliationSuspendsOnCloseFailure() = runBlocking { assertReconciliationStreamFailure(false, true) }
    @Test fun missingVttOnlyReconciliationSuspendsOnReadFailure() = runBlocking { assertReconciliationStreamFailure(true, false) }
    @Test fun missingVttOnlyReconciliationSuspendsOnCloseFailure() = runBlocking { assertReconciliationStreamFailure(true, true) }

    @Test fun historicalVttOnlyTaskWithoutReplaceFieldKeepsDic4SavePath() = runBlocking {
        val phone = MemoryStore().apply { files["Note-20261111120000.mp3"] = "audio".toByteArray() }
        val initial = missingSavingTask(phone, hasMd = false).copy(synthesis = null, replaceVtt = true)
        val stateFile = File(tmp.root, "state.json")
        val store = LocalStateStore(stateFile)
        store.save(LocalState(transcriptionTasks = listOf(initial)))
        val json = org.json.JSONObject(stateFile.readText())
        json.getJSONArray("transcriptionTasks").getJSONObject(0).remove("replaceVtt")
        stateFile.writeText(json.toString())
        assertTrue(store.load().transcriptionTasks.single().replaceVtt)
        val repo = LocalStateRepository(store)
        var writes = 0
        val coordinator = TranscriptionCoordinator(repo, resumeClient(), TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, phoneStore = { error("DIC-4 ne consulte pas le dossier en reprise") }, saveVtt = { _, _, promote -> promote { writes++ } })
        assertEquals(RunOutcome.Finished, coordinator.runBounded("id"))
        assertEquals(1, writes)
        assertEquals(TranscriptionPhase.COMPLETED, store.load().transcriptionTasks.single().phase)
    }

    @Test fun activeSttExcludedBeforeValidationAllowsOtherMd() = runBlocking {
        val phone = MemoryStore().apply {
            files["Note-20261111120000.mp3"] = "audio".toByteArray()
            files["Note-20261111130000.mp3"] = "audio".toByteArray()
            files["Note-20261111130000.vtt"] = validVtt
        }
        val active = TranscriptionPreparation.prepare(phone, "content://folder", sttSettings, emptyMap(), setOf("20261111120000"),
            mdSettings, RequestedWork.MISSING) { "active" }.single()
        val store = LocalStateStore(File(tmp.root, "state.json"))
        store.save(LocalState(transcriptionTasks = listOf(active)))
        val repo = LocalStateRepository(store)
        val stt = object : TranscriptionClient {
            override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
            override suspend fun test(settings: TranscriptionSettings) = Unit
        }
        val coordinator = TranscriptionCoordinator(repo, stt, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, schedule = {}, prepareRequested = { names, request, active ->
                TranscriptionPreparation.prepare(phone, "content://folder", TranscriptionSettings(), emptyMap(), names, mdSettings, request, active) { "other" }
            })
        assertEquals(listOf("other"), coordinator.start(null))
    }

}

package top.supergeoff.dictee

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AutomaticProcessingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val folderA = "content://synthetic/tree-a"
    private val folderB = "content://synthetic/tree-b"

    @Test fun disabledOptionDoesNotCreateAnIntent() {
        val state = LocalState(autoProcessAfterRecovery = false)
            .beginAutomaticRecovery("recording", folderA, "request")
        assertTrue(state.automaticProcessingRequests.isEmpty())
    }

    @Test fun enabledOptionCapturesIntentAndLaterDisableDoesNotPreventReady() {
        val began = LocalState(autoProcessAfterRecovery = true)
            .beginAutomaticRecovery("recording", folderA, "request")
        val disabled = began.copy(autoProcessAfterRecovery = false)
        val finished = disabled.finishAutomaticRecovery(RecoveryResult.Ok("recording", "recording.mp3", 10), folderA)
        assertEquals(listOf(AutomaticProcessingRequest("request", "recording", folderA, AutomaticProcessingPhase.READY)),
            finished.automaticProcessingRequests)
    }

    @Test fun disabledAtStartDoesNotCreateIntentAfterLaterEnable() {
        val began = LocalState(autoProcessAfterRecovery = false).beginAutomaticRecovery("recording", folderA, "request")
        val enabled = began.copy(autoProcessAfterRecovery = true)
        val finished = enabled.finishAutomaticRecovery(RecoveryResult.Ok("recording", "recording.mp3", 10), folderA)
        assertTrue(finished.automaticProcessingRequests.isEmpty())
    }

    @Test fun failedRecoveryRemovesIntentAndNeverMakesItProgrammable() {
        val pending = LocalState(autoProcessAfterRecovery = true).beginAutomaticRecovery("recording", folderA, "request")
        val failed = pending.finishAutomaticRecovery(RecoveryResult.Failed("recording", "interruption", true), folderA)
        assertTrue(failed.automaticProcessingRequests.isEmpty())
    }

    @Test fun transitionsPreserveOtherFoldersAndDoNotCreateMissingIntent() {
        val other = AutomaticProcessingRequest("other", "recording", folderB, AutomaticProcessingPhase.READY)
        val state = LocalState(autoProcessAfterRecovery = true, automaticProcessingRequests = listOf(other))
        val begun = state.beginAutomaticRecovery("recording", folderA, "request")
        assertEquals(listOf(other, AutomaticProcessingRequest("request", "recording", folderA, AutomaticProcessingPhase.RECOVERING)),
            begun.automaticProcessingRequests)
        assertEquals(listOf(other), state.finishAutomaticRecovery(
            RecoveryResult.Ok("absent", "absent.mp3", 10), folderA).automaticProcessingRequests)
    }

    @Test fun disabledBeginRemovesRecoveringButPreservesValidatedIntent() {
        val recovering = AutomaticProcessingRequest("recovering", "recording", folderA, AutomaticProcessingPhase.RECOVERING)
        val ready = AutomaticProcessingRequest("ready", "recording", folderA, AutomaticProcessingPhase.READY)
        val state = LocalState(autoProcessAfterRecovery = false, automaticProcessingRequests = listOf(recovering, ready))
            .beginAutomaticRecovery("recording", folderA, "new")
        assertEquals(listOf(ready), state.automaticProcessingRequests)
    }

    @Test fun beginReplacesOnlySameFileAndFolderRecoveringIntent() {
        val old = AutomaticProcessingRequest("old", "recording", folderA, AutomaticProcessingPhase.RECOVERING)
        val ready = AutomaticProcessingRequest("ready", "recording", folderA, AutomaticProcessingPhase.READY)
        val other = AutomaticProcessingRequest("other", "recording", folderB, AutomaticProcessingPhase.RECOVERING)
        val begun = LocalState(autoProcessAfterRecovery = true, automaticProcessingRequests = listOf(old, ready, other))
            .beginAutomaticRecovery("recording", folderA, "new")
        assertEquals(listOf(other, AutomaticProcessingRequest("new", "recording", folderA, AutomaticProcessingPhase.RECOVERING)),
            begun.automaticProcessingRequests)
    }

    @Test fun phasesRoundTripThroughLocalStateJson() {
        val requests = AutomaticProcessingPhase.entries.mapIndexed { i, phase ->
            AutomaticProcessingRequest("id-$i", "recording-$i", folderA, phase, if (phase == AutomaticProcessingPhase.WAITING_SETTINGS) "settings" else null)
        }
        val store = LocalStateStore(tmp.newFile("state.json"))
        val state = LocalState(autoProcessAfterRecovery = true, automaticProcessingRequests = requests)
        store.save(state)
        assertEquals(state, store.load())
    }
    private val credentials = object : CredentialStore {
        override fun save(secret: String) = "ref"
        override fun read(ref: String) = "key"
        override fun remove(ref: String) = Unit
    }
    private val client = object : TranscriptionClient {
        override suspend fun submit(task: TranscriptionTask): SubmitResult = error("unexpected network")
        override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected network")
        override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected network")
        override suspend fun test(settings: TranscriptionSettings) = error("unexpected network")
    }
    private val name = "20261111120000"
    private fun request(id: String = "request", phase: AutomaticProcessingPhase = AutomaticProcessingPhase.READY) =
        AutomaticProcessingRequest(id, name, folderA, phase)
    private val configured = TranscriptionSettings("https://example.invalid/v1", "fr", "model", "ref")
    private fun coordinator(repo: LocalStateRepository, phone: MemoryStore, schedule: suspend (String) -> Unit = {},
        beforePrepare: suspend () -> Unit = {}) = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials),
        RecoverySafety(repo), verifyAudio = { true }, schedule = schedule,
        prepareAutomatic = { name, folder ->
            beforePrepare()
            val state = repo.awaitInitialized()
            TranscriptionPreparation.prepareAutomatic(phone, folder, state.transcriptionSettings, state.errors, name,
                state.synthesisSettings) { "generated" }
        })
    private fun phone(vararg extensions: String) = MemoryStore().apply {
        extensions.forEach { files["Note-$name.$it"] = if (it == "vtt") "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nBonjour\n".toByteArray() else "synthetic".toByteArray() }
    }
    private fun store(state: LocalState): LocalStateStore = LocalStateStore(tmp.newFile()).also { it.save(state) }
    private fun state(vararg requests: AutomaticProcessingRequest) = LocalState(phoneTreeUri = folderA,
        transcriptionSettings = configured, automaticProcessingRequests = requests.toList())

    @Test fun restartReconcilesReadyButNotInterruptedRecovery() = runBlocking {
        for (success in listOf(false, true)) {
            val disk = store(state().copy(autoProcessAfterRecovery = true))
            val initial = LocalStateRepository(disk)
            val safety = RecoverySafety(initial)
            safety.begin(name, folderA)
            if (success) safety.finish(RecoveryResult.Ok(name, "Note-$name.mp3", 10), folderA)
            val restarted = LocalStateRepository(disk)
            val scheduled = mutableListOf<String>()
            AutomaticProcessingCoordinator(restarted, coordinator(restarted, phone("mp3"), { scheduled += it })).reconcile()
            assertEquals(if (success) 1 else 0, scheduled.size)
            assertEquals(if (success) 1 else 0, disk.load().transcriptionTasks.size)
        }
    }

    @Test fun recoverySchedulesFirstBeforeSecondCompletesWithoutDeadlock() = runBlocking {
        val second = "20261111130000"
        val bytes = javaClass.getResource("/stream.bin")!!.readBytes()
        fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val link = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) {
            reply(Cmd.FILE, byteArrayOf(0) + u32(bytes.size.toLong())); audio(bytes); reply(Cmd.FILE, byteArrayOf(2))
        } }
        val phone = MemoryStore()
        val disk = store(state().copy(autoProcessAfterRecovery = true))
        val repo = LocalStateRepository(disk)
        val safety = RecoverySafety(repo)
        val scheduled = mutableListOf<String>()
        val transcription = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), safety,
            verifyAudio = { true }, schedule = { scheduled += it }, prepareAutomatic = { n, folder ->
                val current = repo.awaitInitialized()
                TranscriptionPreparation.prepareAutomatic(phone, folder, current.transcriptionSettings, current.errors, n,
                    current.synthesisSettings) { "generated" }
            })
        val automatic = AutomaticProcessingCoordinator(repo, transcription)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val labels = mutableListOf<String>()
        val job = async(Dispatchers.Default) {
            RecoveryJob(V05Session(link, {}), phone, tmp.newFolder(), ListLog()).run(
                listOf(ListItem.Entry(second, bytes.size.toLong(), 0), ListItem.Entry(name, bytes.size.toLong(), 0)),
                { _, progress -> labels += progress.label() }, { automatic.reconcile() },
                beforeRecovery = { entry ->
                    safety.begin(entry.name, folderA)
                    if (entry.name == second) { entered.complete(Unit); release.await() }
                }, validateResult = { safety.finish(it, folderA) },
                withRecoveryLock = { block -> safety.withTransferLock(block) })
        }
        try {
            withTimeout(5000) { entered.await() }
            assertEquals(1, scheduled.size)
            val during = repo.awaitInitialized()
            assertEquals(listOf(name), during.transcriptionTasks.map { it.recordingName })
            assertEquals(AutomaticProcessingPhase.RECOVERING, during.automaticProcessingRequests.single().phase)
        } finally { release.complete(Unit) }
        withTimeout(5000) { job.await() }
        assertEquals(2, scheduled.size)
        assertEquals("Récupération 1/2 · 0 %", labels.first())
        assertEquals("Récupération 2/2 · 100 %", labels.last())
    }

    @Test fun readyIsConsumedAtomicallyAndConcurrentReconciliationDoesNotDuplicate() = runBlocking {
        val store = store(state(request(), request("recovering", AutomaticProcessingPhase.RECOVERING)))
        val repo = LocalStateRepository(store)
        val scheduled = mutableListOf<String>()
        val transcription = coordinator(repo, phone("mp3"), { scheduled += it })
        val automatic = AutomaticProcessingCoordinator(repo, transcription)
        coroutineScope { repeat(2) { launch { automatic.reconcile() } } }
        val result = store.load()
        assertEquals(listOf("request"), result.transcriptionTasks.map { it.localId })
        assertEquals(listOf("recovering"), result.automaticProcessingRequests.map { it.id })
        assertEquals(listOf("request"), scheduled)
        assertEquals(SynthesisPhase.WAITING_VTT, result.transcriptionTasks.single().synthesis?.phase)
        assertNull(result.transcriptionTasks.single().synthesis?.context)
    }

    @Test fun existingTerminalAndActiveManualTasksSatisfyRequest() = runBlocking {
        for (terminal in listOf(false, true)) {
            val p = phone("mp3")
            val task = TranscriptionPreparation.prepare(p, folderA, configured, emptyMap(), setOf(name), SynthesisSettings(), RequestedWork.MISSING) { "manual" }.single()
                .copy(localId = if (terminal) "request" else "manual", phase = if (terminal) TranscriptionPhase.COMPLETED else TranscriptionPhase.PENDING)
            val store = store(state(request()).copy(transcriptionTasks = listOf(task)))
            val repo = LocalStateRepository(store)
            AutomaticProcessingCoordinator(repo, coordinator(repo, p)).reconcile()
            assertEquals(listOf(task), store.load().transcriptionTasks)
            assertTrue(store.load().automaticProcessingRequests.isEmpty())
        }
    }

    @Test fun existingOutputsDoNotRetranscribeAndCompleteOutputsNeedNoTask() = runBlocking {
        for (md in listOf(false, true)) {
            val p = if (md) phone("mp3", "vtt", "md") else phone("mp3", "vtt")
            val synthesis = SynthesisSettings(baseUrl = "https://example.invalid/v1", model = "model", prompt = "prompt", credentialRef = "ref")
            val store = store(state(request()).copy(synthesisSettings = synthesis))
            val repo = LocalStateRepository(store)
            AutomaticProcessingCoordinator(repo, coordinator(repo, p)).reconcile()
            val tasks = store.load().transcriptionTasks
            assertEquals(if (md) 0 else 1, tasks.size)
            if (!md) { assertEquals("", tasks.single().mp3Sha256); assertEquals(TranscriptionPhase.SYNTHESIZING, tasks.single().phase) }
            assertTrue(store.load().automaticProcessingRequests.isEmpty())
        }
    }

    @Test fun missingSettingsWaitThenRetryAndFolderChangeNeverPrepares() = runBlocking {
        val store = store(state(request()).copy(transcriptionSettings = TranscriptionSettings()))
        val repo = LocalStateRepository(store)
        var prepared = 0
        val transcription = coordinator(repo, phone("mp3"), beforePrepare = { prepared++ })
        val automatic = AutomaticProcessingCoordinator(repo, transcription)
        automatic.reconcile()
        assertEquals(AutomaticProcessingPhase.WAITING_SETTINGS, store.load().automaticProcessingRequests.single().phase)
        assertTrue(store.load().transcriptionTasks.isEmpty())
        repo.updateDurably { it.copy(transcriptionSettings = configured, phoneTreeUri = folderB) }
        automatic.reconcile()
        assertEquals(1, prepared)
        assertEquals("Le dossier du téléphone a changé.", store.load().automaticProcessingRequests.single().reason)
        repo.updateDurably { it.copy(phoneTreeUri = folderA) }
        automatic.reconcile()
        assertEquals(listOf("request"), store.load().transcriptionTasks.map { it.localId })
    }

    @Test fun preparationFailureIsIsolatedAndSchedulingFailureLeavesDurableWork() = runBlocking {
        val second = request("second").copy(recordingName = "20261112120000")
        val store = store(state(request(), second))
        val repo = LocalStateRepository(store)
        val p = phone("mp3").apply { files["Note-${second.recordingName}.mp3"] = "synthetic".toByteArray() }
        var attempts = 0
        val transcription = coordinator(repo, p, schedule = { throw java.io.IOException("scheduler") }, beforePrepare = {
            if (attempts++ == 0) throw java.io.IOException("preparation")
        })
        AutomaticProcessingCoordinator(repo, transcription).reconcile()
        assertEquals(listOf("request"), store.load().automaticProcessingRequests.map { it.id })
        assertEquals(listOf("second"), store.load().transcriptionTasks.map { it.localId })
        val scheduled = mutableListOf<String>()
        coordinator(repo, p, { scheduled += it }).reconcile()
        assertEquals(listOf("second"), scheduled)
    }

    @Test fun changingFolderDuringPreparationUsesCapturedFolderAndDoesNotCommit() = runBlocking {
        val store = store(state(request()))
        val repo = LocalStateRepository(store)
        var usedFolder: String? = null
        val transcription = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
            verifyAudio = { true }, schedule = {}, prepareAutomatic = { _, folder ->
                repo.updateDurably { it.copy(phoneTreeUri = folderB) }
                usedFolder = folder
                AutomaticPreparation.Complete
            })
        AutomaticProcessingCoordinator(repo, transcription).reconcile()
        assertEquals(folderA, usedFolder)
        assertEquals("Le dossier du téléphone a changé.", store.load().automaticProcessingRequests.single().reason)
        assertTrue(store.load().transcriptionTasks.isEmpty())
    }
    @Test fun manualDuplicateBecomingTerminalBeforeCommitRetriesPreparation() = runBlocking {
        val p = phone("mp3")
        val manual = TranscriptionPreparation.prepare(p, folderA, configured, emptyMap(), setOf(name), SynthesisSettings(), RequestedWork.MISSING) { "manual" }.single()
        val store = store(state(request()).copy(transcriptionTasks = listOf(manual)))
        val repo = LocalStateRepository(store)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transcription = coordinator(repo, p, schedule = { locked.complete(Unit); release.await() })
        val reconcile = launch { transcription.reconcile() }
        locked.await()
        val automatic = async(start = CoroutineStart.UNDISPATCHED) { transcription.startAutomatic("request") }
        repo.updateDurably { it.copy(transcriptionTasks = listOf(manual.copy(phase = TranscriptionPhase.CANCELLED))) }
        release.complete(Unit)
        reconcile.join()
        automatic.await()
        assertEquals(listOf("manual", "request"), store.load().transcriptionTasks.map { it.localId })
        assertTrue(store.load().automaticProcessingRequests.isEmpty())
    }

    @Test fun missingMp3AndRecoveryFailureRetainRequestAndReasonUntilRetry() = runBlocking {
        for (recoveryFailure in listOf(false, true)) {
            val p = if (recoveryFailure) phone("mp3") else phone()
            val original = request().copy(phase = AutomaticProcessingPhase.WAITING_SETTINGS, reason = "raison conservée")
            val errors = if (recoveryFailure) mapOf(name to mapOf(Step.RECOVER to "interruption")) else emptyMap()
            val store = store(state(original).copy(errors = errors))
            val repo = LocalStateRepository(store)
            val automatic = AutomaticProcessingCoordinator(repo, coordinator(repo, p))
            automatic.reconcile()
            assertEquals(original, store.load().automaticProcessingRequests.single())
            assertTrue(store.load().transcriptionTasks.isEmpty())
            p.files["Note-$name.mp3"] = "synthetic".toByteArray()
            repo.updateDurably { it.copy(errors = emptyMap()) }
            automatic.reconcile()
            assertEquals(listOf("request"), store.load().transcriptionTasks.map { it.localId })
        }
    }

    @Test fun vttOnlyWithoutSynthesisConfigurationConsumesWithoutTask() = runBlocking {
        val store = store(state(request()))
        val repo = LocalStateRepository(store)
        AutomaticProcessingCoordinator(repo, coordinator(repo, phone("mp3", "vtt"))).reconcile()
        assertTrue(store.load().automaticProcessingRequests.isEmpty())
        assertTrue(store.load().transcriptionTasks.isEmpty())
    }

}

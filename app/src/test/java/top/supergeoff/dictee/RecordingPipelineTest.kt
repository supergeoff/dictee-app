package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class RecordingPipelineTest {
    private val name = "20261111120000"
    private fun recording(vararg kinds: FileKind, onBox: Boolean = true) = Recording(
        name, RecordType.NOTE, 100, onBox,
        kinds.associateWith { PhoneFile("Note-$name.${it.ext}", 100) }, DisplayState.OnBox,
    )
    private fun task(
        phase: TranscriptionPhase = TranscriptionPhase.TRACKING,
        name: String = this.name,
        suspended: Boolean = false,
        work: SynthesisWork? = null,
        error: String? = null,
    ) = TranscriptionTask("id-$name", name, "Note-$name.mp3", "Note-$name.vtt", "folder", "hash", "url", "ref", "fr", "model", phase,
        error = error, suspended = suspended, suspensionReason = if (suspended) "suspendue" else null, synthesis = work)
    private fun work(phase: SynthesisPhase = SynthesisPhase.CALLING) =
        SynthesisWork(phase, "Note-$name.md", false, null, 1, null, null, if (phase == SynthesisPhase.SUSPENDED) "suspendue" else null)
    private fun input(
        r: Recording = recording(), tasks: List<TranscriptionTask> = emptyList(),
        errors: Map<Step, String> = emptyMap(), busy: Busy = Busy(), drive: DriveListing? = null,
        driveEnabled: Boolean = false, configured: Boolean = true, connected: Boolean = true,
        provenance: ProvenanceStatus = ProvenanceStatus.UNKNOWN,
    ) = PipelineInput(r, LocalState(transcriptionTasks = tasks, errors = mapOf(name to errors), driveEnabled = driveEnabled),
        busy, drive, connected, configured, provenance)
    private fun result(i: PipelineInput) = RecordingPipeline.calculate(i)
    private fun status(i: PipelineInput, stage: PipelineStage) = result(i).steps.single { it.stage == stage }.status

    @Test fun remoteCopiesRequireReceiptForCurrentDestinationAndIdentity() {
        val entries = FileKind.entries.map { DriveFile(it.ext, "Call-$name.${it.ext}", 10) }
        val listing = DriveListing(entries.associate { it.name to it.size }, entries)
        val r = Recordings.merge(emptyList(), emptyList(), listing, emptyMap(), Busy()).single()
        val destination = DriveDestination("account", "folder")
        val receipts = entries.map { DriveReceipt(destination, it.name, it.id, "hash") }
        val local = LocalState(driveEnabled = true, driveAccount = destination.account, chosenFolderId = destination.folderId)
        val base = PipelineInput(r, local, Busy(), listing, true, true)
        val variants = listOf(
            emptyList(),
            receipts.map { it.copy(destination = DriveDestination("account", "other")) },
            receipts.map { it.copy(driveFileId = "other") },
            receipts.map { it.copy(sha256 = "") },
        )
        for (proof in variants) {
            val presentation = result(base.copy(local = local.copy(driveReceipts = proof)))
            for (step in presentation.steps.take(3)) {
                assertEquals(PipelineStatus.TODO, step.status)
                assertEquals("Absent", step.label)
                assertTrue(step.reason.orEmpty().contains("non vérifiée"))
            }
            assertNull(presentation.button)
        }
        val missingDestination = result(base.copy(local = local.copy(chosenFolderId = null, driveReceipts = receipts)))
        assertEquals(listOf("Absent", "Absent", "Absent"), missingDestination.steps.take(3).map { it.label })
        val duplicate = entries + DriveFile("duplicate", "Note-$name.vtt", 10)
        val duplicateResult = result(base.copy(
            drive = DriveListing(duplicate.associate { it.name to it.size }, duplicate),
            local = local.copy(driveReceipts = receipts),
        ))
        assertEquals("Absent", duplicateResult.steps.single { it.stage == PipelineStage.VTT }.label)
        val legacy = DriveFile("legacy", "$name.mp3", 10)
        val legacyResult = result(base.copy(
            drive = DriveListing(mapOf(legacy.name to legacy.size), listOf(legacy)),
            local = local.copy(driveReceipts = listOf(DriveReceipt(destination, legacy.name, legacy.id, "hash"))),
        ))
        assertEquals("Sur Drive", legacyResult.steps.single { it.stage == PipelineStage.PHONE }.label)
        val verified = base.copy(local = local.copy(driveReceipts = receipts))
        assertEquals(listOf("Sur Drive", "Sur Drive", "Sur Drive"), result(verified).steps.take(3).map { it.label })
        val absent = result(base.copy(drive = DriveListing(emptyMap())))
        assertEquals(listOf("Absent", "Absent", "Absent"), absent.steps.take(3).map { it.label })
        val localMp3 = result(base.copy(recording = recording(FileKind.MP3, onBox = false)))
        assertEquals(listOf("Fait", "Absent", "Absent"), localMp3.steps.take(3).map { it.label })
        assertEquals(PipelineAction.CREATE_VTT_AND_MD, localMp3.button?.action)
        assertEquals(PipelineAction.CREATE_VTT_AND_MD, result(verified.copy(recording = recording(FileKind.MP3, onBox = false))).button?.action)
        assertEquals(PipelineAction.RECOVER, result(base.copy(recording = recording())).button?.action)
    }

    @Test fun driveCompletionUsesCurrentProofAndStaleMdNeverFinishesBadge() {
        val r = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val entries = r.phoneFiles.values.map { DriveFile(it.fileName, it.fileName, it.size) }
        val hashes = r.phoneFiles.filterKeys { it != FileKind.MP3 }.values.associate { it.fileName to "hash" }
        val destination = DriveDestination("account", "folder")
        val receipts = entries.filter { it.name in hashes }.map { DriveReceipt(destination, it.name, it.id, "hash") }
        val local = LocalState(driveEnabled = true, driveAccount = "account", chosenFolderId = "folder", driveReceipts = receipts)
        val listing = DriveListing(entries.associate { it.name to it.size }, entries)
        val base = PipelineInput(r, local, Busy(), listing, true, true, documentHashes = hashes)
        assertEquals(PipelineStatus.DONE, status(base, PipelineStage.DRIVE))
        assertEquals("Sur Drive", result(base).badge)
        assertEquals(PipelineStatus.TODO, status(base.copy(documentHashes = emptyMap()), PipelineStage.DRIVE))
        assertEquals(PipelineStatus.TODO, status(base.copy(local = local.copy(driveReceipts = emptyList())), PipelineStage.DRIVE))
        assertNotEquals("Sur Drive", result(base.copy(provenance = ProvenanceStatus.STALE)).badge)
    }

    @Test fun counterAndPresentationAgreeOnCurrentDocuments() {
        val r = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val entries = r.phoneFiles.values.map { DriveFile(it.fileName, it.fileName, it.size) }
        val hashes = r.phoneFiles.filterKeys { it != FileKind.MP3 }.values.associate { it.fileName to "hash" }
        val destination = DriveDestination("account", "folder")
        val receipts = entries.filter { it.name in hashes }.map { DriveReceipt(destination, it.name, it.id, "hash") }
        val local = LocalState(driveEnabled = true, driveAccount = "account", chosenFolderId = "folder", driveReceipts = receipts)
        val listing = DriveListing(entries.associate { it.name to it.size }, entries)
        val ui = UiState(local = local, recordings = listOf(r), drive = listing, documentHashes = hashes)
        assertTrue(ui.toSend.isEmpty())
        assertEquals("Sur Drive", recordingListPresentation(r, ui).badge)
        val changed = ui.copy(documentHashes = hashes + (r.phoneFiles.getValue(FileKind.VTT).fileName to "other"))
        assertEquals(listOf(r), changed.toSend)
        assertNotEquals("Sur Drive", recordingListPresentation(r, changed).badge)
    }

    @Test fun mp3PrefixAndLegacyMatchingAgreeWithCounterAndPresentation() {
        val r = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val documents = r.phoneFiles.filterKeys { it != FileKind.MP3 }.values.map { DriveFile(it.fileName, it.fileName, it.size) }
        val hashes = documents.associate { it.name to "hash" }
        val destination = DriveDestination("account", "folder")
        val receipts = documents.map { DriveReceipt(destination, it.name, it.id, "hash") }
        val local = LocalState(driveEnabled = true, driveAccount = "account", chosenFolderId = "folder", driveReceipts = receipts)
        fun ui(vararg mp3: String): UiState {
            val entries = documents + mp3.map { DriveFile(it, it, 100) }
            return UiState(local = local, recordings = listOf(r), drive = DriveListing(entries.associate { it.name to it.size }, entries), documentHashes = hashes)
        }
        for (remote in listOf("Call-$name.mp3", "$name.mp3")) {
            val current = ui(remote)
            assertTrue(current.toSend.isEmpty())
            assertEquals("Sur Drive", recordingListPresentation(r, current).badge)
        }
        val conflict = ui("Note-$name.mp3", "Call-$name.mp3")
        assertEquals(listOf(r), conflict.toSend)
        assertEquals(PipelineAction.SEND, recordingListPresentation(r, conflict).button?.action)
        assertNotEquals("Sur Drive", recordingListPresentation(r, conflict).badge)
    }

    @Test fun documentsCanBeSentWithoutLocalMp3() {
        val r = recording(FileKind.VTT, FileKind.MD, onBox = false)
        val local = LocalState(driveEnabled = true, driveSendChoice = DriveSendChoice.DOCUMENTS_ONLY)
        val ui = UiState(local = local, recordings = listOf(r))
        assertEquals(listOf(r), ui.toSend)
        assertEquals(PipelineAction.SEND, recordingListPresentation(r, ui).button?.action)
    }

    @Test fun automaticWaitingSettingsShowsItsReasonAndOpensSettings() {
        val request = AutomaticProcessingRequest("id", name, "folder", AutomaticProcessingPhase.WAITING_SETTINGS, "Le dossier du téléphone a changé.")
        val base = input(r = recording(FileKind.MP3))
        val waiting = base.copy(local = base.local.copy(automaticProcessingRequests = listOf(request)))
        val presentation = result(waiting)
        val vtt = presentation.steps.single { it.stage == PipelineStage.VTT }
        assertEquals(PipelineStatus.WAITING, vtt.status)
        assertEquals("À configurer", vtt.label)
        assertEquals(request.reason, vtt.reason)
        assertEquals(request.reason, presentation.reason?.text)
        assertEquals(PipelineAction.OPEN_SETTINGS, presentation.button?.action)
        val recovering = result(waiting.copy(busy = Busy(recovering = name)))
        assertNull(recovering.button)
        assertNotEquals(PipelineStatus.WAITING, recovering.steps.single { it.stage == PipelineStage.VTT }.status)
        val active = result(waiting.copy(local = waiting.local.copy(transcriptionTasks = listOf(task()))))
        assertEquals(PipelineStatus.RUNNING, active.steps.single { it.stage == PipelineStage.VTT }.status)
        assertEquals(PipelineAction.CANCEL_VTT, active.button?.action)
        val other = result(waiting.copy(local = waiting.local.copy(automaticProcessingRequests = listOf(request.copy(recordingName = "other")))))
        assertEquals(PipelineStatus.TODO, other.steps.single { it.stage == PipelineStage.VTT }.status)
    }

    @Test fun settingsDestinationFollowsWaitingStageAndFolder() {
        val request = AutomaticProcessingRequest("id", name, "folder", AutomaticProcessingPhase.WAITING_SETTINGS, "Configuration requise")
        val base = input(r = recording(FileKind.MP3))
        val waiting = base.copy(local = base.local.copy(phoneTreeUri = "folder", automaticProcessingRequests = listOf(request)))
        assertEquals(SettingsDestination.TRANSCRIPTION, result(waiting).settingsDestination(waiting))
        val changedFolder = waiting.copy(local = waiting.local.copy(phoneTreeUri = "other"))
        assertEquals(SettingsDestination.STORAGE, result(changedFolder).settingsDestination(changedFolder))
        val synthesis = input(r = recording(FileKind.MP3, FileKind.VTT), configured = false)
        assertEquals(SettingsDestination.SYNTHESIS, result(synthesis).settingsDestination(synthesis))
    }

    @Test fun stageTableMatchesSpec() {
        data class Case(val stage: PipelineStage, val input: PipelineInput, val expected: PipelineStatus)
        val mp3 = recording(FileKind.MP3)
        val vtt = recording(FileKind.MP3, FileKind.VTT)
        val all = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val cases = listOf(
            Case(PipelineStage.PHONE, input(r = mp3, busy = Busy(recovering = name), errors = mapOf(Step.RECOVER to "erreur")), PipelineStatus.DONE),
            Case(PipelineStage.PHONE, input(busy = Busy(recovering = name)), PipelineStatus.RUNNING),
            Case(PipelineStage.PHONE, input(errors = mapOf(Step.RECOVER to "coupure")), PipelineStatus.ERROR),
            Case(PipelineStage.PHONE, input(), PipelineStatus.TODO),
            Case(PipelineStage.PHONE, input(r = recording(onBox = false)), PipelineStatus.TODO),
            Case(PipelineStage.VTT, input(r = vtt, tasks = listOf(task())), PipelineStatus.RUNNING),
            Case(PipelineStage.VTT, input(r = mp3, tasks = listOf(task(TranscriptionPhase.CANCEL_REQUESTED))), PipelineStatus.RUNNING),
            Case(PipelineStage.VTT, input(r = mp3, tasks = listOf(task(name = "20261110120000"), task())), PipelineStatus.WAITING),
            Case(PipelineStage.VTT, input(r = mp3, tasks = listOf(task(TranscriptionPhase.FAILED, error = "échec"))), PipelineStatus.ERROR),
            Case(PipelineStage.VTT, input(r = vtt), PipelineStatus.DONE),
            Case(PipelineStage.VTT, input(r = mp3), PipelineStatus.TODO),
            Case(PipelineStage.MD, input(r = all, tasks = listOf(task(TranscriptionPhase.SYNTHESIZING, work = work()))), PipelineStatus.RUNNING),
            Case(PipelineStage.MD, input(r = vtt, tasks = listOf(task(name = "20261110120000"), task(TranscriptionPhase.SYNTHESIZING, work = work()))), PipelineStatus.WAITING),
            Case(PipelineStage.MD, input(r = vtt, tasks = listOf(task(TranscriptionPhase.SYNTHESIZING, suspended = true, work = work(SynthesisPhase.SUSPENDED)))), PipelineStatus.ERROR),
            Case(PipelineStage.MD, input(r = all, provenance = ProvenanceStatus.STALE), PipelineStatus.TODO),
            Case(PipelineStage.MD, input(r = all, provenance = ProvenanceStatus.CURRENT), PipelineStatus.DONE),
            Case(PipelineStage.MD, input(r = vtt, configured = false), PipelineStatus.TODO),
            Case(PipelineStage.MD, input(r = vtt), PipelineStatus.TODO),
            Case(PipelineStage.DRIVE, input(r = all, driveEnabled = true, busy = Busy(sending = name)), PipelineStatus.RUNNING),
            Case(PipelineStage.DRIVE, input(r = all, driveEnabled = true, errors = mapOf(Step.SEND to "erreur")), PipelineStatus.ERROR),
            Case(PipelineStage.DRIVE, input(r = all, driveEnabled = true, drive = DriveListing(all.phoneFiles.values.associate { it.fileName to it.size })), PipelineStatus.TODO),
            Case(PipelineStage.DRIVE, input(r = all), PipelineStatus.TODO),
            Case(PipelineStage.DRIVE, input(r = all, driveEnabled = true), PipelineStatus.TODO),
        )
        cases.forEachIndexed { index, c -> assertEquals("case $index ${c.stage}", c.expected, status(c.input, c.stage)) }
        assertNotEquals("Ancien VTT", result(input(r = all)).steps.single { it.stage == PipelineStage.MD }.label)
    }

    @Test fun primaryActionTableMatchesSpec() {
        val mp3 = recording(FileKind.MP3)
        val vtt = recording(FileKind.MP3, FileKind.VTT)
        val all = recording(FileKind.MP3, FileKind.VTT, FileKind.MD)
        val cases = listOf(
            input(r = mp3, tasks = listOf(task())) to PipelineAction.CANCEL_VTT,
            input(r = vtt, tasks = listOf(task(TranscriptionPhase.SYNTHESIZING, work = work()))) to PipelineAction.CANCEL_MD,
            input(r = mp3, tasks = listOf(task(name = "20261110120000"), task())) to PipelineAction.REMOVE_FROM_QUEUE,
            input(busy = Busy(recovering = name)) to null,
            input(errors = mapOf(Step.RECOVER to "coupure")) to PipelineAction.RETRY_RECOVERY,
            input() to PipelineAction.RECOVER,
            input(r = mp3, tasks = listOf(task(TranscriptionPhase.FAILED, error = "échec"))) to PipelineAction.RETRY_VTT,
            input(r = mp3) to PipelineAction.CREATE_VTT_AND_MD,
            input(r = recording(FileKind.MP3, FileKind.MD)) to PipelineAction.CREATE_VTT,
            input(r = vtt, tasks = listOf(task(TranscriptionPhase.SYNTHESIZING, suspended = true, work = work(SynthesisPhase.SUSPENDED)))) to PipelineAction.RETRY_MD,
            input(r = vtt, configured = false) to PipelineAction.OPEN_SETTINGS,
            input(r = vtt) to PipelineAction.CREATE_MD,
            input(r = all, provenance = ProvenanceStatus.STALE) to PipelineAction.REDO_MD,
            input(r = all, driveEnabled = true, errors = mapOf(Step.SEND to "erreur")) to PipelineAction.RETRY_SEND,
            input(r = all, driveEnabled = true) to PipelineAction.SEND,
            input(r = all) to null,
            input(r = recording(onBox = false)) to null,
        )
        cases.forEachIndexed { index, (i, expected) -> assertEquals("case $index", expected, result(i).button?.action) }
        assertFalse(result(input(connected = false)).button!!.enabled)
    }

    @Test fun suspendedSynthesisWithMissingContextCannotRetry() {
        val suspended = task(TranscriptionPhase.SYNTHESIZING, suspended = true, work = work(SynthesisPhase.SUSPENDED))
        val presentation = result(input(r = recording(FileKind.MP3, FileKind.VTT), tasks = listOf(suspended)))
        assertEquals(PipelineAction.RETRY_MD, presentation.button?.action)
        assertFalse(presentation.button!!.enabled)
        assertTrue(presentation.reason!!.text.contains("contexte"))
    }

    @Test fun queueRankIgnoresSuspendedAndTerminalTasks() {
        val names = listOf("20261109120000", "20261110120000", name)
        val tasks = listOf(task(name = "20261101120000", suspended = true), task(TranscriptionPhase.COMPLETED, name = "20261102120000")) + names.map { task(name = it) }
        names.forEachIndexed { index, n ->
            val r = recording(FileKind.MP3).copy(name = n)
            assertEquals(index + 1, result(input(r = r, tasks = tasks)).queueRank)
        }
    }

    @Test fun cancellingTaskDoesNotOccupyQueueRank() {
        val cancelling = task(TranscriptionPhase.CANCEL_REQUESTED, name = "20261110120000")
        val current = result(input(r = recording(FileKind.MP3), tasks = listOf(cancelling, task())))
        assertEquals(1, current.queueRank)
        assertEquals(PipelineAction.CANCEL_VTT, current.button?.action)
        val cancellingPresentation = result(input(r = recording(FileKind.MP3).copy(name = cancelling.recordingName), tasks = listOf(cancelling, task())))
        assertNull(cancellingPresentation.queueRank)
        assertEquals("Annulation", cancellingPresentation.steps.single { it.stage == PipelineStage.VTT }.label)
        assertFalse(cancellingPresentation.button!!.enabled)
    }

    @Test fun uncertainSubmissionCannotBeRetried() {
        val uncertain = task(TranscriptionPhase.UNKNOWN_SUBMISSION, suspended = true)
        val presentation = result(input(r = recording(FileKind.MP3), tasks = listOf(uncertain)))
        assertFalse(presentation.button!!.enabled)
        assertTrue(presentation.reason!!.text.contains("risque"))
    }

    @Test fun suspendedTaskWithIncompleteContextCannotBeRetried() {
        val invalid = task(suspended = true).copy(encryptedKeyRef = "")
        val presentation = result(input(r = recording(FileKind.MP3), tasks = listOf(invalid)))
        assertFalse(presentation.button!!.enabled)
        assertTrue(presentation.reason!!.text.contains("contexte"))
    }

    @Test fun callingSynthesisDoesNotShowRetryReason() {
        val calling = work().copy(nextAttemptAtMillis = 123456789L)
        val presentation = result(input(r = recording(FileKind.MP3, FileKind.VTT), tasks = listOf(task(TranscriptionPhase.SYNTHESIZING, work = calling))))
        assertNull(presentation.steps.single { it.stage == PipelineStage.MD }.reason)
    }

    @Test fun firstReasonFollowsPipelineOrder() {
        val i = input(r = recording(), errors = mapOf(Step.RECOVER to "boîtier", Step.SEND to "drive"), driveEnabled = true)
        assertEquals(PipelineStage.PHONE, result(i).reason?.stage)
    }

    @Test fun badgePrioritizesErrorThenRunningThenWaiting() {
        val mp3 = recording(FileKind.MP3)
        assertEquals("Erreur", result(input(r = mp3, errors = mapOf(Step.SEND to "erreur"), busy = Busy(recovering = name), driveEnabled = true)).badge)
        assertTrue(result(input(r = mp3, busy = Busy(sending = name), driveEnabled = true)).badge.startsWith("Envoi"))
        assertEquals("En attente", result(input(r = mp3, tasks = listOf(task(name = "20261110120000"), task()))).badge)
    }

    @Test fun synthesisProgressIsNotTranscription() {
        val current = TranscriptionProgress.current(listOf(task(TranscriptionPhase.SYNTHESIZING, work = work())))!!.second
        assertEquals("Synthèse 1/1", current.label())
    }
}
